"""掃出這台電腦上所有 Claude Code 的 session，讓手機能挑一個接回去。

跟 `state.list_conversations()` 的差別：那邊只認 butler 自己開過的對話
（來源是 data/session.json），在別處用 CLI 或官方 App 跑的東西它一無所知。
這裡直接掃 `~/.claude/projects`，把電腦上所有 session 都撈出來。

兩個踩過的點寫在這裡免得下次重犯：

1. **專案目錄名不能拿來反推路徑。** CLI 把絕對路徑的每個非英數字元換成 `-`，
   `C:\\Users\\you\\Desktop\\我的專案` 會變成 `C--Users-you-Desktop-----`，
   非英數字元全被壓成一個 `-`，是不可逆的。真正的工作目錄要讀 jsonl 裡的 `cwd`。

2. **前幾行通常不是對話。** 檔頭常是 `queue-operation` 之類的維運記錄，
   沒有 `cwd` 也沒有 `message`，要往下掃到第一筆 `type=="user"` 才有料。
   另外實測 `type=="summary"` 一筆都沒有，別指望 CLI 給現成標題。
"""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from .history import _text_of
from .state import _load_map

# session 檔名就是 UUID。同目錄下還有 `<uuid>/tool-results/` 這種同名資料夾，
# 以及 memory/ 之類的非 session 目錄，靠這個格式一併擋掉。
_UUID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", re.I,
)

# 找第一筆 user 記錄時最多往下讀幾行。正常檔案在前三行內就有，
# 讀到上限還沒有就當它沒有——不值得為了少數畸形檔把 467 個檔全部讀完。
_HEAD_LINES = 40

# 標題長度上限。手機一行放不下太多，後面截掉。
_TITLE_MAX = 60

# 跟 history.load_history 同一份黑名單：這些是維運產物，拿來當標題會變成
# 一整排「<command-name>」，看不出哪個是哪個。
_NOISE_PREFIX = (
    "剛才那一步還沒收尾", "剛才沒有收到",
    "/compact", "<command-name>", "<local-command",
    "This session is being continued from a previous conversation",
    "請把我們目前為止的對話壓縮成重點摘要",
)


def _projects_dir() -> Path:
    return Path.home() / ".claude" / "projects"


def _first_user_record(jf: Path) -> dict[str, Any] | None:
    """讀出第一筆能當標題用的 user 記錄。讀不到回 None。"""
    try:
        with jf.open(encoding="utf-8", errors="replace") as f:
            for i, line in enumerate(f):
                if i >= _HEAD_LINES:
                    break
                try:
                    r = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if r.get("type") != "user" or r.get("isCompactSummary"):
                    continue
                text = _text_of((r.get("message") or {}).get("content")).strip()
                if not text or text.startswith(_NOISE_PREFIX):
                    # 有雜訊但仍拿得到 cwd，先記著，繼續往下找像樣的開場白
                    continue
                return {"text": text, "rec": r}
    except OSError:
        return None
    return None


def _any_record_with_cwd(jf: Path) -> dict[str, Any] | None:
    """退而求其次：只要拿到帶 cwd 的記錄就好，標題留空。"""
    try:
        with jf.open(encoding="utf-8", errors="replace") as f:
            for i, line in enumerate(f):
                if i >= _HEAD_LINES:
                    break
                try:
                    r = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if r.get("cwd"):
                    return r
    except OSError:
        return None
    return None


def scan_sessions(limit: int = 60, q: str = "") -> list[dict]:
    """列出電腦上的 session，最近用過的排前面。

    [limit] 是回傳筆數上限；[q] 有值時對標題與工作目錄做不分大小寫的子字串比對。

    butler 自己已經接管的 session 會被濾掉——那些在對話清單裡本來就看得到，
    重複列出只會讓人分不清該點哪一個。
    """
    root = _projects_dir()
    if not root.is_dir():
        return []

    taken = {
        rec.get("session_id") for rec in _load_map().values() if rec.get("session_id")
    }

    # 先只看檔案屬性排序，不開檔——467 個檔全部解析要幾秒，而使用者只看得到前幾十筆
    cands: list[tuple[float, Path]] = []
    for jf in root.glob("*/*.jsonl"):
        if not jf.is_file() or not _UUID_RE.match(jf.stem) or jf.stem in taken:
            continue
        try:
            stat = jf.stat()
        except OSError:
            continue
        if stat.st_size == 0:
            continue
        cands.append((stat.st_mtime, jf))
    cands.sort(key=lambda e: e[0], reverse=True)

    # 有搜尋字串時要多翻幾頁才湊得滿，沒有的話多讀就是白工
    budget = len(cands) if q else min(len(cands), limit * 2)
    needle = q.strip().lower()

    out: list[dict] = []
    for mtime, jf in cands[:budget]:
        hit = _first_user_record(jf)
        rec = hit["rec"] if hit else _any_record_with_cwd(jf)
        if rec is None:
            continue
        cwd = str(rec.get("cwd") or "")
        title = " ".join((hit["text"] if hit else "").split())[:_TITLE_MAX]
        if needle and needle not in title.lower() and needle not in cwd.lower():
            continue
        out.append({
            "session_id": jf.stem,
            "cwd": cwd,
            "title": title or "（沒有開場白）",
            "mtime": mtime,
            "branch": str(rec.get("gitBranch") or ""),
            # subagent 產生的 session 也在同一棵樹裡，標出來讓使用者自己判斷
            "sidechain": bool(rec.get("isSidechain")),
        })
        if len(out) >= limit:
            break
    return out


def session_cwd(session_id: str) -> str:
    """單獨問一個 session 的工作目錄。接管時要用它把對話的 cwd 設對。"""
    for jf in _projects_dir().glob(f"*/{session_id}.jsonl"):
        rec = _any_record_with_cwd(jf)
        return str((rec or {}).get("cwd") or "")
    return ""
