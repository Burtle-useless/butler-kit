"""lifespan 真的跑一遍（不開埠）。

`import transport.app` 過了不代表啟動得起來：2026-09-03 拆檔後 app.py 少了
`model_catalog` 的 import，測試全綠、服務一重啟就 NameError 起不來，
使用者在手機上只看到「電腦這邊出問題了」。這支測試專門抓這種。
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402,F401
from engine import models  # noqa: E402
from transport import app as app_mod  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


async def _noop() -> bool:
    return False


async def main() -> int:
    print("\n[lifespan 跑得起來]")
    # bootstrap 會真的開一條 CLI 連線，測試裡不要
    with patch.object(models, "bootstrap", _noop):
        try:
            async with app_mod.lifespan(app_mod.app):
                await asyncio.sleep(0.1)
            check("進得去也出得來", True)
        except Exception as e:  # noqa: BLE001
            check("進得去也出得來", False, f"{type(e).__name__}: {e}")
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
