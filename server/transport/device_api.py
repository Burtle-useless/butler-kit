"""向手機要即時資料的往返通道。目前只有一種：位置。

跟 `hub.SseFrontend.ask` 的差別在於**問的對象不是人**。ask 會在對話裡畫一張卡片
等使用者點下去，逾時代表「他不想回答」；這裡問的是手機本身，App 收到就自己抓、
自己回，使用者全程看不到任何東西，逾時代表「手機拿不到」。兩者形狀很像，但共用
一套會讓「等人決定」跟「等裝置回報」共享逾時語意與 UI 行為，那是兩件事。

也刻意不綁對話：位置跟哪一段對話無關，事件的 conv_id 一律是 "-"。
"""
from __future__ import annotations

import asyncio
import uuid
from typing import Any, Callable

from fastapi import APIRouter, Body, Depends, HTTPException

from engine import location
from protocol import make_event

from .auth import require_token

router = APIRouter(prefix="/v1/device")

_publish: Callable[[Any], None] | None = None
_has_listeners: Callable[[], bool] | None = None

# 還在等手機回覆的請求。key 是 req_id，手機回報時帶回來對號入座。
_pending: dict[str, asyncio.Future[dict[str, Any]]] = {}


def set_publisher(fn: Callable[[Any], None], listeners: Callable[[], bool]) -> None:
    """注入事件發送與「現在有沒有裝置連著」的查詢。

    後者是為了快速失敗：沒有任何 SSE 連線時，這個請求註定等到逾時，
    與其讓助理空等十五秒，不如當場說手機不在線上。
    """
    global _publish, _has_listeners
    _publish = fn
    _has_listeners = listeners


async def request(kind: str, timeout_sec: float = 15.0) -> dict[str, Any] | None:
    """跟手機要一份資料，等它回報。拿不到一律回 None，呼叫端自己決定退路。"""
    if _publish is None or _has_listeners is None or not _has_listeners():
        return None
    req_id = uuid.uuid4().hex[:12]
    fut: asyncio.Future[dict[str, Any]] = asyncio.get_running_loop().create_future()
    _pending[req_id] = fut
    try:
        _publish(make_event(
            "-", "-", "device.request",
            req_id=req_id, kind=kind, timeout_sec=timeout_sec,
        ))
        return await asyncio.wait_for(fut, timeout=timeout_sec)
    except (asyncio.TimeoutError, asyncio.CancelledError):
        return None
    finally:
        _pending.pop(req_id, None)


def _resolve(req_id: str, payload: dict[str, Any]) -> None:
    """把手機送回來的東西交給等待中的請求。對不上就當作主動回報，不是錯誤。"""
    fut = _pending.get(req_id)
    if fut is not None and not fut.done():
        fut.set_result(payload)


@router.post("/location")
async def report_location(
    payload: dict = Body(...),
    _: str = Depends(require_token),
) -> dict:
    """手機回報目前位置。

    兩種來源共用這一條：回應 `device.request`（帶 req_id），或 App 自己主動送
    （不帶 req_id，例如剛拿到定位權限時先存一筆）。兩者都更新最後已知位置——
    快取愈新，助理之後問位置就愈可能不用真的去吵手機一次。

    抓不到位置時手機也要回報（帶 error），否則等待端只能空等到逾時。
    """
    err = str(payload.get("error") or "")
    req_id = str(payload.get("req_id") or "")
    if err:
        # 失敗不寫進快取——那會把「權限被關掉」蓋掉一筆本來還堪用的舊位置。
        _resolve(req_id, {"error": err})
        return {"ok": False, "error": err}

    try:
        rec = await asyncio.to_thread(location.save, payload)
    except ValueError as e:
        # 座標不合法。等待端要立刻知道，否則它會空等到逾時才發現手機其實回過了。
        _resolve(req_id, {"error": str(e)})
        raise HTTPException(status_code=400, detail=f"位置資料不合法：{e}") from None
    _resolve(req_id, rec)
    return {"ok": True, "location": rec}
