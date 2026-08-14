"""行事曆／鬧鐘／記帳／課表：資料驗證、HTTP 路由、助理的工具。

**先把 store 的檔案路徑改掉再 import 其他東西**——不然這支測試會把真實的
agenda.json 蓋掉。改的是模組變數而不是環境變數，因為 store 在 import 時就決定路徑了。
"""
from __future__ import annotations

import asyncio
import sys
import tempfile
from pathlib import Path

# 直接執行這支檔案時 sys.path[0] 是 tests/，看不到上一層的套件
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from agenda import store          # noqa: E402

store.AGENDA_FILE = Path(tempfile.mkdtemp()) / "agenda.json"

from fastapi.testclient import TestClient          # noqa: E402
from transport.app import app                      # noqa: E402
from transport.auth import require_token           # noqa: E402

app.dependency_overrides[require_token] = lambda: "test"
client = TestClient(app)

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def test_validation() -> None:
    print("\n[輸入驗證]")
    for bad, why in [
        ({"title": "x", "start": "明天早上七點"}, "自然語言時間"),
        ({"title": "x", "start": "2026-13-45T99:99"}, "不存在的日期"),
        ({"title": "", "start": "2026-08-12T09:00"}, "空標題"),
    ]:
        r = client.post("/v1/agenda/events", json=bad)
        check(f"擋下{why}", r.status_code == 400, str(r.status_code))
    # 「7:30」少一位數是模型很愛犯的錯，這個要自動補而不是報錯
    r = client.post("/v1/agenda/alarms", json={"time": "7:30"})
    check("7:30 自動補成 07:30", r.status_code == 200 and r.json()["time"] == "07:30",
          r.text[:60])
    r = client.post("/v1/agenda/alarms", json={"time": "07:00", "days": [0, 9]})
    check("擋下超出範圍的星期", r.status_code == 400, str(r.status_code))
    r = client.post("/v1/agenda/ledger", json={"amount": -50})
    check("擋下負數金額", r.status_code == 400, str(r.status_code))
    r = client.post("/v1/agenda/ledger", json={"amount": 50, "category": "吃飯"})
    check("擋下不在清單的分類", r.status_code == 400, str(r.status_code))


def test_crud() -> None:
    print("\n[新增、修改、刪除]")
    r = client.post("/v1/agenda/events",
                    json={"title": "看牙醫", "start": "2026-08-20T15:00"})
    eid = r.json()["id"]
    check("行程有 id 與預設提醒",
          r.json()["remind_min"] == 10 and eid.startswith("e"), r.text[:80])

    r = client.patch(f"/v1/agenda/events/{eid}", json={"done": True, "title": "改過"})
    check("部分更新生效", r.json()["done"] is True and r.json()["title"] == "改過")
    check("沒動到的欄位還在", r.json()["start"] == "2026-08-20T15:00")

    all_ = client.get("/v1/agenda").json()
    check("一次拿到三類", set(("events", "alarms", "ledger")) <= set(all_))
    check("分類清單有附上", "餐飲" in all_["categories"])

    check("刪得掉", client.delete(f"/v1/agenda/events/{eid}").status_code == 200)
    check("刪第二次是 404",
          client.delete(f"/v1/agenda/events/{eid}").status_code == 404)


def test_summary() -> None:
    print("\n[月結]")
    for amt, cat in [(120, "餐飲"), (80, "餐飲"), (30, "交通")]:
        client.post("/v1/agenda/ledger",
                    json={"amount": amt, "category": cat, "ts": "2026-08-05T12:00"})
    client.post("/v1/agenda/ledger",
                json={"amount": 5000, "income": True, "ts": "2026-08-01T09:00"})
    s = client.get("/v1/agenda/summary?month=2026-08").json()
    check("支出加總", s["expense"] == 230, str(s["expense"]))
    check("收入分開算", s["income"] == 5000, str(s["income"]))
    check("淨額", s["net"] == 4770, str(s["net"]))
    check("分類由多到少", list(s["by_category"]) == ["餐飲", "交通"], str(s["by_category"]))
    empty = client.get("/v1/agenda/summary?month=2020-01").json()
    check("沒帳的月份回 0 而不是報錯", empty["expense"] == 0 and empty["count"] == 0)


def test_courses() -> None:
    print("\n[課表]")
    for bad, why in [
        ({"name": "電子學", "day": 7, "from_period": 1}, "星期 7"),
        ({"name": "電子學", "day": 2, "from_period": 5, "to_period": 3}, "結束早於開始"),
        ({"name": "", "day": 2, "from_period": 1}, "空課名"),
        ({"name": "電子學", "from_period": 1}, "沒給星期"),
        ({"name": "電子學", "day": 2, "from_period": 99}, "第 99 節"),
    ]:
        r = client.post("/v1/agenda/courses", json=bad)
        check(f"擋下{why}", r.status_code == 400, str(r.status_code))

    r = client.post("/v1/agenda/courses", json={
        "name": "電子學", "day": 2, "from_period": 3, "to_period": 4,
        "teacher": "王老師", "room": "工五 301",
    })
    cid = r.json()["id"]
    check("加課成功", r.status_code == 200 and cid.startswith("c"), r.text[:80])
    check("第一堂不該有衝堂", r.json()["conflicts"] == [], str(r.json()["conflicts"]))

    r = client.post("/v1/agenda/courses", json={
        "name": "單節課", "day": 2, "from_period": 4,
    })
    check("只給一節時 to_period 補成同一節", r.json()["to_period"] == 4, r.text[:80])
    # 第 4 節已經被電子學（3-4）佔住，這要被回報但不能被拒絕
    check("重疊的課照樣加得進去", r.status_code == 200, str(r.status_code))
    check("但衝堂要講出來",
          [c["name"] for c in r.json()["conflicts"]] == ["電子學"],
          str(r.json()["conflicts"]))
    client.delete(f"/v1/agenda/courses/{r.json()['id']}")

    r = client.patch(f"/v1/agenda/courses/{cid}", json={"room": "工五 402"})
    check("改教室生效", r.json()["room"] == "工五 402")
    check("沒動到的欄位還在", r.json()["teacher"] == "王老師")
    r = client.patch(f"/v1/agenda/courses/{cid}", json={"day": 9})
    check("改成不合法的星期會被擋", r.status_code == 400, str(r.status_code))

    all_ = client.get("/v1/agenda").json()
    check("課表與節次表都在總表裡", {"courses", "periods"} <= set(all_), str(list(all_)))
    check("節次表有預設值", len(all_["periods"]) == 12, str(len(all_["periods"])))

    check("刪得掉", client.delete(f"/v1/agenda/courses/{cid}").status_code == 200)


def test_periods() -> None:
    print("\n[節次時間表]")
    for bad, why in [
        ({"periods": []}, "空陣列"),
        ({"periods": [{"no": 1, "start": "08:10", "end": "08:10"}]}, "結束等於開始"),
        ({"periods": [{"no": 1, "start": "08:10", "end": "09:00"},
                      {"no": 1, "start": "09:10", "end": "10:00"}]}, "重複的節次"),
        ({"periods": [{"no": 1, "start": "早上八點", "end": "09:00"}]}, "自然語言時間"),
    ]:
        r = client.put("/v1/agenda/periods", json=bad)
        check(f"擋下{why}", r.status_code == 400, str(r.status_code))

    # 每個學校節次不同，這是整份改寫的路徑
    r = client.put("/v1/agenda/periods", json={"periods": [
        {"no": 2, "start": "09:00", "end": "09:50"},
        {"no": 1, "start": "08:00", "end": "08:50"},
    ]})
    check("整份換掉成功", r.status_code == 200, r.text[:80])
    check("順序會被排好", [p["no"] for p in r.json()["periods"]] == [1, 2],
          str(r.json()["periods"]))
    check("沒列到的節次就消失了", len(r.json()["periods"]) == 2)
    # 還原成預設，免得影響後面的測試
    client.put("/v1/agenda/periods",
               json={"periods": [dict(p) for p in store.DEFAULT_PERIODS]})

    st = store.now_status()
    check("現況查詢的欄位齊全",
          {"weekday", "period", "current", "next", "today"} <= set(st), str(list(st)))
    check("星期用 0 到 6", 0 <= st["weekday"] <= 6, str(st["weekday"]))


def test_tools() -> None:
    """助理的工具走的是同一個 store，但參數與錯誤回報是另一條路徑，要分開驗。"""
    print("\n[助理的工具]")
    from engine import agenda_tools as t

    seen: list[str] = []
    t.set_change_hook(lambda what: asyncio.sleep(0, result=seen.append(what)))

    async def run() -> None:
        r = await t.alarm_add.handler({"time": "07:30", "label": "起床", "days": [0, 1, 2]})
        check("設鬧鐘成功", not r.get("is_error"), str(r)[:80])
        check("變更有通知出去", seen == ["alarms"], str(seen))

        r = await t.alarm_add.handler({"time": "半夜三點"})
        check("時間亂寫會被擋", r.get("is_error") is True)
        check("錯誤訊息教它正確格式", "HH:MM" in r["content"][0]["text"],
              r["content"][0]["text"][:60])

        r = await t.ledger_add.handler({"amount": 120, "category": "餐飲", "note": "午餐"})
        check("記帳成功", not r.get("is_error"))

        r = await t.calendar_list.handler({})
        check("查行程有帶現在時間", "now" in r["content"][0]["text"])

        r = await t.alarm_remove.handler({"id": "不存在"})
        check("刪不存在的回錯誤", r.get("is_error") is True)

        seen.clear()
        r = await t.course_add.handler({
            "name": "微積分", "day": 0, "from_period": 1, "to_period": 2,
            "room": "工三 205",
        })
        check("加課成功", not r.get("is_error"), str(r)[:80])
        check("課表變更也會通知出去", seen == ["courses"], str(seen))

        r = await t.course_add.handler({"name": "亂寫", "day": "星期三", "from_period": 1})
        check("星期亂寫會被擋", r.get("is_error") is True)
        check("錯誤訊息說明編號方式", "0=週一" in r["content"][0]["text"],
              r["content"][0]["text"][:60])

        r = await t.course_list.handler({})
        text = r["content"][0]["text"]
        check("查課表會附上節次表", '"periods"' in text)
        check("查課表會附上今天是週幾", '"today"' in text)

        r = await t.course_list.handler({"day": 0})
        check("只查某一天", "微積分" in r["content"][0]["text"])
        r = await t.course_list.handler({"day": 5})
        check("那天沒課就給空的", '"courses": []' in r["content"][0]["text"],
              r["content"][0]["text"][:60])

        r = await t.course_now.handler({})
        check("現況查詢可用", '"weekday"' in r["content"][0]["text"], str(r)[:80])

        r = await t.period_set.handler({"periods": [
            {"no": 1, "start": "08:00", "end": "08:50"},
        ]})
        check("改節次表成功", not r.get("is_error"), str(r)[:80])
        r = await t.period_set.handler({"periods": [{"no": 1, "start": "8點"}]})
        check("節次表格式錯會被擋", r.get("is_error") is True)

    asyncio.run(run())


if __name__ == "__main__":
    test_validation()
    test_crud()
    test_summary()
    test_courses()
    test_periods()
    test_tools()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    sys.exit(1 if FAILED else 0)
