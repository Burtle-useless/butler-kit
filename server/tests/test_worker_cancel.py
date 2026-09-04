"""worker 的取消語意：停這一輪 vs 停 worker、worker 死了要重建、wake 不跟訊息混批。

  (a) delete_conv 在回合進行中 cancel worker：worker 要直接結束，不對著已 pop 的
      frontend 發 STOPPED，也不回去等孤兒佇列。/stop 只取消 `worker.running[conv]`：
      worker 活著、發 STOPPED、繼續讀下一批。
  (b) worker 因例外死掉後，Worker.ensure 要重建，否則之後的訊息永遠沒人讀。
  (c) 佇列裡 [wake, 訊息]：wake 自成一批。停掉 wake 回合時，那則訊息不能無聲消失
      ——它還在佇列裡，要嘛照常跑，要嘛被指名 dropped。

假的 handle_turn／handle_wake，不燒 API、不佔埠（同 test_queue_marks 的做法）。
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path

import config  # noqa: F401
from engine import worker as worker_mod
from engine.state import ConvState
from transport import app as app_mod

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}", flush=True)
    if not cond:
        FAILED.append(name)


class FakeFE:
    def __init__(self) -> None:
        self.events: list = []

    async def emit(self, ev) -> None:
        self.events.append(ev)

    def of(self, t: str) -> list:
        return [e for e in self.events if e.type == t]


class FakeTicket:
    """假票：只要能被 discard、能記下有沒有被丟就夠。"""

    def __init__(self) -> None:
        self.discarded = False

    def pending(self) -> bool:
        return not self.discarded

    async def discard(self) -> None:
        self.discarded = True


async def until(cond, timeout: float = 3.0) -> bool:
    loop = asyncio.get_running_loop()
    end = loop.time() + timeout
    while loop.time() < end:
        if cond():
            return True
        await asyncio.sleep(0.02)
    return cond()


def cleanup(conv: str) -> None:
    w = app_mod.worker.workers.pop(conv, None)
    if w is not None:
        w.cancel()
    app_mod.worker.queues.pop(conv, None)
    app_mod.worker.running.pop(conv, None)


async def test_delete_conv_stops_worker() -> None:
    print("\n[(a) delete_conv：worker 直接結束，不發 STOPPED]", flush=True)
    CONV = "wc-a"
    fe = FakeFE()
    gate: asyncio.Queue = asyncio.Queue()
    app_mod.worker.frontend_for = lambda cid: fe
    worker_mod.get_state = lambda cid: ConvState(conv_id=cid, cwd=Path.home())
    app_mod.state_mod.get_title = lambda cid: "有標題"
    app_mod.state_mod.delete_conversation = lambda cid: True

    async def fake_turn(text, state, frontend, src="") -> None:
        await gate.get()
    worker_mod.handle_turn = fake_turn

    async def fake_drop(cid) -> None:
        pass
    app_mod.client_pool.drop = fake_drop

    await app_mod.send_message(CONV, {"text": "第一則"}, "tok")
    ok = await until(lambda: CONV in app_mod.worker.running)
    check("回合在跑", ok)
    worker = app_mod.worker.workers[CONV]
    before = len(fe.of("error"))
    await app_mod.delete_conv(CONV, "tok")
    ok = await until(lambda: worker.done())
    check("worker 結束了", ok)
    check("worker 是被取消而不是掛在迴圈裡", worker.cancelled())
    check("沒有對著已 pop 的 frontend 發 STOPPED",
          len(fe.of("error")) == before, str([e.data for e in fe.of("error")]))
    check("佇列與 worker 登記都清掉", CONV not in app_mod.worker.queues and CONV not in app_mod.worker.workers)
    cleanup(CONV)


async def test_stop_keeps_worker() -> None:
    print("\n[(a) /stop：只停這一輪，worker 活著繼續讀下一批]", flush=True)
    CONV = "wc-a2"
    fe = FakeFE()
    gate: asyncio.Queue = asyncio.Queue()
    prompts: list[str] = []
    app_mod.worker.frontend_for = lambda cid: fe
    worker_mod.get_state = lambda cid: ConvState(conv_id=cid, cwd=Path.home())
    app_mod.state_mod.get_title = lambda cid: "有標題"

    async def fake_turn(text, state, frontend, src="") -> None:
        prompts.append(text)
        await gate.get()
    worker_mod.handle_turn = fake_turn

    await app_mod.send_message(CONV, {"text": "第一則"}, "tok")
    await until(lambda: CONV in app_mod.worker.running)
    worker = app_mod.worker.workers[CONV]
    res = await app_mod.stop(CONV, "tok")
    check("stop 回 ok", res.get("ok") is True)
    ok = await until(lambda: bool(fe.of("error")))
    check("發了 STOPPED", ok and fe.of("error")[0].data.get("kind") == "STOPPED")
    check("worker 還活著", not worker.done())
    await app_mod.send_message(CONV, {"text": "第二則"}, "tok")
    ok = await until(lambda: prompts[-1:] == ["第二則"])
    check("下一則照常被讀走", ok, str(prompts))
    gate.put_nowait(None)
    cleanup(CONV)


async def test_dead_worker_rebuilt() -> None:
    print("\n[(b) worker 死了要重建]", flush=True)
    CONV = "wc-b"
    fe = FakeFE()
    prompts: list[str] = []
    app_mod.worker.frontend_for = lambda cid: fe
    worker_mod.get_state = lambda cid: ConvState(conv_id=cid, cwd=Path.home())
    app_mod.state_mod.get_title = lambda cid: "有標題"
    boom = [True]

    async def fake_turn(text, state, frontend, src="") -> None:
        prompts.append(text)
        if boom[0]:
            boom[0] = False
            raise RuntimeError("handle_turn 之外的漏網例外")
    worker_mod.handle_turn = fake_turn

    await app_mod.send_message(CONV, {"text": "第一則"}, "tok")
    w1 = app_mod.worker.workers[CONV]
    ok = await until(lambda: w1.done())
    check("worker 因例外死掉（模擬）", ok and not w1.cancelled() and w1.exception() is not None)
    await app_mod.send_message(CONV, {"text": "第二則"}, "tok")
    w2 = app_mod.worker.workers[CONV]
    check("Worker.ensure 換了一個新的 worker", w2 is not w1 and not w2.done())
    ok = await until(lambda: "第二則" in prompts)
    check("第二則有人讀", ok, str(prompts))
    cleanup(CONV)


async def test_wake_not_batched_with_messages() -> None:
    print("\n[(c) wake 自成一批：後面的訊息不會無聲消失]", flush=True)
    CONV = "wc-c"
    fe = FakeFE()
    gate: asyncio.Queue = asyncio.Queue()
    prompts: list[str] = []
    wakes: list = []
    app_mod.worker.frontend_for = lambda cid: fe
    worker_mod.get_state = lambda cid: ConvState(conv_id=cid, cwd=Path.home())
    app_mod.state_mod.get_title = lambda cid: "有標題"

    async def fake_wake(ticket, state, frontend) -> None:
        wakes.append(ticket)
        await gate.get()

    async def fake_turn(text, state, frontend, src="") -> None:
        prompts.append(text)
    worker_mod.handle_wake = fake_wake
    worker_mod.handle_turn = fake_turn

    # 先把 worker 卡在一個回合上，才能讓 wake 與訊息同時排著
    blocker: asyncio.Queue = asyncio.Queue()

    async def first_turn(text, state, frontend, src="") -> None:
        prompts.append(text)
        await blocker.get()
    worker_mod.handle_turn = first_turn
    await app_mod.send_message(CONV, {"text": "第一則"}, "tok")
    await until(lambda: CONV in app_mod.worker.running)
    worker_mod.handle_turn = fake_turn
    # 排入 [wake, 訊息]
    ticket = FakeTicket()
    app_mod.worker.dispatch_wake(CONV, None, ticket)  # type: ignore[arg-type]
    await app_mod.send_message(CONV, {"text": "醒來之後的訊息"}, "tok")
    q = app_mod.worker.queues[CONV]
    check("佇列裡是 [wake, 訊息]", q.qsize() == 2)

    blocker.put_nowait(None)                     # 放行第一輪
    ok = await until(lambda: len(wakes) == 1)
    check("wake 被接了", ok)
    check("訊息還留在佇列裡（沒被拉進同一批）",
          [p["text"] for p in app_mod.worker.pending_of(CONV)] == ["醒來之後的訊息"],
          str(app_mod.worker.pending_of(CONV)))
    # 停掉 wake 回合
    app_mod.worker.running[CONV].cancel()
    ok = await until(lambda: bool(fe.of("error")))
    check("發了 STOPPED", ok)
    dropped = [mid for e in fe.of("message.dropped") for mid in e.data["msg_ids"]]
    ums = fe.of("user.message")
    msg_id = ums[-1].data["msg_id"]
    check("那則訊息被指名 dropped，不是無聲消失", msg_id in dropped, str(dropped))
    check("它沒有被當成已處理", "醒來之後的訊息" not in "".join(prompts), str(prompts))
    check("worker 還活著", not app_mod.worker.workers[CONV].done())
    cleanup(CONV)

    print("\n[(c) 不停的話：wake 收工後訊息接著跑]", flush=True)
    CONV = "wc-c2"
    fe = FakeFE()
    prompts.clear()
    wakes.clear()
    worker_mod.handle_turn = first_turn
    await app_mod.send_message(CONV, {"text": "第一則"}, "tok")
    await until(lambda: CONV in app_mod.worker.running)
    worker_mod.handle_turn = fake_turn
    app_mod.worker.dispatch_wake(CONV, None, FakeTicket())  # type: ignore[arg-type]
    await app_mod.send_message(CONV, {"text": "醒來之後的訊息"}, "tok")
    blocker.put_nowait(None)
    await until(lambda: len(wakes) == 1)
    gate.put_nowait(None)                        # 放行 wake
    ok = await until(lambda: "醒來之後的訊息" in "".join(prompts))
    check("wake 收工後那則訊息照常跑", ok, str(prompts))
    cleanup(CONV)


async def main() -> int:
    await test_delete_conv_stops_worker()
    await test_stop_keeps_worker()
    await test_dead_worker_rebuilt()
    await test_wake_not_batched_with_messages()
    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
