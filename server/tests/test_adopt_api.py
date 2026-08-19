"""接管端點的端到端測試。

這條路徑先前一行測試都沒有，代價是 2026-08-18 把 `_first_user_record` 改名成
`_head_scan` 時漏掉了 `_find_session_meta` 裡的延遲 import——那個 import 寫在
函式體內，py_compile 與所有既有測試都碰不到它，於是「接電腦上的 session」整個
功能壞掉，是使用者在手機上點下去才發現的。

所以這裡刻意打真的端點，而不是直接呼叫底層函式。
"""
from __future__ import annotations

import json
import shutil
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config                                          # noqa: E402

_TMP = Path(tempfile.mkdtemp(prefix="butler_adopt_"))
config.SESSION_FILE = _TMP / "session.json"

from fastapi.testclient import TestClient              # noqa: E402
from engine import history, sessions, state as state_mod   # noqa: E402
from transport.app import app                          # noqa: E402
from transport.auth import require_token               # noqa: E402

app.dependency_overrides[require_token] = lambda: "test"
client = TestClient(app)

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def _make_session(root: Path, sid: str) -> Path:
    proj = root / "C--Users-you"
    proj.mkdir(parents=True, exist_ok=True)
    jf = proj / f"{sid}.jsonl"
    rows = [
        {"type": "user", "sessionId": sid, "uuid": "u1", "cwd": str(root),
         "message": {"content": [{"type": "text", "text": "幫我看一下這個專案"}]}},
        {"type": "assistant", "sessionId": sid, "uuid": "u2", "parentUuid": "u1",
         "message": {"content": [{"type": "text", "text": "好"}]}},
    ]
    with jf.open("w", encoding="utf-8", newline="\n") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    return jf


def test_adopt_end_to_end() -> None:
    print("\n[接管既有 session]")
    root = Path(tempfile.mkdtemp(prefix="butler_proj_"))
    sid = "12345678-1234-1234-1234-123456789abc"
    src = _make_session(root, sid)

    # 標題那步會叫 Haiku，測試裡不要真的發請求
    async def _no_title(_conv: str, _text: str = "") -> bool:
        return False

    # history._session_file 把 ~/.claude/projects 寫死在函式裡，沒有可換的接縫，
    # 只能整個換掉它
    def _find(s: str) -> Path | None:
        return next(iter(root.glob(f"*/{s}.jsonl")), None)

    with patch.object(sessions, "_projects_dir", lambda: root), \
            patch.object(history, "_session_file", _find), \
            patch("transport.app._rename_adopted", _no_title):
        r = client.post(f"/v1/sessions/{sid}/adopt")

    check("回 200", r.status_code == 200, f"實際 {r.status_code} {r.text[:120]}")
    if r.status_code != 200:
        shutil.rmtree(root, ignore_errors=True)
        return

    body = r.json()
    cid = body.get("conv_id", "")
    check("有回 conv_id", bool(cid))
    check("有回標題", bool(body.get("title")), repr(body.get("title")))

    st = state_mod.get_state(cid)
    check("接的不是原本那個 id（要用複印本）", st.session_id != sid,
          f"session_id={st.session_id}")
    check("記下了 forked_from", st.forked_from == sid)

    # 複印本要真的存在，而且原檔不能被動到
    forked = root / "C--Users-you" / f"{st.session_id}.jsonl"
    check("複印本存在", forked.exists())
    check("原始檔還在", src.exists())
    if forked.exists():
        sids = {json.loads(x).get("sessionId")
                for x in forked.read_text(encoding="utf-8").splitlines()}
        check("複印本的 sessionId 全部換新", sids == {st.session_id}, str(sids))
        check("複印本的 mtime 跟原檔一致（不會被 --continue 挑走）",
              abs(forked.stat().st_mtime - src.stat().st_mtime) <= 1)

    shutil.rmtree(root, ignore_errors=True)


def test_adopt_missing() -> None:
    print("\n[接不存在的 session]")
    root = Path(tempfile.mkdtemp(prefix="butler_proj_"))
    with patch.object(sessions, "_projects_dir", lambda: root), \
            patch.object(history, "_session_file", lambda s: None):
        r = client.post("/v1/sessions/00000000-0000-0000-0000-000000000000/adopt")
    check("回 404", r.status_code == 404, f"實際 {r.status_code}")
    shutil.rmtree(root, ignore_errors=True)


def main() -> int:
    test_adopt_end_to_end()
    test_adopt_missing()
    shutil.rmtree(_TMP, ignore_errors=True)
    print("\n全部通過" if not FAILED else f"\n失敗 {len(FAILED)}：{FAILED}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
