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
來源不同。列課之前先跑 `courses.workspaces.sync`：課表上每門課都保證有
資料夾，對到的名字寫回課表那筆的 `folder`，這裡就照那個欄位對；舊資料沒有那個欄位
才退回 `names_match`（正規化後互相包含）。

換學期（`/semester`）：這學期的資料夾與課表收進 `_封存/<學期>/`，課程對話一起收掉，
見 `courses.semester`。
"""
from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Body, Depends, HTTPException
from fastapi.responses import FileResponse

from agenda.store import AGENDA_FILE
from courses import semester, workspaces
from courses.workspaces import course_dirs, names_match, norm, root  # noqa: F401 — 測試與舊呼叫端從這裡拿
from engine import bg_notify, client_pool
from engine import state as state_mod
from engine.profiles import COURSE_PREFIX
from util import read_text_with_retry

from . import agenda_api
from .auth import require_token

log = logging.getLogger(__name__)

router = APIRouter(prefix="/v1/courses")

LEVELS = ("懂", "半懂", "不會")
# 教材夾：App 的檔案區列這幾個。index.md／log.md 有自己的區，不在這裡重列
FILE_DIRS = ("raw", "notes", "對話")
# 一門課列到這麼多檔就該整理了，不是這裡的問題；擋住是為了別把一整個 raw/ 的
# 逐字稿目錄一次塞進一個 JSON
FILE_MAX = 400


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
def course_dir(name: str) -> Path:
    """[name] 一定要是 `course_dirs()` 真的列得出來的其中一門課，否則 404。

    這組端點會回檔案內容，讓它走到 courses 之外就是任意讀檔。黑名單的寫法
    （擋路徑分隔、`..`、`_`／`.` 開頭）會漏掉**磁碟代號**：Windows 上
    `root() / "F:"` 直接變成 F 槽，`/v1/courses/F:/file?path=…` 讀得到整顆 F 槽
    黑名單總會漏一種寫法，所以用白名單。
    """
    for d in course_dirs():
        if d.name == name:
            return d
    raise HTTPException(status_code=404, detail="沒有這門課")


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
    """這門課在課表上是哪幾格（一門課一週可能上兩次）。

    課表那筆寫了 `folder`（workspaces.sync 對好的）就只認它；沒寫的舊資料才用課名比。
    """
    return [
        str(c.get("id")) for c in agenda
        if c.get("id") and (
            c.get("folder") == folder if c.get("folder")
            else names_match(folder, str(c.get("name", "")))
        )
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
    # 課表上有、資料夾沒有的課先補上——課是從 App、助理、還是手改課表加的都一樣
    workspaces.sync()
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


@router.get("/semester")
async def get_semester(_: str = Depends(require_token)) -> dict[str, Any]:
    """「換學期」面板：現在是哪學期、換過去叫什麼、會搬哪幾個資料夾。

    **一定要排在 `/{name}` 前面**，不然 "semester" 會被當成課名、回 404。
    """
    return await asyncio.to_thread(semester.status)


@router.post("/semester")
async def post_semester(
    payload: dict = Body(...), _: str = Depends(require_token),
) -> dict[str, Any]:
    """換學期。`{"next": "115-2"}`。

    順序有講究：先確定沒有任何一條課程對話在跑（搬走它正在寫的資料夾會讓那一輪
    寫進一個不存在的路徑），再丟掉那些對話的連線，才搬資料夾；搬完才把對話忘掉——
    搬失敗的話對話還在，下次傳訊息會自己重連。
    """
    nxt = str(payload.get("next") or "").strip()
    records = state_mod.records_with_prefix(COURSE_PREFIX)
    busy = [cid.removeprefix(COURSE_PREFIX) for cid in records
            if core.worker.is_running(cid) or bg_notify.active(cid)]
    if busy:
        raise HTTPException(
            status_code=409,
            detail=f"這幾門課的對話還在跑：{'、'.join(busy)}。等它們講完再換")
    for cid in records:
        await client_pool.drop(cid)
    try:
        result = await asyncio.to_thread(semester.switch, nxt, records)
    except semester.SemesterError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    for cid in records:
        await core.worker.remove(cid)
        core._frontends.pop(cid, None)
        state_mod.delete_conversation(cid)
    result["conversations"] = len(records)
    await agenda_api.abroadcast("courses")
    return result


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


# 換學期要收掉課程對話的 worker 與畫面連線，那兩樣在 app.py 的模組層。
# 放尾端 import 的理由同 conversations_api：app.py 在模組層 include 這個 router。
from . import app as core  # noqa: E402
