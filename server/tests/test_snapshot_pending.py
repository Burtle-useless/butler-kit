"""snapshot 要把排隊中的訊息一起帶回來。

使用者 2026-08-18 回報：「我這邊介面看不到我傳的訊息了，他實際還排在那邊沒錯」。
成因是排隊中的訊息還沒送進 CC，逐字稿裡沒有它，而 App 重建畫面就是讀逐字稿——
訊息於是從畫面上消失，但伺服器照樣處理它，最後助理回覆了一則你看不到自己問過什麼
的訊息。

這裡盯的是 `Worker.pending_of`：它讀 asyncio.Queue 的內部 deque，一旦 Python 換掉那個
實作細節就會靜默回空清單（畫面又變回什麼都沒有），所以要有測試守著。
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from transport import app as app_mod                 # noqa: E402

FAILED: list[str] = []
CONV = "ctest_pending"


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


async def scenario() -> None:
    print("\n[佇列裡的訊息看得到]")
    q: asyncio.Queue = asyncio.Queue()
    app_mod.worker.queues[CONV] = q

    check("沒有佇列時回空清單", app_mod.worker.pending_of("不存在的對話") == [])
    check("空佇列回空清單", app_mod.worker.pending_of(CONV) == [])

    await q.put(("m1", "先幫我看一下這個", "手機", []))
    await q.put(("m2", "順便把那個也跑了", "電腦", []))
    got = app_mod.worker.pending_of(CONV)

    check("兩則都拿得到", len(got) == 2, str(len(got)))
    # 來源只給模型看，不該跟著排隊中的氣泡跑到畫面上
    check("來源不外流到畫面", all("src" not in r for r in got), str(got[0]))
    check("順序是舊到新", [r["msg_id"] for r in got] == ["m1", "m2"],
          str([r.get("msg_id") for r in got]))
    check("內容原封不動", got[0]["text"] == "先幫我看一下這個")
    check("欄位就是 msg_id、text 與 attachments",
          all(set(r) == {"msg_id", "text", "attachments"} for r in got),
          str(got[0].keys()))

    print("\n[讀走之後就不該再出現]")
    await q.get()
    left = app_mod.worker.pending_of(CONV)
    check("剩下一則", [r["msg_id"] for r in left] == ["m2"],
          str([r.get("msg_id") for r in left]))

    # peek 不能有副作用——它會被 snapshot 頻繁呼叫（每次重連都一次）
    app_mod.worker.pending_of(CONV)
    app_mod.worker.pending_of(CONV)
    check("看過幾次都不會少東西", q.qsize() == 1, str(q.qsize()))

    app_mod.worker.queues.pop(CONV, None)


async def asks_scenario() -> None:
    """未決的提問也要交得出去。

    這一種比排隊訊息嚴重：卡片從畫面消失後伺服器仍在等，等不到就 fail-closed
    當成使用者拒絕——那件事被擋掉，而使用者不知道它問過。
    """
    from protocol import CONFIRM_CHOICES, AskRequest
    from transport.hub import EventHub, SseFrontend

    print("\n[未決的提問看得到]")
    h = EventHub(size=10)
    fe = SseFrontend(h, "main")
    check("沒有提問時回空清單", fe.pending_asks() == [])

    task = asyncio.create_task(fe.ask(AskRequest(
        kind="confirm_destructive", title="要刪掉整個資料夾", body="確定嗎",
        raw="rm -rf /tmp/x", choices=CONFIRM_CHOICES, timeout_sec=5,
    )))
    await asyncio.sleep(0.05)          # 讓 ask 跑到登記那一步

    got = fe.pending_asks()
    check("拿得到那一則", len(got) == 1, str(len(got)))
    if not got:
        task.cancel()
        return
    a = got[0]
    check("有 ask_id", bool(a["ask_id"]))
    check("標題帶回來了", a["title"] == "要刪掉整個資料夾")
    # 指令原文絕不能少：攻擊面正是「說明講 A、指令做 B」，重建後的卡片
    # 要是沒有原文，使用者就是在看不到指令的情況下按同意
    check("指令原文完整", a["raw"] == "rm -rf /tmp/x")
    check("選項帶回來了", len(a["choices"]) == len(CONFIRM_CHOICES),
          str(len(a["choices"])))
    check("選項有 id 與 label",
          all(c.get("id") and c.get("label") for c in a["choices"]))

    print("\n[答完之後就不該再出現]")
    check("resolve 對得上", fe.resolve(a["ask_id"], "yes") is True)
    await task
    check("答完就從清單消失", fe.pending_asks() == [], str(fe.pending_asks()))

    print("\n[逾時之後也不該留著]")
    t2 = asyncio.create_task(fe.ask(AskRequest(
        kind="confirm_destructive", title="t", choices=CONFIRM_CHOICES,
        timeout_sec=0.2,
    )))
    await asyncio.sleep(0.05)
    check("等待中看得到", len(fe.pending_asks()) == 1)
    await t2
    check("逾時後清空", fe.pending_asks() == [])


def main() -> int:
    asyncio.run(scenario())
    asyncio.run(asks_scenario())
    print("\n全部通過" if not FAILED else f"\n失敗 {len(FAILED)}：{FAILED}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
