"""換學期：把這學期收起來。

沒有這一步的話，課表要一門一門刪，課程資料夾跟課程對話（`course:<資料夾名>`）
會跟下學期同名的課混在一起：下學期的「統計學」會接著這學期那條對話講、
把紀錄寫進這學期的 log.md。

換學期做三件事，**全部可以退回**（只搬不刪）：
1. 這學期的每個課程資料夾搬進 `<課程目錄>/_封存/<學期>/`
   （`_` 開頭的資料夾不會被當成課，課程清單上就看不到了）。
2. 課表另存一份到同一處（`課表.json`），然後清空；節次表留著。
3. 課程對話收起來：session 對應寫進同一處（`課程對話.json`），伺服器這邊忘掉它們
   ——那一步在 transport（要動 worker 與連線），這裡只負責寫檔。

新學期的課加進課表時，`workspaces.sync` 會自動開新的資料夾。

**外部程式開著某個課程資料夾時搬不動**（Windows 不讓搬別人正在用的目錄，例如把它當
工作目錄的另一支程式）。那種情況整批退回、訊息講明是哪個資料夾，關掉那支程式再換。
"""
from __future__ import annotations

import json
import logging
import re
from datetime import date
from pathlib import Path
from typing import Any

from agenda import store

from . import workspaces

log = logging.getLogger(__name__)

ARCHIVE = "_封存"
# 115-1、115-2；暑修少見但存在，給 3
_LABEL = re.compile(r"^\d{2,3}-[1-3]$")


class SemesterError(ValueError):
    """換不了。訊息直接給使用者看。"""


def guess_label(today: date) -> str:
    """照日期推學期代號（民國年＋上下學期）。8 月起算新學年的上學期，2～7 月是下學期。

    2026-09 → 115-1；2027-01 → 115-1（上學期的期末）；2027-03 → 115-2；2027-08 → 116-1。
    """
    roc = today.year - 1911
    if today.month >= 8:
        return f"{roc}-1"
    if today.month == 1:
        return f"{roc - 1}-1"
    return f"{roc - 1}-2"


def next_label(label: str) -> str:
    """下一個學期：115-1 → 115-2 → 116-1。認不得的格式照日期推。"""
    m = re.match(r"^(\d{2,3})-([1-3])$", label)
    if not m:
        return guess_label(date.today())
    year, term = int(m.group(1)), int(m.group(2))
    return f"{year}-2" if term == 1 else f"{year + 1}-1"


def current_label() -> str:
    """現在這學期。課表裡記了就用記的，沒記過（換學期功能出現之前）照日期推。"""
    return store.get_semester() or guess_label(date.today())


def archive_dir(label: str) -> Path:
    return workspaces.root() / ARCHIVE / label


def status() -> dict[str, Any]:
    """App 的「換學期」面板要顯示的東西：現在是哪學期、換過去會叫什麼、會搬哪些。"""
    cur = current_label()
    return {
        "current": cur,
        "next": next_label(cur),
        "folders": [d.name for d in workspaces.course_dirs()],
        "archived": sorted(
            p.name for p in (workspaces.root() / ARCHIVE).glob("*") if p.is_dir()
        ) if (workspaces.root() / ARCHIVE).is_dir() else [],
    }


def switch(next_semester: str, conv_records: dict[str, Any]) -> dict[str, Any]:
    """搬資料夾、存課表與對話對應、清空課表。回傳 {archived, next, moved, dest}。

    [conv_records] 是這學期課程對話的 session 對應（conv_id → 紀錄），由呼叫端從
    engine.state 抄出來；這裡只負責把它存進封存夾，之後要找回哪條對話的原文有地方查。

    任何一步失敗就把已經搬走的搬回去、課表放回去，然後拋 [SemesterError]——
    不留下「一半在封存、一半還在」的狀態。
    """
    nxt = next_semester.strip()
    cur = current_label()
    if not _LABEL.match(nxt):
        raise SemesterError(f"學期代號要寫成「115-2」這種格式，收到「{next_semester}」")
    if nxt == cur:
        raise SemesterError(f"現在就是 {cur}，不用換")

    root = workspaces.root()
    if not root.is_dir():
        raise SemesterError(f"找不到課程資料夾：{root}")
    dest = archive_dir(cur)
    folders = workspaces.course_dirs()
    clash = [d.name for d in folders if (dest / d.name).exists()]
    if clash:
        raise SemesterError(
            f"封存夾 {dest} 裡已經有同名的：{'、'.join(clash)}。先看一下那裡是什麼再換")
    dest.mkdir(parents=True, exist_ok=True)

    moved: list[tuple[Path, Path]] = []
    try:
        for d in folders:
            target = dest / d.name
            try:
                d.rename(target)
            except OSError as e:
                raise SemesterError(
                    f"「{d.name}」搬不動（{e.strerror or e}）。多半是有程式正開著它"
                    "（例如把它當工作目錄），先關掉再換") from e
            moved.append((d, target))
        snapshot = store.clear_courses(nxt)
    except Exception:
        for src, target in reversed(moved):
            try:
                target.rename(src)
            except OSError:
                log.exception("換學期失敗後搬回 %s 也失敗，要手動從 %s 搬回去", src.name, target)
        raise

    try:
        (dest / "課表.json").write_text(
            json.dumps(snapshot, ensure_ascii=False, indent=2), encoding="utf-8")
        (dest / "課程對話.json").write_text(
            json.dumps(conv_records, ensure_ascii=False, indent=2), encoding="utf-8")
    except OSError:
        # 資料夾已經搬好、課表已經清了：這兩份是備查用的副本，寫不進去不值得整個退回，
        # 但要留紀錄——課表內容還在 log 裡查得到
        log.exception("換學期的課表／對話對應備份寫不進 %s；清掉之前的課表：%s",
                      dest, json.dumps(snapshot, ensure_ascii=False))
    log.info("換學期 %s → %s：%d 個資料夾收進 %s", cur, nxt, len(moved), dest)
    return {
        "archived": cur,
        "next": nxt,
        "moved": [src.name for src, _ in moved],
        "dest": dest.as_posix(),
    }
