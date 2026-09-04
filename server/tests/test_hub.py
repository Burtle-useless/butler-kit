"""EventHub 續傳邏輯測試。

這些是 Phase 1 成功條件②的基礎：斷線續傳如果在這層就錯了，
上面接 HTTP 也不會對。刻意用小的 ring buffer 逼出淘汰與斷層路徑。
"""
from __future__ import annotations

import asyncio
import sys

import config  # noqa: F401
from protocol import make_event
from transport.hub import EventHub, SseFrontend

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def test_replay() -> None:
    print("\n[replay 續傳]")
    h = EventHub(size=5)
    evs = [make_event("main", "t1", "text.delta", d=str(i)) for i in range(3)]
    for e in evs:
        h.publish(e)

    got, gap = h.replay_from(None)
    check("after=None 不 replay（新連線只收之後的）", got == [] and not gap)

    got, gap = h.replay_from(evs[0].seq)
    check("從第一則之後續傳，補回 2 則", len(got) == 2 and not gap,
          f"got={[e.data['d'] for e in got]}")

    got, gap = h.replay_from(evs[-1].seq)
    check("已是最新 → 沒有要補的", got == [] and not gap)

    # 「比第一則還早的游標」在真實世界只有一種來源：服務重啟過，手機帶著
    # 上一世代的游標回來。先前這裡斷言「全部補回」，那正是使用者回報的
    # 「訊息回溯」——舊事件整批重播、前端沒有去重、於是對話自己倒帶。
    got, gap = h.replay_from(evs[0].seq - 1)
    check("上一世代的游標 → 一則都不補，交給 snapshot", got == [] and not gap,
          f"got={len(got)}")


async def test_stale_cursor_no_replay() -> None:
    """迴歸測試：服務重啟後不可以重播上一世代的事件。

    2026-08-17 使用者回報「訊息回溯」。這條盯住的是行為本身而不是實作：
    帶舊游標連上時，收到的第一則必須是 stream.reset，且**不含任何舊事件**。
    """
    print("\n[重啟後不重播舊世代]")
    h = EventHub(size=100)
    old = [make_event("main", "t1", "text.delta", d=f"舊{i}") for i in range(5)]
    for e in old:
        h.publish(e)

    stale = old[0].seq - 1          # 上一世代留下的游標
    check("認得出舊世代游標", h.is_stale_cursor(stale) is True)
    check("本世代的游標不會被誤判", h.is_stale_cursor(old[2].seq) is False)

    async def collect() -> list:
        """收 1 秒。stream 會先把該送的一次吐完，之後就掛在心跳上等，
        所以不必真的等滿一個 SSE_KEEPALIVE_SEC。"""
        out: list = []
        agen = h.stream(stale)

        async def pump() -> None:
            async for ev in agen:
                if ev is not None:
                    out.append(ev)

        try:
            await asyncio.wait_for(pump(), timeout=1.0)
        except asyncio.TimeoutError:
            pass
        finally:
            # 逾時是預期路徑（stream 吐完就掛在心跳上等），但被 wait_for 取消的
            # async generator 不會自己收尾，留到直譯器結束才 GC 會讓整支腳本
            # 以非零狀態退出——測試明明全過卻報失敗。
            await agen.aclose()
        return out

    got = await collect()
    check("第一則是 stream.reset", bool(got) and got[0].type == "stream.reset",
          f"got={[e.type for e in got]}")
    check("沒有任何舊事件被重播", all(e.type != "text.delta" for e in got),
          f"共 {len(got)} 則")


async def test_stale_before_first_publish() -> None:
    """迴歸測試：hub 剛建好、一則都還沒 publish，帶舊世代游標連上也要拿到 stream.reset。

    先前 `_boot_seq` 要等第一次 publish 才有值，重啟後搶先重連的裝置（SSE 重連比
    第一個回合快得多）既不算 stale、也拿不到 seq.gap，畫面就停在上一世代。
    """
    print("\n[還沒 publish 過就重連：也要 stream.reset]")
    old_cursor = make_event("main", "t0", "status").seq     # 「上一世代」最後看到的序號
    h = EventHub(size=10)
    check("建構後就認得出舊世代游標", h.is_stale_cursor(old_cursor) is True)
    got, gap = h.replay_from(old_cursor)
    check("不重播、不報斷層", got == [] and gap is False)

    out: list = []
    agen = h.stream(old_cursor)
    try:
        out.append(await asyncio.wait_for(agen.__anext__(), timeout=1.0))
    finally:
        await agen.aclose()
    check("第一則就是 stream.reset", bool(out) and out[0].type == "stream.reset",
          str([e.type for e in out]))
    # 本世代之後發的事件不會被誤判
    ev = make_event("main", "t1", "status")
    h.publish(ev)
    check("本世代的序號不算 stale", h.is_stale_cursor(ev.seq) is False)


def test_gap() -> None:
    print("\n[seq.gap 斷層偵測]")
    h = EventHub(size=3)
    evs = [make_event("main", "t1", "text.delta", d=str(i)) for i in range(6)]
    for e in evs:
        h.publish(e)   # buffer 只留最後 3 則，前 3 則被擠掉

    check("ring buffer 有上限", len(h._buf) == 3)
    got, gap = h.replay_from(evs[0].seq)
    check("要補的起點已被擠掉 → 回報斷層", gap is True)
    got, gap = h.replay_from(evs[3].seq)
    check("要補的起點還在 → 不算斷層", gap is False, f"got={len(got)}")


async def test_stream_live() -> None:
    print("\n[stream 即時推送]")
    h = EventHub(size=10)
    seen: list[str] = []

    async def consume() -> None:
        async for ev in h.stream(None):
            if ev is None:
                continue
            seen.append(ev.data.get("d", ev.type))
            if len(seen) >= 3:
                break

    task = asyncio.create_task(consume())
    await asyncio.sleep(0.05)
    for i in range(3):
        h.publish(make_event("main", "t1", "text.delta", d=f"live{i}"))
    await asyncio.wait_for(task, timeout=3)
    check("即時收到 3 則且順序正確", seen == ["live0", "live1", "live2"], f"seen={seen}")


async def test_no_lost_wakeup() -> None:
    """迴歸測試：yield 期間到達的事件不可以漏。

    真實災情：reply.final 緊跟在 turn.end 後幾毫秒 publish，落在消費者處理上一則
    事件的空檔，喚醒訊號被下一圈的 clear() 吃掉，那則事件要等滿 15 秒心跳才補送——
    使用者看到的是「回合跑完了但最終回覆never出現」。
    這裡的 timeout 3 秒遠小於心跳 15 秒，一旦漏喚醒就會逾時失敗。
    """
    print("\n[漏喚醒迴歸]")
    h = EventHub(size=50)
    seen: list[str] = []

    async def consume() -> None:
        async for ev in h.stream(None):
            if ev is None:
                continue
            seen.append(ev.data.get("d", ""))
            if len(seen) == 1:
                # 模擬「正在處理這則時，下一則剛好送達」
                h.publish(make_event("main", "t1", "text.delta", d="race"))
                await asyncio.sleep(0)
            if len(seen) >= 2:
                break

    t = asyncio.create_task(consume())
    await asyncio.sleep(0.05)
    h.publish(make_event("main", "t1", "text.delta", d="first"))
    try:
        await asyncio.wait_for(t, timeout=3)
        check("yield 期間到達的事件不會漏", seen == ["first", "race"], f"seen={seen}")
    except asyncio.TimeoutError:
        t.cancel()
        check("yield 期間到達的事件不會漏", False, "逾時＝喚醒訊號被吃掉了")


async def test_stream_resume_no_loss() -> None:
    print("\n[stream 斷線續傳不漏不重]")
    h = EventHub(size=100)
    first: list[int] = []

    async def phase1() -> None:
        async for ev in h.stream(None):
            if ev is None:
                continue
            first.append(ev.seq)
            if len(first) >= 2:
                break

    t = asyncio.create_task(phase1())
    await asyncio.sleep(0.05)
    for i in range(2):
        h.publish(make_event("main", "t1", "text.delta", d=f"a{i}"))
    await asyncio.wait_for(t, timeout=3)

    # 「斷線」期間繼續產生事件
    missed = [make_event("main", "t1", "text.delta", d=f"b{i}") for i in range(4)]
    for e in missed:
        h.publish(e)

    # 帶 Last-Event-ID 重連
    resumed: list[int] = []

    async def phase2() -> None:
        async for ev in h.stream(first[-1]):
            if ev is None:
                continue
            resumed.append(ev.seq)
            if len(resumed) >= 4:
                break

    await asyncio.wait_for(asyncio.create_task(phase2()), timeout=3)
    check("漏掉的 4 則全部補上", resumed == [e.seq for e in missed],
          f"resumed={len(resumed)}")
    check("沒有重複收到斷線前的事件", not set(resumed) & set(first))


async def test_ask_roundtrip() -> None:
    print("\n[SseFrontend ask 往返]")
    h = EventHub(size=10)
    fe = SseFrontend(h, "main")
    await fe.emit(make_event("main", "t9", "turn.start", prompt="x"))

    async def answer_later() -> None:
        await asyncio.sleep(0.05)
        ask_ev = next(e for e in h._buf if e.type == "ask.request")
        fe.resolve(ask_ev.data["ask_id"], "yes")

    from protocol import CONFIRM_CHOICES, AskRequest
    asyncio.create_task(answer_later())
    resp = await fe.ask(AskRequest(kind="confirm_destructive", title="t",
                                   raw="rm -rf /", choices=CONFIRM_CHOICES,
                                   timeout_sec=3))
    check("拿到答案", resp is not None and resp.choice_id == "yes")
    ask_ev = next(e for e in h._buf if e.type == "ask.request")
    check("ask.request 帶完整指令原文（未截斷）", ask_ev.data["raw"] == "rm -rf /")
    check("ask 解決後發出 ask.resolved", any(e.type == "ask.resolved" for e in h._buf))


async def test_ask_timeout() -> None:
    print("\n[SseFrontend ask 逾時 fail-closed]")
    h = EventHub(size=10)
    fe = SseFrontend(h, "main")
    from protocol import CONFIRM_CHOICES, AskRequest, is_approved
    resp = await fe.ask(AskRequest(kind="confirm_destructive", title="t",
                                   choices=CONFIRM_CHOICES, timeout_sec=0.2))
    check("逾時回 None", resp is None)
    check("None 一律不放行（fail-closed）", is_approved(resp) is False)


async def test_emit_never_raises() -> None:
    print("\n[emit 契約：永不拋例外]")

    class Broken(EventHub):
        def publish(self, ev):
            raise RuntimeError("模擬前端爆炸")

    fe = SseFrontend(Broken(), "main")
    try:
        await fe.emit(make_event("main", "t1", "status"))
        check("前端爆炸不會拖垮回合", True)
    except Exception as e:
        check("前端爆炸不會拖垮回合", False, repr(e))


async def main() -> int:
    test_replay()
    await test_stale_cursor_no_replay()
    await test_stale_before_first_publish()
    test_gap()
    await test_stream_live()
    await test_no_lost_wakeup()
    await test_stream_resume_no_loss()
    await test_ask_roundtrip()
    await test_ask_timeout()
    await test_emit_never_raises()
    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
