"""session_id 要在回合跑到一半就落地，不能等收尾。

2026-08-23 的「對話紀錄整條不見」事件。`fold_messages` 只從 `ResultMessage`
取 session_id，而那是一個回合的最後一則訊息——回合被中斷（伺服器重啟、當掉）
就完全沒留下線索，那條對話從此失聯：狀態檔裡沒有它，對話列表也不會有它。

逐字稿其實好端端躺在 ~/.claude/projects 底下，丟的只是「哪條對話對到哪個
session」這個對應。使用者按了重新啟動，一條讀 PDF 讀了七分鐘、8.8MB 的對話
就是這樣消失的。
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path

import config  # noqa: F401
from claude_agent_sdk import AssistantMessage, TextBlock

from engine import runner as runner_mod
from engine.state import ConvState
from test_runner_alien import FakeBox, FakeClient, SilentFrontend, result_msg

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def assistant_msg(text: str, sid: str) -> AssistantMessage:
    """帶 session_id 的 assistant 訊息——真品一直都帶著，只是沒人讀它。"""
    return AssistantMessage(
        content=[TextBlock(text=text)], model="claude-opus-5", session_id=sid,
    )


def install(stream: list) -> list[ConvState]:
    """換掉收發中樞與 persist，回傳「被存過的狀態」清單。"""
    box = FakeBox(FakeClient(stream))

    async def fake_acquire(state, frontend):
        box.frontend = frontend
        return box

    saved: list[ConvState] = []
    runner_mod.client_pool.acquire = fake_acquire      # type: ignore[assignment]
    runner_mod.persist = lambda st: saved.append(st)   # type: ignore[assignment]
    return saved


def make_state() -> ConvState:
    return ConvState(conv_id="test", cwd=Path.home())


async def test_interrupted_turn_keeps_sid() -> None:
    """回合沒收尾也要留下 session_id。這條紅了就等於對話會憑空消失。"""
    print("\n[回合被中斷]")
    # 只有 assistant 訊息、沒有 ResultMessage：這正是被重啟切斷的樣子
    saved = install([assistant_msg("讀到一半", "sid-live")])
    st = make_state()
    res = await runner_mod.run_turn("讀這份 PDF", st, SilentFrontend(), "t-1")
    check("狀態上有 session_id", st.session_id == "sid-live", str(st.session_id))
    check("而且真的存檔了", len(saved) == 1, f"存了 {len(saved)} 次")
    # 這一條是對照組：中斷的回合本來就拿不到 ResultMessage，
    # 回傳值為空是正確的——正因為如此才需要上面那兩條
    check("回傳值仍然是空的（所以不能只靠它）", res.session_id is None,
          str(res.session_id))


async def test_no_repeat_writes() -> None:
    """同一個 sid 不要每則訊息都寫一次檔。"""
    print("\n[不重複寫檔]")
    saved = install([
        assistant_msg("第一段", "sid-live"),
        assistant_msg("第二段", "sid-live"),
        assistant_msg("第三段", "sid-live"),
        result_msg("sid-live"),
    ])
    st = make_state()
    await runner_mod.run_turn("做事", st, SilentFrontend(), "t-2")
    check("三則訊息只存一次", len(saved) == 1, f"存了 {len(saved)} 次")


async def test_normal_turn_unaffected() -> None:
    """正常回合的行為不能被動到。"""
    print("\n[正常回合]")
    install([assistant_msg("一般回答", "sid-1"), result_msg("sid-1")])
    st = make_state()
    res = await runner_mod.run_turn("嗨", st, SilentFrontend(), "t-3")
    check("回覆照樣拿得到", res.reply == "一般回答", res.reply)
    check("收尾的 session_id 照樣有", res.session_id == "sid-1", str(res.session_id))


async def main() -> int:
    await test_interrupted_turn_keeps_sid()
    await test_no_repeat_writes()
    await test_normal_turn_unaffected()
    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
