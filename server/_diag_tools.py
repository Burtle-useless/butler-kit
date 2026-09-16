"""診斷：什麼條件下 CC session 才拿得到 AskUserQuestion。

環境變數設了沒用，所以逐項對照 permission_mode 與其他選項。
注意檢查工具名要用精確比對——"ask" 子字串會被 Task/TaskCreate 誤判成有。
"""
from __future__ import annotations

import asyncio
import os
import sys

import config  # noqa: F401  # 環境清洗＋AskUserQuestion 開關都在這裡
from claude_agent_sdk import ClaudeAgentOptions, ClaudeSDKClient

from engine.runner import iter_messages

CASES: dict[str, dict] = {
    "bypass": {"permission_mode": "bypassPermissions"},
    "default": {"permission_mode": "default"},
    "acceptEdits": {"permission_mode": "acceptEdits"},
    "無 permission_mode": {},
}


async def probe(label: str, extra: dict) -> None:
    opts = ClaudeAgentOptions(
        cwd=str(config.DEFAULT_CWD),
        cli_path=config.CLAUDE_CLI,
        model=config.DEFAULT_MODEL,
        **extra,
    )
    try:
        async with ClaudeSDKClient(opts) as c:
            await c.query("hi")
            async for msg in iter_messages(c):
                if type(msg).__name__ == "SystemMessage":
                    tools = (getattr(msg, "data", {}) or {}).get("tools") or []
                    has = "AskUserQuestion" in tools     # 精確比對
                    print(f"{label:18s} 工具數={len(tools):3d}  AskUserQuestion={has}")
                    return
                if type(msg).__name__ == "ResultMessage":
                    print(f"{label:18s} （沒有 SystemMessage）")
                    return
    except Exception as e:
        print(f"{label:18s} 失敗 {type(e).__name__}: {str(e)[:60]}")


async def main() -> int:
    print("env ASK_USER_QUESTION =",
          os.environ.get("CLAUDE_CODE_ENABLE_ASK_USER_QUESTION_TOOL"), "\n")
    for label, extra in CASES.items():
        await probe(label, extra)
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
