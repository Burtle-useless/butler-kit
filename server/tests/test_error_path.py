"""錯誤要走錯誤路徑，不要當回覆。

2026-09-02 12:23 實錄：額度用盡時 CLI 送 RateLimitEvent(status=rejected)，接著讓模型
「回」一句 You've hit your session limit · resets 3pm，Result 正常收出。butler 把那句
當回覆定稿、推播「做完了」、續跑再補一輪、每則新訊息前又先壓縮一次。

這裡釘住：
  - runner：收到 rejected 的 RateLimitEvent → 回合以 CCError(RATE_LIMIT, resets_at) 收場，
    client **不丟**（進程好端端的，丟了只是多付啟動又殺背景工作）
  - runner：Result.is_error 也走同一條
  - turn.handle_turn：發 error 事件（帶 resets_at）、記 diag、不定稿、不發 turn.done，
    回傳那個錯誤讓 transport 決定要不要等
  - turn._maybe_compact：壓縮沒把 context 壓下來就冷卻；額度未回復期間直接跳過
  - turn.handle_wake：票失效時要收一則 turn.done（notify=False），不然手機端 busy 卡死
"""
from __future__ import annotations

import asyncio
import contextlib
import sys
import time
from pathlib import Path

import config  # noqa: F401
from claude_agent_sdk import (
    AssistantMessage, RateLimitEvent, RateLimitInfo, ResultMessage, TextBlock,
)

from engine import diag
from engine import runner as runner_mod
from engine import turn as turn_mod
from engine.errors import CCError
from engine.mailbox import MailboxClosed
from engine.runner import TurnResult
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

    def of(self, t: str) -> list[Event]:
        return [e for e in self.events if e.type == t]


LIMIT_TEXT = "You've hit your session limit · resets 3pm (Asia/Taipei)"


def result_msg(text: str | None, is_error: bool = False) -> ResultMessage:
    return ResultMessage(
        subtype="error_during_execution" if is_error else "success",
        duration_ms=1, duration_api_ms=1, is_error=is_error, num_turns=1,
        session_id="sid-1", result=text,
    )


def assistant_msg(text: str) -> AssistantMessage:
    return AssistantMessage(content=[TextBlock(text=text)], model="claude-opus-5")


def limit_event(resets_at: float) -> RateLimitEvent:
    return RateLimitEvent(
        rate_limit_info=RateLimitInfo(
            status="rejected", resets_at=int(resets_at), rate_limit_type="five_hour",
        ),
        uuid="u-r", session_id="sid-1",
    )


class FakeClient:
    def __init__(self) -> None:
        self.queries: list[str] = []

    async def query(self, prompt: str) -> None:
        self.queries.append(prompt)

    async def get_context_usage(self) -> dict:
        return {}


class _Inbox:
    def __init__(self, items: list) -> None:
        self._items = list(items)

    async def get(self, timeout: float | None = None):
        if self._items:
            return self._items.pop(0)
        if timeout is not None:
            raise asyncio.TimeoutError()
        raise MailboxClosed()


class FakeBox:
    def __init__(self, client: FakeClient, items: list) -> None:
        self.client = client
        self.frontend = None
        self._items = items

    @contextlib.asynccontextmanager
    async def claim(self, ticket=None):
        yield _Inbox(self._items)


def install(items: list) -> tuple[FakeClient, list[str]]:
    """把假 client 接進 runner；回傳 (client, drop 被叫了幾次的紀錄)。"""
    client = FakeClient()
    box = FakeBox(client, items)
    drops: list[str] = []

    async def fake_acquire(state, frontend):
        box.frontend = frontend
        return box

    async def fake_drop(conv):
        drops.append(conv)

    runner_mod.client_pool.acquire = fake_acquire  # type: ignore[assignment]
    runner_mod.client_pool.drop = fake_drop  # type: ignore[assignment]
    runner_mod.persist = lambda s: None  # type: ignore[assignment]
    turn_mod.persist = lambda s: None  # type: ignore[assignment]
    turn_mod.client_pool.drop = fake_drop  # type: ignore[assignment]
    return client, drops


def make_state() -> ConvState:
    return ConvState(conv_id="test", cwd=Path.home())


async def test_rate_limit_is_error() -> None:
    print("\n[額度用盡：回合以 RATE_LIMIT 收場，client 不丟]")
    resets = time.time() + 3600
    client, drops = install([
        limit_event(resets),
        assistant_msg(LIMIT_TEXT),
        result_msg(LIMIT_TEXT),
    ])
    fe = SilentFrontend()
    err: CCError | None = None
    try:
        await runner_mod.run_turn("最新的apk丟過來", make_state(), fe, "t-1")
    except CCError as e:
        err = e
    check("拋出 CCError", err is not None)
    if err:
        check("分類是 RATE_LIMIT", err.kind == "RATE_LIMIT", err.kind)
        check("帶著回復時刻", err.resets_at == int(resets), str(err.resets_at))
        check("使用者文案有時間也說會自動接著做",
              "回復後我會自動接著做" in err.user_msg and ":" in err.user_msg, err.user_msg)
    check("client 沒被丟掉", drops == [], str(drops))
    check("發了 turn.end ok=false",
          any(e.type == "turn.end" and e.data.get("ok") is False for e in fe.events))
    check("沒有定稿", fe.of("reply.final") == [])


async def test_is_error_result() -> None:
    print("\n[Result.is_error 也走錯誤路徑]")
    _client, drops = install([
        assistant_msg("Something went wrong"),
        result_msg("API Error: 529 overloaded", is_error=True),
    ])
    err: CCError | None = None
    try:
        await runner_mod.run_turn("hi", make_state(), SilentFrontend(), "t-2")
    except CCError as e:
        err = e
    check("拋出 CCError", err is not None)
    check("依文字分類（529 → OVERLOADED）", err is not None and err.kind == "OVERLOADED",
          err.kind if err else "")
    check("client 沒被丟掉", drops == [])


async def test_handle_turn_surfaces_error() -> None:
    print("\n[handle_turn：error 事件帶 resets_at、記 diag、不推「做完了」]")
    resets = time.time() + 1800
    install([limit_event(resets), assistant_msg(LIMIT_TEXT), result_msg(LIMIT_TEXT)])
    fe = SilentFrontend()
    recorded: list[dict] = []
    orig = diag.record
    diag.record = lambda kind, **f: recorded.append({"kind": kind, **f})  # type: ignore[assignment]
    try:
        turn_mod._limit_until.clear()
        err = await turn_mod.handle_turn("最新的apk丟過來", make_state(), fe)
    finally:
        diag.record = orig  # type: ignore[assignment]
    check("回傳那個錯誤", isinstance(err, CCError) and err.kind == "RATE_LIMIT")
    errs = fe.of("error")
    check("發了一則 error 事件", len(errs) == 1, str(len(errs)))
    if errs:
        check("事件帶 resets_at", errs[0].data.get("resets_at") == float(int(resets)),
              str(errs[0].data.get("resets_at")))
        check("事件 kind 是 RATE_LIMIT", errs[0].data.get("kind") == "RATE_LIMIT")
    check("沒有 reply.final", fe.of("reply.final") == [])
    check("沒有 turn.done", fe.of("turn.done") == [])
    check("diag 記了 error",
          any(r["kind"] == "error" and r.get("err") == "RATE_LIMIT" for r in recorded),
          str([(r["kind"], r.get("err")) for r in recorded]))
    check("記住了額度回復時刻", turn_mod.limit_until("test") == float(int(resets)),
          str(turn_mod.limit_until("test")))
    # 過期就自動忘掉
    turn_mod._limit_until["test"] = time.time() - 1
    check("過期的限流不再算數", turn_mod.limit_until("test") is None)


async def test_compact_cooldown() -> None:
    print("\n[壓縮沒壓下來就冷卻；額度未回復不壓]")
    calls: list[str] = []
    ctx_after = [200_000]

    async def fake_run_turn(prompt, state, frontend, turn_id, expect_assistant=True, wake=None):
        calls.append(turn_id)
        state.ctx_tokens = ctx_after[0]
        return TurnResult(reply="", session_id="sid-1", ctx_authoritative=True)

    orig = turn_mod.run_turn
    turn_mod.run_turn = fake_run_turn  # type: ignore[assignment]
    turn_mod.persist = lambda s: None  # type: ignore[assignment]
    try:
        turn_mod._compact_failed.clear()
        turn_mod._limit_until.clear()
        st = make_state()
        st.ctx_tokens = 200_000
        st.ctx_threshold = 167_000
        fe = SilentFrontend()
        await turn_mod._maybe_compact(st, fe, "test")
        check("第一次會壓", len(calls) == 1, str(len(calls)))
        check("沒壓下來 → 記成失敗", "test" in turn_mod._compact_failed)
        await turn_mod._maybe_compact(st, fe, "test")
        check("冷卻期間不再壓", len(calls) == 1, str(len(calls)))
        # 冷卻過了、而且這次真的壓下來
        turn_mod._compact_failed["test"] -= turn_mod.COMPACT_COOLDOWN_SEC + 1
        ctx_after[0] = 40_000
        await turn_mod._maybe_compact(st, fe, "test")
        check("冷卻過後再壓", len(calls) == 2, str(len(calls)))
        check("壓成功就解除失敗紀錄", "test" not in turn_mod._compact_failed)
        check("ctx 用的是權威值不是歸零", st.ctx_tokens == 40_000, str(st.ctx_tokens))
        # 額度未回復：門檻超標也不壓
        st.ctx_tokens = 200_000
        turn_mod._limit_until["test"] = time.time() + 600
        await turn_mod._maybe_compact(st, fe, "test")
        check("限流期間不壓", len(calls) == 2, str(len(calls)))
    finally:
        turn_mod.run_turn = orig  # type: ignore[assignment]
        turn_mod._limit_until.clear()


async def test_wake_consumed_closes_turn() -> None:
    print("\n[wake 票失效也要收 turn.done]")

    async def fake_run_turn(prompt, state, frontend, turn_id, expect_assistant=True, wake=None):
        return None

    orig = turn_mod.run_turn
    turn_mod.run_turn = fake_run_turn  # type: ignore[assignment]
    try:
        fe = SilentFrontend()
        await turn_mod.handle_wake(object(), make_state(), fe)  # type: ignore[arg-type]
        dones = fe.of("turn.done")
        check("發了 turn.done", len(dones) == 1, str(len(dones)))
        check("而且不推播", bool(dones) and dones[0].data.get("notify") is False)
    finally:
        turn_mod.run_turn = orig  # type: ignore[assignment]


async def main() -> int:
    await test_rate_limit_is_error()
    await test_is_error_result()
    await test_handle_turn_surfaces_error()
    await test_compact_cooldown()
    await test_wake_consumed_closes_turn()
    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
