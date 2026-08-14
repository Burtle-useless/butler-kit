"""本機用量：這台電腦上**所有** Claude Code session 的 token 明細。

跟另外兩支的分工（三件事，不要混）：
  usage.py      ── 走助理這條路的回合，butler 自己記的帳
  plan_usage.py ── 訂閱方案還剩多少額度，全帳號，含網頁版與手機 App
  local_usage.py ── 這台電腦上所有 CC 的 token，含 cc-bot 與終端機直接開的

plan_usage 給的是「總量剩多少」，回答不了「被誰吃掉的」。這支補的就是組成：
資料來源是 ~/.claude/projects 下的逐字稿，每則回應都帶著 usage 欄位。

拿不到金額。成本只在 SDK 的 ResultMessage 裡，那則不會寫進逐字稿。
訂閱制下金額本來也沒有意義（見 plan_usage 的說明），token 才是會撞上限的東西。
"""
from __future__ import annotations

import json
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import config

PROJECTS_DIR: Path = Path.home() / ".claude" / "projects"
STATE_FILE: Path = config.DATA_DIR / "local_usage.json"

# 留多久。跟 usage.py 對齊，手機上兩者要畫在同一張圖裡。
KEEP_DAYS = 92

# 掃描間隔。逐字稿只在有人用 CC 時才長大，一分鐘一次已經比任何人的閱讀頻率快。
SCAN_EVERY_SEC = 60

# 統計口徑版本。改了切法（新增維度、換去重規則）就加一，舊的累計值會被丟掉重掃。
VERSION = 2

_LOCK = threading.Lock()
_scanning = False       # 掃描是否正在進行，避免同時跑兩份


def _empty() -> dict[str, Any]:
    return {"v": VERSION, "days": {}, "files": {}, "scanned_at": "", "elapsed": 0.0}


def _load() -> dict[str, Any]:
    if not STATE_FILE.exists():
        return _empty()
    try:
        data = json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return _empty()          # 壞檔就重掃，這份資料隨時可以從逐字稿重建
    if not isinstance(data, dict) or not isinstance(data.get("days"), dict):
        return _empty()
    # 統計口徑改過就整份重來。舊資料留著會跟新口徑疊加，圖表上是憑空多出來的量，
    # 而且看不出哪裡錯——重掃只要三秒，不值得為它寫一套遷移。
    if data.get("v") != VERSION:
        return _empty()
    data.setdefault("files", {})
    return data


def _save(data: dict[str, Any]) -> None:
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = STATE_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    tmp.replace(STATE_FILE)


def _bucket() -> dict[str, Any]:
    return {"in": 0, "out": 0, "cache_read": 0, "cache_write": 0, "turns": 0}


def _add(dst: dict[str, Any], src: dict[str, int]) -> None:
    for k in ("in", "out", "cache_read", "cache_write", "turns"):
        dst[k] = int(dst.get(k, 0)) + int(src.get(k, 0))


def _local_date(ts: str) -> str:
    """逐字稿的 timestamp 是 UTC，要換算成本地日期才對得上 usage.py 的記帳。

    差八小時的意思是：半夜十二點前後的用量會被記到前一天，圖表上兩條線對不齊。
    """
    try:
        dt = datetime.fromisoformat(ts.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.astimezone().date().isoformat()
    except (ValueError, AttributeError):
        return ""


def _scan_file(path: Path, start: int) -> tuple[list[tuple[str, str, dict]], int]:
    """從 byte offset 讀到檔尾，回傳 (筆數清單, 新的 offset)。

    每筆是 (日期, 模型, 用量)。專案與主／子代理由呼叫端從路徑決定——
    那兩個對整份檔案是固定的，逐行重算沒有意義。
    """
    rows: list[tuple[str, str, dict]] = []
    last_req = ""
    try:
        with path.open("r", encoding="utf-8", errors="replace") as f:
            f.seek(start)
            for line in f:
                if not line.endswith("\n"):
                    break            # 最後一行還沒寫完，留到下次
                try:
                    d = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if d.get("type") != "assistant":
                    continue
                msg = d.get("message") or {}
                u = msg.get("usage") or {}
                if not u:
                    continue
                # 同一個 API request 的多個 assistant 行帶著**完全相同**的 usage
                # （一行文字一行工具呼叫是常態）。不去重的話用量會憑空翻倍。
                req = str(d.get("requestId") or msg.get("id") or "")
                if req and req == last_req:
                    continue
                last_req = req
                day = _local_date(str(d.get("timestamp") or ""))
                if not day:
                    continue
                rows.append((day, str(msg.get("model") or "unknown"), {
                    "in": int(u.get("input_tokens") or 0),
                    "out": int(u.get("output_tokens") or 0),
                    "cache_read": int(u.get("cache_read_input_tokens") or 0),
                    "cache_write": int(u.get("cache_creation_input_tokens") or 0),
                    "turns": 1,
                }))
            return rows, f.tell()
    except OSError:
        return [], start


def scan() -> dict[str, Any]:
    """增量掃描所有逐字稿。回傳掃完的狀態。

    只讀每個檔案新長出來的那一段。唯一會整份重讀的情況是檔案**變小**——
    CC 的 auto-compact 會就地改寫逐字稿，舊內容被摘要取代，這時沿用舊的
    offset 會從一個對不上的位置開始切，讀出來的是半行 JSON。
    """
    global _scanning
    with _LOCK:
        if _scanning:
            return _load()
        _scanning = True
    started = time.time()
    try:
        data = _load()
        days: dict[str, Any] = data["days"]
        files: dict[str, Any] = data["files"]
        # 遞迴：subagent 與 workflow 的逐字稿埋在 <session>/subagents/ 底下，
        # 只掃一層會漏掉 320 個檔——那些是真的花掉的 token，不是附屬紀錄。
        for path in sorted(PROJECTS_DIR.glob("**/*.jsonl")):
            try:
                rel = path.relative_to(PROJECTS_DIR)
            except ValueError:
                continue
            key = rel.as_posix()
            project = rel.parts[0]
            kind = "subagent" if "subagents" in rel.parts else "main"
            try:
                size = path.stat().st_size
            except OSError:
                continue
            prev = files.get(key) or {}
            start = int(prev.get("offset", 0))
            if size == start:
                continue                 # 沒長大，跳過
            if size < start:
                start = 0                # 被改寫過，整份重來
            rows, end = _scan_file(path, start)
            files[key] = {"offset": end}
            for day, model, one in rows:
                bucket = days.setdefault(
                    day, {"projects": {}, "models": {}, "kinds": {}, **_bucket()},
                )
                for dim in ("projects", "models", "kinds"):
                    bucket.setdefault(dim, {})
                _add(bucket, one)
                _add(bucket["projects"].setdefault(project, _bucket()), one)
                _add(bucket["models"].setdefault(model, _bucket()), one)
                _add(bucket["kinds"].setdefault(kind, _bucket()), one)
        for k in sorted(days)[: max(0, len(days) - KEEP_DAYS)]:
            days.pop(k, None)
        data["scanned_at"] = datetime.now().strftime("%Y-%m-%dT%H:%M")
        data["elapsed"] = round(time.time() - started, 1)
        _save(data)
        return data
    finally:
        _scanning = False


def _fresh_enough(data: dict[str, Any]) -> bool:
    try:
        at = datetime.fromisoformat(data.get("scanned_at") or "")
    except ValueError:
        return False
    return (datetime.now() - at).total_seconds() < SCAN_EVERY_SEC


def report(span: int = 14) -> dict[str, Any]:
    """本機用量報表：今日、本月、逐日、本月按專案與模型的分佈。

    資料太舊才重掃。1.2GB 的逐字稿首掃要跑十幾秒，掛在 HTTP 請求上會逾時，
    所以呼叫端該用背景執行緒叫它（見 transport/app.py 的 /v1/usage）。
    """
    data = _load()
    if not _fresh_enough(data):
        data = scan()
    days: dict[str, Any] = data["days"]

    today = datetime.now().date()
    today_key = today.isoformat()
    month_key = today.strftime("%Y-%m")

    month = _bucket()
    projects: dict[str, dict[str, Any]] = {}
    models: dict[str, dict[str, Any]] = {}
    kinds: dict[str, dict[str, Any]] = {}
    for key, row in days.items():
        if not key.startswith(month_key):
            continue
        _add(month, row)
        for dim, into in (("projects", projects), ("models", models), ("kinds", kinds)):
            for name, v in (row.get(dim) or {}).items():
                _add(into.setdefault(name, _bucket()), v)

    ordinal = today.toordinal()
    series = []
    for i in range(span - 1, -1, -1):
        key = datetime.fromordinal(ordinal - i).date().isoformat()
        row = days.get(key) or _bucket()
        series.append({
            "date": key,
            "in": int(row.get("in", 0)),
            "out": int(row.get("out", 0)),
            "cache_read": int(row.get("cache_read", 0)),
            "turns": int(row.get("turns", 0)),
        })

    def _rank(d: dict[str, dict[str, Any]], label: str) -> list[dict]:
        # 按「真的會算進額度的量」排：cache_read 動輒是 input 的百倍，
        # 拿總和排序的話每一列都會被快取讀取洗成同一個名次。
        return sorted(
            ({label: n, **v} for n, v in d.items()),
            key=lambda r: r["in"] + r["out"], reverse=True,
        )

    return {
        "today": {**_bucket(), **{k: v for k, v in (days.get(today_key) or {}).items()
                                  if k not in ("projects", "models", "kinds")}},
        "month": month,
        "month_key": month_key,
        "days": series,
        "projects": _rank(projects, "project"),
        "models": _rank(models, "model"),
        "kinds": _rank(kinds, "kind"),
        "scanned_at": data.get("scanned_at", ""),
        "scan_sec": data.get("elapsed", 0.0),
    }
