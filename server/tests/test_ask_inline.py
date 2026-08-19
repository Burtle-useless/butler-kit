"""附在訊息上的選項（[[ASK:]]）。

2026-08-19 之前是「伺服器停在那裡等答案、五分鐘沒回就把卡片收掉」。使用者在
手機上常常隔更久才回，回來只看到選項不見了、也不知道剛才被問了什麼。現在改成
選項是訊息的一部分：助理問完就收工，他什麼時候點都算數。

這個檔盯兩件事：
1. **兩條路產出的格式要一模一樣**。現場走 turn.ask_dict、重建歷史走
   fold.ask_payload，差一個欄位名就會變成「剛送到看得見、重開 App 就不見」。
2. **已經回答過的就不要再掛按鈕**。判準是「這則之後還有沒有他說的話」。

跑法（在 server 目錄下）：
    <python> tests\test_ask_inline.py
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from engine import turn as turn_mod  # noqa: E402
from engine.fold import ask_payload, clean_reply  # noqa: E402
from engine.history import _drop_answered_asks  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def test_payload() -> None:
    print("\n[從原文抽選項]")
    raw = "兩條路都行，我偏好第一個。[[ASK:要用哪個做法？|重寫整份|只補那一段]]"
    p = ask_payload(raw)
    check("抽得出來", p is not None)
    check("題目正確", p and p["title"] == "要用哪個做法？", str(p))
    check("選項正確",
          p and [c["label"] for c in p["choices"]] == ["重寫整份", "只補那一段"], str(p))
    check("id 與 label 一致（點下去就是送這句話）",
          p and all(c["id"] == c["label"] for c in p["choices"]), str(p))
    check("沒有標記就沒有選項", ask_payload("普通的一句話") is None)
    check("只有問題沒有選項不算", ask_payload("[[ASK:你要不要？]]") is None)


def test_two_paths_agree() -> None:
    """現場與重建歷史是兩條不同的程式路徑，格式一個字都不能差。"""
    print("\n[兩條路產出一致]")
    for raw in [
        "說明在前面。[[ASK:要哪個？|甲|乙|丙]]",
        "[[ASK:單純兩選一|要|不要]]",
        "換行\n也要抓得到。[[ASK: 中間有空白 | 選項一 | 選項二 ]]",
    ]:
        from engine.fold import parse_ask_marker
        req = turn_mod.parse_ask(parse_ask_marker(raw))
        live = turn_mod.ask_dict(req) if req else None
        check(f"一致：{raw[:14]}…", live == ask_payload(raw),
              f"{live} vs {ask_payload(raw)}")


def test_marker_not_leaked() -> None:
    print("\n[標記不外洩]")
    raw = "我建議第一個。[[ASK:要哪個？|甲|乙]]"
    cleaned = clean_reply(raw)
    check("標記被剝掉", "ASK" not in cleaned and "[[" not in cleaned, cleaned)
    check("正文留著", "我建議第一個" in cleaned, cleaned)
    # 剝掉之後就抽不出選項了——所以重建歷史一定要在 clean_reply **之前**抽。
    # 這條測試存在的用途就是釘住那個順序。
    check("剝掉之後抽不到（順序寫反的話按鈕會永遠消失）",
          ask_payload(cleaned) is None, cleaned)


def test_drop_answered() -> None:
    print("\n[已回答的不再掛按鈕]")
    ask = {"title": "要哪個？", "choices": [{"id": "甲", "label": "甲", "detail": ""}]}

    # 問完之後他還沒說話 → 按鈕留著
    items = [
        {"role": "user", "text": "幫我看看"},
        {"role": "assistant", "text": "你要哪個？", "ask": dict(ask)},
    ]
    out = _drop_answered_asks(items)
    check("最後一則的選項留著", "ask" in out[-1], str(out[-1]))

    # 問完之後他回話了 → 按鈕收掉
    items = [
        {"role": "user", "text": "幫我看看"},
        {"role": "assistant", "text": "你要哪個？", "ask": dict(ask)},
        {"role": "user", "text": "甲"},
        {"role": "assistant", "text": "好，照甲做完了"},
    ]
    out = _drop_answered_asks(items)
    check("答過的收掉", "ask" not in out[1], str(out[1]))

    # 連問兩次，只有最後那次還在等
    items = [
        {"role": "assistant", "text": "第一題", "ask": dict(ask)},
        {"role": "user", "text": "甲"},
        {"role": "assistant", "text": "第二題", "ask": dict(ask)},
    ]
    out = _drop_answered_asks(items)
    check("舊的那題收掉", "ask" not in out[0], str(out[0]))
    check("新的那題留著", "ask" in out[2], str(out[2]))

    check("沒有訊息也不會爆", _drop_answered_asks([]) == [])


def main() -> int:
    test_payload()
    test_two_paths_agree()
    test_marker_not_leaked()
    test_drop_answered()
    print("\n" + "=" * 50)
    if FAILED:
        print(f"失敗 {len(FAILED)} 項：")
        for f in FAILED:
            print(f"  - {f}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
