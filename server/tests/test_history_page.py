"""歷史分頁：snapshot 只帶最後一頁，往上捲用 /history 以時間往前補。"""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402,F401
from engine import history  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def _rec(role: str, text: str, ms: int) -> str:
    from datetime import datetime, timezone
    ts = datetime.fromtimestamp(ms / 1000, tz=timezone.utc).isoformat().replace("+00:00", "Z")
    return json.dumps(
        {"type": role, "timestamp": ts,
         "message": {"content": [{"type": "text", "text": text}]}},
        ensure_ascii=False,
    )


def test_pages() -> None:
    print("\n[歷史分頁]")
    with tempfile.TemporaryDirectory() as d:
        home = Path(d)
        proj = home / ".claude" / "projects" / "C--Users-you"
        proj.mkdir(parents=True)
        sid = "c1d2e3f4-1111-2222-3333-444455556666"
        base = 1_756_800_000_000
        lines = []
        for i in range(10):
            lines.append(_rec("user", f"問{i}", base + i * 2000))
            lines.append(_rec("assistant", f"答{i}", base + i * 2000 + 1000))
        (proj / f"{sid}.jsonl").write_text("\n".join(lines), encoding="utf-8")
        with patch.object(config, "claude_projects_dir", lambda: proj.parent), \
                patch.object(history, "_load_map", lambda: {"c1": {"session_id": sid}}):
            last = history.load_history("c1", 6)
            check("最後一頁取最新 6 則", [m["text"] for m in last] == ["問7", "答7", "問8", "答8", "問9", "答9"],
                  str([m["text"] for m in last]))
            first_ms = last[0]["at_ms"]
            older, more = history.load_history_before("c1", first_ms, 6)
            check("往前一頁緊接在前面", [m["text"] for m in older] == ["問4", "答4", "問5", "答5", "問6", "答6"],
                  str([m["text"] for m in older]))
            check("還有更早", more)
            older2, more2 = history.load_history_before("c1", older[0]["at_ms"], 6)
            check("再往前一頁", [m["text"] for m in older2] == ["問1", "答1", "問2", "答2", "問3", "答3"])
            check("還有更早（剩兩則）", more2)
            older3, more3 = history.load_history_before("c1", older2[0]["at_ms"], 6)
            check("最後只剩開頭兩則", [m["text"] for m in older3] == ["問0", "答0"], str(older3))
            check("沒有更早了", not more3)
            none, more4 = history.load_history_before("c1", older3[0]["at_ms"], 6)
            check("再往前是空的", none == [] and not more4)
        with patch.object(history, "_load_map", lambda: {}):
            check("沒有 session 回空", history.load_history_before("nope", base, 6) == ([], False))


if __name__ == "__main__":
    test_pages()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    sys.exit(1 if FAILED else 0)
