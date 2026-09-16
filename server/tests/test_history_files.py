"""snapshot 補回檔案卡片：時間軸對齊的迴歸測試。

盯的就是那個八小時。逐字稿的時間戳是 UTC 帶 Z（`2026-08-10T12:30:22.938Z`，
2026-08-18 實測），outbox 的登記時間是本地時間、不帶時區。App 要把卡片依時間
插回訊息之間，兩邊就必須落在同一條 epoch 軸上——錯了不會拋例外、不會有 log，
只有卡片會安靜地跑到八小時外的位置去。

所以這裡不寫死「差 8 小時」，而是拿同一個時刻的兩種表示法餵進兩條路，
斷言它們算出來的 epoch 對得起來。在任何時區跑這個測試都該通過。
"""
from __future__ import annotations

import json
import shutil
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import outbox                                    # noqa: E402
from engine import history                       # noqa: E402

FAILED: list[str] = []

# 隨便挑的一個 UTC 時刻，兩條路都從它出發
MOMENT = datetime(2026, 8, 10, 12, 30, 22, 938000, tzinfo=timezone.utc)


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def test_history_reads_utc() -> None:
    print("\n[逐字稿的時間戳當 UTC 解]")
    root = Path(tempfile.mkdtemp(prefix="butler_hist_"))
    jf = root / "s.jsonl"
    rows = [
        {"type": "user", "timestamp": "2026-08-10T12:30:22.938Z",
         "message": {"content": [{"type": "text", "text": "傳個檔案給我"}]}},
        {"type": "assistant", "timestamp": "2026-08-10T12:31:00.000Z",
         "message": {"content": [{"type": "text", "text": "好了"}]}},
    ]
    with jf.open("w", encoding="utf-8", newline="\n") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    with patch.object(history, "_load_map", lambda: {"c1": {"session_id": "s"}}), \
            patch.object(history, "_session_file", lambda _s: jf):
        got = history.load_history("c1")

    check("兩則都回來了", len(got) == 2, str(len(got)))
    if len(got) != 2:
        shutil.rmtree(root, ignore_errors=True)
        return
    check("每則都帶 at_ms", all("at_ms" in m for m in got))
    check("第一則的 at_ms 就是那個 UTC 時刻",
          got[0]["at_ms"] == int(MOMENT.timestamp() * 1000),
          f"{got[0]['at_ms']} vs {int(MOMENT.timestamp() * 1000)}")
    check("時間是遞增的", got[0]["at_ms"] < got[1]["at_ms"])
    shutil.rmtree(root, ignore_errors=True)


def test_history_missing_timestamp_carries_forward() -> None:
    print("\n[沒有時間戳的那則沿用上一則]")
    root = Path(tempfile.mkdtemp(prefix="butler_hist_"))
    jf = root / "s.jsonl"
    rows = [
        {"type": "user", "timestamp": "2026-08-10T12:30:22.938Z",
         "message": {"content": [{"type": "text", "text": "一"}]}},
        # 時間戳不見了：不能讓它掉回 0，否則後面的卡片會全部被擠到最前面
        {"type": "assistant",
         "message": {"content": [{"type": "text", "text": "二"}]}},
    ]
    with jf.open("w", encoding="utf-8", newline="\n") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    with patch.object(history, "_load_map", lambda: {"c1": {"session_id": "s"}}), \
            patch.object(history, "_session_file", lambda _s: jf):
        got = history.load_history("c1")

    check("沒掉回 0", len(got) == 2 and got[1]["at_ms"] == got[0]["at_ms"],
          str([m.get("at_ms") for m in got]))
    shutil.rmtree(root, ignore_errors=True)


def test_outbox_at_is_local_time() -> None:
    print("\n[outbox 的登記時間當本地時間解]")
    # 同一個時刻，換成本機時區的牆上時間——offer() 存進去的就是這個字串
    local_text = MOMENT.astimezone().strftime("%Y-%m-%dT%H:%M")
    got = outbox._at_to_ms(local_text)

    # 秒數補到 59.999，所以答案落在那一分鐘之內
    minute = int(MOMENT.replace(second=0, microsecond=0).timestamp() * 1000)
    check("跟逐字稿算出來的是同一分鐘", minute <= got < minute + 60_000,
          f"at='{local_text}' -> {got}，該落在 [{minute}, {minute + 60_000})")
    # 這是整個測試的重點：兩條路對同一個時刻算出來的 epoch 不能差到一小時以上
    check("跟 UTC 那條路沒有差出時區",
          abs(got - int(MOMENT.timestamp() * 1000)) < 3_600_000,
          f"差了 {(got - int(MOMENT.timestamp() * 1000)) / 3_600_000:.1f} 小時")
    check("解析不出來回 0", outbox._at_to_ms("壞掉的字串") == 0)


def test_card_sorts_after_the_message_in_same_minute() -> None:
    print("\n[同一分鐘內卡片排在訊息後面]")
    # 卡片的語意是「這一輪講完之後才落地」，所以同分鐘時要排在後面
    msg_ms = int(MOMENT.timestamp() * 1000)
    card_ms = outbox._at_to_ms(MOMENT.astimezone().strftime("%Y-%m-%dT%H:%M"))
    check("卡片在後", card_ms > msg_ms, f"卡片 {card_ms} vs 訊息 {msg_ms}")


def test_for_conv_filters_and_hides_path() -> None:
    print("\n[for_conv 只給該對話的，而且不外洩路徑]")
    tmp = Path(tempfile.mkdtemp(prefix="butler_ob_"))
    data = {
        "f1": {"file_id": "f1", "path": r"C:\x\a.png", "name": "a.png", "bytes": 10,
               "mime": "image/png", "note": "", "conv_id": "cA", "at": "2026-08-10T20:30"},
        "f2": {"file_id": "f2", "path": r"C:\x\b.png", "name": "b.png", "bytes": 20,
               "mime": "image/png", "note": "", "conv_id": "cB", "at": "2026-08-10T20:31"},
        "f3": {"file_id": "f3", "path": r"C:\x\c.png", "name": "c.png", "bytes": 30,
               "mime": "image/png", "note": "", "conv_id": "cA", "at": "2026-08-09T09:00"},
    }
    with patch.object(outbox, "_load", lambda: data):
        got = outbox.for_conv("cA")
        empty = outbox.for_conv("-")

    check("只拿到 cA 的兩筆", [r["file_id"] for r in got] == ["f3", "f1"],
          str([r["file_id"] for r in got]))
    check("舊到新", all("at_ms" in r for r in got) and got[0]["at_ms"] < got[1]["at_ms"])
    check("沒有把本機路徑送出去", all("path" not in r for r in got))
    check("來源不明的對話不回東西", empty == [])
    shutil.rmtree(tmp, ignore_errors=True)


def main() -> int:
    test_history_reads_utc()
    test_history_missing_timestamp_carries_forward()
    test_outbox_at_is_local_time()
    test_card_sorts_after_the_message_in_same_minute()
    test_for_conv_filters_and_hides_path()
    print("\n全部通過" if not FAILED else f"\n失敗 {len(FAILED)}：{FAILED}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
