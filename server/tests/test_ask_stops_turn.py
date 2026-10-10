"""打了 `[[ASK:]]` 就停在那裡等回答，不要繼續往下做。

prompt 早就這樣要求了，但那是請求不是保證：實際發生過問完之後模型自己繼續做
別的事，工具一行行往下推，選項卡被推到看不見的地方，使用者根本不知道有東西
在等。這裡在 runner 補上硬擋。

釘住的行為：
  1. 某一步的回覆裡出現 `[[ASK:問題|A|B]]` → 立刻 interrupt，CLI 收得了尾就正常
     收工（reply.final 照發、選項按鈕照出），**不走逾時那條 drop 的路**。
  2. 沒有標記 → 一次都不 interrupt（不能因為這條修正就讓正常回合被打斷）。
  3. 標記不完整（只有問題、沒有選項）→ 不算，那種標記本來就不會變成按鈕，
     停下來只會讓使用者卡在一個點不下去的畫面前。
  4. interrupt 收不掉 → 不重複打斷、不卡死，交回原本的閒置逾時處理。

假 client／假收件匣，不燒 API。逾時常數縮到毫秒級。
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from test_runner_reliability import (  # noqa: E402
    FAILED, SilentFrontend, assistant_msg, check, fast_timeouts, install,
    make_state, result_msg,
)

from claude_agent_sdk import ResultMessage  # noqa: E402

from engine import runner as runner_mod  # noqa: E402
from engine.mailbox import MailboxClosed  # noqa: E402

ASK = "要選哪一個？[[ASK:選一個|甲|乙]]"


async def test_ask_interrupts_turn() -> None:
    print("\n[打了 ASK 標記 → 立刻收工]")
    # 收件匣只有這一則，之後沒東西——模擬模型問完還想繼續跑。
    # interrupt 時 CLI 收出 Result 並關閉串流，讀取任務自然走完。
    client, inbox, drops = install(
        [assistant_msg(ASK)],
        on_interrupt=[result_msg(ASK), MailboxClosed()],
    )
    with fast_timeouts(idle=30, tool=30):
        res = await asyncio.wait_for(
            runner_mod.run_turn("幫我決定", make_state(), SilentFrontend(), "ask-1"),
            timeout=5)
    check("有 interrupt", client.interrupts == 1, str(client.interrupts))
    check("只打斷一次", client.interrupts == 1, str(client.interrupts))
    check("正常收工，不是逾時 drop", drops == [], str(drops))
    check("回覆折得出來", res is not None, str(res is not None))


async def test_no_marker_no_interrupt() -> None:
    print("\n[沒有標記 → 一次都不打斷]")
    client, inbox, drops = install(
        [assistant_msg("做完了"), result_msg("做完了"), MailboxClosed()])
    with fast_timeouts(idle=30, tool=30):
        res = await asyncio.wait_for(
            runner_mod.run_turn("做事", make_state(), SilentFrontend(), "ask-2"),
            timeout=5)
    check("沒有 interrupt", client.interrupts == 0, str(client.interrupts))
    check("回覆正常", res is not None and "做完了" in res.reply,
          res.reply if res else "None")


async def test_incomplete_marker_ignored() -> None:
    print("\n[標記不完整（沒有選項）→ 不算]")
    # parse_ask_marker 對「少於兩段」回 None：那種標記不會變成按鈕，
    # 停下來只會讓使用者面對一個點不下去的畫面。
    client, inbox, drops = install(
        [assistant_msg("[[ASK:只有問題]]"), result_msg("x"), MailboxClosed()])
    with fast_timeouts(idle=30, tool=30):
        await asyncio.wait_for(
            runner_mod.run_turn("x", make_state(), SilentFrontend(), "ask-3"),
            timeout=5)
    check("沒有 interrupt", client.interrupts == 0, str(client.interrupts))


async def test_interrupt_fails_falls_back_to_timeout() -> None:
    print("\n[interrupt 收不掉 → 不卡死，交回閒置逾時]")
    # on_interrupt=None：打了也沒用，讀取任務不會結束。
    client, inbox, drops = install([assistant_msg(ASK)])
    with fast_timeouts(idle=0.4, tool=0.4, grace=0.1):
        err: BaseException | None = None
        t0 = asyncio.get_running_loop().time()
        try:
            await asyncio.wait_for(
                runner_mod.run_turn("x", make_state(), SilentFrontend(), "ask-4"),
                timeout=5)
        except asyncio.TimeoutError as e:
            err = e
        dt = asyncio.get_running_loop().time() - t0
    check("最後還是收掉了，沒有卡在迴圈裡", err is not None and dt < 4, f"{dt:.2f}s")
    # ask 打一次、逾時再打一次＝2。重點是「不會每輪都打」——輪詢是 0.05 秒，
    # 沒擋的話這裡會是幾十次。
    check("沒有每輪重打", client.interrupts <= 2, str(client.interrupts))


def error_result_msg() -> ResultMessage:
    """被 interrupt 的週期實際收出的 Result：subtype=error_during_execution、is_error。"""
    return ResultMessage(
        subtype="error_during_execution", duration_ms=1, duration_api_ms=1,
        is_error=True, num_turns=1, session_id="sid-1", result=None,
    )


async def test_ask_interrupt_error_result_not_failure() -> None:
    print("\n[ASK 打斷後收到 error_during_execution → 照常收工，不是失敗]")
    # 真實 CLI 被 interrupt 時收出的是 is_error 的 Result，不是 success。
    # 打斷是我們自己要的，這一輪寫好的回覆與選項卡不能因此作廢。
    client, inbox, drops = install(
        [assistant_msg(ASK)],
        on_interrupt=[error_result_msg(), MailboxClosed()],
    )
    try:
        with fast_timeouts(idle=30, tool=30):
            res = await asyncio.wait_for(
                runner_mod.run_turn("幫我決定", make_state(), SilentFrontend(), "ask-5"),
                timeout=5)
    except Exception as e:     # 修之前會在這裡拿到 CCError(EXEC_ERROR)
        res = None
        print(f"    run_turn 拋出 {type(e).__name__}: {e}")
    check("有 interrupt", client.interrupts == 1, str(client.interrupts))
    check("回覆保留下來", res is not None and "要選哪一個" in res.reply,
          res.reply if res else "None")


async def test_orphan_cycle_not_interrupted_by_previous_ask() -> None:
    """2026-10-10 實錄：回合回覆帶 ASK 正常收出 Result，同時有一則插話
    撲空，CLI 另開週期去回它。主迴圈看到 ask_pending 就 interrupt，打斷的是那個在回
    新訊息的孤兒週期 → error_during_execution → 整個回合判失敗，第一輪的回覆跟選項全丟。"""
    print("\n[撲空孤兒週期不能被前一輪的 ASK 打斷]")
    client, inbox, drops = install(
        [], on_interrupt=[error_result_msg(), MailboxClosed()])

    async def cli() -> None:
        while "test" not in runner_mod._steerable:
            await asyncio.sleep(0.01)
        # 下面全部同步做完、中間不讓出：插話記帳、模型帶 ASK 收尾、孤兒週期開頭
        await runner_mod.try_steer("test", "再補一個問題")
        inbox.push(assistant_msg(ASK))
        inbox.push(result_msg(ASK))
        await asyncio.sleep(0.5)          # 孤兒週期的模型在想，期間主迴圈輪詢好幾輪
        inbox.push(assistant_msg("補充回答"))
        inbox.push(result_msg("補充回答"))
        inbox.push(MailboxClosed())

    feeder = asyncio.create_task(cli())
    try:
        with fast_timeouts(idle=30, tool=30):
            res = await asyncio.wait_for(
                runner_mod.run_turn("幫我決定", make_state(), SilentFrontend(), "ask-6"),
                timeout=5)
    except Exception as e:     # 修之前會在這裡拿到 CCError(EXEC_ERROR)
        res = None
        print(f"    run_turn 拋出 {type(e).__name__}: {e}")
    finally:
        feeder.cancel()
    check("孤兒週期沒被打斷", client.interrupts == 0, str(client.interrupts))
    check("兩段回覆都在", res is not None and "要選哪一個" in res.reply
          and "補充回答" in res.reply, res.reply if res else "None")


async def main() -> int:
    await test_ask_interrupts_turn()
    await test_no_marker_no_interrupt()
    await test_incomplete_marker_ignored()
    await test_interrupt_fails_falls_back_to_timeout()
    await test_ask_interrupt_error_result_not_failure()
    await test_orphan_cycle_not_interrupted_by_previous_ask()
    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
