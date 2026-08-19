"""看板資料層測試。

重點在拖放：App 送來的是「畫面上的第幾個」，reorder 必須把整欄重新編號，
不然會撞號、拖完位置亂跳。跨欄拖（等於推進進度）也一起驗。

跑法（在 server 目錄下）：
    <python> tests\test_kanban.py
"""
from __future__ import annotations

import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import kanban  # noqa: E402

_fails: list[str] = []


def check(cond: bool, msg: str) -> None:
    if cond:
        print(f"  ok   {msg}")
    else:
        print(f"  FAIL {msg}")
        _fails.append(msg)


def fresh() -> None:
    """每個案例用一份乾淨的暫存資料檔。"""
    kanban._DATA = Path(tempfile.mkdtemp()) / "kanban.json"


def titles(status: str) -> list[str]:
    col = next(c for c in kanban.get_board()["columns"] if c["status"] == status)
    return [c["title"] for c in col["cards"]]


def test_add_and_order() -> None:
    print("新增照順序排在該欄最後")
    fresh()
    for t in ["A", "B", "C"]:
        kanban.add_card(t)
    check(titles("todo") == ["A", "B", "C"], "三張卡照加入順序")
    check(titles("doing") == [], "進行中是空的")


def test_urgent_does_not_jump() -> None:
    print("急件不會自動置頂（拖到哪就在哪）")
    fresh()
    kanban.add_card("A")
    kanban.add_card("B", urgent=True)
    check(titles("todo") == ["A", "B"], "標了急件也不動位置")


def test_reorder_down() -> None:
    print("往下拖")
    fresh()
    a = kanban.add_card("A")
    kanban.add_card("B")
    kanban.add_card("C")
    kanban.reorder(a["id"], "todo", 2)
    check(titles("todo") == ["B", "C", "A"], "A 拖到第三個")


def test_reorder_up() -> None:
    print("往上拖")
    fresh()
    kanban.add_card("A")
    kanban.add_card("B")
    c = kanban.add_card("C")
    kanban.reorder(c["id"], "todo", 0)
    check(titles("todo") == ["C", "A", "B"], "C 拖到第一個")


def test_reorder_no_duplicate_order() -> None:
    print("重排後整欄 order 連號不撞號")
    fresh()
    a = kanban.add_card("A")
    kanban.add_card("B")
    kanban.add_card("C")
    kanban.reorder(a["id"], "todo", 1)
    col = next(c for c in kanban.get_board()["columns"] if c["status"] == "todo")
    orders = [c["order"] for c in col["cards"]]
    check(orders == [0, 1, 2], f"order 應為 0,1,2，實際 {orders}")


def test_reorder_cross_column() -> None:
    print("跨欄拖＝推進進度")
    fresh()
    a = kanban.add_card("A")
    kanban.add_card("B")
    kanban.add_card("X", status="doing")
    kanban.reorder(a["id"], "doing", 0)
    check(titles("todo") == ["B"], "A 離開待辦")
    check(titles("doing") == ["A", "X"], "A 插進進行中的第一個")


def test_reorder_clamps() -> None:
    print("落點超出範圍會夾住不會爆")
    fresh()
    a = kanban.add_card("A")
    kanban.add_card("B")
    kanban.reorder(a["id"], "todo", 99)
    check(titles("todo") == ["B", "A"], "order 99 夾成最後一個")


def test_update_status_moves_to_end() -> None:
    print("改狀態換欄會排到新欄最後面")
    fresh()
    kanban.add_card("X", status="doing")
    a = kanban.add_card("A")
    kanban.update_card(a["id"], status="doing")
    check(titles("doing") == ["X", "A"], "A 排在 X 後面而不是撞號")


def test_done_column_hides_old() -> None:
    print("完成欄只顯示最近幾張")
    fresh()
    n = kanban.DONE_VISIBLE + 3
    for i in range(n):
        kanban.add_card(f"D{i}", status="done")
    col = next(c for c in kanban.get_board()["columns"] if c["status"] == "done")
    check(col["total"] == n, f"總數 {n}")
    check(len(col["cards"]) == kanban.DONE_VISIBLE, "只回傳可見的張數")
    check(col["hidden"] == 3, "收起 3 張")
    check(col["cards"][0]["title"] == f"D{n - 1}", "最近完成的排最上面")


def test_archive() -> None:
    print("封存後就不出現在板上")
    fresh()
    a = kanban.add_card("A")
    kanban.add_card("B")
    check(kanban.archive_card(a["id"]), "封存回報成功")
    check(titles("todo") == ["B"], "板上只剩 B")
    check(not kanban.archive_card("不存在"), "封存不存在的卡回報失敗")


def test_update_missing() -> None:
    print("動不存在的卡回 None")
    fresh()
    check(kanban.update_card("沒這張", status="done") is None, "update 回 None")
    check(kanban.reorder("沒這張", "todo", 0) is None, "reorder 回 None")


def test_bad_status_falls_back() -> None:
    print("亂填的 status 退回 todo")
    fresh()
    card = kanban.add_card("A", status="亂寫")
    check(card["status"] == "todo", "新增時退回 todo")


if __name__ == "__main__":
    for fn in [
        test_add_and_order, test_urgent_does_not_jump,
        test_reorder_down, test_reorder_up, test_reorder_no_duplicate_order,
        test_reorder_cross_column, test_reorder_clamps,
        test_update_status_moves_to_end, test_done_column_hides_old,
        test_archive, test_update_missing, test_bad_status_falls_back,
    ]:
        fn()
    print()
    if _fails:
        print(f"失敗 {len(_fails)} 項：")
        for f in _fails:
            print(f"  - {f}")
        raise SystemExit(1)
    print("全部通過")
