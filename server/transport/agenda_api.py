"""行事曆／鬧鐘／記帳／課表的 HTTP 路由（手動設定用）。

跟助理的 MCP 工具（engine/agenda_tools.py）共用同一個 store，所以
「你在 App 上手動加的」跟「助理幫你加的」是同一批資料、同一套驗證。

每個會改到資料的動作都推一則 agenda.changed 事件：手機收到就重拉並重排本地鬧鐘。
少了這個，助理幫你設的鬧鐘要等到你下次打開 App 才會被排進 AlarmManager——
也就是不會響。
"""
from __future__ import annotations

import asyncio
from typing import Any, Callable

from fastapi import APIRouter, Body, Depends, HTTPException

from agenda import store
from protocol import make_event

from .auth import require_token

router = APIRouter(prefix="/v1/agenda")

# 由 app.py 注入的廣播函式（把 Event 丟進 hub）。
# 這裡不直接 import app.hub——會變成循環 import。
_publish: Callable[[Any], None] | None = None


def set_publisher(fn: Callable[[Any], None]) -> None:
    global _publish
    _publish = fn


def broadcast(what: str) -> None:
    """通知所有裝置：這類資料變了。conv_id 用 "-" 表示不屬於任何對話。"""
    if _publish is not None:
        _publish(make_event("-", "-", "agenda.changed", what=what))


async def abroadcast(what: str) -> None:
    """給 engine 的 async 掛勾用。publish 本身同步不阻塞，包一層只為了型別對得上。"""
    broadcast(what)


def _guard(fn: Callable[[], Any]) -> Any:
    """把 AgendaError 轉成 400。驗證訊息本來就是寫給人看的，直接透出去。"""
    try:
        return fn()
    except store.AgendaError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e


@router.get("")
async def get_all(_: str = Depends(require_token)) -> dict:
    """四類加節次表一次全給。App 開日常頁就是要全部，分幾趟只是多幾次往返。"""
    data = await asyncio.to_thread(store.list_all)
    return {**data, "categories": list(store.CATEGORIES)}


@router.put("/periods")
async def put_periods(
    payload: dict = Body(...),
    _: str = Depends(require_token),
) -> dict:
    """整份改寫節次時間表。

    放在 /{kind} 這幾條路由**之前**：FastAPI 依宣告順序比對，寫在後面的話
    PUT 沒事但 `POST /periods` 會被 `/{kind}` 吃掉當成新增一筆「periods」類別。
    """
    rows = _guard(lambda: store.set_periods(payload.get("periods")))
    broadcast("periods")
    return {"periods": rows}


@router.get("/summary")
async def get_summary(month: str = "", _: str = Depends(require_token)) -> dict:
    return await asyncio.to_thread(lambda: _guard(lambda: store.summarize(month)))


@router.post("/{kind}")
async def create(
    kind: str,
    payload: dict = Body(...),
    _: str = Depends(require_token),
) -> dict:
    if kind == "events":
        item = _guard(lambda: store.add_event(
            title=payload.get("title", ""), start=payload.get("start", ""),
            end=payload.get("end"), note=payload.get("note", ""),
            remind_min=int(payload.get("remind_min", 10)),
        ))
    elif kind == "alarms":
        item = _guard(lambda: store.add_alarm(
            time=payload.get("time", ""), label=payload.get("label", ""),
            days=payload.get("days"), on_date=payload.get("date"),
        ))
    elif kind == "ledger":
        item = _guard(lambda: store.add_entry(
            amount=payload.get("amount", 0), category=payload.get("category", "其他"),
            note=payload.get("note", ""), ts=payload.get("ts"),
            income=bool(payload.get("income", False)),
        ))
    elif kind == "courses":
        # day 與 from_period 不給預設值：缺了就讓 store 的驗證擋下來並回一句中文，
        # 自己補 0 跟 1 會讓「忘記填星期」變成安靜地排到週一。
        item = _guard(lambda: store.add_course(
            name=payload.get("name", ""), day=payload.get("day"),
            from_period=payload.get("from_period"),
            to_period=payload.get("to_period"),
            teacher=payload.get("teacher", ""), room=payload.get("room", ""),
            note=payload.get("note", ""),
        ))
    else:
        raise HTTPException(status_code=404, detail=f"未知的類別：{kind}")
    broadcast(kind)
    return item


@router.patch("/{kind}/{item_id}")
async def patch(
    kind: str,
    item_id: str,
    payload: dict = Body(...),
    _: str = Depends(require_token),
) -> dict:
    if kind not in ("events", "alarms", "ledger", "courses"):
        raise HTTPException(status_code=404, detail=f"未知的類別：{kind}")
    row = _guard(lambda: store.update(kind, item_id, payload))  # type: ignore[arg-type]
    broadcast(kind)
    return row


@router.delete("/{kind}/{item_id}")
async def delete(
    kind: str,
    item_id: str,
    _: str = Depends(require_token),
) -> dict:
    if kind not in ("events", "alarms", "ledger", "courses"):
        raise HTTPException(status_code=404, detail=f"未知的類別：{kind}")
    if not store.remove(kind, item_id):  # type: ignore[arg-type]
        raise HTTPException(status_code=404, detail=f"找不到：{item_id}")
    broadcast(kind)
    return {"deleted": item_id}
