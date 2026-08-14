"""工具端點的實作：截圖等「電腦端能力」。"""
from __future__ import annotations

import asyncio
import io


def _grab_sync(all_screens: bool) -> bytes:
    """截目前螢幕畫面，回 PNG bytes。

    用 PIL.ImageGrab 而不是外呼 PowerShell：同進程、無暫存檔、快一個數量級。
    all_screens=True 涵蓋整個虛擬桌面（這台機器是雙螢幕，只抓主螢幕會漏掉一半，
    見過去 Win32_VideoController -First 1 推錯結論的教訓）。
    """
    from PIL import ImageGrab

    img = ImageGrab.grab(all_screens=all_screens)
    # 手機看不需要原尺寸；雙螢幕拼起來寬度會超過 3000px，等比縮到 1600 寬省流量
    if img.width > 1600:
        ratio = 1600 / img.width
        img = img.resize((1600, int(img.height * ratio)))
    buf = io.BytesIO()
    img.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


async def screenshot(all_screens: bool = True) -> bytes:
    """非同步包裝：GDI 截圖是阻塞操作，丟 thread 免得卡住事件迴圈。"""
    return await asyncio.to_thread(_grab_sync, all_screens)
