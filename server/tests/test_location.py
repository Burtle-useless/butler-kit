"""定位：快取策略、手機沒回應時的退路、以及不合法座標。

這功能最容易出的錯不是「拿不到位置」，而是**拿到舊的卻當成現在的**——
助理會很有自信地說「你現在在學校」，而他三小時前就回家了。所以這裡花最多篇幅
釘住的是「退回快取時一定要標明幾分鐘前」。

另一條是別重複吵手機：每呼叫一次工具就是他的手機開一次 GPS，而他不會看到
任何提示，所以「快取夠新就不要去抓」必須是確定性的行為，不能靠模型自律。
"""
from __future__ import annotations

import asyncio
import json
import sys
import tempfile
import time
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from engine import location                       # noqa: E402
from transport import device_api                  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def _tmp_file() -> Path:
    return Path(tempfile.mkdtemp()) / "location.json"


def _write_cached(path: Path, minutes_ago: float) -> dict:
    """直接寫一筆指定新舊程度的快取（不經 save，才控制得了時間戳）。"""
    rec = {
        "lat": 24.7956, "lon": 120.9936, "address": "新竹市東區五福路二段",
        "accuracy_m": 12.0,
        "ts": (datetime.now() - timedelta(minutes=minutes_ago)).strftime(
            "%Y-%m-%dT%H:%M:%S"),
    }
    path.write_text(json.dumps(rec, ensure_ascii=False), encoding="utf-8")
    return rec


def _payload(res: dict) -> dict:
    """工具回傳的 content 拆出 JSON。is_error 的回傳不是 JSON，呼叫端自己判斷。"""
    return json.loads(res["content"][0]["text"])


def test_save_validates() -> None:
    print("\n[座標驗證]")
    f = _tmp_file()
    with patch.object(location, "LOCATION_FILE", f):
        rec = location.save({"lat": "24.7956", "lon": 120.9936,
                             "address": " 新竹市東區 ", "accuracy_m": 12.34})
        check("字串型別的座標會轉成數字", rec["lat"] == 24.7956, str(rec["lat"]))
        check("地址前後空白被去掉", rec["address"] == "新竹市東區", repr(rec["address"]))
        check("精度留一位小數", rec["accuracy_m"] == 12.3, str(rec["accuracy_m"]))
        check("時間戳存到秒", len(rec["ts"]) == 19, rec["ts"])
        check("讀得回來", (location.last_known() or {}).get("lat") == 24.7956)

        # 不合法的一律擋下來。經緯度顛倒（lat=120）是最常見的那種錯，
        # 不擋的話會存進一個在南極海的位置，而且完全沒有人會發現。
        for bad, why in [
            ({"lat": 120.9, "lon": 24.7}, "經緯度顛倒"),
            ({"lat": None, "lon": 1}, "缺 lat"),
            ({"lat": "abc", "lon": 1}, "lat 不是數字"),
            ({"lat": 1, "lon": 999}, "lon 超出範圍"),
        ]:
            try:
                location.save(bad)
                check(f"{why} 被擋下", False, "居然存進去了")
            except ValueError:
                check(f"{why} 被擋下", True)

        # 地址轉不出來（手機沒網路）仍要存座標——總比什麼都沒有好
        rec2 = location.save({"lat": 25.0, "lon": 121.5})
        check("沒有地址也存得下去", rec2["address"] == "" and rec2["lat"] == 25.0,
              str(rec2))


def test_fresh_cache_skips_device() -> None:
    """快取夠新就直接用，絕對不去吵手機。"""
    print("\n[快取夠新不抓]")
    f = _tmp_file()
    _write_cached(f, minutes_ago=1)
    asked = []

    async def hook() -> dict | None:
        asked.append(1)
        return {"lat": 0.0, "lon": 0.0, "address": "不該用到", "ts": "x"}

    with patch.object(location, "LOCATION_FILE", f), \
            patch.object(location, "_ask_device", hook):
        got = _payload(asyncio.run(location.where_am_i.handler({})))

    check("完全沒有去問手機", not asked, f"問了 {len(asked)} 次")
    check("回的是快取", got["source"] == "快取", got["source"])
    check("有標明幾分鐘前", got["age_min"] >= 1, str(got.get("age_min")))


def test_max_age_zero_forces_refetch() -> None:
    """max_age_min=0 代表他在移動中，一定要重抓。"""
    print("\n[強制重抓]")
    f = _tmp_file()
    _write_cached(f, minutes_ago=0.1)

    async def hook() -> dict | None:
        return location.save({"lat": 25.033, "lon": 121.5654,
                              "address": "臺北市信義區", "accuracy_m": 8})

    with patch.object(location, "LOCATION_FILE", f), \
            patch.object(location, "_ask_device", hook):
        got = _payload(asyncio.run(location.where_am_i.handler({"max_age_min": 0})))
        # 快取要在 patch 還生效時讀，不然讀到的是這台機器上真正的 location.json
        cached_after = location.last_known() or {}

    check("拿到剛抓的", got["source"] == "剛抓的", got["source"])
    check("地址是新的", got["address"] == "臺北市信義區", got["address"])
    check("有寫回快取", cached_after.get("address") == "臺北市信義區", str(cached_after))


def test_device_silent_falls_back_and_says_so() -> None:
    """手機沒回應時退回快取，但**必須**講明那是多久以前的。

    這是整個功能最重要的一條：不講的話助理會把兩小時前的位置當成現在。
    """
    print("\n[手機沒回應]")
    f = _tmp_file()
    _write_cached(f, minutes_ago=125)

    async def dead() -> dict | None:
        return None

    with patch.object(location, "LOCATION_FILE", f), \
            patch.object(location, "_ask_device", dead):
        got = _payload(asyncio.run(location.where_am_i.handler({})))

    check("仍然給得出位置", got.get("address") == "新竹市東區五福路二段", str(got))
    check("來源講明手機沒回應", "沒回應" in got["source"], got["source"])
    check("年齡算對（約 125 分鐘）", 124 <= got["age_min"] <= 126, str(got["age_min"]))

    # 手機有回但回了錯誤（權限被關掉）——原因要一起帶給助理，它才講得出人話
    async def refused() -> dict | None:
        return {"error": "定位權限沒開"}

    with patch.object(location, "LOCATION_FILE", f), \
            patch.object(location, "_ask_device", refused):
        got2 = _payload(asyncio.run(location.where_am_i.handler({})))
    check("帶上手機給的失敗原因", got2.get("device_error") == "定位權限沒開", str(got2))


def test_nothing_at_all_is_an_error() -> None:
    """沒快取又拿不到，要回 is_error，不可以回一個空殼讓助理亂編。"""
    print("\n[完全拿不到]")
    f = _tmp_file()          # 不建檔

    async def dead() -> dict | None:
        return None

    with patch.object(location, "LOCATION_FILE", f), \
            patch.object(location, "_ask_device", dead):
        res = asyncio.run(location.where_am_i.handler({}))

    check("標成錯誤", res.get("is_error") is True, str(res.keys()))
    check("說得出可能的原因", "權限" in res["content"][0]["text"],
          res["content"][0]["text"][:40])


def test_request_fails_fast_without_listeners() -> None:
    """沒有裝置連著時要立刻回 None，不可以等滿逾時。

    等滿的話助理每問一次位置就卡二十秒，而答案早就註定是「拿不到」。
    """
    print("\n[沒裝置連線]")
    device_api.set_publisher(lambda _ev: None, lambda: False)
    t0 = time.time()
    got = asyncio.run(device_api.request("location", timeout_sec=5.0))
    elapsed = time.time() - t0
    check("回 None", got is None, str(got))
    check("立刻回，沒有等逾時", elapsed < 0.5, f"{elapsed:.2f} 秒")

    # 有裝置連著時要真的把事件發出去，而且帶得回 req_id
    sent: list = []
    device_api.set_publisher(lambda ev: sent.append(ev), lambda: True)

    async def ask_then_answer() -> dict | None:
        task = asyncio.create_task(device_api.request("location", timeout_sec=5.0))
        await asyncio.sleep(0.05)              # 讓 request 先把事件發出去
        req_id = sent[0].data["req_id"]
        device_api._resolve(req_id, {"lat": 1.0, "lon": 2.0})
        return await task

    got2 = asyncio.run(ask_then_answer())
    check("事件型別正確", sent and sent[0].type == "device.request",
          sent[0].type if sent else "沒發事件")
    check("事件不綁對話", sent[0].conv_id == "-", sent[0].conv_id)
    check("回報對得回原請求", got2 == {"lat": 1.0, "lon": 2.0}, str(got2))
    device_api.set_publisher(lambda _ev: None, lambda: False)


if __name__ == "__main__":
    t0 = time.time()
    test_save_validates()
    test_fresh_cache_skips_device()
    test_max_age_zero_forces_refetch()
    test_device_silent_falls_back_and_says_so()
    test_nothing_at_all_is_an_error()
    test_request_fails_fast_without_listeners()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}"
          f"（{time.time() - t0:.1f} 秒）")
    sys.exit(1 if FAILED else 0)
