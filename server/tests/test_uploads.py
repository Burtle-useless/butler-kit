"""上傳端點：落檔位置、路徑穿越、大小上限。

用 TestClient 直接打路由（不進 lifespan，不需要真的起服務），
認證用 dependency_overrides 繞過——這裡要驗的是檔案處理，不是 auth。
"""
from __future__ import annotations

import sys

from fastapi.testclient import TestClient

from transport import files
from transport.app import app
from transport.auth import require_token

app.dependency_overrides[require_token] = lambda: "test"
client = TestClient(app)

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def test_roundtrip() -> None:
    print("\n[上傳往返]")
    r = client.post("/v1/uploads?name=note.txt", content=b"hello")
    check("回 200", r.status_code == 200, str(r.status_code))
    path = r.json()["path"]
    check("落在 uploads 目錄下", path.startswith(str(files.UPLOAD_DIR)), path)
    check("內容一致", open(path, "rb").read() == b"hello")
    check("回報大小", r.json()["bytes"] == 5)


def test_traversal() -> None:
    print("\n[路徑穿越]")
    r = client.post("/v1/uploads?name=../../pwned.txt", content=b"x")
    path = r.json()["path"]
    # 目錄成分必須被剝掉，檔案不能跑到 uploads 之外
    check("沒有逃出 uploads", path.startswith(str(files.UPLOAD_DIR)), path)
    check("檔名只剩尾段", path.endswith("pwned.txt"), path)


def test_limits() -> None:
    print("\n[界線]")
    r = client.post("/v1/uploads?name=empty.bin", content=b"")
    check("空檔被拒（400）", r.status_code == 400, str(r.status_code))
    big = b"\0" * (files.MAX_UPLOAD_BYTES + 1)
    r = client.post("/v1/uploads?name=big.bin", content=big)
    check("超過上限被拒（413）", r.status_code == 413, str(r.status_code))


if __name__ == "__main__":
    test_roundtrip()
    test_traversal()
    test_limits()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    sys.exit(1 if FAILED else 0)
