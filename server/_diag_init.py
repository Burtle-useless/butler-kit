"""INIT_TIMEOUT bisect 診斷：逐步加參數，找出讓 initialize 卡死的那一個。

用法：python _diag_init.py <case>
每個 case 獨立跑一次（init timeout 是 60s，一次跑完全部要好幾分鐘）。
"""
from __future__ import annotations

import asyncio
import sys
import time

import config  # noqa: F401  # 環境清洗
from claude_agent_sdk import ClaudeAgentOptions, ClaudeSDKClient, HookMatcher

from engine.options import SYSTEM_APPEND


async def _noop_hook(input_data: dict, tool_use_id: str | None, context: object) -> dict:
    return {}


CASES: dict[str, dict] = {
    # 1. 最小：只有 cwd + cli_path
    "min": {},
    # 2. 加 model / effort / permission_mode
    "model": {
        "model": config.DEFAULT_MODEL,
        "permission_mode": "bypassPermissions",
    },
    # 3. 加 fallback_model / max_buffer_size
    "buffers": {
        "model": config.DEFAULT_MODEL,
        "permission_mode": "bypassPermissions",
        "fallback_model": config.FALLBACK_MODEL,
        "max_buffer_size": config.MAX_BUFFER_SIZE,
    },
    # 4. 加 system_prompt preset（無 append）
    "preset": {
        "model": config.DEFAULT_MODEL,
        "permission_mode": "bypassPermissions",
        "system_prompt": {"type": "preset", "preset": "claude_code"},
    },
    # 5. 加 system_prompt preset + append（懷疑對象：特殊字元）
    "append": {
        "model": config.DEFAULT_MODEL,
        "permission_mode": "bypassPermissions",
        "system_prompt": {
            "type": "preset", "preset": "claude_code", "append": SYSTEM_APPEND,
        },
    },
    # 6. 加 thinking
    "thinking": {
        "model": config.DEFAULT_MODEL,
        "permission_mode": "bypassPermissions",
        "thinking": {"type": "adaptive", "display": "summarized"},
    },
    # 7. 加 hooks（PreToolUse）
    "hooks": {
        "model": config.DEFAULT_MODEL,
        "permission_mode": "bypassPermissions",
        "hooks": {"PreToolUse": [HookMatcher(
            matcher="Bash|PowerShell|AskUserQuestion", hooks=[_noop_hook])]},
    },
    # 8. 全部（等同 build_options）
    "full": {
        "model": config.DEFAULT_MODEL,
        "permission_mode": "bypassPermissions",
        "fallback_model": config.FALLBACK_MODEL,
        "max_buffer_size": config.MAX_BUFFER_SIZE,
        "system_prompt": {
            "type": "preset", "preset": "claude_code", "append": SYSTEM_APPEND,
        },
        "thinking": {"type": "adaptive", "display": "summarized"},
        "hooks": {"PreToolUse": [HookMatcher(
            matcher="Bash|PowerShell|AskUserQuestion", hooks=[_noop_hook])]},
    },
}


async def main() -> int:
    case = sys.argv[1] if len(sys.argv) > 1 else "min"
    extra = CASES[case]
    opts = ClaudeAgentOptions(
        cwd=str(config.DEFAULT_CWD), cli_path=config.CLAUDE_CLI, **extra
    )
    if len(sys.argv) > 2 and sys.argv[2] == "--partial":
        opts.include_partial_messages = True
    t0 = time.time()
    print(f"[{case}] connecting… keys={sorted(extra)}", flush=True)
    try:
        c = ClaudeSDKClient(opts)
        await c.connect()
        print(f"[{case}] ✅ init OK  {time.time() - t0:.1f}s", flush=True)
        await c.disconnect()
        return 0
    except Exception as e:
        print(f"[{case}] ❌ {type(e).__name__}: {e}  {time.time() - t0:.1f}s", flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
