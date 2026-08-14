"""回合管線控制流測試。

用假的 runner 驗證重試／續跑／反問／善後的判斷邏輯，不燒 API。
這些是 cc-bot 用一年災情換來的規則，每一條都要有測試釘住，
否則下次重構時「看起來多餘」就被順手拿掉了。
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path

import config  # noqa: F401
from engine import fold as fold_mod
from engine import turn as turn_mod
from engine.errors import CCError
from engine.fold import NO_RESPONSE
from engine.runner import TurnResult
from engine.state import ConvState
from protocol import AskResponse, Event, make_event

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


class FakeFrontend:
    def __init__(self, answers: list[AskResponse | None] | None = None) -> None:
        self.events: list[Event] = []
        self.asks: list = []
        self._answers = answers or []

    async def emit(self, ev: Event) -> None:
        self.events.append(ev)

    async def ask(self, req):
        self.asks.append(req)
        return self._answers.pop(0) if self._answers else None

    def replies(self) -> list[str]:
        return [e.data.get("markdown", "") for e in self.events if e.type == "reply.final"]

    def errors(self) -> list[dict]:
        return [e.data for e in self.events if e.type == "error"]


def make_state() -> ConvState:
    return ConvState(conv_id="test", cwd=Path.home())


class Scripted:
    """依序回傳預設結果的假 run_turn，並記錄每次呼叫時的旗標。"""

    def __init__(self, results: list[TurnResult], echo_delta: bool = False) -> None:
        self.results = results
        self.prompts: list[str] = []
        self.no_think_flags: list[bool] = []
        self.expect_flags: list[bool] = []
        # 模擬「模型逐字把字吐到畫面上」。要驗哪些輪次的字該外洩、哪些不該時才開。
        self.echo_delta = echo_delta

    async def __call__(self, prompt, state, frontend, turn_id, expect_assistant=True):
        self.prompts.append(prompt)
        self.no_think_flags.append(state._no_think)
        self.expect_flags.append(expect_assistant)
        # 模擬 runner 的 status 心跳。壓縮期間狀態列該顯示什麼，靠的正是這些
        # 心跳有沒有被蓋上階段旗標——假 runner 不發，這條路徑就驗不到。
        await frontend.emit(make_event(state.conv_id, turn_id, "status", elapsed=1.0))
        if self.echo_delta:
            await frontend.emit(make_event(
                state.conv_id, turn_id, "text.delta",
                d="壓縮摘要" if "壓縮" in prompt else prompt,
            ))
        if not self.results:
            return TurnResult(reply="（腳本用完了）")
        return self.results.pop(0)


def install(scripted: Scripted) -> None:
    turn_mod.run_turn = scripted            # type: ignore[assignment]
    turn_mod.persist = lambda s: None       # type: ignore[assignment]


async def test_normal() -> None:
    print("\n[一般回合]")
    install(Scripted([TurnResult(reply="做完了", used_tool=False, done=True)]))
    fe = FakeFrontend()
    await turn_mod.handle_turn("嗨", make_state(), fe)
    check("送出最終回覆", fe.replies() == ["做完了"], f"{fe.replies()}")
    check("沒有錯誤事件", not fe.errors())


async def test_empty_three_layers() -> None:
    print("\n[空回覆三層防線]")
    s = Scripted([
        TurnResult(reply=NO_RESPONSE, all_think="第一輪的完整思考內容"),
        TurnResult(reply=NO_RESPONSE, all_think="短"),
        TurnResult(reply=NO_RESPONSE, all_think=""),
        TurnResult(reply=NO_RESPONSE, all_think=""),
    ])
    install(s)
    fe = FakeFrontend()
    st = make_state()
    await turn_mod.handle_turn("做事", st, fe)

    check("重試到上限（共 1+3 次）", len(s.prompts) == 4, f"實際 {len(s.prompts)} 次")
    check("第一次仍帶思考", s.no_think_flags[0] is False)
    check("第二次起關思考（逃生門）", s.no_think_flags[2:] == [True, True],
          f"{s.no_think_flags}")
    check("旗標最後有還原（否則此對話永久不思考）", st._no_think is False)
    reply = fe.replies()[0] if fe.replies() else ""
    check("兜底用了最長的那份思考，沒被後續空值洗掉",
          "第一輪的完整思考內容" in reply, reply[:60])


async def test_auto_continue() -> None:
    print("\n[未完成自動續跑]")
    s = Scripted([
        TurnResult(reply="我先讀了檔案", used_tool=True, done=False, wait=False),
        TurnResult(reply="接著改完了", used_tool=True, done=True),
    ])
    install(s)
    fe = FakeFrontend()
    await turn_mod.handle_turn("改檔案", make_state(), fe)
    check("動過工具沒打 DONE → 自動補跑", len(s.prompts) == 2, f"{len(s.prompts)} 次")
    check("補跑用中性話術", "DONE" in s.prompts[1])
    # 兩段各自定稿，不合併成一則：合併代表第一段要等續跑跑完才送得出去，
    # 而它早就逐字打在畫面上了，中間那段空窗就是使用者說的「打了又收回去」。
    check("兩段回覆各自定稿", fe.replies() == ["我先讀了檔案", "接著改完了"],
          str(fe.replies()))


async def test_done_stops() -> None:
    print("\n[DONE / WAIT 抑制續跑]")
    s = Scripted([TurnResult(reply="好了", used_tool=True, done=True)])
    install(s)
    await turn_mod.handle_turn("x", make_state(), FakeFrontend())
    check("打了 DONE 就不補跑", len(s.prompts) == 1, f"{len(s.prompts)} 次")

    s2 = Scripted([TurnResult(reply="請問要哪一個？", used_tool=True, wait=True)])
    install(s2)
    await turn_mod.handle_turn("x", make_state(), FakeFrontend())
    check("打了 WAIT 就不補跑（不逼它自問自答）", len(s2.prompts) == 1,
          f"{len(s2.prompts)} 次")

    s3 = Scripted([TurnResult(reply="聊天回應", used_tool=False, done=False)])
    install(s3)
    await turn_mod.handle_turn("今天好累", make_state(), FakeFrontend())
    check("純聊天沒動工具就不補跑", len(s3.prompts) == 1, f"{len(s3.prompts)} 次")


async def test_ask_loop() -> None:
    print("\n[反問迴路]")
    ask_data = {"questions": [{
        "question": "要刪哪一個？",
        "options": [{"label": "A 檔", "description": "舊的"},
                    {"label": "B 檔", "description": "新的"}],
    }]}
    s = Scripted([
        TurnResult(reply="我找到兩個檔案", ask=ask_data, used_tool=True),
        TurnResult(reply="已刪除 A 檔", used_tool=True, done=True),
    ])
    install(s)
    fe = FakeFrontend(answers=[AskResponse(choice_id="A 檔")])
    await turn_mod.handle_turn("清掉舊檔", make_state(), fe)
    check("有向使用者提問", len(fe.asks) == 1)
    check("選項正確帶過去", len(fe.asks[0].choices) == 2 if fe.asks else False,
          str([c.label for c in fe.asks[0].choices]) if fe.asks else "")
    check("答案當成下一輪輸入", s.prompts[1] == "A 檔" if len(s.prompts) > 1 else False,
          str(s.prompts))
    check("問題前的說明有先送出", "我找到兩個檔案" in " ".join(fe.replies()),
          str(fe.replies()))
    # 手機端靠這個旗標決定要不要推「做完了」。沒有它的話，提問那一輪會同時
    # 收到 reply.final 與 ask.request，通知欄疊出兩則，前一則還說事情做完了。
    finals = [e for e in fe.events if e.type == "reply.final"]
    flags = [e.data.get("pending_ask") for e in finals]
    check("提問前的定稿標了 pending_ask", flags[:1] == [True], str(flags))
    check("答完之後的定稿沒有標", all(f is False for f in flags[1:]), str(flags))


async def test_ask_marker() -> None:
    """助理用純文字標記提問 → 手機上跳出按鈕。

    CC 內建的 AskUserQuestion **不在 SDK session 的工具清單裡**：實測四種
    permission_mode 的 36 個工具都沒有它，連 CLAUDE_CODE_ENABLE_ASK_USER_QUESTION_TOOL=1
    也叫不出來。模型看不到的工具不可能被呼叫，所以先前那條攔 ToolUseBlock 的路徑
    從頭到尾一次都沒觸發過——使用者連續回報好幾輪「選項面板出不來」就是這個。
    這條測試釘住取代它的文字標記通道，整段路：解析、清乾淨、變選項、答案接回下一輪。
    """
    print("\n[文字標記提問]")
    raw = "這兩個做法都行，我偏好第一個。[[ASK:要用哪個做法？|重寫整份|只補那一段]]"
    cleaned = fold_mod.clean_reply(raw)

    parsed = fold_mod.parse_ask_marker(raw)
    check("標記解析得出來", parsed is not None)
    check("標記不會洩漏到畫面上", "ASK" not in cleaned, cleaned)
    check("問題前的說明留著", "我偏好第一個" in cleaned, cleaned)

    req = turn_mod.parse_ask(parsed) if parsed else None
    check("轉得成 AskRequest（格式與內建工具相容）", req is not None)
    check("問題與選項都對",
          req is not None and req.title == "要用哪個做法？"
          and [c.label for c in req.choices] == ["重寫整份", "只補那一段"],
          f"{req.title} / {[c.label for c in req.choices]}" if req else "")

    # 少於兩段＝一個選項都沒有，跳出去會是點不下去的空面板，那比不跳還糟
    check("只有問題沒有選項就不觸發",
          fold_mod.parse_ask_marker("[[ASK:你要不要？]]") is None)

    s = Scripted([
        TurnResult(reply="我需要你決定一下", ask=parsed, used_tool=True),
        TurnResult(reply="好，照你說的做完了", used_tool=True, done=True),
    ])
    install(s)
    fe = FakeFrontend(answers=[AskResponse(choice_id="只補那一段")])
    await turn_mod.handle_turn("改一下", make_state(), fe)
    check("手機上真的跳了問題", len(fe.asks) == 1)
    check("點選的選項變成下一輪輸入",
          s.prompts[1] == "只補那一段" if len(s.prompts) > 1 else False, str(s.prompts))


async def test_ask_timeout() -> None:
    print("\n[反問逾時]")
    ask_data = {"question": "要哪個？",
                "options": [{"label": "甲", "description": ""}]}
    s = Scripted([TurnResult(reply="請選", ask=ask_data, used_tool=True)])
    install(s)
    fe = FakeFrontend(answers=[None])   # 逾時
    await turn_mod.handle_turn("x", make_state(), fe)
    check("逾時不會卡死，有給使用者交代", any("逾時" in r for r in fe.replies()),
          str(fe.replies()))


async def test_error_recovery() -> None:
    print("\n[錯誤善後]")
    dropped: list[str] = []

    async def fake_drop(cid: str) -> None:
        dropped.append(cid)

    turn_mod.client_pool.drop = fake_drop   # type: ignore[assignment]

    async def boom_auth(*a, **k):
        raise CCError("AUTH", "401 unauthorized")

    turn_mod.run_turn = boom_auth           # type: ignore[assignment]
    turn_mod.persist = lambda s: None       # type: ignore[assignment]
    fe = FakeFrontend()
    st = make_state()
    st.session_id = "keep-me"
    await turn_mod.handle_turn("x", st, fe)
    errs = fe.errors()
    check("發出 error 事件", bool(errs), str(errs)[:80])
    check("AUTH 丟掉 client（否則永遠 401）", dropped == ["test"], str(dropped))
    check("AUTH 不清 session（對話要保住）", st.session_id == "keep-me")

    async def boom_ctx(*a, **k):
        raise CCError("CONTEXT_FULL", "prompt is too long")

    turn_mod.run_turn = boom_ctx            # type: ignore[assignment]
    dropped.clear()
    st2 = make_state()
    st2.session_id = "old"
    await turn_mod.handle_turn("x", st2, FakeFrontend())
    check("CONTEXT_FULL 清掉 session", st2.session_id is None)
    check("CONTEXT_FULL 也丟 client", dropped == ["test"])


async def test_compact() -> None:
    print("\n[auto-compact]")
    s = Scripted([
        TurnResult(reply="摘要好了", session_id="new-sid"),
        TurnResult(reply="正事做完", done=True),
    ])
    install(s)
    st = make_state()
    # 門檻要照這條對話的實際上限算——上限隨模型變（Opus 是 1M），
    # 寫死 200K 的話高階模型根本到不了門檻，這個測試會靜靜地什麼都沒驗到
    st.ctx_tokens = int(turn_mod.ctx_limit(st) * 0.9)   # 超過 85% 門檻
    fe = FakeFrontend()
    await turn_mod.handle_turn("辦正事", st, fe)
    check("先壓縮再辦正事", len(s.prompts) == 2 and "壓縮" in s.prompts[0],
          str(s.prompts[0])[:30])
    check("壓縮後 ctx 歸零", st.ctx_tokens == 0)
    # /compact 整輪由 CLI 處理、模型不發言。忘了關孤兒過濾的話，runner 會把
    # 這一輪自己的 ResultMessage 當成別人的而一直等，壓縮從此每次都卡到逾時。
    check("壓縮那輪關掉孤兒過濾", s.expect_flags[:1] == [False], str(s.expect_flags))
    check("正事那輪維持過濾", s.expect_flags[1:] == [True], str(s.expect_flags))

    s2 = Scripted([TurnResult(reply="直接做", done=True)])
    install(s2)
    st2 = make_state()
    st2.ctx_tokens = 1000       # 遠低於門檻
    await turn_mod.handle_turn("辦正事", st2, FakeFrontend())
    check("沒到門檻不壓縮", len(s2.prompts) == 1)


async def test_ctx_limit_by_model() -> None:
    """context 上限要隨模型變，不是一律 200K。

    寫死 200K 時 Opus 在 17 萬 token 就被壓縮一次——白白剪掉八成還能用的脈絡，
    而且壓縮本身要花十幾秒。
    """
    print("\n[context 上限分模型]")
    st = make_state()
    st.model = "claude-sonnet-4-6"
    check("Sonnet 是 200K", turn_mod.ctx_limit(st) == turn_mod.CTX_LIMIT_DEFAULT)
    # 方案是設定值不是預設值，測試自己設，不然改預設會連累這裡
    st.model = "claude-opus-5"
    saved = config.ACCOUNT_PLAN
    try:
        config.ACCOUNT_PLAN = "max"
        check("Opus 在 max 方案是 1M", turn_mod.ctx_limit(st) == turn_mod.CTX_LIMIT_1M)
        config.ACCOUNT_PLAN = ""
        check("沒設方案就不給 1M", turn_mod.ctx_limit(st) == turn_mod.CTX_LIMIT_DEFAULT)
    finally:
        config.ACCOUNT_PLAN = saved
    st.model = "claude-sonnet-4-6[1m]"
    check("[1m] 後綴強制 1M", turn_mod.ctx_limit(st) == turn_mod.CTX_LIMIT_1M)


async def test_compact_is_silent() -> None:
    """壓縮那一輪的產物是維運資料，不是助理說的話，一個字都不該進畫面。

    先前它跟正常回覆走同一條路串流出去，使用者看到一大段對話摘要被打出來、
    然後被下一個 turn.start 清空——他的原話「顯示一堆對話摘要再收回去」。
    """
    print("\n[壓縮不外洩]")
    s = Scripted([
        TurnResult(reply="摘要好了", session_id="new-sid"),
        TurnResult(reply="正事做完", done=True),
    ], echo_delta=True)
    install(s)
    st = make_state()
    st.ctx_tokens = int(turn_mod.ctx_limit(st) * 0.9)
    fe = FakeFrontend()
    await turn_mod.handle_turn("辦正事", st, fe)

    deltas = [e.data.get("d") for e in fe.events if e.type == "text.delta"]
    check("壓縮那輪的字沒進畫面", "壓縮摘要" not in deltas, str(deltas))
    check("正事那輪的字照送", "辦正事" in deltas, str(deltas))
    check("摘要沒被當成回覆", fe.replies() == ["正事做完"], str(fe.replies()))
    # 全靜音會讓畫面十幾秒沒動靜，所以 status 仍要放行
    notes = [e.data.get("note", "") for e in fe.events if e.type == "status"]
    check("但有告訴使用者在整理記憶", any("整理" in n for n in notes), str(notes))

    # 那則開場白只出現一次，之後每兩秒一次的心跳才是狀態列真正在讀的東西。
    # 心跳沒帶旗標的話，手機端沒有思考可顯示就退回「想一下」——謊稱它在回話。
    stats = [e for e in fe.events if e.type == "status"]
    comp = [e for e in stats if e.turn_id.startswith("c-")]
    check("壓縮期間的心跳全都標了階段", bool(comp) and
          all(e.data.get("phase") == turn_mod.COMPACT_PHASE for e in comp),
          str([e.data.get("phase") for e in comp]))
    check("正事那輪的心跳不帶階段旗標",
          all(not e.data.get("phase") for e in stats if not e.turn_id.startswith("c-")))
    check("轉發沒有換掉序號（續傳錨點）",
          all(e.seq > 0 for e in comp) and
          len({e.seq for e in stats}) == len(stats))


async def test_recheck_after_compact() -> None:
    """CLI 在回合中途自己壓縮之後，要回頭核對有沒有做了卻沒說的事。

    實錄：`ledger_add` 已經執行完成、那句「145 記好了」還沒說出口就撞上壓縮，
    重啟後的摘要卻寫著「已經跟他說記好了」——帳真的進去了，使用者卻從頭到尾
    沒看到任何確認，只能自己發現「我提過的東西後面不見了」。
    摘要是模型自己寫的，寫錯了沒有任何機制會發現，只能強制回頭對一次帳。
    """
    print("\n[壓縮後回頭核對]")
    s = Scripted([
        TurnResult(reply="", used_tool=True, done=True, compacted=True),
        TurnResult(reply="補一句：145 已經記進去了", done=True),
    ])
    install(s)
    fe = FakeFrontend()
    await turn_mod.handle_turn("記個帳順便查東西", make_state(), fe)
    check("被壓縮切過就補跑一輪核對", len(s.prompts) == 2, f"{len(s.prompts)} 次")
    check("核對用的是專屬提示（不是續跑那句）",
          len(s.prompts) > 1 and s.prompts[1] == turn_mod.COMPACT_RECHECK_NUDGE,
          str(s.prompts[1])[:30] if len(s.prompts) > 1 else "")
    check("補講的話有送到畫面上",
          fe.replies() == ["補一句：145 已經記進去了"], str(fe.replies()))
    notes = [e.data.get("note", "") for e in fe.events if e.type == "status"]
    check("有告訴使用者為什麼多跑一輪", any("壓縮" in n for n in notes), str(notes))

    # 沒被壓縮的回合一步都不能多跑：每一輪都是錢跟時間
    s2 = Scripted([TurnResult(reply="做完了", used_tool=True, done=True)])
    install(s2)
    await turn_mod.handle_turn("x", make_state(), FakeFrontend())
    check("沒壓縮就不多跑", len(s2.prompts) == 1, f"{len(s2.prompts)} 次")

    # 旗標要跨輪累積：壓縮發生在反問之前，答完之後照樣得核對，
    # 否則「壓縮 → 提問 → 回答」這條最常見的路徑剛好整條漏掉。
    ask_data = {"question": "要哪個？", "options": [{"label": "甲", "description": ""}]}
    s3 = Scripted([
        TurnResult(reply="要你選一下", ask=ask_data, used_tool=True, compacted=True),
        TurnResult(reply="照做了", used_tool=True, done=True),
        TurnResult(reply="沒有漏掉的", done=True),
    ])
    install(s3)
    fe3 = FakeFrontend(answers=[AskResponse(choice_id="甲")])
    await turn_mod.handle_turn("x", make_state(), fe3)
    check("壓縮在反問之前也記得核對",
          len(s3.prompts) == 3 and s3.prompts[2] == turn_mod.COMPACT_RECHECK_NUDGE,
          str(len(s3.prompts)))

    # 空回覆重試會換掉整個 TurnResult，旗標若跟著被覆寫就等於沒偵測到
    s4 = Scripted([
        TurnResult(reply=NO_RESPONSE, compacted=True, all_think="想了但沒說"),
        TurnResult(reply="這是結果", used_tool=True, done=True),
        TurnResult(reply="核對完畢", done=True),
    ])
    install(s4)
    await turn_mod.handle_turn("x", make_state(), FakeFrontend())
    check("旗標不被空回覆重試洗掉",
          len(s4.prompts) == 3 and s4.prompts[2] == turn_mod.COMPACT_RECHECK_NUDGE,
          str(len(s4.prompts)))


async def main() -> int:
    await test_normal()
    await test_empty_three_layers()
    await test_auto_continue()
    await test_done_stops()
    await test_ask_loop()
    await test_ask_marker()
    await test_ask_timeout()
    await test_error_recovery()
    await test_compact()
    await test_ctx_limit_by_model()
    await test_compact_is_silent()
    await test_recheck_after_compact()
    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
