"""Worker 單元測試：不經 FastAPI，直接對 `engine.worker.Worker` 驗排隊語意。

transport 那幾支（test_queue_marks／test_worker_cancel／test_rate_limit_wait）走的是
HTTP 端點函式；這裡釘的是 cc-bot 會直接用的那組介面——submit／stop／remove／
dispatch_wake／restore_pending——與它們發出的事件。假 Frontend 收事件、假 handle_turn
不燒 API、不佔埠。

    <python> tests\\test_worker_unit.py
"""
from __future__ import annotations

import asyncio
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402  # 環境清洗要最先跑；跑在 tests/ 底下 DATA_DIR 自動是暫存目錄
from engine import runner as runner_mod  # noqa: E402
from engine import state as state_mod  # noqa: E402
from engine import worker as worker_mod  # noqa: E402
from engine.errors import CCError  # noqa: E402
from engine.state import ConvState  # noqa: E402
from engine.worker import Submitted, Worker  # noqa: E402

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

    async def ask(self, req):
        return None

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


def make_worker(fe: FakeFE, **kw) -> Worker:
    return Worker(lambda cid: fe, pending_file=config.DATA_DIR / "pending_unit.json", **kw)


async def test_submit_and_queue() -> None:
    print("\n[submit：閒著立刻跑、忙著就排隊、合併成一輪]", flush=True)
    fe = FakeFE()
    w = make_worker(fe)
    gate: asyncio.Queue = asyncio.Queue()
    prompts: list[str] = []
    srcs: list[str] = []

    async def fake_turn(text, state, frontend, src="", atts=None) -> None:
        prompts.append(text)
        srcs.append(src)
        await gate.get()

    worker_mod.handle_turn = fake_turn
    worker_mod.get_state = lambda cid: ConvState(conv_id=cid, cwd=Path.home())

    r1 = await w.submit("u1", "第一則", "Discord")
    check("回 Submitted", isinstance(r1, Submitted))
    check("閒著時不排隊也沒插話", r1.queued is False and r1.steered is False)
    ok = await until(lambda: prompts == ["第一則"])
    check("立刻被讀走", ok, str(prompts))
    check("來源原樣帶到回合", srcs == ["Discord"], str(srcs))
    check("is_running 看得到", w.is_running("u1"))
    ums = fe.of("user.message")
    check("回音事件帶 msg_id 且 queued=False",
          bool(ums) and ums[0].data["msg_id"] == r1.msg_id and ums[0].data["queued"] is False)
    taken = fe.of("message.taken")
    check("message.taken 指名那一則", bool(taken) and taken[0].data["msg_ids"] == [r1.msg_id])

    r2 = await w.submit("u1", "第二則", "Discord")
    r3 = await w.submit("u1", "第三則", "手機")
    check("忙碌時排隊", r2.queued is True and r3.queued is True)
    check("qsize 累計", r2.qsize == 1 and r3.qsize == 2, f"{r2.qsize} {r3.qsize}")
    check("pending_of 舊到新",
          [p["msg_id"] for p in w.pending_of("u1")] == [r2.msg_id, r3.msg_id])
    check("pending_of 只有 msg_id、text 與 attachments（來源不外流）",
          all(set(p) == {"msg_id", "text", "attachments"} for p in w.pending_of("u1")))
    check("別條對話什麼都沒有", w.pending_of("u2") == [] and not w.is_running("u2"))

    gate.put_nowait(None)                       # 放行第一輪
    ok = await until(lambda: len(prompts) == 2)
    check("排著的兩則合併成一輪", ok and "第二則" in prompts[1] and "第三則" in prompts[1],
          str(prompts))
    check("混批取最後一則的來源", srcs[1:] == ["手機"], str(srcs))
    check("合併時有說明", any("一起處理" in e.data.get("note", "") for e in fe.of("status")))
    gate.put_nowait(None)
    ok = await until(lambda: not w.is_running("u1"))
    check("跑完就閒下來", ok)
    await w.shutdown()


async def test_ensure() -> None:
    print("\n[ensure：同一條回同一個佇列、worker 死了會重建]", flush=True)
    w = make_worker(FakeFE())
    q = w.ensure("e1")
    check("同一條對話回同一個佇列", w.ensure("e1") is q)
    dead = w.workers["e1"]
    dead.cancel()
    await asyncio.wait({dead})
    w.ensure("e1")
    check("worker 死了 ensure 會換一個活的", w.workers["e1"] is not dead and not w.workers["e1"].done())
    await w.shutdown()


async def test_steer() -> None:
    print("\n[插話：回合進行中且佇列空才插]", flush=True)
    fe = FakeFE()
    w = make_worker(fe)
    gate: asyncio.Queue = asyncio.Queue()

    async def fake_turn(text, state, frontend, src="", atts=None) -> None:
        await gate.get()

    worker_mod.handle_turn = fake_turn
    steered: list[str] = []

    async def accept(conv_id: str, text: str) -> bool:
        steered.append(text)
        return True

    orig = runner_mod.try_steer
    runner_mod.try_steer = accept
    try:
        await w.submit("s1", "第一則", "手機")
        await until(lambda: w.is_running("s1"))
        r = await w.submit("s1", "插一句", "電腦")
        check("插進去了", r.steered is True and r.queued is False)
        check("插進去的有蓋時間戳與來源",
              len(steered) == 1 and steered[0].endswith(" 電腦] 插一句"), str(steered))
        check("插話過的不進佇列", w.pending_of("s1") == [])
        ums = fe.of("user.message")
        check("回音事件標 steered、不標排隊",
              ums[-1].data["steered"] is True and ums[-1].data["queued"] is False)

        calls: list[str] = []

        async def refuse(conv_id: str, text: str) -> bool:
            calls.append(text)
            return False

        runner_mod.try_steer = refuse
        r2 = await w.submit("s1", "插不進去的", "手機")
        check("插不進去就排隊", r2.steered is False and r2.queued is True and len(calls) == 1)
        r3 = await w.submit("s1", "後面的", "手機")
        check("佇列已有人排就不再嘗試插話", r3.queued is True and len(calls) == 1, str(calls))
    finally:
        runner_mod.try_steer = orig
    gate.put_nowait(None)
    gate.put_nowait(None)
    await until(lambda: not w.is_running("s1"))
    await w.shutdown()


async def test_stop() -> None:
    print("\n[stop：停這一輪、排著的指名 dropped、worker 活著]", flush=True)
    fe = FakeFE()
    w = make_worker(fe)
    gate: asyncio.Queue = asyncio.Queue()
    prompts: list[str] = []

    async def fake_turn(text, state, frontend, src="", atts=None) -> None:
        prompts.append(text)
        await gate.get()

    worker_mod.handle_turn = fake_turn
    check("沒東西在跑時 stop 回 False", w.stop("t1") is False)
    await w.submit("t1", "第一則", "手機")
    await until(lambda: w.is_running("t1"))
    r2 = await w.submit("t1", "第二則", "手機")
    r3 = await w.submit("t1", "第三則", "手機")
    check("stop 回 True", w.stop("t1") is True)
    ok = await until(lambda: bool(fe.of("error")))
    err = fe.of("error")[0].data if ok else {}
    check("發了 STOPPED", err.get("kind") == "STOPPED" and err.get("retryable") is False, str(err))
    check("交代排著的也取消了", "2 則" in err.get("detail", ""), err.get("detail", ""))
    dropped = fe.of("message.dropped")
    check("指名排著的那兩則",
          bool(dropped) and dropped[0].data["msg_ids"] == [r2.msg_id, r3.msg_id])
    check("佇列清空", w.pending_of("t1") == [])
    check("閒下來了", await until(lambda: not w.is_running("t1")))
    check("worker 還活著", not w.workers["t1"].done())
    await w.submit("t1", "第四則", "手機")
    ok = await until(lambda: prompts[-1:] == ["第四則"])
    check("下一則照常跑", ok, str(prompts))
    gate.put_nowait(None)
    await until(lambda: not w.is_running("t1"))
    await w.shutdown()


async def test_wake() -> None:
    print("\n[wake：自成一批，後面的訊息不混進去；停止時票作廢]", flush=True)
    fe = FakeFE()
    w = make_worker(fe)
    gate: asyncio.Queue = asyncio.Queue()
    wgate: asyncio.Queue = asyncio.Queue()
    prompts: list[str] = []
    wakes: list = []

    async def fake_turn(text, state, frontend, src="", atts=None) -> None:
        prompts.append(text)
        await gate.get()

    async def fake_wake(ticket, state, frontend) -> None:
        wakes.append(ticket)
        await wgate.get()

    worker_mod.handle_turn = fake_turn
    worker_mod.handle_wake = fake_wake

    await w.submit("w1", "第一則", "手機")
    await until(lambda: w.is_running("w1"))
    t = FakeTicket()
    w.dispatch_wake("w1", None, t)
    r = await w.submit("w1", "醒來之後的", "手機")
    check("wake 後面的訊息標排隊", r.queued is True)
    check("pending_of 不列 wake", [p["text"] for p in w.pending_of("w1")] == ["醒來之後的"])
    gate.put_nowait(None)                       # 放行第一輪
    ok = await until(lambda: len(wakes) == 1)
    check("wake 回合接手了那張票", ok and wakes[0] is t)
    check("wake 回合登記在 running", w.is_running("w1"))
    check("訊息還留在佇列（沒被拉進同一批）",
          [p["text"] for p in w.pending_of("w1")] == ["醒來之後的"])
    wgate.put_nowait(None)                      # 放行 wake
    ok = await until(lambda: "醒來之後的" in prompts)
    check("wake 收工後訊息接著跑", ok, str(prompts))
    gate.put_nowait(None)
    await until(lambda: not w.is_running("w1"))

    await w.submit("w2", "卡住", "手機")
    await until(lambda: w.is_running("w2"))
    t2 = FakeTicket()
    w.dispatch_wake("w2", None, t2)
    w.stop("w2")
    ok = await until(lambda: t2.discarded)
    check("停止時排著的 wake 票作廢（收件匣不留給下一個回合認養）", ok)
    check("沒有人打過的話就不報 dropped",
          all(e.data["msg_ids"] for e in fe.of("message.dropped")) and
          not any("w2" == e.conv_id for e in fe.of("message.dropped")))
    await w.shutdown()


async def test_rate_limit_and_restore() -> None:
    print("\n[限流：等到回復時刻再跑一次，等待期間落檔]", flush=True)
    fe = FakeFE()
    w = make_worker(fe)
    calls: list[str] = []
    resets = time.time() + 0.4

    async def fake_turn(text, state, frontend, src="", atts=None) -> CCError | None:
        calls.append(text)
        if len(calls) == 1:
            return CCError("RATE_LIMIT", "limit", resets_at=resets)
        return None

    worker_mod.handle_turn = fake_turn
    # 等待固定多加 5 秒緩衝，測試把它縮短
    orig_sleep = asyncio.sleep

    async def short_sleep(sec):
        await orig_sleep(min(sec, 0.6))

    worker_mod.asyncio.sleep = short_sleep  # type: ignore[assignment]
    try:
        await w.submit("r1", "幫我查", "手機")
        ok = await until(lambda: any(e.data.get("phase") == "waiting" for e in fe.of("status")), 2)
        check("等待中有 status（phase=waiting）", ok)
        check("等待期間算在跑（停止停得掉）", w.is_running("r1"))
        items = w._pending_load()
        check("等待期間已落檔（原文與來源）",
              len(items) == 1 and items[0]["conv"] == "r1" and items[0]["text"] == "幫我查"
              and items[0]["src"] == "手機", str(items))
        ok = await until(lambda: len(calls) == 2, 3)
        check("回復後自動再跑同一則", ok and calls[1] == "幫我查", str(calls))
        ok = await until(lambda: not w.is_running("r1"), 2)
        check("跑完閒下來", ok)
        check("跑完檔案清掉", w._pending_load() == [])
    finally:
        worker_mod.asyncio.sleep = orig_sleep  # type: ignore[assignment]
    await w.shutdown()

    print("\n[restore_pending：重啟後排回佇列]", flush=True)
    fe2 = FakeFE()
    w2 = make_worker(fe2)
    w2._pending_remember("r2", "重啟前卡住的", "Discord")
    w2._pending_remember("r2", "同一對話只留最後一則", "Discord")
    ran: list[tuple[str, str]] = []

    async def record(text, state, frontend, src="", atts=None) -> None:
        ran.append((text, src))

    worker_mod.handle_turn = record
    n = await w2.restore_pending()
    check("排回一則（同對話只留最後一則）", n == 1, str(n))
    ok = await until(lambda: ran == [("同一對話只留最後一則", "Discord")])
    check("排回的訊息真的跑了、來源照舊", ok, str(ran))
    check("排回後檔案清空", w2._pending_load() == [])
    check("沒東西時回 0", (await w2.restore_pending()) == 0)
    await w2.shutdown()


async def test_autoname_callback() -> None:
    print("\n[autoname：回合結束後叫一次、有標題就不叫、沒給就不碰標題]", flush=True)
    fe = FakeFE()
    named: list[tuple[str, str]] = []

    async def autoname(conv_id: str, text: str) -> None:
        named.append((conv_id, text))

    w = make_worker(fe, autoname=autoname)
    ran: list[str] = []

    async def fake_turn(text, state, frontend, src="", atts=None) -> None:
        ran.append(text)

    worker_mod.handle_turn = fake_turn
    orig_get_title = state_mod.get_title
    state_mod.get_title = lambda cid: None
    try:
        await w.submit("n1", "第一則", "手機")
        ok = await until(lambda: named == [("n1", "第一則")])
        check("回合結束後叫了 autoname（帶第一則原文）", ok, str(named))
        state_mod.get_title = lambda cid: "已有標題"
        await w.submit("n1", "第二則", "手機")
        await until(lambda: len(ran) == 2)
        await asyncio.sleep(0.05)
        check("已有標題就不叫", len(named) == 1, str(named))
    finally:
        state_mod.get_title = orig_get_title
    await w.shutdown()

    w2 = make_worker(fe)
    looked: list[str] = []
    ran.clear()
    state_mod.get_title = lambda cid: looked.append(cid)
    try:
        await w2.submit("n2", "x", "手機")
        await until(lambda: ran == ["x"])
        check("沒給 autoname 就連標題都不查", looked == [], str(looked))
    finally:
        state_mod.get_title = orig_get_title
    await w2.shutdown()


async def test_remove_and_shutdown() -> None:
    print("\n[remove：worker 直接結束、不發 STOPPED；shutdown 收掉全部]", flush=True)
    fe = FakeFE()
    w = make_worker(fe)
    gate: asyncio.Queue = asyncio.Queue()

    async def fake_turn(text, state, frontend, src="", atts=None) -> None:
        await gate.get()

    worker_mod.handle_turn = fake_turn
    await w.submit("d1", "第一則", "手機")
    await until(lambda: w.is_running("d1"))
    await w.submit("d1", "排著的", "手機")
    task = w.workers["d1"]
    before = len(fe.of("error"))
    await w.remove("d1")
    check("worker 結束了、而且是被取消", task.done() and task.cancelled())
    check("沒發 STOPPED", len(fe.of("error")) == before)
    check("沒發 dropped", fe.of("message.dropped") == [])
    check("登記表清掉", "d1" not in w.workers and "d1" not in w.queues and not w.is_running("d1"))
    check("再 remove 一次無害", (await w.remove("d1")) is None)

    await w.submit("d2", "a", "手機")
    await w.submit("d3", "b", "手機")
    await until(lambda: w.is_running("d2") and w.is_running("d3"))
    tasks = list(w.workers.values())
    await w.shutdown()
    check("shutdown 後所有 worker 都結束", all(t.done() for t in tasks), str(len(tasks)))
    check("再 shutdown 一次無害", (await w.shutdown()) is None)


async def main() -> int:
    await test_submit_and_queue()
    await test_ensure()
    await test_steer()
    await test_stop()
    await test_wake()
    await test_rate_limit_and_restore()
    await test_autoname_callback()
    await test_remove_and_shutdown()
    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
