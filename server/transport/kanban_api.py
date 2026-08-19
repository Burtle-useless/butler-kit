"""看板的 HTTP 路由（App 直接呼叫用）。

跟助理的 MCP 工具（engine/kanban_tools.py）共用同一個 kanban store，
所以「助理幫你推進的」跟「你在 App 上拖的」是同一份資料。

每次寫入後廣播 kanban.changed，App 收到就重拉。
"""
from __future__ import annotations

from typing import Any, Callable

from fastapi import APIRouter, Body, Depends, HTTPException

import kanban
from protocol import make_event

from .auth import require_token

router = APIRouter(prefix="/v1/kanban")

_publish: Callable[[Any], None] | None = None


def set_publisher(fn: Callable[[Any], None]) -> None:
    global _publish
    _publish = fn


def broadcast() -> None:
    """通知所有裝置：看板變了。conv_id 用 "-" 表示不屬於任何對話。"""
    if _publish is not None:
        _publish(make_event("-", "-", "kanban.changed"))


async def abroadcast(_: str = "") -> None:
    """給 engine 的 async 掛勾用。"""
    broadcast()


@router.get("", dependencies=[Depends(require_token)])
async def get_board() -> dict:
    """整張板子，已照顯示順序排好（見 kanban.get_board）。"""
    return kanban.get_board()


@router.post("/cards", dependencies=[Depends(require_token)])
async def add_card(body: dict = Body(...)) -> dict:
    title = (body.get("title") or "").strip()
    if not title:
        raise HTTPException(400, detail="工作名稱不能是空的")
    card = kanban.add_card(
        title=title,
        status=body.get("status", "todo"),
        urgent=bool(body.get("urgent", False)),
        note=body.get("note", ""),
    )
    broadcast()
    return card


@router.patch("/cards/{card_id}", dependencies=[Depends(require_token)])
async def update_card(card_id: str, body: dict = Body(...)) -> dict:
    """改欄位。拖放請改用 /cards/{id}/move——那條會處理其他卡片的讓位。"""
    card = kanban.update_card(
        card_id,
        title=body.get("title"),
        status=body.get("status"),
        urgent=body.get("urgent"),
        note=body.get("note"),
    )
    if card is None:
        raise HTTPException(404, detail="找不到這張卡片")
    broadcast()
    return card


@router.post("/cards/{card_id}/move", dependencies=[Depends(require_token)])
async def move_card(card_id: str, body: dict = Body(...)) -> dict:
    """拖放落點：status＝落在哪一欄，order＝落在第幾個。"""
    status = (body.get("status") or "").strip()
    if status not in kanban.STATUSES:
        raise HTTPException(400, detail="status 要是 todo/doing/done 其中之一")
    try:
        order = int(body.get("order", 0))
    except (TypeError, ValueError):
        raise HTTPException(400, detail="order 要是數字")
    card = kanban.reorder(card_id, status, max(0, order))
    if card is None:
        raise HTTPException(404, detail="找不到這張卡片")
    broadcast()
    return card


@router.delete("/cards/{card_id}", dependencies=[Depends(require_token)])
async def archive_card(card_id: str) -> dict:
    if not kanban.archive_card(card_id):
        raise HTTPException(404, detail="找不到這張卡片")
    broadcast()
    return {"archived": True}
