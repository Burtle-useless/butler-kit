"""裝置認證：撤銷入口與讀-改-寫競態。

**先把 DEVICES_FILE 改掉再 import 其他東西**——不然這支測試會動到真實的
devices.json，跑完手機就連不上了。
"""
from __future__ import annotations

import json
import sys
import tempfile
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config                      # noqa: E402

TMP = Path(tempfile.mkdtemp())
config.DEVICES_FILE = TMP / "devices.json"   # type: ignore[misc]

from fastapi.testclient import TestClient    # noqa: E402
from transport import auth                   # noqa: E402
from transport.app import app                # noqa: E402

client = TestClient(app)

FAILED: list[str] = []

TOKEN_ME = "token-of-this-phone"
TOKEN_LOST = "token-of-the-lost-phone"


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def _seed() -> None:
    """兩台已授權裝置：手上這台，以及等一下要被撤銷的那台。"""
    config.DEVICES_FILE.write_text(json.dumps({
        "devices": {
            auth.hash_of(TOKEN_ME): {"name": "手上這台", "created": 1.0},
            auth.hash_of(TOKEN_LOST): {"name": "遺失那台", "created": 2.0},
        },
        "revoked": [],
    }, ensure_ascii=False), encoding="utf-8")
    auth._LAST_SEEN.clear()


def test_verify_does_not_write() -> None:
    """驗證 token 不可以寫磁碟。

    這是撤銷競態的根源：先前每個請求都整份讀 devices.json、更新 last_seen、
    再整份寫回，全程無鎖。verify 只要在 revoke 寫入**之前**載入、在其**之後**
    寫回，被撤銷的裝置就整筆復活了——而 SSE 重連會不停送請求，機會多得很。
    last_seen 是給人看的欄位，不是安全邊界，改放記憶體就整個問題消失。
    """
    print("\n[驗證 token 不碰磁碟]")
    _seed()
    before = config.DEVICES_FILE.read_bytes()
    mtime = config.DEVICES_FILE.stat().st_mtime_ns
    for _ in range(50):
        auth.verify(TOKEN_ME)
    check("內容沒被改寫", config.DEVICES_FILE.read_bytes() == before)
    check("檔案根本沒被寫過", config.DEVICES_FILE.stat().st_mtime_ns == mtime)
    check("last_seen 仍記得起來", auth._LAST_SEEN.get(auth.hash_of(TOKEN_ME)) is not None)


def test_revoke_sticks_under_concurrency() -> None:
    """撤銷之後，就算被撤的裝置還在猛送請求也不能復活。"""
    print("\n[撤銷不會被復活]")
    _seed()
    stop = threading.Event()
    seen_ok_after: list[bool] = []

    def keep_verifying() -> None:
        # 模擬被偷的手機不斷重連
        while not stop.is_set():
            auth.verify(TOKEN_LOST)

    threads = [threading.Thread(target=keep_verifying) for _ in range(4)]
    for t in threads:
        t.start()
    try:
        ok = auth.revoke(auth.hash_of(TOKEN_LOST))
        check("撤銷回報成功", ok is True)
        for _ in range(200):
            seen_ok_after.append(auth.verify(TOKEN_LOST))
    finally:
        stop.set()
        for t in threads:
            t.join(timeout=5)

    check("撤銷後一律擋下", not any(seen_ok_after),
          f"{sum(seen_ok_after)}/{len(seen_ok_after)} 次漏放")
    data = json.loads(config.DEVICES_FILE.read_text(encoding="utf-8"))
    check("檔案裡真的沒了", auth.hash_of(TOKEN_LOST) not in data["devices"])
    check("進了撤銷名單", auth.hash_of(TOKEN_LOST) in data["revoked"])
    check("沒有波及另一台", auth.verify(TOKEN_ME) is True)


def test_revoke_endpoint() -> None:
    """撤銷要有入口。

    `auth.revoke()` 從 Phase 1 就寫好了卻**零呼叫端**——模組 docstring 承諾的
    「可以從另一台裝置撤銷」等於沒實作，手機遺失時唯一手段是手改 devices.json。
    """
    print("\n[撤銷的 HTTP 入口]")
    _seed()
    head = {"Authorization": f"Bearer {TOKEN_ME}"}

    r = client.get("/v1/devices", headers=head)
    check("列得出裝置", r.status_code == 200, str(r.status_code))
    rows = r.json()["devices"]
    check("兩台都在", len(rows) == 2, str(len(rows)))
    check("標得出哪台是自己", sum(1 for d in rows if d["this"]) == 1, str(rows))
    check("不外洩明文 token",
          all("token" not in json.dumps(d) or d.get("hash") for d in rows))

    r = client.post(f"/v1/devices/{auth.hash_of(TOKEN_ME)}/revoke", headers=head)
    check("擋下撤銷自己", r.status_code == 400, str(r.status_code))
    check("擋下後自己還能用", auth.verify(TOKEN_ME) is True)

    r = client.post(f"/v1/devices/{auth.hash_of(TOKEN_LOST)}/revoke", headers=head)
    check("撤銷遺失那台成功", r.status_code == 200, str(r.status_code))
    check("清單剩一台", len(r.json()["devices"]) == 1)

    r = client.get("/v1/devices", headers={"Authorization": f"Bearer {TOKEN_LOST}"})
    check("被撤的裝置立刻被擋（401）", r.status_code == 401, str(r.status_code))

    r = client.post("/v1/devices/沒這個雜湊/revoke", headers=head)
    check("撤銷不存在的裝置回 404", r.status_code == 404, str(r.status_code))


if __name__ == "__main__":
    test_verify_does_not_write()
    test_revoke_sticks_under_concurrency()
    test_revoke_endpoint()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    sys.exit(1 if FAILED else 0)
