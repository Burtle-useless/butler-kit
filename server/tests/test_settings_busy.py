"""對話層設定端點與進行中回合的互動。

2026-09-05 實際案例：回合開始 9 秒後使用者在 App 換模型，端點無條件
`client_pool.drop` 把正在跑的 CLI 進程殺掉——回合的讀取端掛在收件匣上
永遠等不到訊息，畫面停在「想一下」五分鐘，直到使用者自己按停止。
修正後：回合在跑就只寫設定，重建交給下回合 acquire 的指紋比對；
閒置才立即 drop。這裡釘住這兩條路。
"""
from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
os.environ.setdefault("BUTLER_DATA_DIR", tempfile.mkdtemp(prefix="butler-test-"))

from fastapi.testclient import TestClient           # noqa: E402
from engine import client_pool                      # noqa: E402
from transport.app import app                       # noqa: E402
from transport import app as core_app               # noqa: E402
from transport.auth import require_token            # noqa: E402

app.dependency_overrides[require_token] = lambda: "test"
client = TestClient(app)

FAILED: list[str] = []

MODEL = "claude-fable-5[1m]"    # 後備清單就有，不依賴 CLI 連線


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def test_settings_while_running() -> None:
    print("\n[回合進行中換模型：不殺 client、設定照寫]")
    calls: list[str] = []

    async def fake_drop(cid: str) -> None:
        calls.append(cid)

    with patch.object(client_pool, "drop", fake_drop), \
            patch.object(core_app.worker, "is_running", lambda cid: True):
        r = client.post("/v1/conversations/ctestbusy/settings",
                        json={"model": MODEL})
    check("回 200", r.status_code == 200, f"實際 {r.status_code} {r.text[:120]}")
    check("沒有 drop（進行中的回合不能陪葬）", calls == [], str(calls))
    check("覆寫已寫入", r.json().get("model_override") == MODEL,
          repr(r.json().get("model_override")))


def test_settings_idle() -> None:
    print("\n[閒置時換模型：立即 drop 重建]")
    calls: list[str] = []

    async def fake_drop(cid: str) -> None:
        calls.append(cid)

    with patch.object(client_pool, "drop", fake_drop), \
            patch.object(core_app.worker, "is_running", lambda cid: False):
        r = client.post("/v1/conversations/ctestidle/settings",
                        json={"model": MODEL})
    check("回 200", r.status_code == 200, f"實際 {r.status_code} {r.text[:120]}")
    check("有 drop（閒置才能立即重建）", calls == ["ctestidle"], str(calls))


def test_cwd_while_running() -> None:
    print("\n[回合進行中換 cwd：同樣不殺 client]")
    calls: list[str] = []

    async def fake_drop(cid: str) -> None:
        calls.append(cid)

    target = tempfile.mkdtemp(prefix="butler-cwd-")
    with patch.object(client_pool, "drop", fake_drop), \
            patch.object(core_app.worker, "is_running", lambda cid: True):
        r = client.post("/v1/conversations/ctestcwd/cwd",
                        json={"path": target})
    check("回 200", r.status_code == 200, f"實際 {r.status_code} {r.text[:120]}")
    check("沒有 drop", calls == [], str(calls))


def main() -> int:
    test_settings_while_running()
    test_settings_idle()
    test_cwd_while_running()
    print("\n全部通過" if not FAILED else f"\n失敗 {len(FAILED)}：{FAILED}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
