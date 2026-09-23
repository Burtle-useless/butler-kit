"""來自請求的 session id 拼進 glob 之前一定要驗格式。

接管端點的 id 若沒驗就拿去 `~/.claude/projects/*/<id>.jsonl` 找檔，
傳 `*` 會對到任意一份 session（接著被複印、接管）。這裡在暫存目錄放一份假 session，
確認萬用字元對不到它、正常的 UUID 照樣找得到。
"""
from __future__ import annotations

import sys
import tempfile
import uuid
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from engine import history, sessions  # noqa: E402
from transport.app import app  # noqa: E402
from transport.auth import require_token  # noqa: E402
from util import is_session_id  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def test_guard() -> None:
    print("\n[is_session_id]")
    sid = str(uuid.uuid4())
    check("標準 UUID", is_session_id(sid))
    for bad in ("*", "*/*", "..", "", "abc", sid + "*", sid[:-1] + "?", None, 123):
        check(f"擋下 {bad!r}", not is_session_id(bad))

    print("\n[萬用字元對不到真的 session]")
    root = Path(tempfile.mkdtemp(prefix="butler-sid-"))
    proj = root / "C--Users-x"
    proj.mkdir()
    (proj / f"{sid}.jsonl").write_text(
        '{"type":"user","cwd":"C:/","message":{"role":"user","content":"hi"}}\n',
        encoding="utf-8",
    )
    with patch.object(config, "claude_projects_dir", lambda: root):
        check("正常 id 找得到", history.session_exists(sid))
        check("* 找不到", not history.session_exists("*"))
        check("session_cwd(*) 空字串", sessions.session_cwd("*") == "")
        check("fork_session(*) 不複印", sessions.fork_session("*") is None)
        check("原資料夾沒有多出複印檔", len(list(proj.glob("*.jsonl"))) == 1)

        app.dependency_overrides[require_token] = lambda: "test"
        try:
            r = TestClient(app).post("/v1/sessions/*/adopt")
            check("接管端點對 * 回 404", r.status_code == 404, str(r.status_code))
        finally:
            app.dependency_overrides.pop(require_token, None)
        check("接管端點沒複印任何東西", len(list(proj.glob("*.jsonl"))) == 1)


def main() -> int:
    test_guard()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
