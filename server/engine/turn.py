"""回合管線：一則使用者訊息的完整生命週期。

這一層是整個引擎「可靠性密度」最高的地方，內容幾乎全部來自 cc-bot 一年份的災情
（`discord_bot.py` 3596-3718 行）。單看每一段都像過度防禦，但每一段都對應一次真實故障：

- 空回覆三層防線 → 上游 #50597：模型只產思考塊、一個字不寫就 end_turn
- 自動續跑 → 任務做到一半停手，使用者看到半成品以為做完了
- auto-compact → context 撐爆後整個對話報廢
- 錯誤善後分流 → 「重新登入也救不回的 401」

`runner.run_turn` 只負責跑一個回合；要不要重試、要不要續跑、怎麼善後，全在這裡。
"""
from __future__ import annotations

import uuid
from dataclasses import replace
from typing import Any

import config
from protocol import AskChoice, AskRequest, AskResponse, Event, Frontend, make_event

from . import client_pool
from .errors import CCError, wrap
from .fold import NO_RESPONSE, think_digest
from .runner import TurnResult, run_turn
from .state import ConvState, eff_model, persist

# 一則使用者訊息最多容許幾輪「CC 反問→使用者回答」。
# 有上限是因為模型偶爾會陷入反覆確認的迴圈，而使用者只想要它動手。
MAX_ASK_ROUNDS = 3

# context 用到幾成就先壓縮。0.85 是 cc-bot 實測值：再高就有機會在壓縮前先撞爆。
COMPACT_AT = 0.85

# 狀態列的階段旗標：壓縮期間的每則 status 都帶著它，手機端才知道這段時間
# 它不是在回話，是在整理記憶。空值＝一般回合。
COMPACT_PHASE = "compacting"

# 各模型的 context 上限。抓保守值即可——這只用來決定何時壓縮，估低頂多多壓一次。
CTX_LIMIT_DEFAULT = 200_000
CTX_LIMIT_1M = 1_000_000
# 高階模型在這些方案下原生就是 1M context（cc-bot context_limit_for 的判定）
_BIG_CTX_PLANS = frozenset({"max", "team", "enterprise"})
_BIG_CTX_FAMILIES = ("opus", "fable", "mythos")

# 開頭必須是 CLI 的 /compact 指令。先前這裡是一句普通中文（「請把對話壓縮成
# 重點摘要…」），模型乖乖輸出一篇摘要文字，但 CLI 的 context **完全沒有被剪**；
# ctx_tokens 歸零後下一輪又立刻超標，於是每則訊息前都假壓一次（實錄：三分鐘
# 觸發四次），摘要問答還全數寫進 session 逐字稿被歷史載入原樣顯示。
# /compact 由 CLI 自己處理：真的剪 context、整輪零 AssistantMessage（cc-bot
# discord_bot.py:1435 的實測註解），runner 只等 ResultMessage 所以天然相容。
COMPACT_PROMPT = (
    "/compact 壓縮時務必完整保留：本次對話實際完成的改動"
    "（檔案路徑、新增或修改的函式與指令與欄位名稱）、版本號變動、尚未完成的待辦。"
    "做了什麼比討論了什麼更重要，不可省略。"
)

CONTINUE_NUDGE = (
    "剛才那一步還沒收尾。如果整件事已經完成，只要回覆 [[DONE]] 就好；"
    "如果還在等我回答，回覆 [[WAIT]]；否則請接著把它做完。"
)

EMPTY_RETRY_NUDGE = "剛才沒有收到你的文字回覆，請把結果直接說出來。"

# 回合中途被 CLI 的 auto-compact 切斷後的核對提示。
# 實錄：記帳工具跑完、那句「記好了」還沒說出口就撞上壓縮，重啟後的摘要裡卻寫著
# 「已經跟他說記好了」——工具真的執行了，使用者卻從頭到尾沒看到任何確認。
# 摘要是模型自己寫的，寫錯了沒有任何機制會發現，只能壓縮後強制回頭對一次帳。
COMPACT_RECHECK_NUDGE = (
    "剛才這一輪中途觸發了自動壓縮，你現在看到的前文是摘要而不是原文。"
    "摘要有可能把「還沒做」寫成「做完了」，也可能漏掉你已經做完、但話還沒說出口的事。"
    "請對照使用者最初那則訊息逐項核對：他交代的每一件事，你是真的做完、而且把結果講給他聽了嗎？"
    "有漏掉的現在補上（只補漏掉的那句，不要把已經講過的重講一遍）；"
    "全部都交代過了就只回 [[DONE]]。"
)


def ctx_limit(state: ConvState) -> int:
    """這條對話的 context 上限，決定何時觸發 auto-compact。

    先前一律回 200K，Opus 這種原生 1M 的模型會在 170K 就被壓縮一次——
    白白剪掉八成還能用的脈絡，而且壓縮本身要花十幾秒。
    """
    model = (eff_model(state) or "").lower()
    if model.endswith("[1m]"):          # 明確要求 1M 的後綴，優先於方案判斷
        return CTX_LIMIT_1M
    if config.ACCOUNT_PLAN in _BIG_CTX_PLANS and any(f in model for f in _BIG_CTX_FAMILIES):
        return CTX_LIMIT_1M
    return CTX_LIMIT_DEFAULT


def parse_ask(ask_data: dict) -> AskRequest | None:
    """把 AskUserQuestion 的工具輸入轉成 AskRequest。

    一次只處理第一題：手機一問一答的介面收不了多題，多的會被吞掉（cc-bot 的防呆）。
    """
    if not isinstance(ask_data, dict):
        return None
    questions = ask_data.get("questions") if "questions" in ask_data else [ask_data]
    if not questions:
        return None
    q = questions[0]
    if not isinstance(q, dict):
        return None
    choices = [
        AskChoice(
            id=str(o.get("label", "")),
            label=str(o.get("label", "")),
            detail=str(o.get("description", "")),
        )
        for o in (q.get("options") or [])
        if isinstance(o, dict) and o.get("label")
    ]
    if not choices:
        return None
    return AskRequest(
        kind="choose",
        title=str(q.get("question") or q.get("header") or "需要你決定一件事"),
        body="",
        choices=choices,
        timeout_sec=config.CONFIRM_TIMEOUT_SEC,
    )


class _QuietFrontend:
    """只讓 status 通過的 Frontend 包裝。

    auto-compact 會叫模型把整段對話寫成重點摘要。那一輪的 text.delta 是**維運
    產物、不是助理在跟使用者說話**，先前卻跟正常回覆走同一條路送到畫面上，
    使用者看到的就是「先打出一大堆對話摘要，然後被下一個 turn.start 清掉」——
    他的原話「顯示一堆對話摘要再收回去」講的就是這個。

    status 仍然放行：壓縮可能跑十幾秒，全靜音會變成畫面卡住不動沒人知道在幹嘛。
    放行時蓋上 phase 旗標——這一輪的思考被擋掉了，手機端的狀態列沒東西可寫就
    退回預設的「想一下」，看起來跟平常在回話一模一樣，人不知道它其實在整理記憶。
    每兩秒一次的心跳都會經過這裡，所以旗標自然覆蓋整段壓縮期間。
    """

    def __init__(self, inner: Frontend) -> None:
        self._inner = inner

    async def emit(self, ev: Event) -> None:
        if ev.type == "status":
            # replace 而不是 make_event：重建會拿到新序號，斷線續傳的錨點會亂
            await self._inner.emit(
                replace(ev, data={**ev.data, "phase": COMPACT_PHASE})
            )

    async def ask(self, req: AskRequest) -> AskResponse | None:
        # 壓縮不該問問題，真問了也要讓它問得到——沉默地吞掉會讓回合卡到逾時
        return await self._inner.ask(req)


async def _maybe_compact(state: ConvState, frontend: Frontend, conv: str) -> None:
    """context 接近上限時先壓縮，避免下一回合直接撞 CONTEXT_FULL。"""
    if state.ctx_tokens < int(ctx_limit(state) * COMPACT_AT):
        return
    turn_id = f"c-{uuid.uuid4().hex[:8]}"
    await frontend.emit(make_event(
        conv, turn_id, "status", note="對話有點長了，我先整理一下記憶",
        phase=COMPACT_PHASE,
    ))
    try:
        # expect_assistant=False：/compact 整輪由 CLI 自己處理，實測只有 SystemMessage／
        # UserMessage／ResultMessage，模型完全不發言。套孤兒判準會把它自己的 ResultMessage
        # 當成別人的而一直等，直到閒置逾時。
        res = await run_turn(
            COMPACT_PROMPT, state, _QuietFrontend(frontend), turn_id,
            expect_assistant=False,
        )
        if res.session_id:
            state.session_id = res.session_id
        state.ctx_tokens = 0
        persist(state)
    except Exception:
        # 壓縮失敗不該讓使用者的原始需求跟著陣亡，讓它繼續往下跑
        pass


async def handle_turn(text: str, state: ConvState, frontend: Frontend) -> None:
    """處理一則使用者訊息，直到產出最終回覆或明確的錯誤。"""
    conv = state.conv_id
    try:
        await _maybe_compact(state, frontend, conv)
        await _run_with_recovery(text, state, frontend, conv)
    except CCError as e:
        await _handle_error(e, state, frontend, conv)
    except Exception as e:  # noqa: BLE001 — 最外層守門，任何漏網都要變成事件而非靜默
        await _handle_error(wrap(e), state, frontend, conv)


async def _say(
    frontend: Frontend, conv: str, markdown: str, pending_ask: bool = False,
) -> None:
    """把一輪的回覆定稿到畫面上。

    `pending_ask` 代表這則定稿後面緊接著一個 ask.request。手機端靠它判斷
    「這回合還沒結束」——否則它會先為 reply.final 推一則「做完了」，
    再為 ask.request 推一則「在等你回答」，兩則疊在通知欄裡，
    而且前面那則的語意是錯的（事情正卡著等人回答，不是做完了）。
    時序上分不出來：兩個事件相隔只有幾毫秒，得由知道答案的源頭直接講。
    """
    await frontend.emit(make_event(
        conv, "-", "reply.final", markdown=markdown, pending_ask=pending_ask,
    ))


async def _run_with_recovery(
    text: str, state: ConvState, frontend: Frontend, conv: str,
) -> None:
    """跑一則訊息，含空回覆重試、反問迴路、未完成續跑。

    **每一輪拿到回覆就立刻定稿**，不再累積到最後一次送出。

    先前是把所有輪次串成一則、等全部跑完才發 reply.final。但續跑與重試之間會
    夾一個新的 turn.start，前端收到就把已經逐字打在畫面上的上一輪回覆清空——
    使用者眼中就是「打了一大段又收回去」，而且要等所有續跑結束才重新出現。
    改成每輪各自定稿後，串流文字原地變成定稿內容，中間不再有空窗。
    """
    prompt = text
    said = False        # 這則訊息至少已經回過一句話（決定要不要續跑）
    compacted = False   # 這則訊息的任何一輪被 auto-compact 切過

    for ask_round in range(MAX_ASK_ROUNDS + 1):
        res = await _run_once_with_empty_retry(prompt, state, frontend, conv)
        compacted = compacted or res.compacted

        # CC 想問使用者一件事 → 直接在這個回合裡問完，不必等使用者另外發訊息。
        # 這段刻意排在定稿之前：定稿要帶上「後面還有問題」，見 _say 的說明。
        ask_req = parse_ask(res.ask) if res.ask else None
        will_ask = ask_req is not None and ask_round < MAX_ASK_ROUNDS

        if res.reply and res.reply != NO_RESPONSE:
            # 問題前的說明也走這裡：使用者得先看到「在問什麼」才看得懂選項
            await _say(frontend, conv, res.reply, pending_ask=will_ask)
            said = True

        if will_ask:
            answer = await frontend.ask(ask_req)
            if answer is None:
                await _say(frontend, conv,
                           "（我等你回覆等到逾時了，先擱著。需要的話再跟我說。）")
                return
            prompt = answer.text or answer.choice_id
            continue

        if compacted:
            res = await _recheck_after_compact(state, frontend, conv)
            said = said or bool(res.reply and res.reply != NO_RESPONSE)
        await _auto_continue(said, res, state, frontend, conv)
        return


async def _run_once_with_empty_retry(
    prompt: str, state: ConvState, frontend: Frontend, conv: str,
) -> TurnResult:
    """跑一個回合；遇到「只有思考、沒有文字」的空回覆時走三層防線。

    上游 #50597 已被官方 closed as not planned，client 端兜底是唯一的路。
    """
    turn_id = f"t-{uuid.uuid4().hex[:8]}"
    res = await run_turn(prompt, state, frontend, turn_id)
    _persist_sid(res, state)

    # 兜底素材自己保管：**不可**每輪覆寫。
    # 關思考的那一輪拿不到思考文字，會把前面有內容的那份洗成空字串，
    # 第三層因此永遠讀到空值、形同死碼（cc-bot 踩過，由另一個 session 抓出來）。
    best_think = (res.all_think or "").strip()
    # 壓縮旗標同樣不可被重試輪覆寫：被切斷的是第一輪，重試輪沒被切不代表沒發生過
    saw_compact = res.compacted
    empty = 0
    try:
        while res.reply == NO_RESPONSE and not res.ask and empty < config.MAX_EMPTY_RETRY:
            empty += 1
            # 第一次仍帶思考（多半一次就過）；再失敗才動用關思考的逃生門，
            # 那是目前唯一公認有效的 workaround。
            state._no_think = empty >= 2
            await frontend.emit(make_event(
                conv, turn_id, "status",
                note=f"剛才沒收到文字，重試第 {empty}/{config.MAX_EMPTY_RETRY} 次",
            ))
            retry_id = f"t-{uuid.uuid4().hex[:8]}"
            res = await run_turn(EMPTY_RETRY_NUDGE, state, frontend, retry_id)
            _persist_sid(res, state)
            saw_compact = saw_compact or res.compacted
            if len((res.all_think or "").strip()) > len(best_think):
                best_think = (res.all_think or "").strip()
    finally:
        # 一定要還原，否則這個對話從此永久不思考。
        # 指紋隨之復原，下次對話會自動重建 client。
        state._no_think = False

    if res.reply == NO_RESPONSE and not res.ask and best_think:
        # 第三層：拿思考摘要當回覆，總比丟一句「無回應」讓整回合的推理白費好
        res.reply = f"（我想了一輪但沒能好好說出來，這是我的思路）\n\n{think_digest(best_think, 1500)}"
    if res.reply == NO_RESPONSE and res.stop_reason == "max_tokens":
        res.reply = "這次的輸出把額度用完了，話沒說完。要我換個小一點的範圍再試一次嗎？"
    res.compacted = saw_compact
    return res


async def _recheck_after_compact(
    state: ConvState, frontend: Frontend, conv: str,
) -> TurnResult:
    """壓縮把一則訊息的處理過程切成兩半之後，回頭核對有沒有做了卻沒說的事。

    只跑一輪，而且提示是中性的：真的什麼都沒漏時，CC 只會回一個 [[DONE]]，
    不會被逼著多講廢話（沿用 _auto_continue 那套話術的設計）。
    """
    turn_id = f"r-{uuid.uuid4().hex[:8]}"
    await frontend.emit(make_event(
        conv, turn_id, "status",
        note="剛才被自動壓縮打斷，我回頭對一下有沒有漏講",
        phase=COMPACT_PHASE,
    ))
    res = await run_turn(COMPACT_RECHECK_NUDGE, state, frontend, turn_id)
    _persist_sid(res, state)
    if res.reply and res.reply != NO_RESPONSE:
        await _say(frontend, conv, res.reply)
    return res


async def _auto_continue(
    said: bool, res: TurnResult, state: ConvState,
    frontend: Frontend, conv: str,
) -> None:
    """未完成自動續跑。

    這輪動過工具、卻沒打完成標記 [[DONE]]、也不在等使用者回答 [[WAIT]]
    → 極可能被截斷或半途停手。用中性話術補跑，最多 MAX_AUTO_CONTINUE 次。
    中性話術讓「其實已完成、只是忘了打標記」的情況無害吸收：
    CC 只會回一個 [[DONE]] 就停，不會被逼著亂做事。
    """
    cont = 0
    while (
        cont < config.MAX_AUTO_CONTINUE
        and not res.ask
        and said
        and res.used_tool
        and not res.wait
        and not res.done
    ):
        cont += 1
        turn_id = f"k-{uuid.uuid4().hex[:8]}"
        res = await run_turn(CONTINUE_NUDGE, state, frontend, turn_id)
        _persist_sid(res, state)
        if res.reply and res.reply != NO_RESPONSE:
            await _say(frontend, conv, res.reply)


def _persist_sid(res: TurnResult, state: ConvState) -> None:
    if res.session_id:
        state.session_id = res.session_id
        persist(state)


async def _handle_error(
    err: CCError, state: ConvState, frontend: Frontend, conv: str,
) -> None:
    """依錯誤類型決定善後動作，再把結果告訴使用者。"""
    if err.should_reset_session:
        state.session_id = None
        state.ctx_tokens = 0
        persist(state)
    if err.should_drop_client:
        # AUTH 尤其重要：帶著過期憑證出生的 client 進程不會自己撿新權杖，
        # 不丟掉就會永遠 401，連重新登入都救不回。
        await client_pool.drop(conv)
    await frontend.emit(make_event(
        conv, "-", "error",
        kind=err.kind,
        detail=err.user_msg,
        raw=err.raw[:500],
        retryable=err.retryable,
    ))
