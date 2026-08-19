"""手機傳上來的檔案落地。

只負責「把 bytes 安全地寫到磁碟上、回傳絕對路徑」。
助理讀這些檔案靠的是 CC 內建的 Read 工具，所以這裡不做任何解析或縮圖——
把路徑塞進訊息文字，剩下的交給模型。
"""
from __future__ import annotations

import re
from datetime import datetime
from pathlib import Path

import config

UPLOAD_DIR: Path = config.DATA_DIR / "uploads"

# 單檔上限。手機照片單張約 2~8MB，錄影才會破這個數字；
# 真要傳大檔用 tailnet 直接複製比走這條路實在。
MAX_UPLOAD_BYTES: int = 32 * 1024 * 1024

# 檔名只留這些字元。路徑分隔符、冒號、萬用字元一律換掉——
# 檔名是手機端送來的，直接拿來組路徑等於把寫入位置交給呼叫端。
_SAFE_RE = re.compile(r"[^\w.\-() 一-鿿]")


def _safe_name(raw: str) -> str:
    """把外來檔名壓成安全的單一檔名（不含任何目錄成分）。"""
    name = Path(raw.strip()).name          # 先剝掉目錄，"../../x" → "x"
    name = _SAFE_RE.sub("_", name).strip("._ ") or "file"
    return name[:80]                        # Windows 路徑總長有限，檔名先設個上限


def save_upload(raw_name: str, data: bytes) -> Path:
    """寫入上傳目錄，回傳絕對路徑。

    檔名前綴時間戳而不是覆寫同名檔：手機相簿的檔名重複率很高
    （IMG_0001.jpg 這種），覆寫會讓上一則訊息引用的路徑指到不同內容。
    """
    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    name = _safe_name(raw_name)
    path = UPLOAD_DIR / f"{stamp}-{name}"
    # 時間戳只到秒，所以「同一秒的同名檔」還是會撞——手機一次多選送兩張 IMG_0001.jpg
    # 就是這個情況，而 write_bytes 會直接覆寫，訊息裡的路徑於是指到另一張圖，
    # 正好是上面那段 docstring 說要避免的事。撞到就加序號，不覆寫任何既有檔案。
    # 用 x 模式建檔（已存在就拋 FileExistsError）而不是先 exists() 再寫：
    # 兩個並行的上傳可以同時通過 exists() 檢查，這個判斷得由檔案系統做才不會有空隙。
    n = 1
    while True:
        try:
            with path.open("xb") as f:
                f.write(data)
            break
        except FileExistsError:
            n += 1
            stem, dot, ext = name.partition(".")
            path = UPLOAD_DIR / f"{stamp}-{stem}-{n}{dot}{ext}"
    return path.resolve()
