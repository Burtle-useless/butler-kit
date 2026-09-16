"""client_pool.acquire：指紋變了但有背景工作在跑，先不重建。

重建＝殺進程＝那些背景工作陪葬（drop 會順手把帳結成 orphan）。改模型、改 cwd
這種事不急在這一回合，沿用舊連線把話講完，背景工作結束後下一次 acquire 自然重建。

假的 ClaudeSDKClient／build_options／models.refresh_from，不開任何進程。
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path
from types import SimpleNamespace

import config  # noqa: F401
from engine import bg_notify
from engine import client_pool as pool
from engine.mailbox import Mailbox
from engine.state import ConvState

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}", flush=True)
    if not cond:
        FAILED.append(name)


class _FakeQuery:
    async def receive_messages(self):
        await asyncio.Event().wait()     # 永遠不吐訊息，pump 就一直活著
        yield {}


class FakeSDKClient:
    made: list["FakeSDKClient"] = []

    def __init__(self, options) -> None:
        self.options = options
        self._query = _FakeQuery()
        self.disconnected = False
        FakeSDKClient.made.append(self)

    async def connect(self) -> None:
        pass

    async def disconnect(self) -> None:
        self.disconnected = True


class FakeFE:
    async def emit(self, ev) -> None:
        pass

    async def ask(self, req):
        return None


async def noop_idle(conv_id, frontend, msg) -> None:
    pass


async def main() -> int:
    CONV = "pooltest"
    pool.ClaudeSDKClient = FakeSDKClient  # type: ignore[assignment]
    pool.build_options = lambda state, frontend: SimpleNamespace(resume=None)  # type: ignore[assignment]

    async def fake_refresh(c) -> None:
        pass
    pool.models.refresh_from = fake_refresh  # type: ignore[assignment]
    bg_notify._tasks.clear()
    bg_notify._recent_done.clear()

    st = ConvState(conv_id=CONV, cwd=Path.home())
    fe = FakeFE()

    print("\n[首次 acquire 建連線]", flush=True)
    box1 = await pool.acquire(st, fe)
    check("建了一個 client", len(FakeSDKClient.made) == 1)
    check("池裡登記了", pool.peek(CONV) is box1)

    print("\n[指紋沒變：拿到同一個]", flush=True)
    box2 = await pool.acquire(st, fe)
    check("同一個 box", box2 is box1)
    check("沒多建", len(FakeSDKClient.made) == 1)

    print("\n[指紋變了、但有背景工作在跑：沿用舊連線]", flush=True)
    bg_notify.remember(CONV, "task-1", "一件跑很久的事")
    check("帳上有一件在跑", len(bg_notify.active(CONV)) == 1)
    st.model = "claude-opus-5"          # 指紋變了
    check("指紋確實不同", pool._sigs[CONV] != pool.client_sig(st))
    box3 = await pool.acquire(st, fe)
    check("拿到的還是舊 box", box3 is box1)
    check("沒重建", len(FakeSDKClient.made) == 1)
    check("舊進程沒被關", FakeSDKClient.made[0].disconnected is False)
    check("背景工作的帳沒被結掉（沒 orphan）", len(bg_notify.active(CONV)) == 1)
    check("指紋登記仍是舊的（下次還會再判一次）", pool._sigs[CONV] != pool.client_sig(st))

    print("\n[背景工作跑完：下一次 acquire 才重建]", flush=True)
    bg_notify.finish(CONV, "task-1", "completed", "", "")
    check("帳上沒有在跑的了", bg_notify.active(CONV) == [])
    box4 = await pool.acquire(st, fe)
    check("這次重建了", box4 is not box1 and len(FakeSDKClient.made) == 2)
    check("舊進程關掉了", FakeSDKClient.made[0].disconnected is True)
    check("指紋更新成新的", pool._sigs[CONV] == pool.client_sig(st))

    await pool.drop(CONV)
    bg_notify._tasks.clear()
    bg_notify._recent_done.clear()

    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
