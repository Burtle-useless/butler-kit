"""wake 回合：助理自己醒來的那一輪要被當成正式回合接住。

背景工作跑完之後 CLI 會自己另起一個模型週期（注入 <task-notification>、讀結果、
接著講）。2026-09-02 之前那整個週期落在回合外被逐則丟掉，butler 再自己排一則
中文散文把模型叫醒第二次——同一件事跑兩遍。實測（bundled CLI 2.1.247）：通知後
0.8 秒串流就開始、單一 Result、週期中 query() 會被插進同一週期。

這裡釘住的行為：
  - 回合外看到模型活動 → 當場開收件匣、發票；後續訊息一則不漏到 idle
  - wake 回合拿票接手：不送 prompt、turn.start 標 origin=wake 並帶理由、正常折出回覆
  - 使用者回合撞上待領的票 → 認養那個收件匣；票隨即失效，wake 回合安靜退場
  - 沒掛派工函式（單元測試那種環境）→ 退回 idle，行為跟以前一樣
  - 票被作廢（停止、刪對話）→ 收件匣收掉，裡面的通知照樣結帳
  - transport：票排進跟使用者訊息同一條佇列，wake 進行中送來的訊息會排隊，
    排隊清單不列 wake
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path
from typing import Any

import config  # noqa: F401
from claude_agent_sdk import StreamEvent, TaskNotificationMessage

from engine import bg_notify
from engine import runner as runner_mod
from engine import turn as turn_mod
from engine.mailbox import Mailbox, WakeConsumed, WakeTicket
from engine.state import ConvState
from protocol import Event

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}",
          flush=True)
    if not cond:
        FAILED.append(name)


class CollectFrontend:
    def __init__(self) -> None:
        self.events: list[Event] = []

    async def emit(self, ev: Event) -> None:
        self.events.append(ev)

    async def ask(self, req: Any) -> None:
        return None

    def of(self, t: str) -> list[Event]:
        return [e for e in self.events if e.type == t]


class _FakeQuery:
    def __init__(self, src: asyncio.Queue) -> None:
        self._src = src

    async def receive_messages(self):
        while True:
            item = await self._src.get()
            if item is None:
                return
            if isinstance(item, Exception):
                raise item
            yield item


class FakeClient:
    """餵原始 dict 進去（跟真 CLI 一樣經過 parse_message），並記下 query()。"""

    def __init__(self) -> None:
        self.src: asyncio.Queue = asyncio.Queue()
        self._query = _FakeQuery(self.src)
        self.queries: list[str] = []
        self.disconnected = False

    async def query(self, prompt: str) -> None:
        self.queries.append(prompt)

    async def get_context_usage(self) -> dict:
        return {}

    async def disconnect(self) -> None:
        self.disconnected = True


def raw_stream(kind: str = "message_start") -> dict:
    return {"type": "stream_event", "uuid": "u-s", "session_id": "s-1",
            "event": {"type": kind}}


def raw_assistant(text: str) -> dict:
    return {
        "type": "assistant",
        "message": {"content": [{"type": "text", "text": text}],
                    "model": "claude-opus-5"},
        "session_id": "s-1", "uuid": "u-a", "parent_tool_use_id": None,
    }


def raw_result(text: str) -> dict:
    return {"type": "result", "subtype": "success", "duration_ms": 1,
            "duration_api_ms": 1, "is_error": False, "num_turns": 1,
            "session_id": "s-1", "result": text}


def task_note(task_id: str, summary: str, status: str = "completed") -> dict:
    return {"type": "system", "subtype": "task_notification", "task_id": task_id,
            "status": status, "output_file": f"/tmp/{task_id}.output",
            "summary": summary, "uuid": "u-1", "session_id": "s-1"}


def make_state() -> ConvState:
    return ConvState(conv_id="test", cwd=Path.home())


def make_box(waker) -> tuple[Mailbox, FakeClient, CollectFrontend, list, list]:
    """接上假 client 的收發中樞。回傳 (box, client, fe, idle 看到的, 發出的票)。"""
    bg_notify._tasks.clear()
    bg_notify._recent_done.clear()
    tickets: list[WakeTicket] = []

    def spy_waker(conv_id: str, frontend: Any, ticket: WakeTicket) -> None:
        tickets.append(ticket)
        if waker is not None:
            waker(conv_id, frontend, ticket)

    bg_notify.set_waker(spy_waker if waker is not False else None)
    client = FakeClient()
    fe = CollectFrontend()
    seen: list = []

    async def spy_idle(conv_id: str, frontend: Any, msg: Any) -> None:
        seen.append(msg)
        await bg_notify.handle(conv_id, frontend, msg)

    box = Mailbox("test", client, fe, spy_idle, on_wake=bg_notify.on_wake)  # type: ignore[arg-type]
    box.start()
    return box, client, fe, seen, tickets


async def settle() -> None:
    for _ in range(8):
        await asyncio.sleep(0)


def patch_pool(box: Mailbox) -> None:
    """wake 回合只接現有連線（peek），不 acquire。狀態落地也關掉，測試不寫檔。"""
    runner_mod.client_pool.peek = lambda cid: box  # type: ignore[assignment]
    runner_mod.persist = lambda s: None  # type: ignore[assignment]
    turn_mod._persist_sid = lambda res, state: None  # type: ignore[assignment]


async def test_activity_opens_wake() -> None:
    print("\n[回合外的模型活動開成 wake 收件匣]")
    box, client, _fe, seen, tickets = make_box(lambda *a: None)
    bg_notify.remember("test", "t1", "跑腳本")
    await client.src.put(task_note("t1", "OK"))       # 通知：走 idle 結帳
    await client.src.put(raw_stream())                 # 模型開口：開票
    await client.src.put(raw_assistant("結果是 OK"))
    await settle()
    check("通知走 idle 處理", len(seen) == 1 and
          isinstance(seen[0], TaskNotificationMessage), str([type(m).__name__ for m in seen]))
    check("發了一張票", len(tickets) == 1, str(len(tickets)))
    if tickets:
        t = tickets[0]
        check("票還沒被領", t.pending())
        check("模型活動全進了票的收件匣", t.q.qsize() == 2, str(t.q.qsize()))
    check("idle 一則模型活動都沒看到",
          not any(isinstance(m, StreamEvent) for m in seen))
    reason = bg_notify.wake_reason("test")
    check("醒來的理由對得上剛結束的工作",
          bool(reason) and reason.get("desc") == "跑腳本", str(reason))
    await box.close()


async def test_wake_turn_reads_cycle() -> None:
    print("\n[wake 回合接手把週期讀完]")
    box, client, fe, seen, tickets = make_box(lambda *a: None)
    patch_pool(box)
    bg_notify.remember("test", "t2", "編譯 APK")
    await client.src.put(task_note("t2", "exit 0"))
    await client.src.put(raw_stream())
    await client.src.put(raw_assistant("背景結果：編譯過了"))
    await settle()
    ticket = tickets[0]

    async def feed() -> None:
        await asyncio.sleep(0.05)      # 回合開始讀之後才到的訊息也要接得到
        await client.src.put(raw_result("背景結果：編譯過了"))

    asyncio.create_task(feed())
    res = await runner_mod.run_turn("", make_state(), fe, "w-1", wake=ticket)
    check("回覆折出來了", res is not None and "編譯過了" in res.reply,
          res.reply if res else "None")
    starts = fe.of("turn.start")
    check("發了 turn.start", len(starts) == 1, str(len(starts)))
    if starts:
        d = starts[0].data
        check("標成 wake", d.get("origin") == "wake", str(d))
        check("prompt 是空的", d.get("prompt") == "", str(d.get("prompt")))
        check("帶著理由", (d.get("wake") or {}).get("desc") == "編譯 APK", str(d.get("wake")))
    check("發了 turn.end", len(fe.of("turn.end")) == 1)
    check("沒有送任何 prompt 給 CLI", client.queries == [], str(client.queries))
    check("票已用掉", not ticket.pending())
    check("收件匣交還了", box._inbox is None and box._wake is None)
    # 收工之後回合外的通知照舊走 idle
    await client.src.put(task_note("t2", "again"))
    await settle()
    check("之後的通知仍走 idle", any(isinstance(m, TaskNotificationMessage) for m in seen))
    await box.close()


async def test_user_turn_adopts_pending_wake() -> None:
    print("\n[使用者回合撞上待領的票就認養它]")
    box, client, fe, _seen, tickets = make_box(lambda *a: None)
    patch_pool(box)
    await client.src.put(raw_stream())
    await client.src.put(raw_assistant("我自己醒來講的"))
    await settle()
    ticket = tickets[0]
    async with box.claim() as inbox:       # 一般回合，沒帶票
        first = await inbox.get()
        check("認養了 wake 收件匣（讀到週期的第一則）", isinstance(first, StreamEvent),
              type(first).__name__)
        check("票立刻失效", not ticket.pending())
    consumed = False
    try:
        async with box.claim(ticket=ticket):
            pass
    except WakeConsumed:
        consumed = True
    check("再拿失效的票來領會被拒", consumed)
    n_before = len(fe.events)
    res = await runner_mod.run_turn("", make_state(), fe, "w-2", wake=ticket)
    check("run_turn 對失效票回 None", res is None)
    check("而且一個事件都不發", len(fe.events) == n_before, str(len(fe.events) - n_before))
    await box.close()


async def test_no_waker_falls_back_to_idle() -> None:
    print("\n[沒掛派工函式就退回 idle]")
    box, client, _fe, seen, tickets = make_box(False)
    await client.src.put(raw_stream())
    await settle()
    check("模型活動落到 idle", len(seen) == 1 and isinstance(seen[0], StreamEvent),
          str([type(m).__name__ for m in seen]))
    check("沒開收件匣", box._inbox is None and box._wake is None)
    check("沒發票", tickets == [])
    await box.close()


async def test_discard_closes_inbox() -> None:
    print("\n[作廢的票要把收件匣收掉，通知照樣結帳]")
    box, client, fe, seen, tickets = make_box(lambda *a: None)
    bg_notify.remember("test", "t3", "會被停掉的")
    await client.src.put(raw_stream())
    await client.src.put(task_note("t3", "", status="stopped"))   # 落在 wake 收件匣裡
    await settle()
    ticket = tickets[0]
    check("通知先進了 wake 收件匣", ticket.q.qsize() == 2, str(ticket.q.qsize()))
    await ticket.discard()
    check("收件匣收掉了", box._inbox is None and box._wake is None)
    check("票失效", not ticket.pending())
    check("裡面的通知仍然結了帳",
          any(t["id"] == "t3" and t["status"] == "stopped" for t in bg_notify.wire("test")),
          str(bg_notify.wire("test")))
    check("推播照發", len(fe.of("notify")) == 1)
    check("模型活動直接丟掉，沒進 idle",
          not any(isinstance(m, StreamEvent) for m in seen))
    check("再作廢一次無害", (await ticket.discard()) is None)
    # 收掉之後 pump 投來的東西回到正常規則：模型活動再開一張新票
    await client.src.put(raw_stream())
    await settle()
    check("之後的模型活動開新票", len(tickets) == 2 and tickets[1].pending())
    await box.close()


async def test_handle_wake_end_to_end() -> None:
    print("\n[handle_wake 走完整套收尾]")
    box, client, fe, _seen, tickets = make_box(lambda *a: None)
    patch_pool(box)
    bg_notify.remember("test", "t4", "跑審查")
    await client.src.put(task_note("t4", "三關全過"))
    await client.src.put(raw_stream())
    await client.src.put(raw_assistant("背景結果：三關全過，沒有要改的。[[DONE]]"))
    await client.src.put(raw_result("背景結果：三關全過，沒有要改的。[[DONE]]"))
    await settle()
    await turn_mod.handle_wake(tickets[0], make_state(), fe)
    finals = fe.of("reply.final")
    check("定稿了一則回覆", len(finals) == 1 and "三關全過" in finals[0].data["markdown"],
          str([f.data.get("markdown") for f in finals]))
    dones = fe.of("turn.done")
    check("發了 turn.done", len(dones) == 1)
    check("有說話就推播", bool(dones) and dones[0].data.get("notify") is True)
    check("沒送 prompt", client.queries == [])
    await box.close()

    print("\n[醒來卻沒說話：不推播、不定稿]")
    box, client, fe, _seen, tickets = make_box(lambda *a: None)
    patch_pool(box)
    await client.src.put(raw_stream())
    await client.src.put(raw_assistant(""))
    await client.src.put(raw_result(""))
    await settle()
    await turn_mod.handle_wake(tickets[0], make_state(), fe)
    check("沒有定稿", fe.of("reply.final") == [])
    dones = fe.of("turn.done")
    check("turn.done 照發但關掉推播",
          len(dones) == 1 and dones[0].data.get("notify") is False,
          str(dones[0].data if dones else None))
    check("沒有靠空回覆重試去戳 CLI", client.queries == [], str(client.queries))
    await box.close()


async def test_transport_queue() -> None:
    print("\n[transport：票排進同一條佇列]")
    from engine import worker as worker_mod
    from transport import app as app_mod

    CONV = "wtest"
    fe = CollectFrontend()
    gates: asyncio.Queue = asyncio.Queue()
    wakes: list = []
    prompts: list[str] = []

    async def fake_wake(ticket, state, frontend) -> None:
        wakes.append(ticket)
        await gates.get()

    async def fake_turn(text, state, frontend, src="") -> None:
        prompts.append(text)

    worker_mod.handle_wake = fake_wake
    worker_mod.handle_turn = fake_turn
    app_mod.worker.frontend_for = lambda cid: fe
    worker_mod.get_state = lambda cid: ConvState(conv_id=cid, cwd=Path.home())
    app_mod.state_mod.get_title = lambda cid: "已有標題"
    bg_notify._tasks.clear()
    bg_notify._recent_done.clear()
    bg_notify.set_waker(app_mod.worker.dispatch_wake)    # 正式接線
    client = FakeClient()

    async def idle(conv_id, frontend, msg) -> None:
        await bg_notify.handle(conv_id, frontend, msg)

    box = Mailbox(CONV, client, fe, idle, on_wake=bg_notify.on_wake)  # type: ignore[arg-type]
    box.start()
    await client.src.put(raw_stream())
    await settle()

    async def until(cond, timeout: float = 3.0) -> bool:
        loop = asyncio.get_running_loop()
        end = loop.time() + timeout
        while loop.time() < end:
            if cond():
                return True
            await asyncio.sleep(0.02)
        return cond()

    ok = await until(lambda: len(wakes) == 1)
    check("worker 接到票並開了 wake 回合", ok, str(len(wakes)))
    task = app_mod.worker.running.get(CONV)
    check("wake 回合登記在 running（插話與停止都認得它）",
          task is not None and not task.done())
    check("排隊清單不列 wake", app_mod.worker.pending_of(CONV) == [])

    await app_mod.send_message(CONV, {"text": "醒來的時候我插一句"}, "tok")
    ums = fe.of("user.message")
    check("wake 進行中送來的訊息標成排隊（假回合不接插話）",
          bool(ums) and ums[-1].data["queued"] is True, str(ums[-1].data if ums else None))
    pend = app_mod.worker.pending_of(CONV)
    check("排隊清單只有那則訊息", [p["text"] for p in pend] == ["醒來的時候我插一句"], str(pend))

    gates.put_nowait(None)                 # 放行 wake 回合
    ok = await until(lambda: prompts == ["醒來的時候我插一句"])
    check("wake 收工後排著的訊息接著跑", ok, str(prompts))
    ok = await until(lambda: CONV not in app_mod.worker.running)
    check("全部收工後 running 清空", ok)

    app_mod.worker.workers[CONV].cancel()
    app_mod.worker.queues.pop(CONV, None)
    app_mod.worker.workers.pop(CONV, None)
    bg_notify.set_waker(None)
    await box.close()


async def main() -> int:
    await test_activity_opens_wake()
    await test_wake_turn_reads_cycle()
    await test_user_turn_adopts_pending_wake()
    await test_no_waker_falls_back_to_idle()
    await test_discard_closes_inbox()
    await test_handle_wake_end_to_end()
    await test_transport_queue()
    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
