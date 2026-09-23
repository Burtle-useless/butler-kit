"""錯誤分類、限流回復時刻、wake 回合的重複錯誤（四條）。

  - 子字串比數字：「14012 ms」→ AUTH、「initialize MCP」→ INIT_TIMEOUT、「400…too long」→ 清 session
  - 「Failed to authenticate」→ UNKNOWN（OAuth 過期時就是這句，client 沒丟，之後每輪 401）
  - 「resets 7:20pm」在 19:31 被解析成隔天（差 24 小時）
  - 四個 wake 回合同一秒撞限流，手機收到四則一樣的錯誤
"""
from __future__ import annotations

import asyncio
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402,F401

from engine import turn  # noqa: E402
from engine.errors import CCError, classify, parse_resets_at  # noqa: E402
from engine.state import ConvState  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def test_classify() -> None:
    print("\n[classify]")
    cases = [
        ("Failed to authenticate. API Error: invalid token", "AUTH"),
        ("OAuth token has expired", "AUTH"),
        ("Invalid API key · Please run /login", "AUTH"),
        ("API Error: 401 {\"type\":\"authentication_error\"}", "AUTH"),
        ("Request took 14012 ms", "UNKNOWN"),
        ("see https://example.com/login for details", "UNKNOWN"),
        ("failed to initialize MCP server blender", "UNKNOWN"),
        ("Control request timeout: initialize", "INIT_TIMEOUT"),
        ("API Error: 400 prompt is too long: 250000 tokens > 200000 maximum", "CONTEXT_FULL"),
        ("API Error: 400 {\"message\":\"text field too long\"}", "UNKNOWN"),
        ("API Error: 429 rate_limit_error", "RATE_LIMIT"),
        ("You've hit your session limit · resets 3pm", "RATE_LIMIT"),
        ("API Error: 529 overloaded_error", "OVERLOADED"),
        ("process 1529 exited", "UNKNOWN"),
        ("error_during_execution", "EXEC_ERROR"),
        ("error_max_turns", "MAX_TURNS"),
        ("Failed to start Claude Code: WinError 267", "STARTUP"),
    ]
    for text, want in cases:
        got = classify(text)
        check(f"{text[:44]!r} → {want}", got == want, got)


def _at(h: int, m: int) -> float:
    return datetime.now().replace(hour=h, minute=m, second=31, microsecond=0).timestamp()


def test_resets() -> None:
    print("\n[parse_resets_at]")
    now = _at(19, 31)
    r = parse_resets_at("You've hit your limit · resets 7:20pm", now)
    check("剛過 11 分鐘：回今天那個過去的時刻，不是明天", r is not None and 0 < now - r < 3600,
          f"差 {(now - (r or 0)) / 3600:.1f} 小時")
    r = parse_resets_at("resets 3pm", now)
    check("過了四個半小時：算明天", r is not None and r - now > 12 * 3600)
    r = parse_resets_at("resets 3:30pm", _at(10, 0))
    check("還沒到：今天", r is not None and 0 < r - _at(10, 0) < 6 * 3600)
    r = parse_resets_at("resets 12am", _at(23, 50))
    check("午夜：十分鐘後", r is not None and 0 < r - _at(23, 50) <= 11 * 60)
    check("過去的時刻 → 文案說等一下再試",
          "等一下" in CCError("RATE_LIMIT", "x", resets_at=time.time() - 600).user_msg)
    check("未來的時刻 → 文案帶時間", ":" in CCError("RATE_LIMIT", "x", resets_at=time.time() + 1800).user_msg)


class CollectFE:
    def __init__(self) -> None:
        self.events: list[Any] = []

    async def emit(self, ev: Any) -> None:
        self.events.append(ev)

    async def ask(self, req: Any) -> None:
        return None


async def test_wake_dedupe() -> None:
    print("\n[wake 回合的重複錯誤只報一次]")
    st = ConvState(conv_id="dedupe-test", cwd=Path("."))
    fe = CollectFE()
    err = CCError("RATE_LIMIT", "hit your limit", resets_at=time.time() + 1800)
    for _ in range(4):
        await turn._handle_error(err, st, fe, st.conv_id, wake=True)
    kinds = [e.type for e in fe.events]
    check("四輪只有一則 error", kinds.count("error") == 1, str(kinds))
    check("其餘三輪安靜收尾（忙碌旗標才清得掉）", kinds.count("turn.done") == 3)
    check("安靜收尾不推播", all(not e.data.get("notify") for e in fe.events if e.type == "turn.done"))

    fe2 = CollectFE()
    for _ in range(2):
        await turn._handle_error(err, st, fe2, st.conv_id)          # 使用者自己的訊息
    check("使用者的訊息出錯每次都報", [e.type for e in fe2.events] == ["error", "error"])

    fe3 = CollectFE()
    other = CCError("OVERLOADED", "overloaded")
    await turn._handle_error(other, st, fe3, st.conv_id, wake=True)
    check("換一種錯誤照報", [e.type for e in fe3.events] == ["error"])


def main() -> int:
    test_classify()
    test_resets()
    asyncio.run(test_wake_dedupe())
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
