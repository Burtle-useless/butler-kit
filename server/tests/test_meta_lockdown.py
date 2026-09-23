"""一次性查詢（生標題、交接稿）不准有工具。

`meta.ask_once` 若開著 bypassPermissions、工具全開、不限輪數，吃的卻是對話原文
與整段逐字稿——裡面可能夾著助理讀過的網頁與檔案裡的注入指令。
這支測試只驗組出來的選項，不開 CLI。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402,F401
from engine import meta  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def test_one_shot_options() -> None:
    print("\n[一次性查詢的選項]")
    for model in (None, "claude-haiku-4-5"):
        o = meta.one_shot_options(model)
        check(f"{model}：內建工具全關", o.tools == [], repr(o.tools))
        check(f"{model}：只回一輪", o.max_turns == 1, repr(o.max_turns))
        check(f"{model}：不載入 MCP 設定", o.strict_mcp_config is True and not o.mcp_servers)
        check(f"{model}：不讀使用者設定", o.setting_sources == [], repr(o.setting_sources))
        check(f"{model}：沒有 bypassPermissions", o.permission_mode != "bypassPermissions",
              repr(o.permission_mode))
        check(f"{model}：不留逐字稿", "no-session-persistence" in (o.extra_args or {}))
    check("有總時限", 0 < meta.ONE_SHOT_TIMEOUT_SEC <= 600)


def main() -> int:
    test_one_shot_options()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
