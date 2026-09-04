"""事件契約：伺服器發的每一種事件，清單裡要有、兩個前端要接。

2026-09-02 審查：`stream.reset`／`kanban.changed` 伺服器發了、前端接了，`EventType`
清單沒有；App 漏接 `alert`（後來整個功能拆掉了）與前景的 `notify`；電腦版漏接四種。
Python 的 Literal 執行期不擋，兩邊的 `when`／`switch` 又各抄一份，沒有任何東西會炸。
這支測試把三邊對起來，漂移就紅。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import config  # noqa: F401
from protocol import make_event
from protocol.events import EVENT_TYPES

FAILED: list[str] = []
ROOT = Path(__file__).resolve().parents[2]      # butler-kit/
SERVER = ROOT / "server"
APP_UI = ROOT / "app" / "app" / "src" / "main" / "java" / "dev" / "butlerkit" / "app"


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}", flush=True)
    if not cond:
        FAILED.append(name)


def emitted_types() -> set[str]:
    """伺服器程式碼裡 make_event(..., "xxx") 用到的事件名。"""
    pat = re.compile(r'make_event\(\s*[^,]+,\s*[^,]+,\s*"([a-z._]+)"')
    out: set[str] = set()
    for py in list((SERVER / "engine").rglob("*.py")) + list((SERVER / "transport").rglob("*.py")) \
            + list((SERVER / "agenda").rglob("*.py")):
        out |= set(pat.findall(py.read_text(encoding="utf-8")))
    return out


def kotlin_handled() -> set[str]:
    """App 的 reducer 與 ViewModel 裡 `"xxx" ->` 分支。"""
    pat = re.compile(r'"([a-z][a-z._]*)"\s*(?:,\s*"[a-z.]+"\s*)*->')
    out: set[str] = set()
    for kt in ("ui/TrailReducer.kt", "ui/ChatViewModel.kt", "notify/ButlerService.kt"):
        src = (APP_UI / kt).read_text(encoding="utf-8")
        out |= set(pat.findall(src))
        # 同一行多個型別（"a", "b" ->）只抓得到最後一個，補抓前面的
        for m in re.finditer(r'((?:"[a-z]+\.[a-z]+"\s*,\s*)+"[a-z]+\.[a-z]+")\s*->', src):
            out |= set(re.findall(r'"([a-z][a-z._]*)"', m.group(1)))
        # ViewModel 用 if (ev.type == "stream.reset") 這種寫法
        out |= set(re.findall(r'ev\.type\s*==\s*"([a-z][a-z._]*)"', src))
    return out


# 不畫也合理的事件
APP_OK_TO_IGNORE = {
    "kanban.changed",   # 看板頁自己拉；聊天不畫
}


def main() -> int:
    print("\n[伺服器發的每一種事件都在清單裡]")
    unknown = emitted_types() - EVENT_TYPES
    check("沒有未登記的事件", not unknown, str(sorted(unknown)))
    try:
        make_event("c", "-", "not.a.type")  # type: ignore[arg-type]
        check("make_event 擋未登記的型別", False)
    except ValueError:
        check("make_event 擋未登記的型別", True)

    print("\n[App 接住清單裡的每一種]")
    app_missing = EVENT_TYPES - kotlin_handled() - APP_OK_TO_IGNORE
    check("App 沒有漏接", not app_missing, str(sorted(app_missing)))

    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(main())
