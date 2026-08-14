"""助理傳來的檔案：清單與下載。

刻意**不把檔案卡片塞進聊天流**。兩個理由：
一是 MCP 工具拿不到 conv_id（工具是無狀態呼叫，不知道自己屬於哪個對話），
硬要塞得另外傳遞脈絡；二是就算做得到也不該做——檔案過兩天要回頭找的時候，
沒有人想往回捲三百則對話。獨立一個收件匣，助理傳來的東西永遠在同一個地方。
"""
from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any, Callable

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse

import outbox
from protocol import make_event

from .auth import require_token

router = APIRouter(prefix="/v1/files")

_publish: Callable[[Any], None] | None = None


def set_publisher(fn: Callable[[Any], None]) -> None:
    global _publish
    _publish = fn


def announce(item: dict[str, Any]) -> None:
    """告訴所有裝置：有新檔案可以拿了。手機收到就發推播並刷新清單。"""
    if _publish is not None:
        _publish(make_event(
            "-", "-", "file.offer",
            file_id=item["file_id"], name=item["name"],
            bytes=item["bytes"], note=item.get("note", ""),
            # mime 登記時就算好了，一併送出手機才知道該不該直接把圖畫出來，
            # 不然前端只能猜副檔名
            mime=item.get("mime", ""),
        ))


async def aannounce(item: dict[str, Any]) -> None:
    """給 engine 的 async 掛勾用（publish 同步不阻塞，包一層只為型別對得上）。"""
    announce(item)


@router.get("")
async def list_files(_: str = Depends(require_token)) -> dict:
    return {"files": await asyncio.to_thread(outbox.list_recent)}


@router.get("/{file_id}")
async def download(file_id: str, _: str = Depends(require_token)) -> FileResponse:
    item = outbox.get(file_id)
    if item is None:
        raise HTTPException(status_code=404, detail="這筆已經過期了")
    p = Path(item["path"])
    if not p.exists():
        # 登記只是「指向磁碟上的檔案」，原檔被移走或刪掉是正常情況
        raise HTTPException(status_code=410, detail=f"原檔已經不在了：{item['name']}")
    return FileResponse(path=p, media_type=item["mime"], filename=item["name"])
