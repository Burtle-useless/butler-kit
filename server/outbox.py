"""助理要傳給手機的檔案清單。

上傳（手機→電腦）是把 bytes 寫到 uploads 目錄；反過來（電腦→手機）**不搬檔案**，
只登記「這個路徑可以被下載」並發一個 id 出去。理由是電腦上的檔案本來就在那裡，
複製一份到 outbox 目錄只會多佔一份磁碟、還要處理原檔被改動後兩份不一致。

登記要落地（不是放記憶體）：手機可能過幾小時才點下載，服務重啟過就找不到那筆了。
"""
from __future__ import annotations

import json
import mimetypes
import threading
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any

import config

OUTBOX_FILE: Path = config.DATA_DIR / "outbox.json"

# 手機一次下載的上限。超過這個數字走 tailnet 也要等很久，而下載中途沒有進度可看，
# 使用者只會覺得 App 壞了——這種檔案請改走檔案分享服務給連結。
MAX_OFFER_BYTES = 256 * 1024 * 1024

# 保留幾筆登記。舊的清掉只是不能再下載，檔案本身不會動到。
KEEP = 60

_LOCK = threading.Lock()


class OutboxError(ValueError):
    """路徑有問題。訊息是寫給助理看的中文，它讀了會自己改。"""


def _load() -> dict[str, Any]:
    if not OUTBOX_FILE.exists():
        return {}
    try:
        data = json.loads(OUTBOX_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return data if isinstance(data, dict) else {}


def _save(data: dict[str, Any]) -> None:
    OUTBOX_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = OUTBOX_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(OUTBOX_FILE)


def offer(path: str, note: str = "") -> dict[str, Any]:
    """登記一個檔案給手機下載，回傳那筆登記（含 file_id）。"""
    raw = (path or "").strip().strip('"')
    if not raw:
        raise OutboxError("要給我檔案的完整路徑")
    p = Path(raw)
    if not p.is_absolute():
        raise OutboxError(f"路徑要用絕對路徑：{raw}")
    if not p.exists():
        raise OutboxError(f"這個檔案不存在：{raw}")
    if p.is_dir():
        raise OutboxError(f"這是資料夾不是檔案，要傳整個資料夾請先壓成 zip：{raw}")
    size = p.stat().st_size
    if size > MAX_OFFER_BYTES:
        raise OutboxError(
            f"檔案太大（{size // (1024 * 1024)}MB，上限 "
            f"{MAX_OFFER_BYTES // (1024 * 1024)}MB）。這種請改用檔案分享服務給他下載連結"
        )

    item = {
        "file_id": f"f{uuid.uuid4().hex[:10]}",
        "path": str(p.resolve()),
        "name": p.name,
        "bytes": size,
        "mime": mimetypes.guess_type(p.name)[0] or "application/octet-stream",
        "note": (note or "").strip(),
        "at": datetime.now().strftime("%Y-%m-%dT%H:%M"),
    }
    with _LOCK:
        data = _load()
        data[item["file_id"]] = item
        # 依登記時間裁切。dict 保留插入順序，但重新載入後順序來自檔案，
        # 所以照 at 排序而不是靠順序——重啟後才不會刪錯人。
        if len(data) > KEEP:
            for k in sorted(data, key=lambda k: data[k].get("at", ""))[: len(data) - KEEP]:
                data.pop(k, None)
        _save(data)
    return item


def get(file_id: str) -> dict[str, Any] | None:
    with _LOCK:
        return _load().get(file_id)


def list_recent(limit: int = 40) -> list[dict[str, Any]]:
    """由新到舊。順帶標記原檔還在不在——手機端要據此把已消失的那筆變灰，
    不然使用者點下去只會拿到一個看不懂的 404。"""
    with _LOCK:
        rows = list(_load().values())
    rows.sort(key=lambda r: r.get("at", ""), reverse=True)
    return [{**r, "gone": not Path(r["path"]).exists()} for r in rows[:limit]]
