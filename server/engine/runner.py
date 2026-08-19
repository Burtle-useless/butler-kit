"""一個回合的訊息迴圈。

與 cc-bot 的 run_claude 對照，這裡做了一件大事：**把渲染整包拿掉**。
原版為了塞進 Discord 單則 2000 字上限而生的 _animate／_roll_message／
_status_block／_append_trace_line 全部不存在（約 100 行最難維護的程式碼消失），
改成往 frontend 發語意事件，由手機端決定怎麼畫。

控制流本身則逐條保留——那些是踩過坑換來的。
"""
from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field
from typing import Any, AsyncIterator

from claude_agent_sdk import (
    AssistantMessage,
    ResultMessage,
    StreamEvent,
    TaskNotificationMessage,
    TaskStartedMessage,
    ToolUseBlock,
)
from claude_agent_sdk._errors import MessageParseError
from claude_agent_sdk._internal.message_parser import parse_message

import config
from protocol import COALESCABLE, Frontend, make_event

from . import client_pool, diag, usage
from .fold import (
    NO_RESPONSE,
    clean_reply,
    fold_messages,
    has_done,
    has_wait,
    parse_ask_marker,
    think_digest,
)
from .state import ConvState, eff_effort, eff_model
from .toolinfo import tool_info


@dataclass(slots=True)
class TurnResult:
    """一個回合的產出。

    cc-bot 把這些散在四個 by-channel 全域 dict（_last_turn_used_tool / _done /
    _wait / _think），呼叫端再去讀。這裡收成一個回傳值——回合管線需要它們做
    續跑與兜底判定，用回傳值傳遞才不會被跨回合覆寫（那正是 cc-bot 兜底層
    變成死碼的根因）。
    """

    reply: str
    session_id: str | None = None
    ask: dict | None = None
    used_tool: bool = False
    done: bool = False          # CC 打了 [[DONE]]：任務已完成，不要續跑
    wait: bool = False          # CC 打了 [[WAIT]]：正在等使用者回答，不要續跑
    all_think: str = ""         # 本回合全部思考文字（空回覆時的兜底素材）
    ctx_tokens: int = 0
    stop_reason: str | None = None
    compacted: bool = False     # 這一輪中途被 CLI 的 auto-compact 切過（見 turn.py 的核對）


async def iter_messages(client: Any) -> AsyncIterator[Any]:
    """逐一取出 client 的訊息並解析成 SDK 物件；解析失敗的直接跳過。

    集中存取 SDK 私有介面（_query.receive_messages）的**唯一入口**：公開 API 遇到
    MessageParseError 會中斷整個回合，這裡改為跳過壞訊息，維持長任務的韌性。
    SDK 升版若動到私有介面，只需要修這一個函式。
    """
    async for raw in client._query.receive_messages():
        try:
            yield parse_message(raw)
        except MessageParseError:
            continue


# 一個回合裡最多跳過幾個「不屬於本 prompt」的 ResultMessage。設上限是避免串流出狀況
# 時無限等下去，屆時寧可退回舊行為（當成本回合的結果）交給既有的空回覆重試接手。
MAX_ALIEN_TURNS = 3


class _DeltaBuffer:
    """逐字 delta 的合併緩衝。

    delta 產生速率遠高於手機消化速率，逐則送會在弱網下把前端淹掉，
    ring buffer 也會被灌爆導致續傳視窗變得極短。這裡按時間窗合併後再送。

    時間窗一定要自己顧。先前 `DELTA_COALESCE_MS` 定義了卻沒有任何地方讀，
    flush 只掛在每 2 秒一次的狀態心跳上——逐字串流因此是**兩秒一大塊**地跳出來，
    不是打字機而是幻燈片。設定是死的，症狀卻只看得出「串流有點頓」，
    對著 config 檢查也不會發現，因為那個數字看起來完全正常。
    """

    def __init__(self, frontend: Frontend, conv_id: str, turn_id: str) -> None:
        self._fe = frontend
        self._conv, self._turn = conv_id, turn_id
        # 型別清單取自 COALESCABLE，不要在這裡另寫一份。那個常數原本零使用端，
        # 而它旁邊的註解寫著「這幾類事件量大且可合併」——看起來是它在決定，
        # 實際上決定權在這一行，改常數不會有任何效果。
        self._buf: dict[str, list[str]] = {k: [] for k in COALESCABLE}
        self._last = time.monotonic()
        self._window = config.DELTA_COALESCE_MS / 1000.0

    def add(self, type_: str, d: str) -> None:
        self._buf[type_].append(d)

    async def maybe_flush(self) -> None:
        """距上次送出超過合併窗才真的送。每個 delta 之後呼叫，成本只是一次減法。"""
        if time.monotonic() - self._last >= self._window:
            await self.flush()

    async def flush(self) -> None:
        self._last = time.monotonic()
        for type_, parts in self._buf.items():
            if not parts:
                continue
            joined = "".join(parts)
            parts.clear()
            await self._fe.emit(make_event(self._conv, self._turn, type_, d=joined))


async def run_turn(
    prompt: str,
    state: ConvState,
    frontend: Frontend,
    turn_id: str,
    expect_assistant: bool = True,
) -> TurnResult:
    """跑完一個回合。呼叫端負責重試、續跑與錯誤呈現（見 turn.py）。

    [expect_assistant] 為 False 時關掉孤兒輪次過濾，給「整輪由 CLI 自己處理、
    模型不發言」的 prompt 用（唯一實例是 /compact）。
    """
    conv = state.conv_id
    start = time.time()
    ctx_before = state.ctx_tokens     # 用來認出 CLI 中途壓縮過（見下面的驟降判斷）
    ctx_usage: dict[str, Any] = {}    # 回合結束時向 SDK 問到的權威 context 數字
    messages: list[Any] = []
    tool_count = 0
    pending_question: dict = {}
    live_text = ""            # 生成中累積的回應文字
    live_think = ""           # 本步累積的思考（定稿進事件後清空）
    all_think = ""            # 本回合全部思考（不隨每步清空，兜底用）
    pending_bg: dict[str, str] = {}
    bg_left = [False]
    compacted = [False]
    saw_assistant = [False]   # 本回合有沒有出現過 AssistantMessage（孤兒輪次判準）
    alien_turns = [0]         # 已跳過幾個別人的 ResultMessage
    last_activity = [time.time()]
    deltas = _DeltaBuffer(frontend, conv, turn_id)

    async def _commit_step(am: Any) -> None:
        """把一個 AssistantMessage 定稿成事件。"""
        nonlocal tool_count, live_text, live_think, pending_question
        content = getattr(am, "content", [])
        if not isinstance(content, list):
            return
        await deltas.flush()
        # 思考摘要定稿：串流中的思考是流動的（下一步一到就被蓋掉），這裡留一份固定紀錄。
        # **不論這步有沒有動工具都要留**——cc-bot 曾把這段寫在 has_tool 判斷之後，
        # 純思考的步驟整步被 return 丟掉，思考就跟著蒸發了。
        # 上限拉到 2000：預設的 220 字太短，使用者回報思考「講到一半就被吞掉」。
        # 截在伺服器端等於**手機永遠拿不回後半段**，而思考裡常有他真正要看的東西
        # （取票代碼、金額、日期）。長度問題交給 App 收摺處理——它預設只露幾行，
        # 想看全文再點開，畫面不會被灌爆。
        digest = think_digest(live_think, 2000) if live_think.strip() else ""
        has_tool = any(isinstance(b, ToolUseBlock) for b in content)
        step_text = "".join(
            (b.text or "") for b in content if hasattr(b, "text")
        ).strip()
        if digest or (has_tool and step_text):
            await frontend.emit(make_event(
                conv, turn_id, "step.commit",
                text=step_text[:400] if has_tool else "",
                think_digest=digest,
            ))
        live_think = ""
        if not has_tool:
            return
        for block in content:
            if isinstance(block, ToolUseBlock):
                name = block.name
                inp = getattr(block, "input", {}) or {}
                if name == "AskUserQuestion":
                    pending_question = inp
                tool_count += 1
                await frontend.emit(make_event(
                    conv, turn_id, "tool.call", **tool_info(name, inp)
                ))
        live_text = ""

    async def _status_loop() -> None:
        """定期送出狀態心跳。取代 cc-bot 的 _animate——這裡不畫任何東西，
        只報事實；轉圈動畫與計時由手機端本地跑，不必為此往返網路。"""
        while True:
            await asyncio.sleep(2)
            await deltas.flush()
            await frontend.emit(make_event(
                conv, turn_id, "status",
                elapsed=round(time.time() - start, 1),
                model=eff_model(state),
                effort=eff_effort(state),
                ctx_tokens=state.ctx_tokens,
                tools=tool_count,
                bg=list(pending_bg.values()),
            ))

    async def _run_client() -> None:
        nonlocal live_text, live_think, all_think
        try:
            client = await client_pool.acquire(state, frontend)
            await client.query(prompt)
            async for message in iter_messages(client):
                last_activity[0] = time.time()   # 有任何訊息＝還活著，重置閒置計時
                # 逐字串流：累積生成中的思考／回應，供前端即時顯示
                if isinstance(message, StreamEvent):
                    ev = message.event or {}
                    if ev.get("type") == "content_block_delta":
                        delta = ev.get("delta", {})
                        dt = delta.get("type")
                        if dt == "text_delta":
                            d = delta.get("text", "")
                            live_text += d
                            deltas.add("text.delta", d)
                        elif dt == "thinking_delta":
                            # 欄位名是 thinking 不是 text（需 display="summarized"）
                            d = delta.get("thinking", "")
                            live_think += d
                            all_think += d
                            deltas.add("thinking.delta", d)
                        await deltas.maybe_flush()
                    continue
                messages.append(message)
                # CLI 自己的 auto-compact 偵測**不在這裡**，在迴圈結束後比對 context
                # 驟降。先前這個位置擋的是 `SystemMessage(subtype="compact_boundary")`，
                # 而 SDK 根本不送這種訊息：已查遍 _internal/message_parser.py 全檔沒有它，
                # 只在 _internal/sessions.py 讀 jsonl 的註解裡出現過。也就是說這條防線
                # 從寫下來的第一天就沒生效過——34 次壓縮只核對到 1 次。
                # 背景任務記帳：純粹為了讓狀態心跳帶上，不影響收工判斷
                if isinstance(message, TaskStartedMessage):
                    pending_bg[message.task_id] = message.description or ""
                elif isinstance(message, TaskNotificationMessage):
                    pending_bg.pop(message.task_id, None)
                if isinstance(message, ResultMessage):
                    # 用量記帳。ResultMessage 是唯一帶 usage 與成本的訊息，
                    # 錯過這一則就永遠拿不回來（SDK 不保留歷史）。
                    usage.record(
                        eff_model(state),
                        getattr(message, "usage", None),
                        getattr(message, "total_cost_usd", None),
                    )
                    # CLI 會為系統事件另起自己的回合，而那一輪的 ResultMessage 會排在
                    # 我們這個 prompt 的前面先到（實例：resume 舊 session 時 CLI 投遞的
                    # 孤兒背景任務通知，它有自己的 promptId、自成一輪）。照單全收就會把
                    # 那一輪的空結果當成本回合的回覆——而**我們的 prompt 其實還在 CLI
                    # 那邊跑**。turn.py 隨即送出空回覆重試或 [[CONTINUE]] 續跑，於是同一
                    # 個 session 上同時有兩條 query 在跑，逐字稿從同一個 parentUuid 長出
                    # 兩支＝session 分叉。重啟 resume 只會接回其中一支，另一支的內容還在
                    # 檔案裡但已不在脈絡裡（2026-08-14 失憶事件的真因，見 助理/待辦事項/TODO.md）。
                    # SDK 無從分辨（ResultMessage 不帶 promptId），只能在這裡判。
                    # 判準：我們送出的 prompt 一定會產生 AssistantMessage——就算是模型只
                    # 思考不吐字的 #50597 空回應，也有一則只含 thinking block 的
                    # AssistantMessage。零 AssistantMessage 的回合必然不是我們的。
                    if (
                        expect_assistant
                        and not saw_assistant[0]
                        and alien_turns[0] < MAX_ALIEN_TURNS
                    ):
                        alien_turns[0] += 1
                        messages.clear()   # 別人回合的訊息不能折進我們的回覆
                        continue
                    # 照 SDK 原生語意收工：receive_response() 的定義就是「收到
                    # ResultMessage 即結束本回合」，背景工作完成時 CLI 會另起一輪，
                    # 那不屬於這一回合。cc-bot v1.40.0 反其道留在迴圈裡等 pending_bg
                    # 清空，遇到「啟動常駐服務」這類永遠不發完成通知的工作就無限卡死，
                    # 回覆早生成完卻送不出去，一路卡到逾時後整段丟失。
                    if pending_bg:
                        bg_left[0] = True
                    break
                if isinstance(message, AssistantMessage):
                    saw_assistant[0] = True   # 上面的孤兒判準靠這個旗標，漏設會把自己的回合也跳掉
                    await _commit_step(message)
            # 回合跑完，順手向 CLI 問一次 context 的權威數字。
            # butler 原本靠模型名稱字串比對去猜上限（opus → 1M），而實測
            # rawMaxTokens=200000、autoCompactThreshold=167000——猜錯五倍的結果是
            # 主動壓縮的門檻算成 85 萬，永遠到不了，34 次壓縮全是 CLI 在回合中途硬切。
            try:
                ctx_usage.update(await client.get_context_usage() or {})
            except Exception:
                pass      # 問不到就退回估算。這是錦上添花，不能讓它拖垮整個回合
        except Exception:
            # 長駐 client 可能已損壞（連線斷／進程死）→ 丟棄，下次重建並 resume 接回
            await client_pool.drop(conv)
            raise

    await frontend.emit(make_event(conv, turn_id, "turn.start", prompt=prompt))
    status_task = asyncio.create_task(_status_loop())
    client_task = asyncio.create_task(_run_client())
    ok = False
    try:
        # 閒置逾時：不限總時長，只在「連續無輸出」超過門檻才視為卡死，
        # 讓長工作流（只要持續有輸出）能一直跑下去。
        # 用 asyncio.wait 而非 sleep 輪詢：任務一完成立即返回，回覆不多等一個輪詢間隔。
        while not client_task.done():
            await asyncio.wait({client_task}, timeout=5)
            if client_task.done():
                break
            if time.time() - last_activity[0] > config.INACTIVITY_TIMEOUT:
                raise asyncio.TimeoutError()
        await client_task
        ok = True
    finally:
        if not client_task.done():
            client_task.cancel()
            try:
                await client_task
            except BaseException:
                pass
            # 被取消＝逾時或使用者喊停：client 卡在半途，丟棄以免下次接到半截回應
            try:
                await client_pool.drop(conv)
            except BaseException:
                pass
        status_task.cancel()
        try:
            await status_task
        except BaseException:
            pass
        await deltas.flush()

    # 收工時還有背景工作沒完成：CLI 稍後會為它另起一輪，那些訊息沒人讀會殘留在串流裡，
    # 被下一則使用者訊息當成自己的回覆讀走（問 A 卻回上一件事的 B）。丟棄 client 斬斷
    # 殘留，下次對話重建並帶 resume 接回同一個 session，脈絡不會斷。
    if bg_left[0]:
        await client_pool.drop(conv)

    content, new_sid, ctx = fold_messages(messages)
    if ctx:
        state.ctx_tokens = ctx
    # 權威值蓋過估算。三個數字各有用途：
    #   totalTokens          → 現在用掉多少（比 result 的估算準）
    #   rawMaxTokens         → 真正的上限，App 的用量長條要拿它當分母
    #   autoCompactThreshold → CLI 會自己動手的門檻，我們得搶在它前面
    total = int(ctx_usage.get("totalTokens") or 0)
    if total:
        state.ctx_tokens = total
    if int(ctx_usage.get("rawMaxTokens") or 0):
        state.ctx_max = int(ctx_usage["rawMaxTokens"])
    if int(ctx_usage.get("autoCompactThreshold") or 0):
        state.ctx_threshold = int(ctx_usage["autoCompactThreshold"])
    # CLI 在這一輪中途壓縮過嗎。context 只會往上長，唯一會讓它掉下來的就是壓縮，
    # 所以「顯著變小」是可靠的事後判準（取代那個從來沒生效的 compact_boundary）。
    # 抓 0.6 而不是「只要變小就算」：留點餘裕給估算與權威值換算之間的抖動。
    if ctx_before and total and total < ctx_before * 0.6:
        compacted[0] = True
    # 續跑判定要用「清理前」的原文判斷 CC 有沒有打標記
    raw_for_flags = content or live_text or ""
    reply = clean_reply(content)
    # 上游 #50597（Opus 工具回合後偶爾把回合末則 text 掉成空 thinking、stop=end_turn，
    # 文字有 stream 出來卻沒進最終訊息物件）→ 用串流累積的 live_text 回收。
    if not reply and live_text.strip():
        reply = clean_reply(live_text)
    stop_reason = None
    if not reply:
        for m in reversed(messages):
            sr = getattr(m, "stop_reason", None)
            if sr:
                stop_reason = sr
                break

    result = TurnResult(
        reply=reply or NO_RESPONSE,
        session_id=new_sid,
        # 內建工具優先、文字標記兜底。實際上 pending_question 永遠是空的——
        # AskUserQuestion 不在 SDK 的工具清單裡（見 fold.ASK_RE 的說明），
        # 真正在運作的是後面這條。前者留著是為了工具哪天回來時自動接上。
        ask=pending_question or parse_ask_marker(raw_for_flags),
        used_tool=tool_count > 0,
        done=has_done(raw_for_flags),
        wait=has_wait(raw_for_flags),
        all_think=all_think,
        ctx_tokens=ctx,
        stop_reason=stop_reason,
        compacted=compacted[0],
    )
    # 這一行是「訊息被吃掉」唯一查得動的證據。三個長度擺在一起才有意義：
    #   texts   → 模型真的講了幾則、各多長（對得上 CC 逐字稿就代表 runner 讀到了）
    #   live    → 串流累積到多少（有值而 fold 為空＝訊息物件掉字，#50597）
    #   reply   → 最後折出來要送的長度（0 就是這裡斷的，不必再往下查）
    # 沒有它的時候，同一個症狀有三種可能的斷點而事後完全分不出來（見 diag.py）。
    diag.record(
        "turn",
        conv=conv, turn=turn_id, prompt=diag.head(prompt, 40),
        msgs=[type(m).__name__ for m in messages],
        texts=[
            len("".join(b.text for b in m.content if hasattr(b, "text")))
            for m in messages if isinstance(m, AssistantMessage)
        ],
        live=len(live_text), fold=len(content), reply=len(reply or ""),
        tools=tool_count, alien=alien_turns[0], compacted=compacted[0],
        stop=stop_reason, ok=ok, bg_left=bg_left[0],
    )
    await frontend.emit(make_event(
        conv, turn_id, "turn.end", ok=ok, elapsed=round(time.time() - start, 1),
    ))
    return result
