"""課程資料的 HTTP 路由：把課程目錄底下每門課的資料夾直接交給 App。

課程頁是「一門課一條對話」的工作區：進去看得到提問紀錄、課程規範、理解程度、
教材檔案。這些東西的真相全是資料夾裡的 .md 與 raw/、notes/ 裡的檔案——由誰維護
不管（人手寫、另一個 session 整理都行），這裡只負責讀。

每門課一個資料夾，長這樣：

    <COURSES_DIR>/<課名>/
        index.md   課程資訊與理解進度表（表格）
        log.md     提問流水帳（表格）
        raw/       講義、投影片、拍到的板書
        notes/     整理過的筆記
        對話/      對話原文

**這裡直接讀 md**，不經過另一支腳本匯出 JSON：多一層產物就多一份會走鐘的東西，
而 App 要的只是「把檔案裡的字拿出來排好」。md 表格解析失敗的代價是**那一段空白、
原文照樣給**——App 有 Markdown 元件，原文永遠看得到。

課表對照：資料夾名（「微積分一」）與 agenda.json 的課名（「微積分（一）」）
來源不同，靠 `norm` 正規化後互相包含來對。對不到就只是課表格子點不進工作區，
資料本身照列。
"""
from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse

import config
from agenda.store import AGENDA_FILE
from engine.profiles import COURSE_PREFIX
from util import read_text_with_retry

from .auth import require_token

log = logging.getLogger(__name__)

router = APIRouter(prefix="/v1/courses")

LEVELS = ("懂", "半懂", "不會")
# 教材夾：App 的檔案區列這幾個。index.md／log.md 有自己的區，不在這裡重列
FILE_DIRS = ("raw", "notes", "對話")
# 一門課列到這麼多檔就該整理了，不是這裡的問題；擋住是為了別把一整個 raw/ 的
# 逐字稿目錄一次塞進一個 JSON
FILE_MAX = 400


# ── 名稱對照 ────────────────────────────────────────────────────────────────
_NORM_DROP = re.compile(r"[\s（）()【】\[\]・．.,，、:：;；\-—–_]")


def norm(s: str) -> str:
    """去空白、括號、標點與「與／的／之」：「光電與材料實驗（一）」→「光電材料實驗一」。"""
    s = _NORM_DROP.sub("", s)
    return s.replace("與", "").replace("的", "").replace("之", "").lower()


def names_match(folder: str, agenda_name: str) -> bool:
    """資料夾名對得到課表課名嗎。

    相等、或短的那個被長的包含——「自我健康促進」對「健康促進：自我健康促進與評估」
    是包含，對「健康促進：生活中的腦神經科學」不是。至少兩個字才算，
    免得「一」這種對到一整排。
    """
    a, b = norm(folder), norm(agenda_name)
    if not a or not b or min(len(a), len(b)) < 2:
        return False
    return a == b or a in b or b in a


# ── md 解析 ─────────────────────────────────────────────────────────────────
_SEP_ROW = re.compile(r"^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$")
_H2 = re.compile(r"^##\s+(.+?)\s*$")


def table_rows(block: str) -> list[list[str]]:
    """一段 md 裡所有表格列（含表頭），分隔列跳過。只認 `|` 開頭的行。"""
    rows: list[list[str]] = []
    for line in block.splitlines():
        t = line.strip()
        if not t.startswith("|") or _SEP_ROW.match(t):
            continue
        rows.append([c.strip() for c in t.strip("|").split("|")])
    return rows


def split_sections(md: str) -> tuple[str, dict[str, str]]:
    """依 `## 標題` 切段。回 (第一個標題之前的前言, {標題: 內文})。"""
    sections: dict[str, str] = {}
    pre: list[str] = []
    cur: str | None = None
    buf: list[str] = []
    for line in md.splitlines():
        m = _H2.match(line)
        if m:
            if cur is None:
                pre = buf
            else:
                sections[cur] = "\n".join(buf).strip()
            cur, buf = m.group(1), []
        else:
            buf.append(line)
    if cur is None:
        pre = buf
    else:
        sections[cur] = "\n".join(buf).strip()
    return "\n".join(pre).strip(), sections


def parse_index(md: str) -> dict[str, Any]:
    """index.md → 標題、課程資訊、理解進度列、其餘段落（原文）。

    理解進度表五欄：主題／程度／依據／卡在哪／最後更新。程度不是三級之一的列
    不進統計——那多半是寫錯，算進去會讓計數對不上畫面。
    """
    pre, sections = split_sections(md)
    title = ""
    for line in pre.splitlines():
        if line.startswith("# "):
            title = line[2:].strip()
            break
    info: dict[str, str] = {}
    for row in table_rows(pre):
        if len(row) >= 2 and row[0] != "項目":
            info[row[0]] = row[1]
    progress: list[dict[str, str]] = []
    for row in table_rows(sections.get("理解進度", "")):
        if len(row) < 2 or row[0] == "主題":
            continue
        row = row + [""] * (5 - len(row))
        if row[1] not in LEVELS:
            continue
        progress.append({
            "topic": row[0], "level": row[1], "basis": row[2],
            "stuck": row[3], "updated": row[4],
        })
    counts = {lv: sum(1 for p in progress if p["level"] == lv) for lv in LEVELS}
    others = [{"title": k, "md": v} for k, v in sections.items() if k != "理解進度"]
    return {
        "title": title, "info": info, "progress": progress,
        "counts": counts, "sections": others,
    }


def parse_log(md: str) -> list[dict[str, str]]:
    """log.md → 一筆一列：時間／主題／問了什麼／判讀。"""
    out: list[dict[str, str]] = []
    for row in table_rows(md):
        if len(row) < 2 or row[0] == "時間":
            continue
        row = row + [""] * (4 - len(row))
        out.append({"time": row[0], "topic": row[1], "asked": row[2], "verdict": row[3]})
    return out


# ── 檔案系統 ────────────────────────────────────────────────────────────────
def root() -> Path:
    return Path(config.COURSES_DIR)


def course_dirs() -> list[Path]:
    """有資料的課：底下的資料夾。`_`／`.` 開頭的是元資料，散在根目錄的檔案不是課。"""
    r = root()
    if not r.is_dir():
        return []
    return sorted(
        p for p in r.iterdir() if p.is_dir() and not p.name.startswith(("_", "."))
    )


def course_dir(name: str) -> Path:
    """[name] 一定要是一個乾淨的資料夾名。

    帶路徑分隔、`..`、或 `_`／`.` 開頭一律 404——這組端點會回檔案內容，
    讓它走到 courses 之外就是任意讀檔。
    """
    bad = (
        not name or name != name.strip()
        or any(ch in name for ch in "/\\")
        or name in (".", "..")
        or name.startswith(("_", "."))
    )
    if bad:
        raise HTTPException(status_code=404, detail="沒有這門課")
    d = root() / name
    if not d.is_dir():
        raise HTTPException(status_code=404, detail="沒有這門課")
    return d


def _read(p: Path) -> str:
    try:
        return read_text_with_retry(p) if p.is_file() else ""
    except OSError as e:
        log.warning("讀不到 %s：%s", p, e)
        return ""


def _agenda_courses() -> list[dict[str, Any]]:
    """agenda.json 的 courses。壞了就當沒有——這裡只是拿來對照，不是它的主人。"""
    try:
        data = json.loads(read_text_with_retry(AGENDA_FILE))
    except (OSError, ValueError):
        return []
    rows = data.get("courses") if isinstance(data, dict) else None
    return [r for r in (rows or []) if isinstance(r, dict)]


def agenda_ids_for(folder: str, agenda: list[dict[str, Any]]) -> list[str]:
    """這門課在課表上是哪幾格（一門課一週可能上兩次）。"""
    return [
        str(c.get("id")) for c in agenda
        if c.get("id") and names_match(folder, str(c.get("name", "")))
    ]


def _stamp(ts: float) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(ts))


def summary_of(d: Path, agenda: list[dict[str, Any]]) -> dict[str, Any]:
    """清單用的一筆：課名、老師教室、三級計數、提問數、課表格子。"""
    idx = parse_index(_read(d / "index.md"))
    logs = parse_log(_read(d / "log.md"))
    return {
        "name": d.name,
        "conv_id": COURSE_PREFIX + d.name,
        "title": idx["title"] or d.name,
        "teacher": idx["info"].get("老師", ""),
        "room": idx["info"].get("教室", ""),
        "slot": idx["info"].get("時段", ""),
        "note": idx["info"].get("備註", ""),
        "counts": idx["counts"],
        "log_count": len(logs),
        "last_log": logs[-1]["time"] if logs else "",
        "agenda_ids": agenda_ids_for(d.name, agenda),
    }


def list_courses() -> dict[str, Any]:
    agenda = _agenda_courses()
    return {
        "root": root().as_posix(),
        "courses": [summary_of(d, agenda) for d in course_dirs()],
    }


def list_files(d: Path) -> list[dict[str, Any]]:
    """raw／notes／對話 底下的檔案，相對路徑。隱藏檔不列。"""
    out: list[dict[str, Any]] = []
    for sub in FILE_DIRS:
        base = d / sub
        if not base.is_dir():
            continue
        for p in sorted(base.rglob("*")):
            if not p.is_file() or p.name.startswith("."):
                continue
            st = p.stat()
            out.append({
                "path": p.relative_to(d).as_posix(), "name": p.name, "dir": sub,
                "size": st.st_size, "mtime": _stamp(st.st_mtime),
            })
            if len(out) >= FILE_MAX:
                return out
    return out


def course_detail(name: str) -> dict[str, Any]:
    d = course_dir(name)
    index_md = _read(d / "index.md")
    log_md = _read(d / "log.md")
    idx = parse_index(index_md)
    body = summary_of(d, _agenda_courses())
    body.update({
        "info": idx["info"],
        "progress": idx["progress"],
        "sections": idx["sections"],
        "index_md": index_md,
        "log": parse_log(log_md),
        "log_md": log_md,
        "files": list_files(d),
    })
    return body


# ── 路由 ────────────────────────────────────────────────────────────────────
@router.get("")
async def get_courses(_: str = Depends(require_token)) -> dict[str, Any]:
    return await asyncio.to_thread(list_courses)


@router.get("/{name}")
async def get_course(name: str, _: str = Depends(require_token)) -> dict[str, Any]:
    return await asyncio.to_thread(course_detail, name)


@router.get("/{name}/files")
async def get_files(name: str, _: str = Depends(require_token)) -> dict[str, Any]:
    d = course_dir(name)
    return {"name": name, "files": await asyncio.to_thread(list_files, d)}


@router.get("/{name}/file")
async def get_file(name: str, path: str, _: str = Depends(require_token)) -> FileResponse:
    """把一份教材原檔交給手機。路徑一定得落在那門課的資料夾裡。"""
    d = course_dir(name)
    target = (d / path).resolve()
    try:
        target.relative_to(d.resolve())
    except ValueError:
        raise HTTPException(status_code=404, detail="沒有這個檔案") from None
    if not target.is_file() or target.name.startswith("."):
        raise HTTPException(status_code=404, detail="沒有這個檔案")
    return FileResponse(target, filename=target.name)
