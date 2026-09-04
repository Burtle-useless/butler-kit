"""FastAPI 應用本體：建 app、lifespan、接線。

這一層只做協定轉換：把 HTTP 請求轉成引擎呼叫、把引擎事件轉成 SSE frame。
不含任何業務邏輯——判斷、重試、續跑全在 engine/。

每條對話的回合佇列與 worker 在 `engine.worker.Worker`，這裡模組層持有一個實例
`worker`。留在這個檔案裡的端點只有會碰到它的那幾個（送訊息、停止、刪對話、
事件流、health）。其餘按領域拆在 `conversations_api`／`settings_api`／
`devices_api`／`files_api`，那些模組在尾端 `from . import app as core` 回頭讀
`core.worker`、`core.frontend_for`。tests/ 對 worker 內部動手時走
`engine.worker`（換假回合）與 `app.worker`（讀登記表），不再經過這個命名空間。
"""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Body, Depends, FastAPI, HTTPException, Request
from sse_starlette.sse import EventSourceResponse, ServerSentEvent

import config
from engine import client_pool, location, ports, titles
from engine import models as model_catalog
from engine import state as state_mod
from engine.worker import Worker

from . import (
    agenda_api,
    conversations_api,
    device_api,
    devices_api,
    files_api,
    kanban_api,
    outbox_api,
    settings_api,
    system_api,
)
from .auth import require_token
from .hub import EventHub, SseFrontend

hub = EventHub()
_frontends: dict[str, SseFrontend] = {}
# 送訊息的那一端自報家門（payload 的 `client`），對到蓋進時間戳裡的中文。
#
# 空字串那一筆是**預設值**，故意指向「手機」：手機 App 到現在都沒送這個欄位，
# 而它是絕大多數訊息的來源。等 App 哪天改了再讓它明講也不遲——反過來預設
# 「電腦」的話，每支還沒更新的 App 都會謊報他坐在電腦前。
_SRC_NAMES: dict[str, str] = {"": "手機", "phone": "手機", "desktop": "電腦"}


def frontend_for(conv_id: str) -> SseFrontend:
    fe = _frontends.get(conv_id)
    if fe is None:
        fe = _frontends[conv_id] = SseFrontend(hub, conv_id)
    return fe


async def _autoname(conv_id: str, first_message: str) -> None:
    """Worker 的自動命名回呼：補上這條對話的 frontend，其餘全在 engine.titles。"""
    await titles.autoname(conv_id, first_message, frontend_for(conv_id))


async def _rename_adopted(conv_id: str) -> None:
    """接管既有 session 之後改名（engine.titles.rename_adopted）。

    留一個殼在這裡是為了補 frontend：conversations_api 的 adopt 端點從這裡叫，
    tests/test_adopt_api 也是 patch 這個名字把 Haiku 換掉。
    """
    await titles.rename_adopted(conv_id, frontend_for(conv_id))


# 所有對話的回合佇列與 worker。一個進程一份。
worker = Worker(frontend_for, autoname=_autoname)


@asynccontextmanager
async def lifespan(app: FastAPI):
    reaper = asyncio.create_task(client_pool.reaper())
    # 沒有模型清單快取就先去要一次，不等第一則訊息（背景跑，不擋啟動）
    boot_models = asyncio.create_task(model_catalog.bootstrap())
    # 上次限流等待中被重啟弄丟的訊息，重新排回去
    n = await worker.restore_pending()
    if n:
        print(f"[butler] 限流等待中的 {n} 則訊息已重新排隊", flush=True)
    try:
        yield
    finally:
        reaper.cancel()
        boot_models.cancel()
        await worker.shutdown()
        await client_pool.shutdown()


app = FastAPI(title="butler", lifespan=lifespan)

def _wire() -> None:
    """所有接線集中在這一處。

    兩個方向：
      transport 各 api 要能把事件丟進 hub（`set_publisher`），
      engine 要能通知手機、跟手機要東西、把自己醒來的週期排隊（`engine.ports`）。

    以前這些散在模組層一條一條接，漏接任何一條都是靜默不動（鬧鐘不響、位置永遠
    拿不到）。現在 engine 那半收成 `Ports` 的必填欄位——少給一個就在這裡 TypeError，
    服務起不來，而不是上線後才發現。

    助理改的（MCP 工具，在 engine 裡）與你手動改的（各 api 的 router）走同一個
    廣播函式，手機端因此只要處理一種事件。engine 反過來 import transport 會是
    循環依賴，所以派工、問位置這些動作由這一層決定、交進去。
    """
    agenda_api.set_publisher(hub.publish)
    kanban_api.set_publisher(hub.publish)
    outbox_api.set_publisher(hub.publish)
    device_api.set_publisher(hub.publish, lambda: hub.has_listeners)
    ports.install(ports.Ports(
        waker=worker.dispatch_wake,
        locate=lambda: device_api.request(
            "location", timeout_sec=location.ASK_TIMEOUT_SEC,
        ),
        offer=outbox_api.aannounce,
        agenda_changed=agenda_api.abroadcast,
        kanban_changed=kanban_api.abroadcast,
    ))


_wire()
app.include_router(agenda_api.router)
app.include_router(kanban_api.router)
app.include_router(outbox_api.router)
app.include_router(device_api.router)
# 服務自己的控制台：看狀態、從手機按重啟。助理不該自己重啟（那會連它一起收掉），
# 所以按鈕在使用者手上，見 system_api 的模組說明。
app.include_router(system_api.router)
# 按領域拆出去的路由（見模組說明）
app.include_router(conversations_api.router)
app.include_router(settings_api.router)
app.include_router(devices_api.router)
app.include_router(files_api.router)


@app.get("/v1/health")
async def health() -> dict:
    """不需要 token——用來確認網路通不通，不洩漏任何內容。"""
    return {
        "ok": True,
        "latest_seq": hub.latest_seq,
        "conversations": len(worker.queues),
        **client_pool.stats(),
    }


@app.get("/v1/stream")
async def stream(request: Request, _: str = Depends(require_token)):
    """下行事件流。帶 Last-Event-ID 即從該序號之後續傳。"""
    raw = request.headers.get("last-event-id") or request.query_params.get("since")
    try:
        after = int(raw) if raw else None
    except ValueError:
        after = None

    async def gen():
        async for ev in hub.stream(after):
            if await request.is_disconnected():
                break
            if ev is None:
                # 心跳：OkHttp 預設 10 秒讀取逾時，而 CC 思考期間可能 30 秒沒有輸出，
                # 沒有這個 client 會自己掐斷連線然後無限重連。
                yield ServerSentEvent(comment="keepalive")
                continue
            yield ev.to_sse()

    return EventSourceResponse(gen())


def _clean_attachments(raw: object) -> list[dict]:
    """把 payload 的 attachments 洗成乾淨的清單。

    **路徑一定要落在上傳目錄底下。** 這個欄位是客戶端給的，直接信任等於讓任何
    配對過的裝置指定一個路徑叫模型去讀——那是整台電腦的任意檔案。上傳端點
    （`/v1/uploads`）寫進去的檔本來就在 `files.UPLOAD_DIR`，超出範圍的一律丟掉。
    """
    from .files import UPLOAD_DIR
    if not isinstance(raw, list):
        return []
    out: list[dict] = []
    for item in raw[:20]:      # 一則訊息掛二十個檔已經很多了，擋住無上限的清單
        if not isinstance(item, dict):
            continue
        path = str(item.get("path") or "").strip()
        if not path:
            continue
        try:
            p = Path(path).resolve()
            p.relative_to(UPLOAD_DIR.resolve())
        except (ValueError, OSError):
            continue
        if not p.is_file():
            continue
        out.append({
            "name": str(item.get("name") or p.name)[:120],
            "path": str(p),
            "bytes": int(item.get("bytes") or p.stat().st_size),
            "mime": str(item.get("mime") or "")[:80],
        })
    return out


@app.post("/v1/conversations/{conv_id}/message")
async def send_message(
    conv_id: str,
    payload: dict = Body(...),
    _: str = Depends(require_token),
) -> dict:
    text = str(payload.get("text") or "").strip()
    atts = _clean_attachments(payload.get("attachments"))
    # 只有附件沒有文字是合法的（丟一張圖過來說「看這個」）
    if not text and not atts:
        raise HTTPException(status_code=400, detail="text or attachments required")
    src = _SRC_NAMES.get(str(payload.get("client") or ""), _SRC_NAMES[""])
    # 插話還是排隊、回音事件、進佇列，全在 worker.submit 裡
    res = await worker.submit(conv_id, text, src, atts)
    return {"queued": res.qsize, "conv_id": conv_id, "msg_id": res.msg_id,
            "steered": res.steered}


# ── 對話管理 ─────────────────────────────────────────────────────────────────
@app.delete("/v1/conversations/{conv_id}")
async def delete_conv(conv_id: str, _: str = Depends(require_token)) -> dict:
    # 助理那條是管家本人的記憶，畫面上沒有刪除鈕，但 API 一直沒擋——一個呼叫就把
    # session 對應清掉。這裡擋死，不靠前端不畫按鈕。
    if conv_id == config.PRIMARY_CONV:
        raise HTTPException(status_code=400, detail="助理那條對話不能刪")
    await worker.remove(conv_id)
    _frontends.pop(conv_id, None)
    await client_pool.drop(conv_id)
    return {"deleted": state_mod.delete_conversation(conv_id)}


@app.post("/v1/conversations/{conv_id}/stop")
async def stop(conv_id: str, _: str = Depends(require_token)) -> dict:
    if not worker.stop(conv_id):
        return {"ok": False, "reason": "nothing running"}
    return {"ok": True}

