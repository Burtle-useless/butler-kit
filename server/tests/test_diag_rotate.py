"""診斷檔輪替：滿了換到上一份，不是整份刪掉；紀錄帶年份；訊息清單改成摘要。

turn_diag.jsonl 滿 8MiB 就 unlink 的話，回合故障唯一的歷史證據會整份消失；
而 85% 的體積是每回合整串的訊息型別名稱。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402,F401
from engine import diag  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


class AssistantMessage:
    pass


class UserMessage:
    pass


class ResultMessage:
    pass


def test_rotate() -> None:
    print("\n[輪替與格式]")
    old_max = diag._MAX_BYTES
    diag._MAX_BYTES = 200
    try:
        for i in range(20):
            diag.record("turn", i=i, pad="x" * 40)
        check("目前這份存在", diag.DIAG_FILE.exists())
        check("上一份留著（不是刪掉）", diag.DIAG_PREV.exists())
        first = json.loads(diag.DIAG_FILE.read_text(encoding="utf-8").splitlines()[0])
        check("時間戳帶年份", len(first["t"]) == 19 and first["t"][4] == "-", first["t"])
    finally:
        diag._MAX_BYTES = old_max

    print("\n[訊息摘要]")
    msgs = [AssistantMessage(), UserMessage(), AssistantMessage(), UserMessage(), ResultMessage()]
    s = diag.msg_summary(msgs, tail=3)
    check("總數", s["n"] == 5)
    check("各型別數量", s["types"] == {"AssistantMessage": 2, "UserMessage": 2, "ResultMessage": 1})
    check("最後幾則照順序", s["tail"] == ["AssistantMessage", "UserMessage", "ResultMessage"])
    check("空清單不炸", diag.msg_summary([]) == {"n": 0, "types": {}, "tail": []})


def main() -> int:
    test_rotate()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
