"""手機↔電腦的檔案往來（使用者主動那一側）：上傳到電腦、抓螢幕截圖。

反方向「助理主動傳檔給手機」在 outbox_api（`/v1/files`），那邊由模型發起、
走 in-process MCP 工具登記；這邊是人按下去的動作。
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import Response

from . import files
from .auth import require_token
from .tools import ScreenshotError
from .tools import screenshot as grab_screenshot

router = APIRouter()


@router.get("/v1/tools/screenshot")
async def tool_screenshot(_: str = Depends(require_token)):
    try:
        png = await grab_screenshot(all_screens=True)
    except ScreenshotError as e:
        # 503 而不是 500：這是「現在不行，等一下再來」，不是服務壞了。
        # 訊息要能讓人在外面分辨是螢幕鎖著還是服務掛了——後者根本連不上，
        # 前者解鎖就好，兩者的下一步完全不同。
        raise HTTPException(status_code=503, detail=str(e)) from e
    return Response(content=png, media_type="image/png")


@router.post("/v1/uploads")
async def upload_file(
    request: Request,
    name: str = "file",
    _: str = Depends(require_token),
) -> dict:
    """手機傳檔上來，回傳它在這台電腦上的絕對路徑。

    body 是**原始 bytes**、檔名走 query——不用 multipart 是為了省掉
    python-multipart 這個相依（服務跑在 MSIX python 上，裝套件比較麻煩），
    而且單檔上傳本來就不需要 multipart 的多段能力。
    """
    # 邊收邊擋，不用 request.body()：那個會先把整包吃進記憶體，檢查才輪得到——
    # 上限拉到 100MB 之後，等於任何一個請求都能先佔掉 100MB 再被拒絕。
    chunks: list[bytes] = []
    total = 0
    async for chunk in request.stream():
        total += len(chunk)
        if total > files.MAX_UPLOAD_BYTES:
            raise HTTPException(
                status_code=413,
                detail=f"檔案太大（上限 {files.MAX_UPLOAD_BYTES // (1024 * 1024)}MB）",
            )
        chunks.append(chunk)
    if not total:
        raise HTTPException(status_code=400, detail="空檔案")
    data = b"".join(chunks)
    path = await asyncio.to_thread(files.save_upload, name, data)
    return {"path": str(path), "bytes": len(data)}
