"""從 CC 的 session 逐字稿重建對話歷史。

存在的理由：事件流是「當下發生什麼」，不是「曾經發生什麼」。
App 冷啟動、重裝、切對話時沒有事件可看，畫面就是空的。
歷史的權威來源是 CC 自己寫的 session jsonl——我們不另外存一份，
避免兩份紀錄不同步（那會比沒有歷史更糟）。
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Final

from .fold import clean_reply
from .state import _load_map

# 這些開頭代表「這一則不是人打的」。CC 的 user turn 是個大雜燴：除了真的使用者
# 訊息，還混著補跑提示、斜線指令條目、背景任務通知、讀圖工具回填的尺寸註記……
# 全部長得跟使用者自己說的話一模一樣，重建歷史時就原樣畫成一顆訊息氣泡。
# 2026-08-14 使用者回報「這些訊息不應該出現在使用者的介面」，截圖裡是整片
# <task-notification> 的英文 XML 卡在他自己的兩句話中間。
#
# 判準用開頭而不是包含：使用者本人完全可能在對話裡提到 /compact 或 task-notification。
# 實測這些注入各自獨佔一則 record，不會跟使用者的話混在同一則，所以整則丟掉是安全的。
#
# 文案改了要回來同步。history 不能反過來 import turn 取那幾個 NUDGE 常數——
# turn → client_pool → history 已經是一條依賴鏈，接上去就繞成環。
_OPS_PREFIXES: Final[tuple[str, ...]] = (
    "剛才那一步還沒收尾",              # turn.CONTINUE_NUDGE
    "剛才沒有收到",                    # turn.EMPTY_RETRY_NUDGE
    "剛才這一輪中途觸發了自動壓縮",      # turn.COMPACT_RECHECK_NUDGE
    "/compact",                       # turn.COMPACT_PROMPT
    "請把我們目前為止的對話壓縮成重點摘要",  # 改用 /compact 之前的假壓縮殘骸
    "<command-name>",                 # CLI 把斜線指令記成這種條目
    "<local-command",                 # 同上，指令的輸出與警語
    "<task-notification>",            # 背景任務結束時 SDK 塞進 user turn 的通知
    "<system-reminder>",
    "[Image: original ",              # 讀圖後回填的尺寸換算註記
    "This session is being continued from a previous conversation",
)


def _session_file(session_id: str) -> Path | None:
    for jf in (Path.home() / ".claude" / "projects").glob(f"*/{session_id}.jsonl"):
        return jf
    return None


def session_exists(session_id: str | None) -> bool:
    """這個 session 還接得回去嗎。

    給 client_pool 在帶 resume 前擋一下。CLI resume 一個不存在的 session 時
    會直接 exit 1，而 stderr 那句「No conversation found with session ID」
    **不會進到 SDK 的例外訊息**——Python 這側只拿得到
    「Command failed with exit code 1 / Check stderr output for details」。
    也就是說錯誤分類器看不出這是 session 的問題，於是既不丟 client 也不清
    session，下一次照樣帶著同一個壞 id 去撞：這條對話就永遠回不了話。
    （2026-08-12 以 _diag_resume.py 實測確認。）

    所以不靠事後解析錯誤字串，改成事前確認檔案還在。
    """
    return bool(session_id) and _session_file(str(session_id)) is not None


def _text_of(content: Any) -> str:
    """把 message.content 抽成純文字（忽略 thinking／tool_use 等區塊）。"""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(
            b.get("text", "") for b in content
            if isinstance(b, dict) and b.get("type") == "text"
        )
    return ""


def load_history(conv_id: str, limit: int = 60) -> list[dict]:
    """重建一個對話的歷史訊息，回 [{role, text}]，舊到新。

    只取 user／assistant 的純文字：工具軌跡與思考不重建——
    那些是「過程」，事後回看價值低，而且會讓歷史膨脹好幾倍。
    """
    rec = _load_map().get(conv_id) or {}
    sid = rec.get("session_id")
    if not sid:
        return []
    jf = _session_file(sid)
    if jf is None:
        return []

    out: list[dict] = []
    try:
        with jf.open(encoding="utf-8") as f:
            for line in f:
                try:
                    r = json.loads(line)
                except json.JSONDecodeError:
                    continue
                role = r.get("type")
                if role not in ("user", "assistant"):
                    continue
                # 壓縮接續摘要：CLI 做完 /compact 會塞一則帶標記的長篇英文摘要
                # 當開場 user 訊息，那是給模型看的維運產物，不是對話
                if r.get("isCompactSummary"):
                    continue
                text = _text_of((r.get("message") or {}).get("content")).strip()
                if not text:
                    continue
                if role == "user" and text.startswith(_OPS_PREFIXES):
                    continue
                out.append({"role": role, "text": clean_reply(text)})
    except OSError:
        return []
    return out[-limit:]
