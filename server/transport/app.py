"""FastAPI 路由。

這一層只做協定轉換：把 HTTP 請求轉成引擎呼叫、把引擎事件轉成 SSE frame。
不含任何業務邏輯——判斷、重試、續跑全在 engine/。
"""
from __future__ import annotations

import asyncio
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Body, Depends, FastAPI, HTTPException, Request
from sse_starlette.sse import EventSourceResponse, ServerSentEvent

import config
from engine import agenda_tools, client_pool, file_tools, get_state
from engine import local_usage, plan_usage
from engine import state as state_mod
from engine import usage as usage_mod
from engine.meta import generate_title
from engine.turn import handle_turn
from protocol import make_event

from . import agenda_api, files, outbox_api
from .auth import require_token
from .hub import EventHub, SseFrontend
from .tools import screenshot as grab_screenshot

hub = EventHub()
_frontends: dict[str, SseFrontend] = {}
# 佇列裡放的是 (msg_id, text)：手機端要能把「還排著」與「已經在做」畫成兩個樣子，
# 就得指名道姓說出是哪幾則被讀走了，光靠則數對不起來。
_queues: dict[str, asyncio.Queue[tuple[str, str]]] = {}
_workers: dict[str, asyncio.Task] = {}
_running: dict[str, asyncio.Task] = {}   # 進行中的 run_turn，供 /stop 取消


def frontend_for(conv_id: str) -> SseFrontend:
    fe = _frontends.get(conv_id)
    if fe is None:
        fe = _frontends[conv_id] = SseFrontend(hub, conv_id)
    return fe


async def _autoname(conv_id: str, first_message: str) -> None:
    """背景生標題：先用訊息前綴立即可見，Haiku 成功再升級。失敗就保持前綴。"""
    if state_mod.get_title(conv_id) is None:
        state_mod.set_title(conv_id, first_message.strip()[:20] or conv_id)
        title = await generate_title(first_message)
        if title:
            state_mod.set_title(conv_id, title)
            await frontend_for(conv_id).emit(make_event(
                conv_id, "-", "status", note=f"conv_renamed:{title}",
            ))


_MERGE_HEADER = (
    "以下是我在你忙的時候連續傳的幾則訊息，請當成同一件事一起處理。"
    "注意：後面的訊息很可能是在修正或補充前面的，不要當成幾件平行任務各做一遍。\n\n"
)


async def _take_batch(
    q: asyncio.Queue[tuple[str, str]],
) -> list[tuple[str, str]]:
    """取出這一輪要處理的所有訊息：先等第一則，再把當下佇列清空。

    刻意不設則數上限——訊息被收下卻不處理，比排隊太長更傷。
    """
    items = [await q.get()]
    while not q.empty():
        items.append(q.get_nowait())
    return items


def _merge(items: list[str]) -> str:
    """把整批訊息合併成一次 prompt（對照 cc-bot 的 _merge_queued）。

    那句「後面的可能在修正前面的」是關鍵：少了它，模型會把幾則訊息當成
    幾件平行任務全做一遍，而人在等待時補的話多半是更正而不是加碼。
    """
    if len(items) == 1:
        return items[0]
    body = "\n\n".join(f"（第 {i} 則）{t}" for i, t in enumerate(items, 1))
    return _MERGE_HEADER + body


async def _worker(conv_id: str) -> None:
    """單一對話的回合工作者：忙碌時收下訊息，回合結束後一次全讀、合併成一輪。

    重試、續跑、壓縮、錯誤善後全在 `engine.turn.handle_turn` 裡，
    這裡只負責排隊與「使用者按了停止」這一種傳輸層才知道的狀況。
    """
    fe = frontend_for(conv_id)
    q = _queues[conv_id]
    while True:
        batch = await _take_batch(q)
        # 這幾則從「排著」變成「正在做」。手機端靠這則事件把淡掉的氣泡點亮，
        # 少了它，畫面上永遠分不出助理讀到哪一則了。
        await fe.emit(make_event(
            conv_id, "-", "message.taken", msg_ids=[mid for mid, _ in batch],
        ))
        text = _merge([t for _, t in batch])
        state = get_state(conv_id)
        need_title = state_mod.get_title(conv_id) is None
        if len(batch) > 1:
            await fe.emit(make_event(
                conv_id, "-", "status", note=f"把剛才那 {len(batch)} 則一起處理",
            ))
        try:
            task = asyncio.create_task(handle_turn(text, state, fe))
            _running[conv_id] = task
            await task
            if need_title:
                # 標題放在回合完成後才生：第一回合常伴隨 client 建立，
                # 同時再開一個 Haiku 進程會讓首則回覆變慢
                asyncio.create_task(_autoname(conv_id, batch[0]))
        except asyncio.CancelledError:
            # 停止時把還排著的一起丟掉，並更正提示——留著「已排隊 N 則」
            # 會讓人以為還排著，實際上已經不會處理了
            dropped: list[str] = []
            while not q.empty():
                mid, _ = q.get_nowait()
                q.task_done()
                dropped.append(mid)
            detail = "好，我停下了。"
            if dropped:
                detail += f"排著的 {len(dropped)} 則也一起取消了。"
                # 那幾則氣泡得從「排隊中」改成「已取消」——留著排隊中的樣子，
                # 人會一直等一件永遠不會發生的事。
                await fe.emit(make_event(
                    conv_id, "-", "message.dropped", msg_ids=dropped,
                ))
            await fe.emit(make_event(
                conv_id, "-", "error", kind="STOPPED",
                detail=detail, retryable=False,
            ))
        finally:
            _running.pop(conv_id, None)
            for _ in batch:
                q.task_done()


def _ensure_worker(conv_id: str) -> asyncio.Queue[str]:
    q = _queues.get(conv_id)
    if q is None:
        q = _queues[conv_id] = asyncio.Queue()
        _workers[conv_id] = asyncio.create_task(_worker(conv_id))
    return q


@asynccontextmanager
async def lifespan(app: FastAPI):
    reaper = asyncio.create_task(client_pool.reaper())
    try:
        yield
    finally:
        reaper.cancel()
        for t in list(_workers.values()):
            t.cancel()
        await client_pool.shutdown()


app = FastAPI(title="butler", lifespan=lifespan)

# 行事曆／鬧鐘／記帳：路由掛上來，並讓兩條寫入路徑都能廣播變更。
# 助理改的（MCP 工具，在 engine 裡）與你手動改的（agenda_api 的 router）
# 走同一個廣播函式，手機端因此只要處理一種事件。
agenda_api.set_publisher(hub.publish)
agenda_tools.set_change_hook(agenda_api.abroadcast)
app.include_router(agenda_api.router)

# 助理傳檔給手機：同樣的注入方式，工具只管登記，廣播與下載都在這一層。
outbox_api.set_publisher(hub.publish)
file_tools.set_offer_hook(outbox_api.aannounce)
app.include_router(outbox_api.router)


@app.get("/v1/health")
async def health() -> dict:
    """不需要 token——用來確認網路通不通，不洩漏任何內容。"""
    return {
        "ok": True,
        "latest_seq": hub.latest_seq,
        "conversations": len(_queues),
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


@app.post("/v1/conversations/{conv_id}/message")
async def send_message(
    conv_id: str,
    payload: dict = Body(...),
    _: str = Depends(require_token),
) -> dict:
    text = str(payload.get("text") or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="text is required")
    msg_id = uuid.uuid4().hex[:12]
    q = _ensure_worker(conv_id)
    # 這則是不是要排隊，只有伺服器知道：手機端的 busy 是上一則 status 的殘影，
    # 而回合停在提問上等人回答時它甚至不算忙，訊息卻照樣排進來乾等。
    queued = conv_id in _running or not q.empty()
    # 回音事件：讓「別台裝置送的訊息」也出現在所有畫面上。
    # App 端一律以這個事件為準渲染使用者訊息（自己送的不在本地先畫），
    # 多裝置同步因此不需要任何額外機制。
    await frontend_for(conv_id).emit(make_event(
        conv_id, "-", "user.message", text=text, msg_id=msg_id, queued=queued,
    ))
    await q.put((msg_id, text))
    return {"queued": q.qsize(), "conv_id": conv_id, "msg_id": msg_id}


# ── 對話管理 ─────────────────────────────────────────────────────────────────
@app.get("/v1/conversations")
async def list_convs(_: str = Depends(require_token)) -> dict:
    return {"conversations": state_mod.list_conversations()}


@app.post("/v1/conversations")
async def create_conv(_: str = Depends(require_token)) -> dict:
    import uuid as _uuid
    cid = f"c{_uuid.uuid4().hex[:8]}"
    get_state(cid)   # 建立並登記記憶體狀態
    return {"conv_id": cid}


@app.get("/v1/conversations/{conv_id}/snapshot")
async def conv_snapshot(conv_id: str, _: str = Depends(require_token)) -> dict:
    """對話的當下全貌：歷史訊息＋伺服器端的真實忙碌狀態。

    一發解決三件事：切對話看不到歷史、重裝 App 畫面全空、
    以及「UI 撒謊」——busy 原本是純本地狀態，重連時 turn.start 早就過去了，
    畫面顯示空閒但伺服器其實在跑，送出鍵沒變成停止鍵。
    """
    from engine.history import load_history
    from engine.state import eff_effort, eff_model
    from engine.turn import ctx_limit
    task = _running.get(conv_id)
    busy = task is not None and not task.done()
    q = _queues.get(conv_id)
    st = get_state(conv_id)
    return {
        "conv_id": conv_id,
        "busy": busy,
        "queued": q.qsize() if q else 0,
        "latest_seq": hub.latest_seq,
        "messages": await asyncio.to_thread(load_history, conv_id),
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


@app.delete("/v1/conversations/{conv_id}")
async def delete_conv(conv_id: str, _: str = Depends(require_token)) -> dict:
    task = _running.get(conv_id)
    if task and not task.done():
        task.cancel()
    w = _workers.pop(conv_id, None)
    if w:
        w.cancel()
    _queues.pop(conv_id, None)
    _frontends.pop(conv_id, None)
    await client_pool.drop(conv_id)
    return {"deleted": state_mod.delete_conversation(conv_id)}


# ── 電腦上的既有 session ─────────────────────────────────────────────────────
@app.get("/v1/sessions")
async def list_local_sessions(
    limit: int = 60, q: str = "", _: str = Depends(require_token),
) -> dict:
    """列出這台電腦上、butler 還沒接管的 Claude Code session。

    掃 467 個檔加解析要幾百毫秒，包 to_thread 免得卡住事件迴圈——
    那條迴圈同時扛著所有裝置的 SSE，堵住等於全部裝置一起頓。
    """
    from engine.sessions import scan_sessions
    return {"sessions": await asyncio.to_thread(scan_sessions, limit, q)}


@app.post("/v1/sessions/{session_id}/adopt")
async def adopt_session(session_id: str, _: str = Depends(require_token)) -> dict:
    """把一個既有 session 接成新對話。

    只登記狀態，不在這裡建 client——resume 由 client_pool 在下一次送訊息時帶上，
    這樣「接管」本身不會失敗，也不會為了一個使用者可能只是點錯的 session
    去開一個 CC 行程。
    """
    from engine.history import session_exists
    from engine.sessions import session_cwd
    if not session_exists(session_id):
        raise HTTPException(404, "找不到這個 session")

    cid = f"c{uuid.uuid4().hex[:8]}"
    st = get_state(cid)
    st.session_id = session_id
    cwd = session_cwd(session_id)
    if cwd and Path(cwd).is_dir():
        st.cwd = Path(cwd)
    state_mod.persist(st)

    # 標題取 session 的開場白。找不到就用工作目錄的資料夾名，
    # 至少還看得出是哪個專案，比一串 c1a2b3c4 強
    sessions = await asyncio.to_thread(_find_session_meta, session_id)
    title = (sessions or {}).get("title", "") or Path(cwd or ".").name or cid
    state_mod.set_title(cid, title[:40])
    return {"conv_id": cid, "title": title[:40], "cwd": str(st.cwd)}


def _find_session_meta(session_id: str) -> dict | None:
    """接管後要拿標題，但 scan_sessions 會濾掉已登記的，所以單獨撈一次。"""
    from engine.sessions import _any_record_with_cwd, _first_user_record, _projects_dir
    for jf in _projects_dir().glob(f"*/{session_id}.jsonl"):
        hit = _first_user_record(jf)
        if hit:
            return {"title": " ".join(hit["text"].split())[:60]}
        return {"title": ""} if _any_record_with_cwd(jf) else None
    return None


# ── 設定 ─────────────────────────────────────────────────────────────────────
# 對齊官方 app 目前的選單（Fable 5 / Opus 5 / Sonnet 5 / Haiku 4.5）再補上一代
_MODELS = [
    "claude-fable-5",
    "claude-opus-5",
    "claude-sonnet-5",
    "claude-haiku-4-5-20251001",
    "claude-opus-4-8",
    "claude-sonnet-4-6",
]
_EFFORTS = ["low", "medium", "high", "xhigh"]


@app.get("/v1/settings")
async def get_settings(_: str = Depends(require_token)) -> dict:
    return {
        "model": state_mod.default_model,
        "effort": state_mod.default_effort,
        "models": _MODELS,
        "efforts": _EFFORTS,
        "default_cwd": str(config.DEFAULT_CWD),
        "confirm_dangerous": config.CONFIRM_ENABLED,
    }


@app.post("/v1/settings")
async def set_settings(payload: dict = Body(...), _: str = Depends(require_token)) -> dict:
    """設帳號預設 model/effort。改了之後未被單獨覆寫的對話下次自動重建 client。"""
    model = payload.get("model")
    effort = payload.get("effort")
    if model is not None and model not in _MODELS + [""]:
        raise HTTPException(status_code=400, detail=f"unknown model: {model}")
    if effort is not None and effort not in _EFFORTS + [""]:
        raise HTTPException(status_code=400, detail=f"unknown effort: {effort}")
    state_mod.save_defaults(model or None, effort or None)
    return {"model": state_mod.default_model, "effort": state_mod.default_effort}


@app.post("/v1/conversations/{conv_id}/settings")
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
        if model and model not in _MODELS:
            raise HTTPException(status_code=400, detail=f"unknown model: {model}")
        st.model = model or None
    if "effort" in payload:
        effort = str(payload.get("effort") or "")
        if effort and effort not in _EFFORTS:
            raise HTTPException(status_code=400, detail=f"unknown effort: {effort}")
        st.effort = effort or None
    state_mod.persist(st)
    await client_pool.drop(conv_id)   # 指紋失效，下回合重建並 resume 接回
    return {
        "model": eff_model(st), "effort": eff_effort(st),
        "model_override": st.model or "", "effort_override": st.effort or "",
    }


@app.post("/v1/conversations/{conv_id}/cwd")
async def set_cwd(conv_id: str, payload: dict = Body(...),
                  _: str = Depends(require_token)) -> dict:
    from pathlib import Path as _P
    raw = str(payload.get("path") or "").strip()
    p = _P(raw)
    if not raw or not p.is_dir():
        raise HTTPException(status_code=400, detail=f"目錄不存在：{raw}")
    st = get_state(conv_id)
    st.cwd = p
    state_mod.persist(st)
    await client_pool.drop(conv_id)   # cwd 變了，client 指紋失效，下回合重建
    return {"cwd": str(p)}


# ── 工具 ─────────────────────────────────────────────────────────────────────
@app.get("/v1/tools/screenshot")
async def tool_screenshot(_: str = Depends(require_token)):
    from fastapi.responses import Response
    png = await grab_screenshot(all_screens=True)
    return Response(content=png, media_type="image/png")


@app.post("/v1/uploads")
async def upload_file(
    request: Request,
    name: str = "file",
    _: str = Depends(require_token),
) -> dict:
    """手機傳檔上來，回傳它在這台電腦上的絕對路徑。

    body 是**原始 bytes**、檔名走 query——不用 multipart 是為了省掉
    python-multipart 這個相依（服務跑在 MSIX python 上，裝套件比較麻煩），
    而且單檔上傳本來就不需要 multipart 的多段能力。
    """
    data = await request.body()
    if not data:
        raise HTTPException(status_code=400, detail="空檔案")
    if len(data) > files.MAX_UPLOAD_BYTES:
        raise HTTPException(
            status_code=413,
            detail=f"檔案太大（上限 {files.MAX_UPLOAD_BYTES // (1024 * 1024)}MB）",
        )
    path = await asyncio.to_thread(files.save_upload, name, data)
    return {"path": str(path), "bytes": len(data)}


@app.get("/v1/usage")
async def get_usage(span: int = 14, _: str = Depends(require_token)) -> dict:
    """用量表。span 是逐日圖要幾天，手機畫面窄，預設兩週。

    三份資料併在同一個回應裡，各自回答一個問題：
      本體        ── 走助理這條路花了多少（butler 自己記的帳）
      limits      ── 整個帳號還剩多少額度（含網頁版與手機 App，官方端點）
      local       ── 這台電腦上所有 CC 的組成（含 cc-bot、終端機、subagent）
    後兩者都可能失敗（對外請求、掃 1.2GB 逐字稿），各自包一層 try——
    絕不能讓附加資訊把主表拖垮。
    """
    days = max(1, min(span, 92))
    report = await asyncio.to_thread(usage_mod.report, days)
    try:
        report["limits"] = await asyncio.to_thread(plan_usage.limits)
    except Exception:
        report["limits"] = []
    try:
        report["local"] = await asyncio.to_thread(local_usage.report, days)
    except Exception:
        report["local"] = None
    return report


@app.get("/v1/search")
async def search_convs(q: str = "", _: str = Depends(require_token)) -> dict:
    from engine.search import search as do_search
    # jsonl 掃描是同步 IO，丟 thread 免得卡住事件迴圈
    hits = await asyncio.to_thread(do_search, q)
    return {"hits": hits}


@app.post("/v1/asks/{ask_id}/answer")
async def answer_ask(
    ask_id: str,
    payload: dict = Body(...),
    _: str = Depends(require_token),
) -> dict:
    """回答一個 ask。任何一台已授權裝置都可以回答（含破壞性指令確認）。"""
    choice_id = str(payload.get("choice_id") or "")
    text = payload.get("text")
    for fe in _frontends.values():
        if fe.resolve(ask_id, choice_id, text):
            return {"ok": True}
    raise HTTPException(status_code=404, detail="ask not found or already resolved")


@app.post("/v1/conversations/{conv_id}/stop")
async def stop(conv_id: str, _: str = Depends(require_token)) -> dict:
    task = _running.get(conv_id)
    if task is None or task.done():
        return {"ok": False, "reason": "nothing running"}
    task.cancel()
    return {"ok": True}
