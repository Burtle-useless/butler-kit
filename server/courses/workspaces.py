"""課表上的每一門課都有工作區（`<課程目錄>/<資料夾>/`）。

資料夾若要人手動建，學期中從 App 或助理加進課表的課就會一直沒有：課程清單只列
資料夾，它們不在清單上，課表格子也點不進去；靠資料夾名去找課的記錄流程也對不到它們。

所以反過來：**課表是真相**（agenda.json），這裡保證課表上每一門課都有資料夾，
並把對到的資料夾名寫回那堂課的 `folder` 欄位——要找某堂課的資料夾直接讀課表，
不必再各自維護一份對照表。

- 對照順序：課表那筆已經寫好的 `folder`（資料夾還在就用它）→ 課名跟現有資料夾比
  （[names_match]）→ 都沒有才建新的，名字由課名推（[folder_name]）。
- 同名的幾堂（微積分一週兩次）共用一個資料夾。
- **只建不刪**：課從課表拿掉，資料夾留著（裡面是他的紀錄）。整學期收起來走 `semester`。
- 課程根目錄不存在就什麼都不做：沒在用課程功能的機器不該被長出一堆資料夾。
"""
from __future__ import annotations

import logging
import re
from pathlib import Path
from typing import Any

import config
from agenda import store

log = logging.getLogger(__name__)

WEEK = "一二三四五六日"

# ── 名稱 ────────────────────────────────────────────────────────────────────
_NORM_DROP = re.compile(r"[\s（）()【】\[\]・．.,，、:：;；\-—–_]")


def norm(s: str) -> str:
    """去空白、括號、標點與「與／的／之」：「普通化學與實驗（一）」→「普通化學實驗一」。"""
    s = _NORM_DROP.sub("", s)
    return s.replace("與", "").replace("的", "").replace("之", "").lower()


def names_match(folder: str, agenda_name: str) -> bool:
    """資料夾名對得到課表課名嗎。

    相等、或短的那個被長的包含——「音樂欣賞」對「通識：音樂欣賞與創作」
    是包含，對「通識：生活中的天文學」不是。至少兩個字才算，
    免得「一」這種對到一整排。
    """
    a, b = norm(folder), norm(agenda_name)
    if not a or not b or min(len(a), len(b)) < 2:
        return False
    return a == b or a in b or b in a


# Windows 檔名不能有的字元
_BAD_CHARS = re.compile(r'[\\/:*?"<>|]')
_BRACKETS = re.compile(r"[（(]\s*([^）)]*?)\s*[）)]")


def folder_name(course_name: str) -> str:
    """課名 → 新建資料夾用的名字。

    「通識：生活中的天文學」→「生活中的天文學」（冒號前是通識分類，不是課名）；
    「體育（二）」→「體育二」（括號拿掉、內容留著——跟人手取的「微積分一」同一種寫法）。
    推不出東西就回空字串，由呼叫端退回課程 id。
    """
    s = course_name.strip()
    for sep in ("：", ":"):
        head, found, tail = s.partition(sep)
        if found and tail.strip():
            s = tail.strip()
            break
    s = _BRACKETS.sub(r"\1", s)
    s = _BAD_CHARS.sub("", s)
    s = re.sub(r"\s+", "", s)
    # 開頭是 _ 或 . 的資料夾會被當成元資料略過（見 course_dirs），結尾的點與空白 Windows 不收
    return s.lstrip("_.").rstrip(". ")[:40]


# ── 檔案系統 ────────────────────────────────────────────────────────────────
def root() -> Path:
    return Path(config.COURSES_DIR)


def course_dirs() -> list[Path]:
    """有資料的課：底下的資料夾。`_`／`.` 開頭的是元資料（含封存），散在根目錄的檔案不是課。"""
    r = root()
    if not r.is_dir():
        return []
    return sorted(
        p for p in r.iterdir() if p.is_dir() and not p.name.startswith(("_", "."))
    )


def slot_label(rows: list[dict[str, Any]], periods: list[dict[str, Any]]) -> str:
    """「週二 第2節（09:10-10:00）、週五 第5-6節（13:10-15:00）」。"""
    span = {p.get("no"): p for p in periods}
    parts: list[str] = []
    for c in sorted(rows, key=lambda r: (r.get("day", 0), r.get("from_period", 0))):
        lo, hi = c.get("from_period"), c.get("to_period")
        nth = f"第{lo}節" if lo == hi else f"第{lo}-{hi}節"
        t0 = span.get(lo, {}).get("start", "")
        t1 = span.get(hi, {}).get("end", "")
        when = f"（{t0}-{t1}）" if t0 and t1 else ""
        day = c.get("day")
        week = f"週{WEEK[day]}" if isinstance(day, int) and 0 <= day <= 6 else ""
        parts.append(f"{week} {nth}{when}".strip())
    return "、".join(parts)


def _cell(s: Any) -> str:
    """表格的一格：直線會把欄位切開，換行會把列切斷。"""
    return str(s or "").replace("|", "／").replace("\n", " ").strip()


def index_template(name: str, rows: list[dict[str, Any]], periods: list[dict[str, Any]]) -> str:
    """新課的 index.md。跟手建的長得一樣——課程對話與 courses_api 都認這個格式。"""
    first = rows[0]
    return (
        f"# {name}\n\n"
        "| 項目 | 內容 |\n|---|---|\n"
        f"| 老師 | {_cell(first.get('teacher'))} |\n"
        f"| 教室 | {_cell(first.get('room'))} |\n"
        f"| 時段 | {_cell(slot_label(rows, periods))} |\n"
        f"| 備註 | {_cell(first.get('note'))} |\n\n"
        "> 課表以伺服器的 agenda.json 為準，這裡是副本，改課表去改那邊。\n"
        "> 這個資料夾是加進課表時自動建的。\n\n"
        "---\n\n"
        "## 理解進度\n\n"
        "程度欄只填三種：`懂` / `半懂` / `不會`。\n"
        "依據欄要寫清楚是怎麼判的——`提問推斷` 或 `答對檢核`，\n"
        "沒有實際檢核過就不准寫成 `答對檢核`。\n\n"
        "| 主題 | 程度 | 依據 | 卡在哪 | 最後更新 |\n"
        "|---|---|---|---|---|\n\n"
        "---\n\n"
        "## 評分方式\n\n"
        "<!-- 老師講了就填：期中/期末/作業/出席各佔幾% -->\n\n"
        "## 待辦\n"
    )


def log_template(name: str) -> str:
    return (
        f"# {name} 提問紀錄\n\n"
        "上課當下的提問逐筆追加在這裡，一行一筆，不要改寫舊的。\n"
        "下課後整理進 index.md 的理解進度表。\n\n"
        "| 時間 | 主題 | 他問了什麼 | 我的判讀 |\n"
        "|---|---|---|---|\n"
    )


def _create(folder: Path, name: str, rows: list[dict[str, Any]],
            periods: list[dict[str, Any]]) -> None:
    """建一門課的資料夾。已經有的檔一律不蓋——只補缺的。"""
    folder.mkdir(parents=False, exist_ok=True)
    for sub in ("raw", "notes"):
        (folder / sub).mkdir(exist_ok=True)
    for fname, text in (("index.md", index_template(name, rows, periods)),
                        ("log.md", log_template(name))):
        p = folder / fname
        if not p.exists():
            p.write_text(text, encoding="utf-8", newline="\n")


def _pick_existing(name: str, rows: list[dict[str, Any]], dirs: set[str]) -> str | None:
    """這門課有沒有現成的資料夾。"""
    for r in rows:
        f = str(r.get("folder") or "")
        if f and f in dirs:
            return f
    exact = [d for d in dirs if norm(d) == norm(name)]
    if exact:
        return exact[0]
    loose = sorted(d for d in dirs if names_match(d, name))
    # 包含比對對到兩個以上就是分不出來（「化學」同時被「普通化學」「化學實驗」包含），
    # 寧可另開一個也不要把兩門課的紀錄寫進同一個資料夾
    return loose[0] if len(loose) == 1 else None


def sync() -> dict[str, Any]:
    """課表上每一門課都要有資料夾；對到的名字寫回課表。回傳 {created, stamped}。

    **永不拋例外**：這支掛在列課程與改課表的路上，建資料夾失敗（磁碟、權限）不能
    讓那些操作跟著失敗。失敗的那門記 log，下次再試。
    """
    out: dict[str, Any] = {"created": [], "stamped": 0}
    r = root()
    if not r.is_dir():
        return out
    try:
        data = store.list_all()
    except Exception:  # noqa: BLE001 — 讀不到課表就是這次不同步，別炸呼叫端
        log.exception("讀課表失敗，這次不同步工作區")
        return out
    periods = data.get(store.PERIODS_KEY) or []
    groups: dict[str, list[dict[str, Any]]] = {}
    for c in data.get("courses") or []:
        name = str(c.get("name") or "").strip()
        if name and c.get("id"):
            groups.setdefault(name, []).append(c)

    dirs = {d.name for d in course_dirs()}
    folders: dict[str, str] = {}
    for name, rows in groups.items():
        folder = _pick_existing(name, rows, dirs)
        if folder is None:
            folder = folder_name(name) or str(rows[0]["id"])
            if folder in dirs:
                # 推出來的名字已經是別門課的資料夾（上面分不出來才會走到這）
                folder = f"{folder}-{rows[0]['id']}"
            try:
                _create(r / folder, name, rows, periods)
            except OSError:
                log.exception("建「%s」的工作區失敗（%s）", name, folder)
                continue
            dirs.add(folder)
            out["created"].append(folder)
            log.info("課表上的「%s」沒有工作區，建了 %s", name, folder)
        for row in rows:
            folders[str(row["id"])] = folder
    try:
        out["stamped"] = store.set_course_folders(folders)
    except Exception:  # noqa: BLE001
        log.exception("把資料夾名寫回課表失敗")
    return out
