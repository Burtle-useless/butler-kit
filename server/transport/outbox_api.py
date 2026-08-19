"""助理傳來的檔案：清單與下載。

兩個落點都要：卡片出現在**發起它的那段對話**裡（當下就看得到），同時也永遠留在
工具頁的收件匣裡（過兩天要回頭找的時候，沒有人想往回捲三百則對話）。

「工具拿不到 conv_id」曾經是只做收件匣的理由，現在傳檔工具改成一個對話一份
（file_tools.server_for），登記時就把來源綁上去了。
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
    """告訴所有裝置：有新檔案可以拿了。手機收到就發推播並刷新清單。

    事件帶上發起的 conv_id（登記時由 file_tools 綁進來），手機端才知道這張卡片
    該放進哪一段對話。舊的登記沒有這個欄位，退回 "-"。
    """
    if _publish is not None:
        _publish(make_event(
            item.get("conv_id") or "-", "-", "file.offer",
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
    item = await asyncio.to_thread(outbox.get, file_id)
    if item is None:
        raise HTTPException(status_code=404, detail="這筆已經過期了")
    p = Path(item["path"])
    if not p.exists():
        # 登記只是「指向磁碟上的檔案」，原檔被移走或刪掉是正常情況
        raise HTTPException(status_code=410, detail=f"原檔已經不在了：{item['name']}")
    # mime 是後來才加進登記的欄位，早期的記錄沒有。硬取會 KeyError → 500，
    # 而上面 announce 那條路早就改用 .get 了，只有下載這條漏掉。
    # 退回 octet-stream：瀏覽器／手機會當成「不知道是什麼，存起來」，
    # 對「把檔案拿到手」這個目的來說完全夠用。
    return FileResponse(path=p, media_type=item.get("mime") or "application/octet-stream",
                        filename=item["name"])
