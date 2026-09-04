"""額度用盡後的自動續跑：訊息留著、等到回復時刻自動再跑一次。

使用者 2026-09-03：「額度恢復的自動續跑也看看能不能做」。機制在 engine.worker 的
Worker._run_messages：handle_turn 回 RATE_LIMIT 且帶 resets_at → 發一則 status
（phase=waiting）→ 等到那個時刻 → 同一則文字再跑一次（只等一次）。等待期間 `running`
指著等待那個 task，按停止停得掉。這裡用假的 handle_turn 釘住這幾件事，另外釘 parse_resets_at。
"""
from __future__ import annotations

import asyncio
import sys
import time
from datetime import datetime
from pathlib import Path

import config  # noqa: F401
from engine import worker as worker_mod
from engine.errors import CCError, parse_resets_at, reset_label
from engine.state import ConvState
from engine.worker import Worker
from transport import app as app_mod

FAILED: list[str] = []
CONV = "rltest"


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


async def until(cond, timeout: float = 5.0) -> bool:
    loop = asyncio.get_running_loop()
    end = loop.time() + timeout
    while loop.time() < end:
        if cond():
            return True
        await asyncio.sleep(0.02)
    return cond()


def test_parse_resets_at() -> None:
    print("\n[從限流文字解析回復時刻]")
    now = datetime(2026, 9, 2, 12, 23).timestamp()
    t = parse_resets_at("You've hit your session limit · resets 3:30pm (Asia/Taipei)", now)
    check("解析出當天 15:30", t is not None and datetime.fromtimestamp(t).strftime("%H:%M") == "15:30",
          str(t and datetime.fromtimestamp(t)))
    t2 = parse_resets_at("You've hit your session limit · resets 11am", now)
    check("時刻已過就算明天", t2 is not None and datetime.fromtimestamp(t2).day == 3
          and datetime.fromtimestamp(t2).hour == 11, str(t2 and datetime.fromtimestamp(t2)))
    t3 = parse_resets_at("You've hit your weekly limit · resets 12am", now)
    check("12am 是 0 點", t3 is not None and datetime.fromtimestamp(t3).hour == 0)
    check("沒有時刻就回 None", parse_resets_at("You've hit your limit", now) is None)
    check("文案帶時間", "15:30" in reset_label(t))
    err = CCError("RATE_LIMIT", "x", resets_at=time.time() + 600)
    check("user_msg 說會自動接著做", "自動接著做" in err.user_msg, err.user_msg)


async def test_auto_resume() -> None:
    print("\n[限流後自動續跑]")
    fe = FakeFE()
    calls: list[str] = []
    resets = time.time() + 0.4

    async def fake_turn(text, state, frontend, src="", atts=None) -> CCError | None:
        calls.append(text)
        if len(calls) == 1:
            return CCError("RATE_LIMIT", "limit", resets_at=resets)
        return None

    worker_mod.handle_turn = fake_turn
    app_mod.worker.frontend_for = lambda cid: fe
    worker_mod.get_state = lambda cid: ConvState(conv_id=cid, cwd=Path.home())
    app_mod.state_mod.get_title = lambda cid: "已有標題"
    # 等待固定多加 5 秒緩衝，測試把它縮短
    orig_sleep = asyncio.sleep

    async def short_sleep(sec):
        await orig_sleep(min(sec, 0.6))

    worker_mod.asyncio.sleep = short_sleep  # type: ignore[assignment]
    try:
        await app_mod.send_message(CONV, {"text": "幫我查一下"}, "tok")
        ok = await until(lambda: len(calls) == 1)
        check("第一次跑了", ok)
        ok = await until(lambda: bool(fe.of("status")), 2)
        notes = [e.data.get("note", "") for e in fe.of("status")]
        check("等待期間有說明", ok and any("自動繼續" in n for n in notes), str(notes))
        check("等待期間 running 有東西（停止停得掉）", CONV in app_mod.worker.running)
        ok = await until(lambda: len(calls) == 2, 3)
        check("回復後自動再跑同一則", ok and calls[1] == "幫我查一下", str(calls))
        ok = await until(lambda: CONV not in app_mod.worker.running, 2)
        check("跑完就閒下來", ok)
        # 第二次再撞就不等了（只等一次）
        calls.clear()

        async def always_limited(text, state, frontend, src="", atts=None) -> CCError | None:
            calls.append(text)
            return CCError("RATE_LIMIT", "limit", resets_at=time.time() + 0.3)

        worker_mod.handle_turn = always_limited
        await app_mod.send_message(CONV, {"text": "再一次"}, "tok")
        ok = await until(lambda: len(calls) == 2, 3)
        check("重跑一次還撞就停手", ok)
        await orig_sleep(0.8)
        check("不會第三次", len(calls) == 2, str(len(calls)))
    finally:
        worker_mod.asyncio.sleep = orig_sleep  # type: ignore[assignment]
        w = app_mod.worker.workers.pop(CONV, None)
        if w:
            w.cancel()
        app_mod.worker.queues.pop(CONV, None)


async def test_pending_persist() -> None:
    """限流等待中的訊息要落檔，重啟後排回佇列。"""
    print("\n[等待中重啟不丟訊息]")
    CONV = "rl-pending"
    # 自己開一個 Worker、用自己的落檔路徑，不碰服務那一份
    w = Worker(lambda cid: FakeFE(), pending_file=config.DATA_DIR / "pending_test.json")
    w._pending_save([])
    w._pending_remember(CONV, "等額度的那則", "phone")
    w._pending_remember(CONV, "同一對話只留最後一則", "phone")
    items = w._pending_load()
    check("落檔且同對話只留一則", len(items) == 1 and items[0]["text"] == "同一對話只留最後一則", str(items))
    w._pending_forget(CONV)
    check("跑完就清掉", w._pending_load() == [])

    # 模擬重啟：檔案裡有一則，啟動時要排回該對話的佇列
    w._pending_remember(CONV, "重啟前卡住的", "phone")
    q: asyncio.Queue = asyncio.Queue()
    # 只看它排進哪裡，不真的起 worker 去讀
    w.ensure = lambda conv: q  # type: ignore[method-assign]
    n = await w.restore_pending()
    check("啟動時排回一則", n == 1 and not q.empty())
    item = q.get_nowait()
    check("排回的是原文與來源", item[1] == "重啟前卡住的" and item[2] == "phone", str(item))
    check("排回後檔案清空", w._pending_load() == [])
    # 壞掉的檔不能讓服務起不來
    w.pending_file.write_text("{not json", encoding="utf-8")
    check("壞檔當成沒有", w._pending_load() == [])
    w._pending_save([])


async def main() -> int:
    test_parse_resets_at()
    await test_auto_resume()
    await test_pending_persist()
    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
