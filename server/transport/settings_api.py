"""帳號層設定與用量的 HTTP 路由。

帳號預設的 model/effort（三層設定的中間層，見 engine.state）與用量表。
單一對話的覆寫在 conversations_api（`/v1/conversations/{id}/settings`）。
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, Body, Depends, HTTPException

import config
from engine import local_usage, plan_usage
from engine import models as model_catalog
from engine import state as state_mod
from engine import usage as usage_mod

from .auth import require_token

router = APIRouter()

# 模型清單**不在這裡寫死**：`engine/models` 從 CLI 的 initialize 回應拿官方的那份
# （Default／Opus 1M／Fable／Sonnet／Haiku，含顯示名與各自支援的思考等級），CLI
# 升版就自動長出新模型。2026-09-02 之前這裡是六個完整 id 的清單，每次官方換代都要人追。
_EFFORTS = list(model_catalog.ALL_EFFORTS)


@router.get("/v1/settings")
async def get_settings(_: str = Depends(require_token)) -> dict:
    return {
        "model": state_mod.default_model,
        "effort": state_mod.default_effort,
        # 舊欄位留著（值的清單，舊版 App 讀這個）；新欄位帶顯示名、說明、思考等級
        "models": model_catalog.values(),
        "model_infos": model_catalog.to_wire(),
        "models_source": model_catalog.source(),
        "efforts": _EFFORTS,
        "default_cwd": str(config.DEFAULT_CWD),
        # 破壞性指令確認現在開著還是關著。由啟動環境的 CONFIRM_DANGEROUS 決定
        # （launch_butler.vbs 設 0＝關），前端要把它顯示出來，別讓人以為有把關。
        "confirm_dangerous": config.CONFIRM_ENABLED,
    }


@router.post("/v1/settings")
async def set_settings(payload: dict = Body(...), _: str = Depends(require_token)) -> dict:
    """設帳號預設 model/effort。改了之後未被單獨覆寫的對話下次自動重建 client。"""
    model = payload.get("model")
    effort = payload.get("effort")
    if model and not model_catalog.is_known(model):
        raise HTTPException(status_code=400, detail=f"unknown model: {model}")
    if effort is not None and effort not in _EFFORTS + [""]:
        raise HTTPException(status_code=400, detail=f"unknown effort: {effort}")
    state_mod.save_defaults(model or None, effort or None)
    return {"model": state_mod.default_model, "effort": state_mod.default_effort}


@router.get("/v1/usage")
async def get_usage(span: int = 14, _: str = Depends(require_token)) -> dict:
    """用量表。span 是逐日圖要幾天，手機畫面窄，預設兩週。

    三份資料併在同一個回應裡，各自回答一個問題：
      本體        ── 走助理這條路花了多少（butler 自己記的帳）
      limits      ── 整個帳號還剩多少額度（含網頁版與手機 App，官方端點）
      local       ── 這台電腦上所有 CC 的組成（含 cc-bot、終端機、subagent）
    後兩者都可能失敗（對外請求、掃 1.2GB 逐字稿），各自包一層 try——
    絕不能讓附加資訊把主表拖垮。
    """
    days = max(1, min(span, 92))
    report = await asyncio.to_thread(usage_mod.report, days)
    try:
        report["limits"] = await asyncio.to_thread(plan_usage.limits)
    except Exception:
        report["limits"] = []
    try:
        report["local"] = await asyncio.to_thread(local_usage.report, days)
    except Exception:
        report["local"] = None
    return report
