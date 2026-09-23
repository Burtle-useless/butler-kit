"""助理那條每天、課程對話每週換一段新的 session（engine.rotation），畫面上的歷史不能跟著歸零。

一條一路接下去的 session 會長到幾百 MB，每句話都帶著一大包脈絡、不時觸發壓縮。
"""
from __future__ import annotations

import asyncio
import json
import os
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
# 課程對話的 profile 只在課程功能開著時才註冊；每週換那一組要測到它
os.environ.setdefault("BUTLER_COURSES", "1")

import config  # noqa: E402

from engine import bg_notify, client_pool, history, profiles, rotation, runner  # noqa: E402
from engine.state import ConvState  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def _rec(role: str, text: str, ms: int) -> str:
    ts = datetime.fromtimestamp(ms / 1000, tz=timezone.utc).isoformat().replace("+00:00", "Z")
    return json.dumps({"type": role, "timestamp": ts,
                       "message": {"content": [{"type": "text", "text": text}]}}, ensure_ascii=False)


D = datetime  # 簡寫


def test_period() -> None:
    print("\n[每一期從什麼時候開始]")
    ps = rotation.period_start
    check("白天：今天四點", ps("daily", D(2026, 9, 23, 10, 0)) == D(2026, 9, 23, 4, 0))
    check("凌晨兩點：還算昨天", ps("daily", D(2026, 9, 23, 2, 0)) == D(2026, 9, 22, 4, 0))
    check("週三：這週一四點", ps("weekly", D(2026, 9, 23, 10, 0)) == D(2026, 9, 21, 4, 0))
    check("週一凌晨三點：還算上週", ps("weekly", D(2026, 9, 21, 3, 0)) == D(2026, 9, 14, 4, 0))


def _st(conv: str, started: datetime | None, sid: str | None = "11111111-2222-3333-4444-555555555555") -> ConvState:
    return ConvState(conv_id=conv, cwd=Path("."), session_id=sid,
                     session_started=started.timestamp() if started else None)


def test_due() -> None:
    print("\n[該不該換]")
    now = D(2026, 9, 23, 10, 0)
    check("助理：昨晚開的，今天要換", rotation.due(_st("main", D(2026, 9, 22, 23, 0)), now))
    check("助理：今天清晨開的，不換", not rotation.due(_st("main", D(2026, 9, 23, 5, 0)), now))
    check("助理：凌晨一點開的，還在昨天那一期，四點後要換", rotation.due(_st("main", D(2026, 9, 23, 1, 0)), now))
    check("課程：上週三開的，這週要換", rotation.due(_st("course:物理", D(2026, 9, 16, 10, 0)), now))
    check("課程：這週一開的，不換", not rotation.due(_st("course:物理", D(2026, 9, 21, 9, 0)), now))
    check("工作對話：再舊也不換", not rotation.due(_st("c1234", D(2026, 1, 1, 0, 0)), now))
    check("還沒有 session：不換", not rotation.due(_st("main", None, sid=None), now))


def test_legacy_start() -> None:
    print("\n[沒記開始時間的舊 session：從逐字稿推]")
    with tempfile.TemporaryDirectory() as d:
        proj = Path(d) / "C--Users-x"
        proj.mkdir()
        sid = "aaaaaaaa-1111-2222-3333-444444444444"
        three_days = int((time.time() - 3 * 86400) * 1000)
        (proj / f"{sid}.jsonl").write_text(_rec("user", "嗨", three_days) + "\n", encoding="utf-8")
        with patch.object(config, "claude_projects_dir", lambda: Path(d)):
            got = history.session_started_at(sid)
            check("讀得到第一筆的時間", got is not None and abs(got - three_days / 1000) < 1, str(got))
            st = _st("main", None, sid=sid)
            check("三天前開的舊 session 要換", rotation.due(st))


class CollectFE:
    def __init__(self) -> None:
        self.events = []

    async def emit(self, ev) -> None:
        self.events.append(ev)

    async def ask(self, req):
        return None


async def test_rotate() -> None:
    print("\n[換的動作]")
    dropped: list[str] = []

    async def fake_drop(cid: str) -> None:
        dropped.append(cid)

    old_sid = "bbbbbbbb-1111-2222-3333-444444444444"
    with patch.object(client_pool, "drop", fake_drop), \
            patch.object(rotation, "persist", lambda st: None):
        st = _st("main", D(2026, 1, 1, 9, 0), sid=old_sid)
        st.ctx_tokens = 150_000
        fe = CollectFE()
        did = await rotation.maybe_rotate(st, fe)
        check("換了", did)
        check("舊 session 收進 past_sessions", st.past_sessions == [old_sid])
        check("目前的清掉，下一輪開新的", st.session_id is None and st.session_started is None)
        check("context 歸零", st.ctx_tokens == 0)
        check("連線丟掉（不能再 resume 舊的）", dropped == ["main"])
        # 專屬事件：status 的 note 會被這一輪的 turn.start 立刻清掉，畫面上等於沒出現過
        check("畫面上有一條分隔線", any(
            e.type == "session.rotated" and "新的一天" in e.data.get("note", "")
            and e.data.get("period") == "daily" for e in fe.events), str([e.type for e in fe.events]))

        st2 = _st("main", D(2026, 1, 1, 9, 0), sid=old_sid)
        bg_notify.remember("main", "t9", "跑很久的腳本")
        try:
            did2 = await rotation.maybe_rotate(st2, CollectFE())
        finally:
            bg_notify.finish("main", "t9", "completed")
        check("有背景工作在跑就先不換", not did2 and st2.session_id == old_sid)

        st3 = _st("c1234", D(2026, 1, 1, 9, 0))
        check("工作對話不換", not await rotation.maybe_rotate(st3, CollectFE()))


def test_history_chain() -> None:
    print("\n[換過 session 之後，畫面往上捲接得到之前的]")
    with tempfile.TemporaryDirectory() as d:
        proj = Path(d) / "C--Users-x"
        proj.mkdir()
        older, old, new = ("cccccccc-0000-0000-0000-000000000001",
                           "cccccccc-0000-0000-0000-000000000002",
                           "cccccccc-0000-0000-0000-000000000003")
        base = 1_758_000_000_000
        for n, sid in enumerate((older, old, new)):
            lines = [_rec("user", f"s{n}問{i}", base + n * 100_000 + i * 2000) for i in range(3)]
            (proj / f"{sid}.jsonl").write_text("\n".join(lines), encoding="utf-8")
        state_map = {"main": {"session_id": new, "past_sessions": [older, old]}}
        with patch.object(config, "claude_projects_dir", lambda: Path(d)), \
                patch.object(history, "_load_map", lambda: state_map):
            D = history.ROTATE_NOTES["daily"]
            msgs = history.load_history("main", 5)
            got = [m["text"] for m in msgs]
            check("最後一頁跨到上一段，中間一條分隔線", got == ["s1問2", D, "s2問0", "s2問1", "s2問2"], str(got))
            div = msgs[1]
            check("分隔線是 system／rotate", div["role"] == "system" and div.get("kind") == "rotate")
            check("分隔線的時間＝上一段最後一則", div["at_ms"] == msgs[0]["at_ms"])
            page, more = history.load_history_before("main", base + 100_000, 4)
            got2 = [m["text"] for m in page]
            check("往前一頁繼續接更早那段（線在那段後面）", got2 == ["s0問0", "s0問1", "s0問2", D], str(got2))
            check("最舊的讀完就沒有更早了", not more)

            # 照 App 的翻法一路往前翻：每則剛好出現一次、兩條線各一次，不管頁怎麼切
            for size in (1, 2, 3, 4, 5, 7):
                seen: list[str] = [m["text"] for m in history.load_history("main", size)]
                cursor_items = history.load_history("main", size)
                more = True
                guard = 0
                while more and guard < 40:
                    guard += 1
                    cursor = min(int(m["at_ms"]) for m in cursor_items)
                    cursor_items, more = history.load_history_before("main", cursor, size)
                    if not cursor_items:
                        break
                    seen = [m["text"] for m in cursor_items] + seen
                want = ["s0問0", "s0問1", "s0問2", D, "s1問0", "s1問1", "s1問2", D,
                        "s2問0", "s2問1", "s2問2"]
                check(f"一頁 {size} 則翻到底：不漏不重", seen == want, str(seen))
            state_map["main"] = {"session_id": None, "past_sessions": [older, old, new]}
            got3 = [m["text"] for m in history.load_history("main", 2)]
            check("剛換完、新的還沒建立：照樣看得到", got3 == ["s2問1", "s2問2"], str(got3))

        print("\n[prompt 帶上一段的路徑]")
        with patch.object(config, "claude_projects_dir", lambda: Path(d)):
            st = ConvState(conv_id="main", cwd=Path("."), past_sessions=[old])
            note = profiles.resolve("main").append_text(st)
            check("助理的 prompt 帶上一段逐字稿", f"{old}.jsonl" in note and "Grep" in note)
            check("沒換過就不帶", f".jsonl" not in profiles.resolve("main").append_text(
                ConvState(conv_id="main", cwd=Path("."))))
            check("工作對話永遠不帶", ".jsonl" not in profiles.resolve("c1").append_text(
                ConvState(conv_id="c1", cwd=Path("."), past_sessions=[old])))
            check("沒有換行（init 握手）", "\n" not in note)


def test_started_recorded() -> None:
    print("\n[新 session 一出現就記開始時間]")
    st = ConvState(conv_id="main", cwd=Path("."), session_id=None)
    with patch.object(runner, "persist", lambda s: None):
        runner._keep_sid("dddddddd-1111-2222-3333-444444444444", st)
        t1 = st.session_started
        check("第一次拿到 id 就記下", t1 is not None and time.time() - t1 < 5)
        runner._keep_sid("dddddddd-1111-2222-3333-444444444444", st)
        check("同一個 id（續接）不重設", st.session_started == t1)


def main() -> int:
    test_period()
    test_due()
    test_legacy_start()
    asyncio.run(test_rotate())
    test_history_chain()
    test_started_recorded()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
