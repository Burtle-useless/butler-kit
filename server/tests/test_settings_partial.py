"""帳號預設的設定端點只改有送來的欄位。

/v1/settings 若兩欄一起存，只送一欄的前端改思考強度就會把預設模型清掉；
型別不對（`model=5`）也不該是 500。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402,F401
from fastapi.testclient import TestClient  # noqa: E402

from engine import models as model_catalog  # noqa: E402
from engine import state as state_mod  # noqa: E402
from transport.app import app  # noqa: E402
from transport.auth import require_token  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def test_partial() -> None:
    print("\n[/v1/settings 只改有送來的欄位]")
    app.dependency_overrides[require_token] = lambda: "test"
    c = TestClient(app)
    model = model_catalog.values()[0]
    effort = sorted(model_catalog.ALL_EFFORTS)[0]
    try:
        r = c.post("/v1/settings", json={"model": model, "effort": effort})
        check("兩欄一起設", r.status_code == 200 and r.json() == {"model": model, "effort": effort}, r.text)
        r = c.post("/v1/settings", json={"effort": ""})
        check("只清思考強度，模型還在", r.json() == {"model": model, "effort": None}, r.text)
        r = c.post("/v1/settings", json={"effort": effort})
        check("只設思考強度，模型還在", r.json() == {"model": model, "effort": effort}, r.text)
        r = c.post("/v1/settings", json={"model": ""})
        check("只清模型，思考強度還在", r.json() == {"model": None, "effort": effort}, r.text)
        check("model 不是字串回 400", c.post("/v1/settings", json={"model": 5}).status_code == 400)
        check("effort 不是字串回 400", c.post("/v1/settings", json={"effort": [1]}).status_code == 400)
        check("不認得的模型回 400", c.post("/v1/settings", json={"model": "gpt-9"}).status_code == 400)
        check("空的 payload 什麼都不動", c.post("/v1/settings", json={}).json() == {"model": None, "effort": effort})
    finally:
        app.dependency_overrides.pop(require_token, None)
        state_mod.save_defaults(None, None)


def main() -> int:
    test_partial()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
