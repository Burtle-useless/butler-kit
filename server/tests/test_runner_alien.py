"""孤兒輪次過濾：不要把別人回合的結果當成自己的。

2026-08-14 失憶事件的根因測試。CLI 會為系統事件另起自己的回合（resume 舊 session
時投遞的孤兒背景任務通知就是一例），那一輪的 ResultMessage 會排在我們這個 prompt
的前面先到。照單全收 → 本回合被當成「已結束、沒有回覆」→ turn.py 送出空回覆重試
或續跑 nudge，而我們的 prompt 其實還在 CLI 那邊跑 → 同一個 session 上兩條 query
並行 → 逐字稿從同一個 parentUuid 長出兩支＝分叉。重啟 resume 只接得回其中一支。

cc-bot 的 discord_bot.py:1435 早就有這段判準，butler 移植時漏抄。
"""
from __future__ import annotations

import asyncio
import sys

import config  # noqa: F401
from claude_agent_sdk import AssistantMessage, ResultMessage, TextBlock

from engine import runner as runner_mod
from engine.state import ConvState
from protocol import Event
from pathlib import Path

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


class SilentFrontend:
    def __init__(self) -> None:
        self.events: list[Event] = []

    async def emit(self, ev: Event) -> None:
        self.events.append(ev)

    async def ask(self, req):
        return None


def result_msg(sid: str = "sid-1") -> ResultMessage:
    """一則 ResultMessage。孤兒與自己的長得一模一樣——SDK 不帶 promptId，
    這正是只能靠「這一輪有沒有 AssistantMessage」來判的原因。"""
    return ResultMessage(
        subtype="success", duration_ms=1, duration_api_ms=1, is_error=False,
        num_turns=1, session_id=sid,
    )


def assistant_msg(text: str) -> AssistantMessage:
    return AssistantMessage(content=[TextBlock(text=text)], model="claude-opus-5")


class FakeClient:
    """吐出預錄訊息流的假 client。記錄 query 被呼叫幾次。"""

    def __init__(self, stream: list) -> None:
        self.stream = stream
        self.queries: list[str] = []

    async def query(self, prompt: str) -> None:
        self.queries.append(prompt)


def install(stream: list) -> FakeClient:
    """把 runner 的 client 取得與訊息迭代換成假的。"""
    client = FakeClient(stream)

    async def fake_acquire(state, frontend):
        return client

    async def fake_iter(c):
        for m in c.stream:
            yield m

    runner_mod.client_pool.acquire = fake_acquire   # type: ignore[assignment]
    runner_mod.iter_messages = fake_iter            # type: ignore[assignment]
    return client


def make_state() -> ConvState:
    return ConvState(conv_id="test", cwd=Path.home())


async def test_alien_skipped() -> None:
    print("\n[孤兒輪次先到]")
    install([
        result_msg(),                    # 別人的回合：零 AssistantMessage
        assistant_msg("這才是我的回覆"),   # 我們的回合開始
        result_msg(),
    ])
    res = await runner_mod.run_turn("做事", make_state(), SilentFrontend(), "t-1")
    check("拿到真正的回覆，不是空的", res.reply == "這才是我的回覆", res.reply)


async def test_normal_not_broken() -> None:
    """最重要的反向測試：saw_assistant 漏設的話這條會紅。

    旗標沒被設 True 時，自己的 ResultMessage 也會被當成孤兒跳掉，
    連跳三次後才勉強收工——每個正常回合都變成空回覆。
    """
    print("\n[正常回合不受影響]")
    install([assistant_msg("一般回答"), result_msg()])
    res = await runner_mod.run_turn("嗨", make_state(), SilentFrontend(), "t-2")
    check("正常回合照樣拿得到回覆", res.reply == "一般回答", res.reply)


async def test_alien_cap() -> None:
    """跳過有上限，否則串流出狀況時會等到閒置逾時。"""
    print("\n[跳過上限]")
    stream = [result_msg() for _ in range(runner_mod.MAX_ALIEN_TURNS + 1)]
    res = await runner_mod.run_turn("做事", make_state(), SilentFrontend(), "t-3")
    check("上限是 3", runner_mod.MAX_ALIEN_TURNS == 3, str(runner_mod.MAX_ALIEN_TURNS))
    install(stream)
    res = await runner_mod.run_turn("做事", make_state(), SilentFrontend(), "t-4")
    check("跳到上限就收工（退回舊行為交給空回覆重試）",
          res.reply == runner_mod.NO_RESPONSE, res.reply)


async def test_expect_assistant_off() -> None:
    """/compact：整輪由 CLI 處理、模型不發言，套判準會一路等到逾時。"""
    print("\n[關掉過濾]")
    install([result_msg()])
    res = await runner_mod.run_turn(
        "壓縮", make_state(), SilentFrontend(), "t-5", expect_assistant=False,
    )
    check("零 AssistantMessage 也正常收工", res.session_id == "sid-1",
          str(res.session_id))


async def main() -> int:
    await test_alien_skipped()
    await test_normal_not_broken()
    await test_alien_cap()
    await test_expect_assistant_off()
    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
