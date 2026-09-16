"""插話（mid-turn steering）：注入、撲空觀察窗、孤兒週期回收。

三個釘住的行為（實測依據見 runner.py 的插話段落註解）：
  - 回合讀取中 try_steer 要能把訊息寫進 client（帶插話前綴），回合外要拒絕
  - 插話撲空時 CLI 會另開一個回應週期——觀察窗要把它整段讀回來折進同一個回覆，
    不能留在串流裡毒化下一回合（問 A 回 B）
  - 插話被回合吃掉時（觀察窗靜默）要能自己醒來收工，不是掛在那裡等到閒置逾時
"""
from __future__ import annotations

import asyncio
import contextlib
import sys
from pathlib import Path

import config  # noqa: F401
from claude_agent_sdk import AssistantMessage, ResultMessage, TextBlock

from engine import runner as runner_mod
from engine.mailbox import MailboxClosed
from engine.state import ConvState
from protocol import Event

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


def result_msg(sid: str = "sid-1", result: str | None = None) -> ResultMessage:
    """真實 CLI 每個回應週期的 Result 都帶著該週期的最終文字（result 欄位），
    撲空回收靠它分辨週期各自講了什麼——測孤兒回收時要照實填。"""
    return ResultMessage(
        subtype="success", duration_ms=1, duration_api_ms=1, is_error=False,
        num_turns=1, session_id=sid, result=result,
    )


def assistant_msg(text: str) -> AssistantMessage:
    return AssistantMessage(content=[TextBlock(text=text)], model="claude-opus-5")


class FakeClient:
    def __init__(self) -> None:
        self.queries: list[str] = []

    async def query(self, prompt: str) -> None:
        self.queries.append(prompt)


class _SteerInbox:
    """預錄訊息流，其中 ("steer", 文字) 代表「使用者此刻插話」。

    插話動作發生在兩則訊息之間——跟真實情況一樣，try_steer 是從讀取迴圈
    外面的請求處理器打進來的，這裡用讀到哨兵時代打來模擬同一個時序。
    """

    def __init__(self, items: list, steer_results: list[bool]) -> None:
        self._items = list(items)
        self._steer_results = steer_results

    async def get(self, timeout: float | None = None):
        while self._items:
            it = self._items.pop(0)
            if isinstance(it, tuple) and it[0] == "steer":
                self._steer_results.append(
                    await runner_mod.try_steer("test", it[1]))
                continue
            return it
        # 預錄流放完：帶逾時的讀（觀察窗）→ 逾時醒來；阻塞讀 → 串流結束
        if timeout is not None:
            raise asyncio.TimeoutError()
        raise MailboxClosed()


class FakeBox:
    def __init__(self, client: FakeClient, items: list,
                 steer_results: list[bool]) -> None:
        self.client = client
        self.frontend = None
        self._items = items
        self._steer_results = steer_results

    @contextlib.asynccontextmanager
    async def claim(self):
        yield _SteerInbox(self._items, self._steer_results)


def install(items: list) -> tuple[FakeClient, list[bool]]:
    client = FakeClient()
    steer_results: list[bool] = []
    box = FakeBox(client, items, steer_results)

    async def fake_acquire(state, frontend):
        box.frontend = frontend
        return box

    runner_mod.client_pool.acquire = fake_acquire  # type: ignore[assignment]
    return client, steer_results


def make_state() -> ConvState:
    return ConvState(conv_id="test", cwd=Path.home())


async def test_steer_injected() -> None:
    print("\n[回合中插話寫得進 client]")
    client, ok = install([
        assistant_msg("開始做了"),
        ("steer", "等等，改用方案 B"),
        assistant_msg("好，改方案 B"),
        result_msg(),
    ])
    res = await runner_mod.run_turn("做事", make_state(), SilentFrontend(), "t-1")
    check("插話被接受", ok == [True], str(ok))
    check("client 收到兩筆（prompt＋插話）", len(client.queries) == 2,
          str(len(client.queries)))
    if len(client.queries) == 2:
        check("插話帶著前綴", client.queries[1].startswith(runner_mod.STEER_PREFIX),
              client.queries[1][:20])
        check("插話內容原封不動", "改用方案 B" in client.queries[1])
    check("回覆正常折出", "改方案 B" in res.reply, res.reply)
    check("登記表已清空", "test" not in runner_mod._steerable
          and "test" not in runner_mod._steered)


async def test_missed_steer_orphan_folded() -> None:
    """撲空：插話落在 Result 之後，CLI 另開的孤兒週期要被觀察窗整段讀回來。"""
    print("\n[撲空的插話由觀察窗接住]")
    _client, ok = install([
        assistant_msg("第一段回覆"),
        ("steer", "補一件事"),                    # 記了帳，但這回合已經到底了＝撲空
        result_msg(result="第一段回覆"),           # 自己的 Result → 因為有帳，開觀察窗
        assistant_msg("孤兒週期的回覆"),            # CLI 另開的週期
        result_msg(result="孤兒週期的回覆"),
    ])
    res = await runner_mod.run_turn("做事", make_state(), SilentFrontend(), "t-2")
    check("插話被接受", ok == [True], str(ok))
    check("兩段都折進同一個回覆",
          "第一段回覆" in res.reply and "孤兒週期的回覆" in res.reply, res.reply)
    check("登記表已清空", "test" not in runner_mod._steerable
          and "test" not in runner_mod._steered)


async def test_consumed_steer_quiet_window() -> None:
    """吃掉了：Result 之後沒有孤兒週期，觀察窗要自己逾時醒來，不能掛著。"""
    print("\n[被吃掉的插話只多付一個靜默窗]")
    _client, ok = install([
        assistant_msg("先做一半"),
        ("steer", "改個方向"),
        assistant_msg("收到，轉向完成"),   # 插話在回合內被吃掉
        result_msg(),                      # 之後串流安靜（觀察窗逾時）
    ])
    res = await asyncio.wait_for(
        runner_mod.run_turn("做事", make_state(), SilentFrontend(), "t-3"),
        timeout=runner_mod.STEER_DRAIN_WINDOW + 5,
    )
    check("回覆正常", "轉向完成" in res.reply, res.reply)
    check("登記表已清空", "test" not in runner_mod._steerable
          and "test" not in runner_mod._steered)


async def test_no_turn_no_steer() -> None:
    print("\n[回合外拒絕插話]")
    ok = await runner_mod.try_steer("nobody", "喂")
    check("沒有回合就回 False", ok is False)
    check("沒留下帳", "nobody" not in runner_mod._steered)


async def main() -> int:
    await test_steer_injected()
    await test_missed_steer_orphan_folded()
    await test_consumed_steer_quiet_window()
    await test_no_turn_no_steer()
    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
