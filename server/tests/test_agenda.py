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
        # 交疊：原本只查單節自己的 end > start，這組兩節都合法但撞在一起，
        # 而 now_status 取第一個命中的節次 → 「現在第幾節」變成看排序運氣
        ({"periods": [{"no": 1, "start": "08:10", "end": "09:00"},
                      {"no": 2, "start": "08:50", "end": "09:40"}]}, "兩節時間重疊"),
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


def test_type_errors_are_400() -> None:
    """型別轉不過去要回 400，不是 500。

    `_guard` 只攔 AgendaError，而路由層自己做的 `int()`／`float()` 拋的是
    ValueError——它直接穿過去變成 500，App 只看得到「伺服器壞了」，
    助理也拿不到能自我修正的中文訊息（它只會收到一個 SDK 例外）。
    """
    print("\n[型別錯誤回 400 不是 500]")
    r = client.post("/v1/agenda/events", json={
        "title": "看牙", "start": "2026-08-20T09:00", "remind_min": "稍後"})
    check("remind_min 非數字 → 400", r.status_code == 400, str(r.status_code))
    r = client.post("/v1/agenda/ledger", json={"amount": "一百", "category": "餐飲"})
    check("amount 非數字 → 400", r.status_code == 400, str(r.status_code))
    # 正常值當然還是要收得下來，別為了擋錯連對的一起擋
    r = client.post("/v1/agenda/events", json={
        "title": "看牙", "start": "2026-08-20T09:00", "remind_min": "15"})
    check("字串數字仍可接受", r.status_code == 200, r.text[:80])
    check("而且真的轉成數字了", r.json()["remind_min"] == 15, str(r.json().get("remind_min")))


def test_update_cross_field() -> None:
    """`update` 不可以繞過新增時的跨欄位驗證。

    這類錯誤不會報錯，只會安靜地給錯答案：課表一旦出現 from=5 to=1，
    `now_status` 的區間判斷永遠不成立，「他現在在上課嗎」一律答沒有——
    助理於是在上課時間打擾他。
    """
    print("\n[update 的跨欄位驗證]")
    c = store.add_course(name="物理", day=0, from_period=3, to_period=5)
    try:
        store.update("courses", c["id"], {"to_period": 1})
        check("擋下 to_period 比 from_period 早", False, "竟然改成功了")
    except store.AgendaError:
        check("擋下 to_period 比 from_period 早", True)
    after = next(x for x in store.list_kind("courses") if x["id"] == c["id"])
    check("失敗的更新沒有落地", after["to_period"] == 5, str(after["to_period"]))

    e = store.add_entry(amount=100, category="餐飲")
    try:
        store.update("ledger", e["id"], {"amount": -50})
        check("擋下負數金額", False, "竟然改成功了")
    except store.AgendaError:
        check("擋下負數金額", True)
    try:
        store.update("ledger", e["id"], {"amount": "很多"})
        check("擋下非數字金額", False, "竟然改成功了")
    except store.AgendaError:
        check("擋下非數字金額", True)
    after_e = next(x for x in store.list_kind("ledger") if x["id"] == e["id"])
    check("金額沒有被改壞", after_e["amount"] == 100, str(after_e["amount"]))
    # 合法的更新照樣要能過
    store.update("ledger", e["id"], {"amount": 250})
    after_e = next(x for x in store.list_kind("ledger") if x["id"] == e["id"])
    check("合法更新仍可通過", after_e["amount"] == 250, str(after_e["amount"]))

    # 把每週重複的鬧鐘改成「只響一次」時要自己補上日期。留 null 的話手機端
    # 只能理解成「下一次到這個時間」，而它響完會重排——設一次的鬧鐘天天響。
    # add_alarm 早就有補，update 這條路原本是缺口。
    a = store.add_alarm(time="07:30", days=[0, 1, 2])
    check("每週重複的鬧鐘不需要日期", a["date"] is None, str(a["date"]))
    store.update("alarms", a["id"], {"days": []})
    after_a = next(x for x in store.list_kind("alarms") if x["id"] == a["id"])
    check("改成只響一次時補上日期", after_a["date"] is not None, str(after_a["date"]))
    # 反向：改回每週重複不該被硬塞日期
    store.update("alarms", a["id"], {"days": [3], "date": None})
    after_a2 = next(x for x in store.list_kind("alarms") if x["id"] == a["id"])
    check("改回每週重複時不補日期", after_a2["date"] is None, str(after_a2["date"]))


def test_corrupt_file_is_kept() -> None:
    """壞掉的 agenda.json 不可以被靜默清空。

    先前 `_load` 解析失敗就回空結構，而下一次任何寫入會把那個空結構整份存回去——
    行程、鬧鐘、記帳、課表一次全滅，原檔也被蓋掉。壞的通常只是尾端幾個 byte
    （寫到一半斷電、磁碟錯誤），前面的內容本來救得回來。
    現在改成先把壞檔改名保留再回空的：服務照常起來，資料留著等人救。
    """
    print("\n[壞檔要留著不能被蓋掉]")
    # 先存一筆真的資料，再把檔案弄壞（模擬尾端被截斷）
    store.add_event(title="重要的事", start="2026-08-20T09:00")
    good = store.AGENDA_FILE.read_text(encoding="utf-8")
    store.AGENDA_FILE.write_text(good[: len(good) // 2], encoding="utf-8")

    items = store.list_kind("events")
    check("讀壞檔不會讓服務掛掉", isinstance(items, list), str(type(items)))

    kept = list(store.AGENDA_FILE.parent.glob("agenda.json.corrupt-*"))
    check("壞檔被改名保留", len(kept) == 1, f"找到 {len(kept)} 個")
    if kept:
        check("保留的內容就是原本那份壞檔",
              kept[0].read_text(encoding="utf-8") == good[: len(good) // 2])
        check("救得回原本的資料", "重要的事" in kept[0].read_text(encoding="utf-8"))

    # 接著寫入不該再炸，而且原檔位置會重新長出一份乾淨的
    store.add_event(title="後來的事", start="2026-08-21T09:00")
    titles = [e["title"] for e in store.list_kind("events")]
    check("壞檔之後仍可正常使用", titles == ["後來的事"], str(titles))
    for f in kept:
        f.unlink()


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
    test_type_errors_are_400()
    test_update_cross_field()
    test_corrupt_file_is_kept()
    test_tools()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    sys.exit(1 if FAILED else 0)
