"""助理與工作區的設定分流（engine.profiles）、兩份 append 的內容、方案額度的解析。

分流這件事沒有測試就等於沒做——它的失效方式是「安靜地套錯 prompt」，
不會拋錯、不會有日誌，只會讓工作對話突然開始嗆人。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config                                            # noqa: E402
from engine import options, persona, plan_usage, profiles  # noqa: E402
from engine.state import get_state                       # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def test_split() -> None:
    print("\n[助理與工作區分流]")
    primary = get_state(config.PRIMARY_CONV)
    work = get_state("work-test-conv")

    check("助理拿到全套", options.append_for(primary) is options.SYSTEM_APPEND)
    check("工作區拿到工作版", options.append_for(work) is options.WORK_APPEND)

    w = options.WORK_APPEND
    check("工作版沒有行事曆規則", "記帳" not in w and "鬧鐘" not in w)
    # 這幾條是「拿掉就會壞掉」的，缺一不可
    check("工作版留著進度標記", "[[DONE]]" in w and "[[WAIT]]" in w)
    check("工作版留著選項標記", "[[ASK:" in w)
    check("工作版留著一次一問", "一次只問一件事" in w)
    check("工作版留著繁中與短話", "繁體中文" in w and "話要短" in w)
    check("工作版留著傳檔", "send_file" in w)

    s = options.SYSTEM_APPEND
    check("助理版有行事曆規則", "course_now" in s)
    check("助理版也有進度標記", "[[DONE]]" in s)

    # 人格是純文字檔。載不到要當場炸而不是靜默退回預設——「人格沒生效」
    # 跟「人格寫得不夠強」在畫面上長得一模一樣，會讓人去改錯的東西。
    for name in ("default", "work", "example-tsundere"):
        check(f"人格檔 {name} 載得到", len(persona.load(name)) > 100)
    try:
        persona.load("這份人格不存在")
        check("人格檔不存在會炸", False)
    except FileNotFoundError:
        check("人格檔不存在會炸", True)
    check("註解不會進 prompt", "以 # 開頭" not in persona.load("default"))

    # 範例人格是拿來仿寫的，裡面出現控制標記模型會連標記一起學，變成每則都加一個
    check("範例人格不含控制標記", "[[" not in persona.load("example-tsundere"))

    # 失憶自救綁在設定上：沒設筆記檔就整條不注入，免得模型去讀一個不存在的路徑
    check("沒設筆記檔就沒有這條", ("你可能會忘記事情" in s) == bool(config.NOTES_FILE))

    # append 的鐵律：任何一份都不可以有換行，否則 init 握手卡死 60 秒
    for label, text in [("助理", options.SYSTEM_APPEND), ("工作", options.WORK_APPEND)]:
        clean = options.sanitize_append(text)
        check(f"{label}版 sanitize 後無換行", "\n" not in clean, f"{len(clean)} 字")

    qs = options.servers_for(primary)
    ws = options.servers_for(work)
    check("助理有行事曆工具", "agenda" in qs, str(sorted(qs)))
    check("工作區沒有行事曆工具", "agenda" not in ws, str(sorted(ws)))
    check("兩邊都有傳檔工具", "files" in qs and "files" in ws)


def test_resolve() -> None:
    """profile 解析：助理那條精確比對、其餘依 conv_id 前綴、最長前綴優先、"" 是預設。"""
    print("\n[profile 解析]")
    saved = dict(profiles._by_prefix)
    try:
        check("助理那條固定回 PRIMARY",
              profiles.resolve(config.PRIMARY_CONV) is profiles.PRIMARY)
        check("PRIMARY 的 append 就是全套", profiles.PRIMARY.append is options.SYSTEM_APPEND)
        check("沒登記前綴的回預設", profiles.resolve("c1a2b3c4") is profiles.DEFAULT)
        check("預設的 append 是工作版", profiles.DEFAULT.append is options.WORK_APPEND)
        check("預設來源是手機", profiles.DEFAULT.src_default == "手機")

        bot = profiles.Profile(name="bot", append="BOT", servers=lambda s: {},
                               src_default="聊天室")
        group = profiles.Profile(name="group", append="GROUP", servers=lambda s: {"g": 1})
        profiles.register("bot:", bot)
        profiles.register("bot:group:", group)
        check("自訂前綴對得上", profiles.resolve("bot:dm:1") is bot)
        check("最長前綴優先", profiles.resolve("bot:group:9") is group)
        check("短前綴仍管其餘", profiles.resolve("bot:x") is bot)
        check("不相干的 id 還是預設", profiles.resolve("c9") is profiles.DEFAULT)
        # 助理那條不走前綴：登了一個蓋得到 "main" 開頭的前綴，助理那條仍是助理，
        # 但 main2 這種不是（先前就是 == 比對，改成前綴會把工作對話套成助理）
        m = profiles.Profile(name="m", append="M", servers=lambda s: {})
        profiles.register("ma", m)
        check("登了 ma 前綴，助理那條仍固定回 PRIMARY",
              profiles.resolve(config.PRIMARY_CONV) is profiles.PRIMARY)
        check("main2 不是助理（精確比對）",
              profiles.resolve(config.PRIMARY_CONV + "2") is m)
        # options 那兩個入口走的是同一張表
        st = get_state("bot:group:7")
        check("append_for 走 profile", options.append_for(st) == "GROUP")
        check("servers_for 走 profile", options.servers_for(st) == {"g": 1})
        # 重登記 "" 即覆寫預設
        profiles.register("", m)
        check("重登記空前綴即覆寫預設", profiles.resolve("zzz") is m)
    finally:
        profiles._by_prefix.clear()
        profiles._by_prefix.update(saved)
    check("還原後預設回來", profiles.resolve("zzz") is profiles.DEFAULT)


def test_limits() -> None:
    print("\n[方案額度解析]")
    # 灌快取避開對外請求：這裡要驗的是解析與夾限，不是網路通不通
    plan_usage._cache.clear()
    plan_usage._cache["ts"] = float("inf")
    plan_usage._cache["data"] = {
        "five_hour": {"utilization": 86, "resets_at": "2026-08-11T06:20:00Z"},
        "seven_day": {"percent_used": 7.4, "resets_at": "2026-08-18T01:00:00Z"},
        "seven_day_opus": {"utilization": 137},        # 超用
        "seven_day_sonnet": {"utilization": "壞掉的值"},  # 髒資料
    }
    out = plan_usage.limits()
    by = {x["key"]: x for x in out}
    check("兩種欄位名都認得", by["five_hour"]["pct"] == 86.0
          and by["seven_day"]["pct"] == 7.4)
    check("超用夾到 100", by["seven_day_opus"]["pct"] == 100.0)
    check("髒資料整筆跳過", "seven_day_sonnet" not in by, str(sorted(by)))
    check("有中文標題", by["five_hour"]["label"] == "5 小時")
    check("順序照 BUCKETS", [x["key"] for x in out] == ["five_hour", "seven_day",
                                                        "seven_day_opus"])

    # 拿不到資料時必須是空陣列，不是例外
    plan_usage._cache.clear()
    saved = plan_usage.CREDENTIALS
    plan_usage.CREDENTIALS = Path("這個檔不存在.json")
    try:
        check("讀不到憑證回空陣列", plan_usage.limits() == [])
    finally:
        plan_usage.CREDENTIALS = saved
        plan_usage._cache.clear()


if __name__ == "__main__":
    test_split()
    test_resolve()
    test_limits()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    sys.exit(1 if FAILED else 0)
