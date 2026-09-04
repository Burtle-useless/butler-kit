"""收發中樞：回合外的訊息要有人接。

2026-08-23 使用者回報「我們好像忘了做自動喚醒」。實情是助理把腳本丟到背景、說了
「等它完成」就收工，腳本一分鐘後跑完並寫好輸出檔，而那則完成通知**沒有任何人讀**
——回合早就結束了，舊架構只有回合會去讀串流。

這組測試釘住新的歸屬規則：
  - 有回合在收件 → 訊息歸回合，不可以外洩到回合外處理器
  - 沒有回合    → 訊息歸 bg_notify，變成推播
  - 回合交還收件匣時沒讀完的 → **一則都不能丟**（背景完成通知最常落在這個縫）
"""
from __future__ import annotations

import asyncio
import sys
from typing import Any

import config  # noqa: F401
from claude_agent_sdk import TaskNotificationMessage

from engine import bg_notify
from engine.history import _OPS_PREFIXES
from engine.mailbox import Mailbox
from protocol import Event

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


class CollectFrontend:
    def __init__(self) -> None:
        self.events: list[Event] = []

    async def emit(self, ev: Event) -> None:
        self.events.append(ev)

    async def ask(self, req: Any) -> None:
        return None


class _FakeQuery:
    """假的 SDK 私有查詢物件。訊息由測試逐則餵進來，時序才控制得住。"""

    def __init__(self, src: asyncio.Queue) -> None:
        self._src = src

    async def receive_messages(self):
        while True:
            item = await self._src.get()
            if item is None:            # 串流正常結束
                return
            if isinstance(item, Exception):
                raise item
            yield item


class FakeClient:
    def __init__(self) -> None:
        self.src: asyncio.Queue = asyncio.Queue()
        self._query = _FakeQuery(self.src)
        self.disconnected = False

    async def disconnect(self) -> None:
        self.disconnected = True


def task_update(task_id: str, status: str | None) -> dict:
    """一則 task_updated 的原始格式。

    `status` 放在 `patch` 裡（解析器就是讀 `patch.get("status")`）。傳 None
    模擬「只帶 end_time、沒帶 status」那種非終結的 patch。
    """
    patch: dict = {"end_time": 1_700_000_000}
    if status is not None:
        patch["status"] = status
    return {
        "type": "system",
        "subtype": "task_updated",
        "task_id": task_id,
        "patch": patch,
        "uuid": "u-2",
        "session_id": "s-1",
    }


def task_note(task_id: str, summary: str, status: str = "completed") -> dict:
    """一則 task_notification 的原始格式。

    欄位照 SDK 的 message_parser 要求逐個給齊——少一個就是 MessageParseError，
    而 `iter_messages` 會安靜跳過解析失敗的訊息，測試會變成假通過。
    """
    return {
        "type": "system",
        "subtype": "task_notification",
        "task_id": task_id,
        "status": status,
        "output_file": f"/tmp/{task_id}.output",
        "summary": summary,
        "uuid": "u-1",
        "session_id": "s-1",
    }


def make_box() -> tuple[Mailbox, FakeClient, CollectFrontend, list]:
    """建一個接上假 client 的收發中樞。回傳的 list 收下所有走回合外的訊息。

    順手把 bg_notify 的模組級帳本清空。那份帳跨回合、跨對話活著（那正是它存在
    的理由），測試之間不清就會互相污染，而症狀只是某個 check 莫名其妙變紅。
    這裡的 box 刻意**不掛 on_wake**：這組測的是通知的歸屬，wake 回合另有
    test_wake.py。
    """
    bg_notify._tasks.clear()
    bg_notify._recent_done.clear()
    client = FakeClient()
    fe = CollectFrontend()
    seen: list = []

    async def spy_idle(conv_id: str, frontend: Any, msg: Any) -> None:
        seen.append(msg)
        await bg_notify.handle(conv_id, frontend, msg)

    box = Mailbox("test", client, fe, spy_idle)  # type: ignore[arg-type]
    box.start()
    return box, client, fe, seen


async def settle() -> None:
    """讓讀取者有機會跑完手上的訊息。"""
    for _ in range(5):
        await asyncio.sleep(0)


async def test_in_turn_stays_in_turn() -> None:
    print("\n[回合收件期間不外洩]")
    box, client, _fe, seen = make_box()
    async with box.claim() as inbox:
        await client.src.put(task_note("t1", "跑完了"))
        msg = await asyncio.wait_for(inbox.__anext__(), 2)
        check("訊息進了回合", isinstance(msg, TaskNotificationMessage),
              type(msg).__name__)
        check("同一則沒有同時走回合外", seen == [], str(seen))
    await box.close()


async def test_outside_turn_goes_idle() -> None:
    print("\n[回合外的訊息有人接]")
    box, client, fe, seen = make_box()
    # 完全沒有 claim，模擬「助理早就收工了，腳本現在才跑完」
    await client.src.put(task_note("t2", "三關重跑完成"))
    await settle()
    check("落到回合外處理器", len(seen) == 1, str(len(seen)))
    notes = [e for e in fe.events if e.type == "notify"]
    check("發出了推播事件", len(notes) == 1, str([e.type for e in fe.events]))
    if notes:
        check("內文是那段摘要", notes[0].data.get("body") == "三關重跑完成",
              str(notes[0].data.get("body")))
        check("帶上輸出檔路徑",
              notes[0].data.get("output_file") == "/tmp/t2.output",
              str(notes[0].data.get("output_file")))
    await box.close()


async def test_leftover_not_dropped() -> None:
    """**最重要的一條。** 回合 break 之後到交還收件匣之間還有好幾個 await，
    背景完成通知最常落在這個縫裡。丟掉就等於回到舊行為。"""
    print("\n[交還收件匣時剩下的訊息不會被丟掉]")
    box, client, fe, seen = make_box()
    async with box.claim() as inbox:
        await client.src.put(task_note("a", "第一則"))
        await client.src.put(task_note("b", "第二則"))
        await asyncio.wait_for(inbox.__anext__(), 2)   # 只讀第一則就走人
        await settle()                                  # 第二則已經進了收件匣
    await settle()
    check("沒讀完的那則轉給了回合外", len(seen) == 1, str(len(seen)))
    if seen:
        check("轉過去的是第二則", getattr(seen[0], "task_id", "") == "b",
              str(getattr(seen[0], "task_id", "")))
    check("而且真的發了推播",
          len([e for e in fe.events if e.type == "notify"]) == 1)
    await box.close()


async def test_single_claim() -> None:
    print("\n[同時只能有一個回合收件]")
    box, client, _fe, _seen = make_box()
    async with box.claim():
        try:
            async with box.claim():
                check("第二個 claim 應該被擋下", False)
        except RuntimeError:
            check("第二個 claim 被擋下", True)
    await box.close()


async def test_stream_error_reaches_turn() -> None:
    """串流出事要傳給正在等的回合，讓既有的錯誤善後接手；靜默結束的話
    回合會一路等到閒置逾時。"""
    print("\n[串流錯誤傳得到回合]")
    box, client, _fe, _seen = make_box()
    async with box.claim() as inbox:
        await client.src.put(RuntimeError("連線斷了"))
        try:
            await asyncio.wait_for(inbox.__anext__(), 2)
            check("應該要拋出來", False)
        except RuntimeError as e:
            check("回合收到那個錯誤", str(e) == "連線斷了", str(e))
        except asyncio.TimeoutError:
            check("回合收到那個錯誤", False, "等到逾時")
    await box.close()


async def test_description_carried_across_turns() -> None:
    """完成通知沒有描述欄位，得靠開工時記下的那份，通知才說得出是哪件事。"""
    print("\n[跨回合記住工作在做什麼]")
    box, client, fe, _seen = make_box()
    bg_notify.remember("test", "t9", "重跑三關審查")
    await client.src.put(task_note("t9", "全部通過"))
    await settle()
    notes = [e for e in fe.events if e.type == "notify"]
    check("標題帶上工作內容",
          bool(notes) and notes[0].data.get("title") == "背景工作完成：重跑三關審查",
          str(notes[0].data.get("title")) if notes else "沒有事件")
    await box.close()


async def test_failed_status() -> None:
    print("\n[失敗與中止講不同的話]")
    box, client, fe, _seen = make_box()
    await client.src.put(task_note("t8", "編譯錯誤", status="failed"))
    await settle()
    notes = [e for e in fe.events if e.type == "notify"]
    check("失敗說失敗",
          bool(notes) and notes[0].data.get("title") == "背景工作失敗",
          str(notes[0].data.get("title")) if notes else "沒有事件")
    await box.close()


async def test_done_becomes_wake_reason() -> None:
    """跑完之後助理會自己醒來（CLI 原生的 wake 週期），那一輪開頭要說得出
    是為了哪件事醒來。通知與週期是兩則獨立的訊息，靠這份「最近結束的」對上。

    2026-09-02 之前這裡測的是 butler 自己排一則「〔背景工作回報〕」散文把助理
    叫醒——那條路已經拆掉（同一件事跑兩遍，見 bg_notify 模組說明）。
    舊逐字稿裡那些散文仍要被歷史濾掉，所以前綴清單那一條留著。
    """
    print("\n[背景跑完記成 wake 回合的理由]")
    box, client, _fe, _seen = make_box()
    bg_notify.remember("test", "t5", "重跑三關審查")
    check("還沒有任何工作結束時沒有理由", bg_notify.wake_reason("test") is None)
    await client.src.put(task_note("t5", "三關全過"))
    await settle()
    reason = bg_notify.wake_reason("test")
    check("結束後查得到理由", reason is not None, str(reason))
    if reason:
        check("說得出是哪件工作", reason.get("desc") == "重跑三關審查", str(reason))
        check("帶著摘要", reason.get("summary") == "三關全過", str(reason))
        check("狀態是完成", reason.get("status") == "completed", str(reason))
    check("別的對話沒有理由", bg_notify.wake_reason("other") is None)
    # 太久以前結束的不算：CLI 醒來是通知後不到一秒的事，隔很久才出現的
    # 自主週期不該頂著一個過時的理由
    task, at = bg_notify._recent_done["test"]
    bg_notify._recent_done["test"] = (task, at - bg_notify.WAKE_REASON_WINDOW - 1)
    check("超過時間窗就不算", bg_notify.wake_reason("test") is None)
    check("舊散文的前綴仍在歷史過濾清單裡",
          any("〔背景工作回報".startswith(pre) for pre in _OPS_PREFIXES))
    await box.close()


async def test_updated_closes_task() -> None:
    """**終結狀態可能只來自 task_updated。**

    SDK 的 TaskUpdatedMessage docstring 寫明：背景工作的終結狀態可能只以
    task_updated 抵達，完全沒有對應的 task_notification，要求消費端從任一種
    訊息看到終結狀態就把 task id 清掉。

    2026-08-29 實際踩到：一件「產生測試件並出圖」的背景工作跑完之後，卡片停在進行中
    26 分鐘，帳上那筆進度全是 0。當時 butler 只認 task_notification。
    """
    print("\n[task_updated 也要能結帳]")
    box, client, fe, _seen = make_box()
    bg_notify.remember("test", "u1", "產生測試件並出圖")
    await client.src.put(task_update("u1", "completed"))
    await settle()
    rows = bg_notify.wire("test")
    check("卡片不再是進行中", rows and rows[0]["status"] == "completed",
          str(rows))
    check("沒有殘留在進行中清單", not bg_notify.active("test"))

    # 非終結的 patch（只帶 end_time）不可以結帳——那只是生命週期中途的更新
    bg_notify.remember("test", "u2", "還在跑的")
    await client.src.put(task_update("u2", None))
    await client.src.put(task_update("u2", "running"))
    await settle()
    live = [t.task_id for t in bg_notify.active("test")]
    check("非終結狀態不結帳", "u2" in live, str(live))
    await box.close()


async def test_two_terminal_messages_one_push() -> None:
    """同一件工作的終結會來兩則（task_updated 先、task_notification 後），
    推播只能發一次，摘要要以後到的那則為準。2026-09-02 端對端實測：手機
    每件工作收到兩則同標題的通知。"""
    print("\n[兩則終結訊息只推播一次]")
    box, client, fe, _seen = make_box()
    bg_notify.remember("test", "d2", "跑腳本")
    await client.src.put(task_update("d2", "completed"))
    await client.src.put(task_note("d2", "exit code 0"))
    await settle()
    notes = [e for e in fe.events if e.type == "notify"]
    check("只推播一次", len(notes) == 1, str(len(notes)))
    rows = {t["id"]: t for t in bg_notify.wire("test")}
    check("摘要以通知那則為準", rows.get("d2", {}).get("summary") == "exit code 0",
          str(rows.get("d2")))
    states = [e for e in fe.events if e.type == "bg.state"]
    check("兩則都讓卡片更新", len(states) == 2, str(len(states)))
    await box.close()


async def test_killed_maps_to_stopped() -> None:
    """`killed` 要當成「被停掉」，不是「完成」。

    兩套詞彙：task_notification 送 stopped，task_updated 送原始的 killed。
    先前 finish() 是 `status if status in _TITLES else "completed"`，killed
    掉進 else 被標成完成——被停掉的工作在畫面上顯示成跑完了，而且會照
    「完成」那條路把助理叫醒去接手一份半成品。
    """
    print("\n[killed 等於 stopped]")
    box, client, fe, _seen = make_box()
    bg_notify.remember("test", "k1", "被停掉的工作")
    await client.src.put(task_update("k1", "killed"))
    await settle()
    rows = [t for t in bg_notify.wire("test") if t["id"] == "k1"]
    check("標成中止不是完成", rows and rows[0]["status"] == "stopped", str(rows))
    notes = [e for e in fe.events if e.type == "notify"]
    check("推播說的是被中止",
          bool(notes) and notes[-1].data.get("status") == "stopped",
          str(notes[-1].data if notes else None))
    await box.close()


async def test_orphan_on_drop() -> None:
    """連線被回收時，還掛著的背景工作要一起結掉。

    背景工作跑在 CLI 進程裡，進程一收工作就死了，而且**不會有任何通知**：
    TaskStarted 之後收不到 progress，也不會有終結訊息。2026-09-01 實例：
    一件「看五路實際讀值」在帳上掛了 12.6 小時，工具 0、token 0。
    """
    print("\n[連線回收要順手結帳]")
    bg_notify.remember("orphan", "o1", "看五路實際讀值")
    bg_notify.remember("orphan", "o2", "另一件")
    bg_notify.finish("orphan", "o2", "completed", "這件自己跑完了")
    n = bg_notify.orphan_all("orphan")
    check("只結還在跑的那件", n == 1, str(n))
    rows = {t["id"]: t["status"] for t in bg_notify.wire("orphan")}
    check("卡住那件轉成中止", rows.get("o1") == "stopped", str(rows))
    check("已完成的不被改掉", rows.get("o2") == "completed", str(rows))
    check("沒有殘留在進行中", not bg_notify.active("orphan"))
    check("再結一次不會出事", bg_notify.orphan_all("orphan") == 0)


async def test_stopped_notifies() -> None:
    """按下停止也要有推播，文案要說是被中止，不是跑完了。"""
    print("\n[被中止的工作照樣通知]")
    box, client, fe, _seen = make_box()
    bg_notify.remember("test", "s1", "跑一個很久的腳本")
    await client.src.put(task_note("s1", "", status="stopped"))
    await settle()
    notes = [e for e in fe.events if e.type == "notify"]
    check("推播照發", len(notes) == 1, str(len(notes)))
    if notes:
        check("說的是被中止",
              notes[0].data.get("title") == "背景工作被中止：跑一個很久的腳本",
              str(notes[0].data.get("title")))
        check("內文說停下來了", "停下來" in notes[0].data.get("body", ""),
              str(notes[0].data.get("body")))
    await box.close()


def descs(conv_id: str) -> list[str]:
    """還在跑的那幾件在做什麼。"""
    return [t.description for t in bg_notify.active(conv_id)]


async def test_bg_state_survives_turn() -> None:
    """回合結束後那幾張卡片要留著。帳記在回合裡就是留不住。"""
    print("\n[背景工作清單跨回合存活]")
    box, client, fe, _seen = make_box()
    bg_notify.remember("test", "a1", "跑審查腳本")
    bg_notify.remember("test", "a2", "編譯 APK")
    check("兩件都在跑", descs("test") == ["跑審查腳本", "編譯 APK"],
          str(descs("test")))
    # 一件跑完（回合外），另一件還在
    await client.src.put(task_note("a1", "跑完了"))
    await settle()
    check("跑完的那件不再算進行中", descs("test") == ["編譯 APK"],
          str(descs("test")))
    states = [e for e in fe.events if e.type == "bg.state"]
    check("發了狀態事件讓畫面更新", len(states) == 1, str(len(states)))
    if states:
        tasks = states[-1].data.get("tasks") or []
        by_id = {t["id"]: t for t in tasks}
        check("事件帶著兩件（一件完成一件還在跑）", len(tasks) == 2, str(tasks))
        check("完成的標成 completed",
              by_id.get("a1", {}).get("status") == "completed",
              str(by_id.get("a1")))
        check("跑完的帶著摘要", by_id.get("a1", {}).get("summary") == "跑完了",
              str(by_id.get("a1")))
        check("還在跑的標成 running",
              by_id.get("a2", {}).get("status") == "running",
              str(by_id.get("a2")))
    check("別的對話不受影響", descs("other") == [])
    await box.close()


async def test_done_kept_until_user_speaks() -> None:
    """完成的紀錄要留著讓人回頭看得到結果，直到他自己講下一件事。

    先前這裡是 pop：跑完的工作連同摘要與輸出檔路徑一起從帳上消失，
    除了一則會被滑掉的推播之外，沒有任何地方查得到剛剛那件事的結果。
    """
    print("\n[完成的紀錄留到使用者下次發言]")
    box, client, _fe, _seen = make_box()
    bg_notify.remember("test", "d1", "跑審查腳本")
    await client.src.put(task_note("d1", "全過"))
    await settle()
    check("完成之後紀錄還在", len(bg_notify.wire("test")) == 1,
          str(bg_notify.wire("test")))
    check("但不算進行中", descs("test") == [], str(descs("test")))
    check("使用者發言時收起來", bg_notify.clear_done("test"))
    check("收起來之後就空了", bg_notify.wire("test") == [],
          str(bg_notify.wire("test")))
    check("沒東西可收時不謊報有變動", not bg_notify.clear_done("test"))
    await box.close()


async def test_progress_recorded() -> None:
    """SDK 一直在送進度（token、工具次數、最近呼叫哪個工具），先前整包丟棄。"""
    print("\n[進度數字接得到]")
    box, _client, _fe, _seen = make_box()
    bg_notify.remember("test", "p1", "掃全庫")
    check("有變動才回 True",
          bg_notify.progress("test", "p1",
                             {"total_tokens": 1200, "tool_uses": 3}, "Grep"))
    t = bg_notify.active("test")[0]
    check("token 記下來了", t.tokens == 1200, str(t.tokens))
    check("工具次數記下來了", t.tool_uses == 3, str(t.tool_uses))
    check("最近的工具記下來了", t.last_tool == "Grep", t.last_tool)
    # 同一份數字再送一次不該讓手機收到一則沒有內容變化的事件
    check("數字沒變就不回報",
          not bg_notify.progress("test", "p1",
                                 {"total_tokens": 1200, "tool_uses": 3}, "Grep"))
    check("沒記到的任務安靜跳過",
          not bg_notify.progress("test", "nope", {"total_tokens": 9}, "Read"))
    await box.close()


async def test_close_stops_reader() -> None:
    print("\n[關閉會停掉讀取者並斷線]")
    box, client, _fe, _seen = make_box()
    check("開著的時候是活的", box.alive())
    await box.close()
    check("關掉之後不是活的", not box.alive())
    check("client 也斷了", client.disconnected)


async def main() -> int:
    await test_in_turn_stays_in_turn()
    await test_outside_turn_goes_idle()
    await test_leftover_not_dropped()
    await test_single_claim()
    await test_stream_error_reaches_turn()
    await test_description_carried_across_turns()
    await test_failed_status()
    await test_done_becomes_wake_reason()
    await test_updated_closes_task()
    await test_two_terminal_messages_one_push()
    await test_killed_maps_to_stopped()
    await test_orphan_on_drop()
    await test_stopped_notifies()
    await test_bg_state_survives_turn()
    await test_done_kept_until_user_speaks()
    await test_progress_recorded()
    await test_close_stops_reader()
    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
