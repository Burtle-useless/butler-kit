"""排隊標記：手機端要分得出「還排著」與「已經在做」。

沒有這組事件，排隊中的訊息在畫面上跟已處理的長得一模一樣，人只能猜助理讀到
哪一則了。這裡用假的 handle_turn 把回合卡住，驗證三件事：

  1. 送出當下就在忙 → user.message 帶 queued=True
  2. 被讀進某一輪 → message.taken 指名是哪幾則
  3. 按停止連帶取消 → message.dropped 指名是哪幾則

不走 HTTP、不佔埠：正式服務綁在 tailnet 上常駐著，測試不該逼人把它關掉。
"""
from __future__ import annotations

import asyncio
from pathlib import Path

import config  # noqa: F401
from engine import worker as worker_mod
from engine.state import ConvState
from transport import app as app_mod

FAILED: list[str] = []
CONV = "qtest"


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}",
          flush=True)
    if not cond:
        FAILED.append(name)


class FakeFE:
    def __init__(self) -> None:
        self.events: list = []

    async def emit(self, ev) -> None:
        self.events.append(ev)


def of_type(fe: FakeFE, t: str) -> list:
    return [e for e in fe.events if e.type == t]


async def until(cond, timeout: float = 3.0) -> bool:
    """等一個條件成立。輪詢而不是固定 sleep，慢機器上才不會偽陽性失敗。"""
    loop = asyncio.get_running_loop()
    end = loop.time() + timeout
    while loop.time() < end:
        if cond():
            return True
        await asyncio.sleep(0.02)
    return cond()


async def main() -> None:
    fe = FakeFE()
    gates: asyncio.Queue = asyncio.Queue()   # 放一個進去＝讓卡住的那輪跑完
    prompts: list[str] = []
    # 每一輪拿到的來源。傳輸層有沒有把 client 一路帶到回合，只有這裡驗得到
    srcs: list[str] = []

    async def fake_turn(text, state, frontend, src="") -> None:
        prompts.append(text)
        srcs.append(src)
        await gates.get()

    # 換掉真回合與標題生成：這裡測的是排隊語意，不該燒 API
    worker_mod.handle_turn = fake_turn
    app_mod.worker.frontend_for = lambda cid: fe
    worker_mod.get_state = lambda cid: ConvState(conv_id=cid, cwd=Path.home())
    app_mod.state_mod.get_title = lambda cid: "已有標題"

    async def send(text: str, client: str | None = None) -> None:
        payload = {"text": text}
        if client is not None:
            payload["client"] = client
        await app_mod.send_message(CONV, payload, "tok")

    print("\n[第一則不必排隊]", flush=True)
    await send("第一則")
    ok = await until(lambda: prompts == ["第一則"])
    check("空閒時送出的訊息立刻被讀走", ok, str(prompts))
    ums = of_type(fe, "user.message")
    check("它身上沒有排隊標記", ums[0].data["queued"] is False)
    taken = of_type(fe, "message.taken")
    check("有指名它被讀進這一輪", bool(taken) and
          taken[0].data["msg_ids"] == [ums[0].data["msg_id"]])
    # 沒帶 client 的一律當手機：還沒更新的 App 送的就是這一種
    check("沒報來源時當成手機", srcs == ["手機"], str(srcs))

    print("\n[忙碌時送出的會排隊]", flush=True)
    await send("第二則")
    await send("第三則", "desktop")
    ums = of_type(fe, "user.message")
    check("第二則標成排隊中", ums[1].data["queued"] is True)
    check("第三則標成排隊中", ums[2].data["queued"] is True)
    check("排著的期間不會多發 taken", len(of_type(fe, "message.taken")) == 1)

    print("\n[讀進下一輪時一起轉正]", flush=True)
    gates.put_nowait(None)                    # 放行第一輪
    ok = await until(lambda: len(of_type(fe, "message.taken")) == 2)
    check("第二批被讀走時發了 taken", ok)
    ids = of_type(fe, "message.taken")[1].data["msg_ids"]
    check("指名的正是排著的那兩則", ids == [ums[1].data["msg_id"],
                                            ums[2].data["msg_id"]], str(ids))
    check("兩則合併成同一輪處理", len(prompts) == 2 and
          "第二則" in prompts[1] and "第三則" in prompts[1])
    # 這一批混了手機與電腦，取最後一則的來源——那則最接近他現在人在哪
    check("混批取最新那則的來源", srcs[1] == "電腦", str(srcs))

    print("\n[按停止：排著的要標成取消]", flush=True)
    # 第二輪還卡在 fake_turn 裡，所以這兩則都只能排著
    await send("第四則")
    await send("第五則")
    ums = of_type(fe, "user.message")
    check("這兩則都標成排隊中",
          ums[3].data["queued"] is True and ums[4].data["queued"] is True)
    app_mod.worker.running[CONV].cancel()     # 等同使用者按下停止
    ok = await until(lambda: bool(of_type(fe, "message.dropped")))
    check("發出了 dropped", ok)
    dropped = of_type(fe, "message.dropped")[0].data["msg_ids"] if ok else []
    check("指名的正是還排著的那兩則",
          dropped == [ums[3].data["msg_id"], ums[4].data["msg_id"]], str(dropped))
    check("已經被讀進那一輪的不算被取消",
          ums[2].data["msg_id"] not in dropped)
    check("停止有給使用者交代",
          any("取消" in e.data.get("detail", "")
              for e in of_type(fe, "error")))

    # 收工：worker 是無窮迴圈，留著會讓進程不肯結束
    app_mod.worker.workers[CONV].cancel()
    app_mod.worker.queues.pop(CONV, None)
    app_mod.worker.workers.pop(CONV, None)

    print("\n" + "=" * 50, flush=True)
    if FAILED:
        print(f"失敗 {len(FAILED)} 項：" + "、".join(FAILED), flush=True)
        raise SystemExit(1)
    print("全部通過", flush=True)


if __name__ == "__main__":
    asyncio.run(main())
