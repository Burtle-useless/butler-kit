"""session 清單的來源過濾：只列「由人發起」的對話。

清單要的是「人真的講過話」的對話——之前模型探測（「回一個字：好」）、標題生成、各種一次性測試 client
全部混進清單。

釘住的行為（規則本體在 sessions._user_initiated）：
  1. entrypoint 不是 sdk-py（官方桌面 App 的 claude-desktop 等）→ 一律列。
  2. sdk-py 且第一句帶 stamp 前綴（前端蓋的 `[MM/DD 週X HH:MM 來源]`）→ 列。
  3. sdk-py 且無前綴（程式自己開的）→ 濾掉。
  4. 過濾發生在分頁之前——濾掉的不佔 offset。
"""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch

import config  # noqa: F401
from engine import sessions

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def _mk(root: Path, sid: str, text: str, entrypoint: str, mtime: float) -> None:
    proj = root / "C--Users-hower"
    proj.mkdir(parents=True, exist_ok=True)
    jf = proj / f"{sid}.jsonl"
    rec = {
        "type": "user", "sessionId": sid, "uuid": "u1",
        "cwd": "C:\\Users\\hower", "entrypoint": entrypoint,
        "message": {"content": [{"type": "text", "text": text}]},
    }
    jf.write_text(json.dumps(rec, ensure_ascii=False) + "\n", encoding="utf-8")
    import os
    os.utime(jf, (mtime, mtime))


def main() -> int:
    print("\n[規則本體]")
    ok = sessions._user_initiated
    check("官方桌面 App 一律列", ok("claude-desktop", "這甚麼意思"))
    check("沒見過的官方入口也列", ok("cli", "hello"))
    check("手機前綴列", ok("sdk-py", "[09/05 週六 11:34 手機] 早安"))
    check("電腦前綴列", ok("sdk-py", "[09/03 週四 09:14 電腦] 開個檔"))
    check("Discord 前綴列", ok("sdk-py", "[09/05 週六 10:03 Discord] [鳥龜]: 看一下"))
    check("SDK 無前綴濾掉（探測）", not ok("sdk-py", "回一個字：好"))
    check("SDK 無前綴濾掉（一般句）", not ok("sdk-py", "幫我看一下這個專案"))
    check("entrypoint 抓不到又無前綴＝雜訊", not ok("", "some leftover"))
    check("假冒的前綴格式不完整不算", not ok("sdk-py", "[週六] 嗨"))

    print("\n[整條 scan_sessions]")
    root = Path(tempfile.mkdtemp(prefix="butler-scanfilter-"))
    base = 1_700_000_000.0
    _mk(root, "aaaaaaaa-0000-0000-0000-000000000001",
        "[09/05 週六 11:00 手機] 修一下捲動", "sdk-py", base + 40)
    _mk(root, "aaaaaaaa-0000-0000-0000-000000000002",
        "回一個字：好", "sdk-py", base + 30)
    _mk(root, "aaaaaaaa-0000-0000-0000-000000000003",
        "幫我看一下超執行緒", "claude-desktop", base + 20)
    _mk(root, "aaaaaaaa-0000-0000-0000-000000000004",
        "[09/05 週六 05:54 Discord] [吳建宏]: 課程大綱", "sdk-py", base + 10)
    with patch.object(sessions, "_projects_dir", lambda: root):
        rows = sessions.scan_sessions(limit=10)
        ids = [r["session_id"][-1] for r in rows]
        check("探測那條不見了、其餘照舊序", ids == ["1", "3", "4"], str(ids))
        # 過濾在分頁之前：offset=1 應該跳過的是「留下來的第一筆」，不是被濾掉的
        page2 = sessions.scan_sessions(limit=10, offset=1)
        check("offset 數的是過濾後的筆數",
              [r["session_id"][-1] for r in page2] == ["3", "4"],
              str([r["session_id"][-1] for r in page2]))
    import shutil
    shutil.rmtree(root, ignore_errors=True)

    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(main())
