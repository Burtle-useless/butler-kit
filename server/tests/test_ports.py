"""engine.ports：接線要一次接齊，而且每一條都真的通。

以前五個掛勾散在 app.py 一條一條接，漏接就靜默不動。這裡驗兩件事：
`install` 之後從 engine 那端的入口叫進去，假的 transport 端真的收到；
`Ports` 少給任何一個欄位就 TypeError，不允許半套接線。

    <python> tests\test_ports.py
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402,F401  # 環境清洗要最先跑
from engine import agenda_tools, bg_notify, file_tools, kanban_tools, location, ports  # noqa: E402
from engine.mailbox import WakeTicket  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def _fake_ports(seen: dict[str, list[Any]]) -> ports.Ports:
    def waker(conv_id: str, frontend: Any, ticket: WakeTicket) -> None:
        seen["waker"].append(conv_id)

    async def locate() -> dict[str, Any] | None:
        seen["locate"].append(True)
        return {"lat": 24.9, "lon": 121.2, "accuracy_m": 30.0, "address": "假地址",
                "ts": "2026-09-03T00:00:00+08:00"}

    async def offer(item: dict[str, Any]) -> None:
        seen["offer"].append(item)

    async def agenda_changed(what: str) -> None:
        seen["agenda"].append(what)

    async def kanban_changed(what: str) -> None:
        seen["kanban"].append(what)

    return ports.Ports(
        waker=waker, locate=locate, offer=offer,
        agenda_changed=agenda_changed, kanban_changed=kanban_changed,
    )


def test_missing_field_is_loud() -> None:
    print("\n[少一個欄位就炸]")
    try:
        ports.Ports(  # type: ignore[call-arg]
            waker=lambda *_: None, locate=None, offer=None, agenda_changed=None,
        )
        check("少給 kanban_changed 會 TypeError", False)
    except TypeError:
        check("少給 kanban_changed 會 TypeError", True)
    try:
        ports.Ports()  # type: ignore[call-arg]
        check("全部不給會 TypeError", False)
    except TypeError:
        check("全部不給會 TypeError", True)


async def _run_hooks(seen: dict[str, list[Any]]) -> None:
    # waker：mailbox 那條路的入口是 bg_notify.on_wake
    ticket = WakeTicket(object(), asyncio.Queue())  # type: ignore[arg-type]
    check("on_wake 回 True（有人接）",
          bg_notify.on_wake("conv-x", object(), ticket) is True)
    check("waker 收到對話 id", seen["waker"] == ["conv-x"], str(seen["waker"]))

    # agenda：走 agenda_tools 的內部通知入口
    await agenda_tools._changed("alarms")
    check("agenda_changed 收到", seen["agenda"] == ["alarms"], str(seen["agenda"]))

    # kanban：同上
    await kanban_tools._changed()
    check("kanban_changed 收到", seen["kanban"] == ["kanban"], str(seen["kanban"]))

    # offer：透過 file_tools 的掛勾變數本身（工具 handler 會真的登記到 outbox，
    # 這裡只驗接線）
    assert file_tools._on_offer is not None
    await file_tools._on_offer({"file_id": "f1", "name": "a.png", "bytes": 1})
    check("offer 收到", [i["file_id"] for i in seen["offer"]] == ["f1"], str(seen["offer"]))

    # locate：location 的掛勾變數
    assert location._ask_device is not None
    got = await location._ask_device()
    check("locate 有回位置", bool(got and got.get("lat") == 24.9), str(got))
    check("locate 真的被叫了", seen["locate"] == [True])


def test_install_wires_everything() -> None:
    print("\n[install 之後每條都通]")
    seen: dict[str, list[Any]] = {
        "waker": [], "locate": [], "offer": [], "agenda": [], "kanban": [],
    }
    ports.install(_fake_ports(seen))
    try:
        asyncio.run(_run_hooks(seen))
    finally:
        # 還原：別讓假的接線留給同一個行程裡的其他測試
        bg_notify.set_waker(None)


def main() -> int:
    test_missing_field_is_loud()
    test_install_wires_everything()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + '、'.join(FAILED)}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
