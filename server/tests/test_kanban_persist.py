"""看板存檔：讀不到就失敗、壞檔隔離、寫入原子。

讀檔任何錯誤都回 {}、寫入又不是原子的話，寫到一半被殺
留下半截 JSON，下一次新增就把整塊看板蓋成只剩那一張卡。
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch

os.environ.setdefault("BUTLER_DATA_DIR", tempfile.mkdtemp(prefix="butler-kanban-"))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402,F401
import kanban  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def _titles() -> list[str]:
    return sorted(c["title"] for col in kanban.get_board()["columns"] for c in col["cards"])


def test_persist() -> None:
    data = kanban._DATA
    print("\n[正常寫入]")
    kanban.add_card("甲")
    kanban.add_card("乙")
    check("兩張卡都在", _titles() == ["乙", "甲"], str(_titles()))
    check("是合法 JSON", isinstance(json.loads(data.read_text(encoding="utf-8")), dict))
    check("沒有留下暫存檔", not list(data.parent.glob("kanban.json.*.tmp")))

    print("\n[讀不到檔：這次操作失敗，看板不能被清掉]")
    before = data.read_text(encoding="utf-8")
    raised = False
    with patch.object(kanban, "read_text_with_retry", side_effect=PermissionError("撞鎖")):
        try:
            kanban.add_card("丙")
        except PermissionError:
            raised = True
    check("新增拋出錯誤", raised)
    check("檔案原封不動", data.read_text(encoding="utf-8") == before)
    check("原本的卡還在", _titles() == ["乙", "甲"], str(_titles()))

    print("\n[壞掉的檔：隔離留證據，不是悄悄蓋掉]")
    data.write_text('{"half": ', encoding="utf-8")
    kanban.add_card("丁")
    corrupt = list(data.parent.glob("kanban.json.corrupt-*"))
    check("壞檔被改名留著", len(corrupt) == 1)
    check("壞檔內容還在", bool(corrupt) and corrupt[0].read_text(encoding="utf-8") == '{"half": ')
    check("新板子可以用", _titles() == ["丁"], str(_titles()))


def main() -> int:
    test_persist()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
