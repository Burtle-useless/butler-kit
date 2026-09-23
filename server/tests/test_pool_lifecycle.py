"""連線的生命週期：關閉必叫醒讀者、收尾不怕被取消、忙碌的連線不淘汰不回收。

兩個會殺掉在忙的連線的洞：
  - 閒置回收與 LRU 淘汰只看「上次取用」，長回合、背景工作、第 4 條對話都會殺掉在忙的連線；
  - Mailbox.close() 取消讀取者時什麼都不投遞，正在收件的回合永遠等不到東西（殭屍回合）。
另外 SDK 的 close() 在呼叫端被取消時會跳過 terminate／kill，CLI 行程因此留下來。
全部用假 client，不開任何進程。
"""
from __future__ import annotations

import asyncio
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402
from claude_agent_sdk import CLIConnectionError  # noqa: E402

from engine import bg_notify  # noqa: E402
from engine import client_pool as pool  # noqa: E402
from engine.mailbox import Mailbox  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}", flush=True)
    if not cond:
        FAILED.append(name)


class _FakeQuery:
    def __init__(self) -> None:
        self.src: asyncio.Queue = asyncio.Queue()

    async def receive_messages(self):
        while True:
            yield await self.src.get()


class FakeClient:
    def __init__(self, disconnect_delay: float = 0.0) -> None:
        self._query = _FakeQuery()
        self.disconnected = False
        self._delay = disconnect_delay

    async def disconnect(self) -> None:
        await asyncio.sleep(self._delay)
        self.disconnected = True


class FakeFE:
    async def emit(self, ev: Any) -> None:
        pass

    async def ask(self, req: Any) -> None:
        return None


async def noop_idle(conv_id: str, frontend: Any, msg: Any) -> None:
    pass


def make_box(cid: str, delay: float = 0.0) -> Mailbox:
    box = Mailbox(cid, FakeClient(delay), FakeFE(), noop_idle)
    box.start()
    return box


async def test_close_wakes_reader() -> None:
    print("\n[關閉會叫醒正在收件的回合]")
    box = make_box("c1")
    got: list[BaseException] = []

    async def turn() -> None:
        async with box.claim() as inbox:
            try:
                await inbox.get()
            except BaseException as e:  # noqa: BLE001
                got.append(e)

    t = asyncio.create_task(turn())
    await asyncio.sleep(0.05)
    check("收件中算忙", box.busy())
    await box.close()
    await asyncio.wait({t}, timeout=2)
    check("回合在兩秒內醒來", t.done())
    check("拿到的是連線錯誤（走既有的善後）", bool(got) and isinstance(got[0], CLIConnectionError),
          repr(got[:1]))
    check("回合結束後不算忙", not box.busy())
    check("client 有 disconnect", box.client.disconnected)


async def test_close_survives_cancel() -> None:
    print("\n[呼叫端被取消，收尾照樣跑完]")
    box = make_box("c2", delay=0.3)

    async def closer() -> None:
        await box.close()

    t = asyncio.create_task(closer())
    await asyncio.sleep(0.05)
    t.cancel()                    # 例如使用者按停止，drop 跑在被取消的回合收尾裡
    try:
        await t
    except asyncio.CancelledError:
        pass
    check("取消照常傳給呼叫端", t.cancelled())
    await asyncio.sleep(0.5)
    check("disconnect 仍然完成", box.client.disconnected)


async def test_pool_busy_rules() -> None:
    print("\n[淘汰與閒置回收跳過忙碌的連線]")
    pool._clients.clear()
    pool._used.clear()
    now = time.monotonic()
    a, b, c = make_box("a"), make_box("b"), make_box("c")
    for cid, box in (("a", a), ("b", b), ("c", c)):
        pool._clients[cid] = box
        pool._used[cid] = now - 5000       # 很久以前取用過
        box.last_activity = now - 5000
    # a：有回合在收件；b：有背景工作在跑；c：真的閒著
    claim = a.claim()
    await claim.__aenter__()
    bg_notify.remember("b", "t1", "跑很久的腳本")
    try:
        check("LRU 只挑閒著的", pool.pick_victim() == "c", str(pool.pick_victim()))
        gone = await pool.reap_idle()
        check("回收只收閒著的", gone == ["c"], str(gone))
        check("在忙的還在池子裡", set(pool._clients) == {"a", "b"})
        check("全都在忙時不挑任何人", pool.pick_victim() is None)
        # 取用很久以前、但剛剛還有訊息進來：不算閒置
        b_act = make_box("d")
        pool._clients["d"] = b_act
        pool._used["d"] = now - 5000
        b_act.last_activity = time.monotonic()
        check("剛有動靜的不回收", "d" not in await pool.reap_idle())
    finally:
        await claim.__aexit__(None, None, None)
        bg_notify.finish("b", "t1", "completed")
        for cid in list(pool._clients):
            await pool.drop(cid)


async def test_reaper_survives_errors() -> None:
    print("\n[回收器單輪出錯不會整個死掉]")
    calls = 0
    real = pool.reap_idle

    async def boom(now: float | None = None) -> list[str]:
        nonlocal calls
        calls += 1
        raise RuntimeError("測試：這一輪出錯")

    pool.reap_idle = boom          # type: ignore[assignment]
    old = pool.REAP_INTERVAL_SEC
    pool.REAP_INTERVAL_SEC = 0.02
    try:
        t = asyncio.create_task(pool.reaper())
        await asyncio.sleep(0.15)
        check("出錯後還在巡", calls >= 3 and not t.done(), f"calls={calls}")
        t.cancel()
    finally:
        pool.reap_idle = real      # type: ignore[assignment]
        pool.REAP_INTERVAL_SEC = old


async def main() -> int:
    await test_close_wakes_reader()
    await test_close_survives_cancel()
    await test_pool_busy_rules()
    await test_reaper_survives_errors()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
