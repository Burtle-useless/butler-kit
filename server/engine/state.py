"""對話狀態與三層設定。

沿用 cc-bot 的 ChannelState 設計，但拿掉所有 Discord 專屬欄位
（_live_msg / _sidebar / _named），並把 channel id(int) 換成 conv_id(str)。
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import config
from util import atomic_write_text


@dataclass(slots=True)
class ConvState:
    """一個對話的會話狀態。

    slots=True：欄位名打錯（讀或寫）都立刻 AttributeError（fail-loud），
    不會像 dict 那樣靜默回 None 或長出殭屍鍵。
    底線開頭為執行期旗標，不進持久化內容。
    """

    conv_id: str                              # 對話 id
    cwd: Path                                 # 工作目錄
    session_id: str | None = None             # CC session id（None＝尚未開始）
    model: str | None = None                  # 對話覆寫模型（None＝跟隨帳號預設）
    effort: str | None = None                 # 對話覆寫思考程度（None＝跟隨帳號預設）
    wt: dict | None = None                    # worktree 資訊（path/branch/base/repo/prev_cwd）
    ctx_tokens: int = 0                       # 最近一次 result 回報的 context 用量
    pending_options: list[str] | None = None  # 待答選項
    _session_label: str | None = None         # 顯示用標題快取
    _no_think: bool = False                   # 本次是否關閉思考（空回覆重試逃生門）


# ── 帳號預設（三層設定的中間層）──────────────────────────────────────────────
# 優先序：對話覆寫（ConvState.model/effort）→ 帳號預設（此處）→ 內建後備（config）。
_DEFAULTS_FILE = config.DATA_DIR / "account_defaults.json"


def _load_defaults() -> tuple[str | None, str | None]:
    try:
        d = json.loads(_DEFAULTS_FILE.read_text(encoding="utf-8"))
        return d.get("model"), d.get("effort")
    except Exception:
        return None, None


default_model, default_effort = _load_defaults()


def save_defaults(model: str | None, effort: str | None) -> None:
    """設定帳號預設並落檔。改了之後，未單獨覆寫的對話下次會自動重建 client。"""
    global default_model, default_effort
    default_model, default_effort = model, effort
    try:
        atomic_write_text(
            _DEFAULTS_FILE,
            json.dumps({"model": model, "effort": effort}, ensure_ascii=False),
        )
    except Exception:
        pass


def eff_model(state: ConvState) -> str:
    """該對話實際生效的模型：對話覆寫 → 帳號預設 → 內建後備。"""
    return state.model or default_model or config.DEFAULT_MODEL


def eff_effort(state: ConvState) -> str | None:
    """該對話實際生效的思考程度：對話覆寫 → 帳號預設（None＝SDK 預設）。"""
    return state.effort or default_effort


# ── Session 持久化 ───────────────────────────────────────────────────────────
def _load_map() -> dict:
    try:
        return json.loads(config.SESSION_FILE.read_text(encoding="utf-8"))
    except Exception:
        return {}


def persist(state: ConvState) -> None:
    """整包存：session_id + model/effort/cwd/wt，重啟後設定不會變回預設。"""
    try:
        data = _load_map()
        data[state.conv_id] = {
            "session_id": state.session_id,
            "model": state.model,
            "effort": state.effort,
            "cwd": str(state.cwd or config.DEFAULT_CWD),
            "wt": state.wt,
        }
        atomic_write_text(config.SESSION_FILE, json.dumps(data, ensure_ascii=False))
    except Exception:
        pass


_states: dict[str, ConvState] = {}


def get_state(conv_id: str) -> ConvState:
    """取得對話狀態：記憶體 → 硬碟 → 新建。"""
    st = _states.get(conv_id)
    if st is not None:
        return st
    rec = _load_map().get(conv_id) or {}
    cwd = Path(rec.get("cwd") or config.DEFAULT_CWD)
    wt = rec.get("wt")
    # worktree 目錄若已被外部刪除，降級回原本的工作目錄，否則 build_options 會拿到死路徑
    if wt and not Path(wt.get("path", "")).is_dir():
        cwd = Path(wt.get("prev_cwd") or config.DEFAULT_CWD)
        wt = None
    if not cwd.is_dir():
        cwd = config.DEFAULT_CWD
    st = ConvState(
        conv_id=conv_id,
        cwd=cwd,
        session_id=rec.get("session_id"),
        model=rec.get("model"),
        effort=rec.get("effort"),
        wt=wt,
    )
    _states[conv_id] = st
    return st


def all_states() -> dict[str, ConvState]:
    """目前記憶體中的所有對話狀態（唯讀用途）。"""
    return dict(_states)


# ── 標題 ─────────────────────────────────────────────────────────────────────
_TITLES_FILE = config.DATA_DIR / "titles.json"


def _load_titles() -> dict[str, str]:
    try:
        return json.loads(_TITLES_FILE.read_text(encoding="utf-8"))
    except Exception:
        return {}


def get_title(conv_id: str) -> str | None:
    return _load_titles().get(conv_id)


def set_title(conv_id: str, title: str) -> None:
    data = _load_titles()
    data[conv_id] = title
    try:
        atomic_write_text(_TITLES_FILE, json.dumps(data, ensure_ascii=False, indent=1))
    except Exception:
        pass


# ── 對話列表 ─────────────────────────────────────────────────────────────────
def list_conversations() -> list[dict]:
    """列出所有已知對話（記憶體＋硬碟合併），新到舊。

    最後活動時間以 session jsonl 的 mtime 為準——那是唯一跨重啟仍準確的來源。
    """
    titles = _load_titles()
    known: dict[str, dict] = {}
    for cid, rec in _load_map().items():
        known[cid] = {"conv_id": cid, "session_id": rec.get("session_id")}
    for cid, st in _states.items():
        known.setdefault(cid, {"conv_id": cid, "session_id": st.session_id})

    out = []
    claude_home = Path.home() / ".claude" / "projects"
    for cid, rec in known.items():
        mtime = 0.0
        sid = rec.get("session_id")
        if sid:
            for jf in claude_home.glob(f"*/{sid}.jsonl"):
                try:
                    mtime = jf.stat().st_mtime
                except OSError:
                    pass
                break
        out.append({
            "conv_id": cid,
            "title": titles.get(cid) or cid,
            "mtime": mtime,
            "has_session": bool(sid),
        })
    return sorted(out, key=lambda e: e["mtime"], reverse=True)


def delete_conversation(conv_id: str) -> bool:
    """刪除一個對話的狀態與標題。session jsonl 檔刻意不動——那是 CC 的資料。"""
    existed = conv_id in _states
    _states.pop(conv_id, None)
    data = _load_map()
    if conv_id in data:
        existed = True
        data.pop(conv_id)
        try:
            atomic_write_text(config.SESSION_FILE, json.dumps(data, ensure_ascii=False))
        except Exception:
            pass
    titles = _load_titles()
    if conv_id in titles:
        titles.pop(conv_id)
        try:
            atomic_write_text(_TITLES_FILE, json.dumps(titles, ensure_ascii=False, indent=1))
        except Exception:
            pass
    return existed
