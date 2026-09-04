"""對話的 HTTP 路由：列表、快照、接管電腦上的 session、對話層設定、搜尋、提問回答。

只做協定轉換，不含業務邏輯。會動到回合佇列的端點（送訊息、停止、刪對話）
留在 `app.py`——那些是 worker 核心的一部分，跟這裡的唯讀／設定類端點不同。

模組尾端的 `from . import app as core` 是刻意的：這裡要讀 `core.worker`（哪條在跑、
排著哪幾則）與 `core.frontend_for`，而 app.py 在模組層 include 這個 router，
頂端互相 import 會繞成一圈。放在尾端、於呼叫時才查屬性，測試對 `transport.app`
的 monkeypatch（例如換掉 `_rename_adopted`）也照樣生效。
"""
from __future__ import annotations

import asyncio
import uuid
from pathlib import Path

from fastapi import APIRouter, Body, Depends, HTTPException

import outbox
from engine import bg_notify, client_pool, get_state
from engine import models as model_catalog
from engine import state as state_mod

from .auth import require_token

router = APIRouter()


# ── 對話管理 ─────────────────────────────────────────────────────────────────
@router.get("/v1/conversations")
async def list_convs(_: str = Depends(require_token)) -> dict:
    return {"conversations": state_mod.list_conversations()}


@router.post("/v1/conversations")
async def create_conv(_: str = Depends(require_token)) -> dict:
    cid = f"c{uuid.uuid4().hex[:8]}"
    get_state(cid)   # 建立並登記記憶體狀態
    return {"conv_id": cid}


@router.get("/v1/conversations/{conv_id}/snapshot")
async def conv_snapshot(conv_id: str, _: str = Depends(require_token)) -> dict:
    """對話的當下全貌：歷史訊息＋伺服器端的真實忙碌狀態。

    一發解決三件事：切對話看不到歷史、重裝 App 畫面全空、
    以及「UI 撒謊」——busy 原本是純本地狀態，重連時 turn.start 早就過去了，
    畫面顯示空閒但伺服器其實在跑，送出鍵沒變成停止鍵。
    """
    from engine.history import load_history, HISTORY_PAGE
    from engine.state import eff_effort, eff_model
    from engine.turn import ctx_limit
    busy = core.worker.is_running(conv_id)
    st = get_state(conv_id)
    # 排隊數只算使用者訊息，跟 pending 那份清單同一個定義（wake 不算）
    pending = core.worker.pending_of(conv_id)
    messages = await asyncio.to_thread(load_history, conv_id, HISTORY_PAGE)
    return {
        "conv_id": conv_id,
        "busy": busy,
        "queued": len(pending),
        "latest_seq": core.hub.latest_seq,
        "messages": messages,
        # 只帶最後一頁；捲到頂再打 /history 補更早的。「還有更早」用「這頁滿了」
        # 近似——多算一次整份逐字稿只為了這個布林值不划算，錯的代價只是多點一次
        "has_more": len(messages) >= HISTORY_PAGE,
        # 這段對話裡傳過的檔案。卡片不在 CC 的逐字稿裡（那是 butler 自己造的），
        # 不從這裡補的話，App 一重啟／伺服器一重啟，對話裡的下載框就整排消失——
        # 使用者 2026-08-18 回報「更新之後下載框會不見」。檔案本身沒丟，
        # 工具頁的收件匣一直都在，消失的只有對話裡那張卡片。
        "files": await asyncio.to_thread(outbox.for_conv, conv_id),
        # 排著還沒輪到的訊息。同樣不在逐字稿裡，同樣得從這裡補，
        # 否則畫面重建後使用者看不到自己剛剛送出了什麼（見 Worker.pending_of）。
        "pending": pending,
        # 還在等你回答的提問。同一個病的第四種：這個不只是看不到，等不到答案
        # 會被 fail-closed 當成拒絕，那件事就被擋掉了（見 SseFrontend.pending_asks）。
        "asks": core.frontend_for(conv_id).pending_asks(),
        # 還在跑的背景工作。同一個病的第五種：`bg.state` 只在有變化時發，
        # App 重開之後在下一件工作跑完之前收不到任何一則，那行「背景：…」
        # 就消失了——而它正是「等一下還會有東西自己冒出來」的唯一預告。
        "bg": bg_notify.wire(conv_id),
        # 對話層狀態（等同 cc-bot 的 /status）：生效值＋覆寫值分開給，
        # App 才能顯示「跟隨帳號預設（sonnet）」與「已覆寫成 opus」的差別
        "model": eff_model(st),
        "effort": eff_effort(st),
        "model_override": st.model or "",
        "effort_override": st.effort or "",
        "cwd": str(st.cwd),
        "ctx_tokens": st.ctx_tokens,
        # 上限隨模型變（Opus 在 max 方案是 1M），App 端不能自己猜——
        # 猜 200K 會讓 Opus 的用量長條在兩成時就顯示滿格
        "ctx_limit": ctx_limit(st),
    }


@router.post("/v1/conversations/{conv_id}/tasks/{task_id}/stop")
async def stop_bg_task(
    conv_id: str,
    task_id: str,
    _: str = Depends(require_token),
) -> dict:
    """停掉一件還在跑的背景工作。

    先前要停只能開口叫助理去停，而助理可能正忙著別的回合，一句話得排隊等好幾分鐘
    ——這對「我不想讓它再跑下去」這種需求太慢了。SDK 一直有 `stop_task()`，
    只是沒人接。

    停掉之後 CLI 會送一則 status=stopped 的完成通知，帳跟卡片走既有那條路更新，
    這裡不自己動帳——兩處都寫就會有兩份真相。
    """
    box = client_pool.peek(conv_id)
    if box is None:
        # 進程已經被回收，那件工作早就跟著沒了。帳上若還掛著就地結掉，
        # 否則卡片會永遠停在「進行中」——那是這條路唯一會漏的狀態。
        if bg_notify.finish(conv_id, task_id, "stopped") is not None:
            await core.frontend_for(conv_id).emit(bg_notify.bg_event(conv_id))
        return {"ok": True, "detail": "連線已回收，工作也早就結束了"}
    await box.client.stop_task(task_id)
    return {"ok": True}


# ── 電腦上的既有 session ─────────────────────────────────────────────────────
@router.get("/v1/sessions")
async def list_local_sessions(
    limit: int = 60, q: str = "", offset: int = 0, _: str = Depends(require_token),
) -> dict:
    """列出這台電腦上、butler 還沒接管的 Claude Code session。

    掃上百個檔加解析要幾百毫秒，包 to_thread 免得卡住事件迴圈——
    那條迴圈同時扛著所有裝置的 SSE，堵住等於全部裝置一起頓。

    分頁而不是一次全給：這台機器上有上百個 session 且只會愈來愈多，每一筆都要
    開檔讀前幾行，而使用者九成的情況只看最上面幾筆。has_more 讓 App 滑到底再續拉。
    """
    from engine.sessions import scan_sessions
    rows = await asyncio.to_thread(scan_sessions, limit, q, offset)
    return {"sessions": rows, "has_more": len(rows) >= limit}


@router.post("/v1/sessions/{session_id}/adopt")
async def adopt_session(session_id: str, _: str = Depends(require_token)) -> dict:
    """把一個既有 session 接成新對話。

    只登記狀態，不在這裡建 client——resume 由 client_pool 在下一次送訊息時帶上，
    這樣「接管」本身不會失敗，也不會為了一個使用者可能只是點錯的 session
    去開一個 CC 行程。

    接的是**複印本**而不是原本那個 id，理由見 `sessions.fork_session`：共用
    同一份逐字稿會讓桌面與手機兩邊互相覆蓋。複印要讀寫整個檔案，包 to_thread
    免得卡住事件迴圈。

    步驟的順序是刻意的：**所有查得到、會失敗的事情都排在複印前面**，複印成功
    之後只剩下不會失敗的賦值。2026-08-18 吃過反過來的虧——複印排在第二步、
    抓標題排在後面，而抓標題那行有個壞掉的 import，於是每點一次接管就留下
    一份上百 MB 的複印檔加一條沒有標題的殭屍對話，使用者只看到「失敗」。
    真的走到一半才炸的話也要把複印檔收掉，不能留在硬碟上。
    """
    from engine.history import session_exists
    from engine.sessions import fork_session, session_cwd
    if not session_exists(session_id):
        raise HTTPException(404, "找不到這個 session")

    # 先把要用的東西全部備齊，這幾步都只讀不寫，失敗了也不留痕跡
    cwd = session_cwd(session_id)
    meta = await asyncio.to_thread(_find_session_meta, session_id)
    title = (meta or {}).get("title", "") or Path(cwd or ".").name

    forked = await asyncio.to_thread(fork_session, session_id)
    if forked is None:
        raise HTTPException(500, "複製這個 session 失敗，沒有接管")

    cid = f"c{uuid.uuid4().hex[:8]}"
    try:
        st = get_state(cid)
        st.session_id = forked
        st.forked_from = session_id
        # cwd 仍然問原本那個檔：複印本的內容一模一樣，但原 id 才是使用者選的
        # 那個，查不到東西時比較好對照。
        if cwd and Path(cwd).is_dir():
            st.cwd = Path(cwd)
        state_mod.persist(st)
        state_mod.set_title(cid, (title or cid)[:40])
    except Exception:
        # 收掉剛複印出來的檔，否則失敗一次就在硬碟上多一份上百 MB 的垃圾
        from engine.sessions import _projects_dir
        for jf in _projects_dir().glob(f"*/{forked}.jsonl"):
            jf.unlink(missing_ok=True)
        raise

    # 真正的短標題交給 _rename_adopted 在背景生，幾秒後自己換掉。
    asyncio.create_task(core._rename_adopted(cid))
    return {"conv_id": cid, "title": (title or cid)[:40], "cwd": str(st.cwd)}


def _find_session_meta(session_id: str) -> dict | None:
    """接管後要拿標題，但 scan_sessions 會濾掉已登記的，所以單獨撈一次。"""
    from engine.sessions import _head_scan, _projects_dir
    for jf in _projects_dir().glob(f"*/{session_id}.jsonl"):
        # _head_scan 一次掃描就同時給開場白與 cwd；找不到開場白但檔案有料時
        # 它回的 text 是空字串，正好對應「有這個 session、但沒有像樣的標題」。
        hit = _head_scan(jf)
        if hit is None:
            return None
        return {"title": " ".join(hit["text"].split())[:60]}
    return None


# ── 對話層設定 ───────────────────────────────────────────────────────────────
@router.post("/v1/conversations/{conv_id}/settings")
async def set_conv_settings(conv_id: str, payload: dict = Body(...),
                            _: str = Depends(require_token)) -> dict:
    """只覆寫這一個對話的 model/effort（等同 cc-bot 的 /model_session、/effort_session）。

    空字串＝清除覆寫、回到跟隨帳號預設。ConvState 的 model/effort 欄位與
    eff_model/eff_effort 三層邏輯早就在了，先前只是沒有任何端點會寫入它們。
    """
    from engine.state import eff_effort, eff_model
    st = get_state(conv_id)
    if "model" in payload:
        model = str(payload.get("model") or "")
        if model and not model_catalog.is_known(model):
            raise HTTPException(status_code=400, detail=f"unknown model: {model}")
        st.model = model or None
    if "effort" in payload:
        effort = str(payload.get("effort") or "")
        if effort and effort not in model_catalog.ALL_EFFORTS:
            raise HTTPException(status_code=400, detail=f"unknown effort: {effort}")
        st.effort = effort or None
    state_mod.persist(st)
    await client_pool.drop(conv_id)   # 指紋失效，下回合重建並 resume 接回
    return {
        "model": eff_model(st), "effort": eff_effort(st),
        "model_override": st.model or "", "effort_override": st.effort or "",
    }


@router.post("/v1/conversations/{conv_id}/cwd")
async def set_cwd(conv_id: str, payload: dict = Body(...),
                  _: str = Depends(require_token)) -> dict:
    raw = str(payload.get("path") or "").strip()
    p = Path(raw)
    if not raw or not p.is_dir():
        raise HTTPException(status_code=400, detail=f"目錄不存在：{raw}")
    st = get_state(conv_id)
    st.cwd = p
    state_mod.persist(st)
    await client_pool.drop(conv_id)   # cwd 變了，client 指紋失效，下回合重建
    return {"cwd": str(p)}


# ── 搜尋與提問 ───────────────────────────────────────────────────────────────
@router.get("/v1/conversations/{conv_id}/history")
async def conv_history(
    conv_id: str, before_ms: int, limit: int = 60, _: str = Depends(require_token),
) -> dict:
    """歷史分頁：早於 `before_ms` 的最後 `limit` 則，舊到新。App 捲到頂時用。"""
    from engine.history import HISTORY_PAGE, load_history_before
    limit = max(1, min(int(limit), HISTORY_PAGE * 4))
    msgs, more = await asyncio.to_thread(load_history_before, conv_id, before_ms, limit)
    return {"messages": msgs, "has_more": more}


@router.get("/v1/search")
async def search_convs(q: str = "", _: str = Depends(require_token)) -> dict:
    from engine.search import search as do_search
    # jsonl 掃描是同步 IO，丟 thread 免得卡住事件迴圈
    hits = await asyncio.to_thread(do_search, q)
    return {"hits": hits}


@router.post("/v1/asks/{ask_id}/answer")
async def answer_ask(
    ask_id: str,
    payload: dict = Body(...),
    _: str = Depends(require_token),
) -> dict:
    """回答一個 ask。任何一台已授權裝置都可以回答（含破壞性指令確認）。"""
    choice_id = str(payload.get("choice_id") or "")
    for fe in core._frontends.values():
        if fe.resolve(ask_id, choice_id):
            return {"ok": True}
    raise HTTPException(status_code=404, detail="ask not found or already resolved")


# 見模組說明：刻意放在尾端，避免與 app.py 在頂端互相 import。
from . import app as core  # noqa: E402
