"""跨層共用的小工具。刻意保持極薄——放不進 engine/transport 任一層的才擺這裡。"""
from __future__ import annotations

import os
import uuid
from pathlib import Path


def atomic_write_text(path: Path, text: str, encoding: str = "utf-8") -> None:
    """原子寫入：先寫同目錄唯一暫存檔、再 os.replace 換上。

    寫入途中崩潰／斷電不會留下半截 JSON——session、裝置清單、排程等狀態檔都吃這條。
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f"{path.name}.{uuid.uuid4().hex[:6]}.tmp")
    try:
        tmp.write_text(text, encoding=encoding)
        os.replace(tmp, path)
    finally:
        tmp.unlink(missing_ok=True)
