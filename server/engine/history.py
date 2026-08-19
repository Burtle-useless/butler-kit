"""從 CC 的 session 逐字稿重建對話歷史。

存在的理由：事件流是「當下發生什麼」，不是「曾經發生什麼」。
App 冷啟動、重裝、切對話時沒有事件可看，畫面就是空的。
歷史的權威來源是 CC 自己寫的 session jsonl——我們不另外存一份，
避免兩份紀錄不同步（那會比沒有歷史更糟）。
"""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path
from typing import Any, Final

from .fold import ask_payload, clean_reply
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


# 單則歷史訊息能帶多少思考。助理平常一則幾百字，這是安全網不是常態上限——
# 歷史一次回 60 則，不設限的話一個長回合就能讓 snapshot 膨脹到幾 MB，
# 手機在外面用行動網路切個對話要等半天。
_THINK_MAX: Final[int] = 3000


def _thinking_of(content: Any) -> str:
    """抽出 assistant 訊息裡的思考區塊。

    逐字稿裡的形狀是 `{"type":"thinking","thinking":"…","signature":"…"}`
    （2026-08-17 以助理的 session 實測確認，assistant 訊息只有 text 與 thinking 兩種區塊）。
    """
    if not isinstance(content, list):
        return ""
    return "\n\n".join(
        b.get("thinking", "") for b in content
        if isinstance(b, dict) and b.get("type") == "thinking"
    ).strip()


def _at_ms(rec: dict) -> int | None:
    """這則記錄的時間，epoch 毫秒；解析不出來回 None。

    **一定要當成 UTC 解。** 實測 2026-08-18，逐字稿的格式是帶 Z 的 UTC
    （`2026-08-10T12:30:22.938Z`），而 outbox 的登記時間是本地時間、不帶時區。
    要把兩邊排在同一條時間軸上，漏掉時區就是整整差八小時——不會拋例外、
    不會有 log，只會把檔案卡片安靜地排到八小時外的某個位置去。
    """
    ts = rec.get("timestamp")
    if not isinstance(ts, str) or not ts:
        return None
    try:
        dt = datetime.fromisoformat(ts.replace("Z", "+00:00"))
    except ValueError:
        return None
    # 沒帶時區的話寧可不猜。猜錯的代價跟上面那八小時是同一種。
    if dt.tzinfo is None:
        return None
    return int(dt.timestamp() * 1000)


def load_history(conv_id: str, limit: int = 60, head: int = 0) -> list[dict]:
    """重建一個對話的歷史訊息，回 [{role, text, at_ms, think?}]，舊到新。

    `at_ms` 是那則的時間（epoch 毫秒）。加它是為了讓 App 能把檔案卡片依時間
    插回原位——卡片不在逐字稿裡（那是 butler 自己的東西），重建畫面時只能靠
    時間跟訊息對齊。

    `limit` 取**最後** N 則（畫面要看的是最近的），`head` 取**最前** N 則並在
    湊滿時立刻停止讀檔。取標題只需要開頭那幾則，先前是拿 `limit=10**9` 撈全部
    再切前 20——逐字稿動輒上 MB，那等於為了開頭幾行把整份解析一遍。
    兩個參數同時給的時候 `head` 先生效（它決定讀到哪裡為止）。

    工具軌跡不重建（那是過程，事後回看價值低），但**思考要重建**。

    先前這裡連思考一起丟掉，理由跟工具軌跡寫在一起。實際後果是：畫面上的
    💭 只在「當下那一次串流」看得到，服務一重啟、App 一重裝、對話一切走再切回來，
    整條對話的思考就全部變成空的——使用者以為助理沒在想事情，或以為思考被吃掉了。
    2026-08-17 使用者回報「思考描述會消失」。思考在 App 上是摺疊的，不佔版面，
    沒有理由不還原。

    一個回合可能有好幾則 assistant record（每次工具往返一則），思考散在各則、
    而最終回覆只在最後一則。所以思考是**累積**到下一則有文字的訊息上，
    還原出來的形狀才跟即時串流時看到的一樣：一段回覆配一整包該回合的思考。
    """
    rec = _load_map().get(conv_id) or {}
    sid = rec.get("session_id")
    if not sid:
        return []
    jf = _session_file(sid)
    if jf is None:
        return []

    out: list[dict] = []
    pending_think: list[str] = []
    # 解析不出時間的那則沿用上一則的，時間軸才不會突然掉回 0 把後面的卡片
    # 全部擠到前面去。開頭就解析不出來只能給 0，那時本來也沒有更好的猜測。
    last_ms = 0
    try:
        with jf.open(encoding="utf-8", errors="replace") as f:
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
                content = (r.get("message") or {}).get("content")
                if role == "assistant":
                    th = _thinking_of(content)
                    if th:
                        pending_think.append(th)
                text = _text_of(content).strip()
                if not text:
                    continue
                if role == "user" and text.startswith(_OPS_PREFIXES):
                    # 維運注入（續跑提示等）不算新回合，累積中的思考要留給真正的回覆
                    continue
                at = _at_ms(r)
                if at is None:
                    at = last_ms
                else:
                    last_ms = at
                item: dict = {"role": role, "text": clean_reply(text), "at_ms": at}
                if role == "assistant":
                    # 選項按鈕從原文的 [[ASK:]] 標記重抽。clean_reply 會把標記剝掉
                    # （那是給系統讀的，不該出現在畫面上），所以一定要在剝掉之前
                    # 從 text 抽——順序寫反的話按鈕在重開 App 後就永遠不見了。
                    ask = ask_payload(text)
                    if ask is not None:
                        item["ask"] = ask
                if role == "assistant" and pending_think:
                    th = "\n\n".join(pending_think)
                    item["think"] = (
                        th if len(th) <= _THINK_MAX else th[:_THINK_MAX] + "…"
                    )
                pending_think.clear()
                out.append(item)
                if head and len(out) >= head:
                    break
    except OSError:
        return []
    return _drop_answered_asks(out if head else out[-limit:])


def _drop_answered_asks(items: list[dict]) -> list[dict]:
    """只留下「還沒被回答的」那組選項。

    對話往下走了就代表他已經回答過（不管是點按鈕還是自己打字），舊訊息底下
    再掛一排按鈕只會讓人以為還要再選一次。判準就是「這則之後還有沒有他說的話」。
    """
    last_user = max(
        (i for i, it in enumerate(items) if it.get("role") == "user"), default=-1
    )
    for i, it in enumerate(items):
        if i < last_user:
            it.pop("ask", None)
    return items
