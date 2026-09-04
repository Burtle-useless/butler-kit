"""已授權裝置的 HTTP 路由：列出、撤銷。

跟 `device_api.py`（單數）不是同一件事：那邊是「向手機要即時資料」的往返通道，
這邊是配對裝置的名單管理，資料在 auth 的 devices.json。
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from .auth import hash_of, list_devices, require_token, revoke

router = APIRouter()


@router.get("/v1/devices")
async def get_devices(token: str = Depends(require_token)) -> dict:
    """列出已授權裝置。`this` 標出呼叫端自己那台，UI 才知道哪一列不該給撤銷鈕。"""
    me = hash_of(token)
    return {"devices": [{**d, "this": d["hash"] == me} for d in list_devices()]}


@router.post("/v1/devices/{token_hash}/revoke")
async def revoke_device(token_hash: str, token: str = Depends(require_token)) -> dict:
    """撤銷一台裝置。手機遺失時從另一台已授權裝置呼叫。

    `auth.revoke()` 從 Phase 1 就寫好了卻一直沒有呼叫端——模組 docstring 承諾的
    「可以從另一台裝置撤銷」等於沒實作，遺失時唯一手段是手改 devices.json。

    擋自撤：撤掉自己這台會立刻把自己鎖在門外，而且撤到一台不剩時要重啟服務
    才會重新生 token（`ensure_token` 只在啟動時跑）。真要重置就直接動檔案，
    不該讓一次誤觸做到。
    """
    if token_hash == hash_of(token):
        raise HTTPException(status_code=400, detail="不能撤銷你正在用的這台裝置")
    if not revoke(token_hash):
        raise HTTPException(status_code=404, detail="查無此裝置")
    return {"ok": True, "devices": list_devices()}
