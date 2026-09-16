"""pytest 的接線：讓「腳本式測試」在 pytest 底下也擋得住回歸。

這裡的測試檔全部是腳本風格——`check(name, cond)` 印 PASS／FAIL 並把失敗記進模組級的
`FAILED`，`main()` 最後才依它回 exit code。直接 `python tests/test_x.py` 跑是對的；
但 pytest 收集 `test_*` 函式時，沒有任何一處 assert，**每一條都是綠的**（2026-09-02
審查抓到）。這支 conftest 在每個測試函式前後對照 `FAILED`，有紅就讓 pytest 也紅。

同時把 `BUTLER_DATA_DIR` 指到暫存目錄：測試不該寫進正式的 `data/`（同一天的端對端
就把一條測試對話寫進了正式的 session.json）。config.py 認這個環境變數。
"""
from __future__ import annotations

import os
import tempfile

import pytest

os.environ.setdefault("BUTLER_DATA_DIR", tempfile.mkdtemp(prefix="butler-test-"))


@pytest.fixture(autouse=True)
def _script_style_checks(request):
    failed = getattr(request.module, "FAILED", None)
    if isinstance(failed, list):
        failed.clear()
    yield
    if isinstance(failed, list) and failed:
        pytest.fail("check() 失敗：" + "、".join(str(f) for f in failed))
