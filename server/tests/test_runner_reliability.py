"""runner 的三條可靠性修正：閒置逾時、例外要不要丟 client、用量只記自己的。

釘住的行為：
  1. 有工具在跑（送出 ToolUseBlock、還沒收到 ToolResultBlock）期間，閒置上限改用
     TOOL_INACTIVITY_TIMEOUT，不會在 INACTIVITY_TIMEOUT 到的時候就殺 CLI；
     工具結果回來後上限縮回去；真的超過工具上限還是會逾時。
  2. 逾時先 interrupt()：CLI 收得了尾（讀取任務自己結束）就不 drop；收不了才 drop。
  3. 讀取迴圈裡的非傳輸例外（tool_info 之類的小 bug）照樣往上拋，但 client 不丟；
     傳輸層例外（ProcessError 那幾種）才丟。
  4. 孤兒 Result 先到、自己的 Result 後到 → usage.record 只被叫一次。

全部用假 client／假收件匣，不燒 API。逾時那幾條把 config 與輪詢間隔改到毫秒級。
"""
from __future__ import annotations

import asyncio
import contextlib
import sys
from pathlib import Path

import config  # noqa: F401
from claude_agent_sdk import (
    AssistantMessage, ProcessError, ResultMessage, TextBlock, ToolResultBlock,
    ToolUseBlock, UserMessage,
)

from engine import runner as runner_mod
from engine.mailbox import MailboxClosed
from engine.state import ConvState
from protocol import Event

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}", flush=True)
    if not cond:
        FAILED.append(name)


class SilentFrontend:
    def __init__(self) -> None:
        self.events: list[Event] = []

    async def emit(self, ev: Event) -> None:
        self.events.append(ev)

    async def ask(self, req):
        return None


def result_msg(text: str | None = None) -> ResultMessage:
    return ResultMessage(
        subtype="success", duration_ms=1, duration_api_ms=1, is_error=False,
        num_turns=1, session_id="sid-1", result=text,
        usage={"input_tokens": 1, "output_tokens": 1}, total_cost_usd=0.01,
    )


def assistant_msg(text: str) -> AssistantMessage:
    return AssistantMessage(content=[TextBlock(text=text)], model="claude-opus-5")


def tool_use_msg(tool_id: str) -> AssistantMessage:
    return AssistantMessage(
        content=[ToolUseBlock(id=tool_id, name="Bash", input={"command": "sleep 900"})],
        model="claude-opus-5",
    )


def tool_result_msg(tool_id: str) -> UserMessage:
    return UserMessage(content=[ToolResultBlock(tool_use_id=tool_id, content="done")])


class FakeClient:
    """interrupt() 的行為由測試決定：把東西塞進收件匣（CLI 收尾）或什麼都不做（收不了）。"""

    def __init__(self, inbox: "LiveInbox", on_interrupt: list | None = None) -> None:
        self.queries: list[str] = []
        self.interrupts = 0
        self._inbox = inbox
        self._on_interrupt = on_interrupt

    async def query(self, prompt: str) -> None:
        self.queries.append(prompt)

    async def interrupt(self) -> None:
        self.interrupts += 1
        if self._on_interrupt is not None:
            for it in self._on_interrupt:
                self._inbox.push(it)

    async def get_context_usage(self) -> dict:
        return {}


class LiveInbox:
    """真的會阻塞的收件匣：沒東西就等，跟正式的 _Inbox 一樣。例外物件原地重拋。"""

    def __init__(self, items: list | None = None) -> None:
        self.q: asyncio.Queue = asyncio.Queue()
        for it in items or []:
            self.q.put_nowait(it)

    def push(self, item) -> None:
        self.q.put_nowait(item)

    async def get(self, timeout: float | None = None):
        if timeout is None:
            item = await self.q.get()
        else:
            item = await asyncio.wait_for(self.q.get(), timeout)
        if isinstance(item, BaseException):
            raise item
        return item


class FakeBox:
    def __init__(self, client: FakeClient, inbox: LiveInbox) -> None:
        self.client = client
        self.frontend = None
        self._inbox = inbox

    @contextlib.asynccontextmanager
    async def claim(self, ticket=None):
        yield self._inbox


def install(items: list, on_interrupt: list | None = None) -> tuple[FakeClient, LiveInbox, list[str]]:
    inbox = LiveInbox(items)
    client = FakeClient(inbox, on_interrupt)
    box = FakeBox(client, inbox)
    drops: list[str] = []

    async def fake_acquire(state, frontend):
        box.frontend = frontend
        return box

    async def fake_drop(conv):
        drops.append(conv)

    runner_mod.client_pool.acquire = fake_acquire  # type: ignore[assignment]
    runner_mod.client_pool.drop = fake_drop  # type: ignore[assignment]
    runner_mod.persist = lambda s: None  # type: ignore[assignment]
    return client, inbox, drops


def make_state() -> ConvState:
    return ConvState(conv_id="test", cwd=Path.home())


@contextlib.contextmanager
def fast_timeouts(idle: float, tool: float, grace: float = 0.3):
    """把逾時常數縮到毫秒級。跑完還原，別污染後面的測試。"""
    cfg = runner_mod.config
    saved = (cfg.INACTIVITY_TIMEOUT, cfg.TOOL_INACTIVITY_TIMEOUT,
             cfg.INTERRUPT_GRACE_SEC, runner_mod.IDLE_POLL_SEC)
    cfg.INACTIVITY_TIMEOUT = idle          # type: ignore[misc]
    cfg.TOOL_INACTIVITY_TIMEOUT = tool     # type: ignore[misc]
    cfg.INTERRUPT_GRACE_SEC = grace        # type: ignore[misc]
    runner_mod.IDLE_POLL_SEC = 0.05
    try:
        yield
    finally:
        (cfg.INACTIVITY_TIMEOUT, cfg.TOOL_INACTIVITY_TIMEOUT,
         cfg.INTERRUPT_GRACE_SEC, runner_mod.IDLE_POLL_SEC) = saved   # type: ignore[misc]


async def test_tool_running_extends_timeout() -> None:
    print("\n[工具在跑：閒置上限放寬，不殺 CLI]")
    # 一般上限 0.3 秒、工具上限 10 秒；工具送出後靜默 1 秒（遠超一般上限）
    client, inbox, drops = install([tool_use_msg("tu-1")])
    with fast_timeouts(idle=0.3, tool=10):
        task = asyncio.create_task(
            runner_mod.run_turn("跑個長工具", make_state(), SilentFrontend(), "t-1"))
        await asyncio.sleep(1.0)
        check("靜默 1 秒（> 一般上限）回合還活著", not task.done())
        check("沒有 interrupt", client.interrupts == 0, str(client.interrupts))
        check("client 沒被丟", drops == [], str(drops))
        # 工具結果回來，回合正常收尾
        inbox.push(tool_result_msg("tu-1"))
        inbox.push(assistant_msg("跑完了"))
        inbox.push(result_msg("跑完了"))
        inbox.push(MailboxClosed())
        res = await asyncio.wait_for(task, timeout=3)
    check("回覆正常折出", res is not None and "跑完了" in res.reply,
          res.reply if res else "None")
    check("全程沒有 drop", drops == [], str(drops))


async def test_tool_result_shrinks_timeout() -> None:
    print("\n[工具結果回來後上限縮回一般值]")
    client, inbox, drops = install([tool_use_msg("tu-1"), tool_result_msg("tu-1")])
    with fast_timeouts(idle=0.3, tool=10):
        task = asyncio.create_task(
            runner_mod.run_turn("x", make_state(), SilentFrontend(), "t-2"))
        # 工具結果已經收到 → 之後靜默就是真的沒動靜，0.3 秒後應該逾時
        err: BaseException | None = None
        try:
            await asyncio.wait_for(task, timeout=3)
        except asyncio.TimeoutError as e:
            err = e
        # 注意：wait_for 自己的逾時也是 TimeoutError，要靠時間分辨——
        # 3 秒內結束就是 runner 主動逾時，不是 wait_for 等到底
        check("工具結果回來後靜默 → 逾時", err is not None)
        check("有先 interrupt", client.interrupts >= 1, str(client.interrupts))


async def test_tool_timeout_still_caps() -> None:
    print("\n[工具上限本身還是會到]")
    client, inbox, drops = install([tool_use_msg("tu-1")])
    with fast_timeouts(idle=0.1, tool=0.4):
        t0 = asyncio.get_running_loop().time()
        err: BaseException | None = None
        try:
            await asyncio.wait_for(
                runner_mod.run_turn("x", make_state(), SilentFrontend(), "t-3"), timeout=5)
        except asyncio.TimeoutError as e:
            err = e
        dt = asyncio.get_running_loop().time() - t0
    check("超過工具上限就逾時", err is not None and dt < 4, f"{dt:.2f}s")
    check("逾時前先 interrupt", client.interrupts == 1, str(client.interrupts))
    check("interrupt 收不了尾 → 才 drop", drops == ["test"], str(drops))


async def test_interrupt_saves_client() -> None:
    print("\n[逾時 interrupt：CLI 自己收尾就不 drop]")
    # interrupt() 讓 CLI 收出一則 Result 再結束串流——讀取任務自然走完
    client, inbox, drops = install(
        [assistant_msg("開始")],
        on_interrupt=[result_msg("被中斷"), MailboxClosed()],
    )
    with fast_timeouts(idle=0.3, tool=10, grace=2.0):
        err: BaseException | None = None
        try:
            await asyncio.wait_for(
                runner_mod.run_turn("x", make_state(), SilentFrontend(), "t-4"), timeout=5)
        except asyncio.TimeoutError as e:
            err = e
    check("回合仍以逾時收場", err is not None)
    check("interrupt 被叫了一次", client.interrupts == 1, str(client.interrupts))
    check("CLI 收得了尾 → client 不丟", drops == [], str(drops))


async def test_interrupt_fails_then_drop() -> None:
    print("\n[逾時 interrupt：收不了尾才 drop]")
    client, inbox, drops = install([assistant_msg("開始")], on_interrupt=None)
    with fast_timeouts(idle=0.3, tool=10, grace=0.3):
        err: BaseException | None = None
        try:
            await asyncio.wait_for(
                runner_mod.run_turn("x", make_state(), SilentFrontend(), "t-5"), timeout=5)
        except asyncio.TimeoutError as e:
            err = e
    check("回合以逾時收場", err is not None)
    check("interrupt 被叫過", client.interrupts == 1, str(client.interrupts))
    check("寬限期過了還沒結束 → drop", drops == ["test"], str(drops))


async def test_non_transport_error_keeps_client() -> None:
    print("\n[顯示層的小 bug 不丟 client]")
    client, inbox, drops = install([tool_use_msg("tu-1"), result_msg()])
    orig = runner_mod.tool_info
    runner_mod.tool_info = lambda name, inp: (_ for _ in ()).throw(RuntimeError("tool_info 炸了"))  # type: ignore[assignment]
    try:
        err: BaseException | None = None
        try:
            await runner_mod.run_turn("x", make_state(), SilentFrontend(), "t-6")
        except RuntimeError as e:
            err = e
    finally:
        runner_mod.tool_info = orig  # type: ignore[assignment]
    check("例外照樣往上拋", err is not None and "tool_info" in str(err), repr(err))
    check("但 client 沒被丟", drops == [], str(drops))


async def test_transport_error_drops_client() -> None:
    print("\n[傳輸層例外才丟 client]")
    client, inbox, drops = install([assistant_msg("開始"), ProcessError("CLI 死了", exit_code=1)])
    err: BaseException | None = None
    try:
        await runner_mod.run_turn("x", make_state(), SilentFrontend(), "t-7")
    except ProcessError as e:
        err = e
    check("例外往上拋", err is not None)
    check("client 被丟掉", drops == ["test"], str(drops))


async def test_usage_only_own_result() -> None:
    print("\n[孤兒 Result 不記帳]")
    _client, _inbox, drops = install([
        result_msg("別人的"),          # 孤兒：前面零 AssistantMessage
        assistant_msg("我的回覆"),
        result_msg("我的回覆"),
        MailboxClosed(),
    ])
    recorded: list[tuple] = []
    orig = runner_mod.usage.record
    runner_mod.usage.record = lambda *a: recorded.append(a)  # type: ignore[assignment]
    try:
        res = await runner_mod.run_turn("x", make_state(), SilentFrontend(), "t-8")
    finally:
        runner_mod.usage.record = orig  # type: ignore[assignment]
    check("回覆是自己的", res is not None and "我的回覆" in res.reply)
    check("usage.record 只被叫一次", len(recorded) == 1, str(len(recorded)))


async def main() -> int:
    await test_tool_running_extends_timeout()
    await test_tool_result_shrinks_timeout()
    await test_tool_timeout_still_caps()
    await test_interrupt_saves_client()
    await test_interrupt_fails_then_drop()
    await test_non_transport_error_keeps_client()
    await test_transport_error_drops_client()
    await test_usage_only_own_result()
    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
