"""逐字稿切行不能用 splitlines（U+2028／2029／0085 會把一筆紀錄切成兩段）。

訊息裡有這幾個字元時，那一則會從 snapshot 消失。
先前的 test_history_tail 拿同一個 _tail_lines 當對照組，所以抓不到。
"""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402,F401
from engine import history  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def test_linesep() -> None:
    print("\n[JSONL 只用 \\n 切]")
    texts = ["第一則", "段落分隔 在這裡", "行分隔 與\u0085下一行", "最後一則"]
    d = Path(tempfile.mkdtemp(prefix="butler-linesep-"))
    jf = d / "s.jsonl"
    with jf.open("w", encoding="utf-8", newline="\n") as f:
        for t in texts:
            f.write(json.dumps({"type": "user", "message": {"content": t}}, ensure_ascii=False) + "\n")
    lines, whole = history._tail_lines(jf, None)
    parsed = [json.loads(ln)["message"]["content"] for ln in lines]
    check("整份讀：四筆都在", parsed == texts, str(len(lines)))
    check("整份讀：回報是整份", whole)
    tail, whole2 = history._tail_lines(jf, jf.stat().st_size - 5)
    ok = True
    for ln in tail:
        try:
            json.loads(ln)
        except ValueError:
            ok = False
    check("尾段讀：每一行都是完整的 JSON", ok and bool(tail), str(len(tail)))
    check("尾段讀：最後一則還在", bool(tail) and json.loads(tail[-1])["message"]["content"] == "最後一則")
    check("尾段讀：回報不是整份", not whole2)


def main() -> int:
    test_linesep()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
