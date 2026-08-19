"""P2 迴歸：四個「不會報錯、只會慢慢變糟」的行為。

這批跟 P1 不同，它們不當場壞給你看：搜尋只回一條對話的結果、工具回傳把
context 撐爆、取個標題卻解析整份逐字稿、檔頭全是雜訊就重讀一遍。
共通點是**都要靠資料長大才看得出來**，所以更需要測試把行為釘住——
等真的長到那個規模才發現，通常已經是「助理突然開始失憶」這種難查的症狀。
"""
from __future__ import annotations

import asyncio
import json
import sys
import tempfile
import time
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from engine import history, search, sessions      # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def _rec(role: str, text: str, **extra: object) -> str:
    return json.dumps(
        {"type": role, "message": {"content": [{"type": "text", "text": text}]}, **extra},
        ensure_ascii=False,
    )


def test_history_head() -> None:
    """`head=N` 要拿最前面 N 則，而且不把整份檔案讀完。

    取標題只需要開頭幾則。先前的寫法是 `limit=10**9` 撈全部再切前 20，
    逐字稿動輒上 MB——等於為了開頭那幾行把整份解析一遍。
    """
    print("\n[歷史 head 模式]")
    with tempfile.TemporaryDirectory() as d:
        home = Path(d)
        proj = home / ".claude" / "projects" / "C--Users-you"
        proj.mkdir(parents=True)
        sid = "aaaa1111-2222-3333-4444-555566667777"
        lines = [_rec("user" if i % 2 == 0 else "assistant", f"第{i}則") for i in range(30)]
        (proj / f"{sid}.jsonl").write_text("\n".join(lines), encoding="utf-8")

        with patch.object(Path, "home", staticmethod(lambda: home)), \
                patch.object(history, "_load_map", lambda: {"c1": {"session_id": sid}}):
            head = history.load_history("c1", head=5)
            tail = history.load_history("c1", limit=5)
            whole = history.load_history("c1", limit=10 ** 9)

    check("head 拿到指定筆數", len(head) == 5, str(len(head)))
    check("head 拿的是最舊的那幾則",
          [m["text"] for m in head] == [f"第{i}則" for i in range(5)],
          str([m["text"] for m in head]))
    check("limit 拿的仍是最新的那幾則",
          [m["text"] for m in tail] == [f"第{i}則" for i in range(25, 30)],
          str([m["text"] for m in tail]))
    check("head 不影響完整載入", len(whole) == 30, str(len(whole)))
    # head 比總數大時不可以爆，也不該補空值
    with tempfile.TemporaryDirectory() as d:
        home = Path(d)
        proj = home / ".claude" / "projects" / "C--Users-you"
        proj.mkdir(parents=True)
        sid = "bbbb1111-2222-3333-4444-555566667777"
        (proj / f"{sid}.jsonl").write_text(_rec("user", "只有一則"), encoding="utf-8")
        with patch.object(Path, "home", staticmethod(lambda: home)), \
                patch.object(history, "_load_map", lambda: {"c1": {"session_id": sid}}):
            few = history.load_history("c1", head=20)
    check("head 大於總筆數時回全部", [m["text"] for m in few] == ["只有一則"], str(few))


def test_calendar_list_cap() -> None:
    """`all=true` 要有筆數上限，而且被截掉時一定要講。

    沒有上限的話，累積一兩年（每天三筆≈2000 筆）一次工具回傳就可以到數十萬
    token，直接把 session 打成 CONTEXT_FULL 而重置。
    而截掉不講更糟：助理會把「查到的」當成「全部」，回答「你沒有其他行程」。
    """
    print("\n[行事曆查詢上限]")
    from engine import agenda_tools as t

    rows = [{"id": f"e{i}", "title": f"行程{i}", "start": f"2026-01-01T00:{i:02d}"}
            for i in range(250)]

    async def run() -> None:
        with patch.object(t.store, "list_kind", lambda _kind: rows):
            r = await t.calendar_list.handler({"all": True})
        payload = json.loads(r["content"][0]["text"])
        got = payload["events"]
        check("有截到上限", len(got) == t._LIST_MAX, str(len(got)))
        check("留下的是最近的那幾筆", got[-1]["id"] == "e249", str(got[-1]))
        check("有講被截掉了", "truncated" in payload, str(payload.keys()))
        check("講清楚原本有幾筆", "250" in str(payload.get("truncated")),
              str(payload.get("truncated")))

        # 沒超過上限時不該無中生有多一個欄位
        with patch.object(t.store, "list_kind", lambda _kind: rows[:5]):
            r2 = await t.calendar_list.handler({"all": True})
        p2 = json.loads(r2["content"][0]["text"])
        check("沒超過就不提截斷", "truncated" not in p2, str(p2.keys()))

    asyncio.run(run())


def test_search_spreads_across_convs() -> None:
    """一條對話最多吃掉幾個名額，其他對話才排得進來。

    先前沒有這個上限：一條長對話裡出現 20 次關鍵字就把結果佔滿，
    而「這個詞我在哪條對話講過」正是搜尋最主要的用途。
    """
    print("\n[搜尋跨對話分配]")
    with tempfile.TemporaryDirectory() as d:
        home = Path(d)
        proj = home / ".claude" / "projects" / "C--Users-you"
        proj.mkdir(parents=True)
        convs = {}
        for n in range(3):
            sid = f"cccc{n}111-2222-3333-4444-555566667777"
            # 每條對話都命中 10 次，遠超過單一對話的配額
            body = "\n".join(_rec("user", f"關鍵字 出現第{i}次") for i in range(10))
            (proj / f"{sid}.jsonl").write_text(body, encoding="utf-8")
            convs[f"conv{n}"] = {"session_id": sid}

        titles: list[str] = []

        def fake_title(cid: str) -> str:
            titles.append(cid)
            return f"標題-{cid}"

        with patch.object(Path, "home", staticmethod(lambda: home)), \
                patch.object(search, "_load_map", lambda: convs), \
                patch.object(search, "get_title", fake_title):
            out = search.search("關鍵字", limit=20)

    per = {c: sum(1 for r in out if r["conv_id"] == c) for c in convs}
    check("每條對話都有結果", all(v > 0 for v in per.values()), str(per))
    check("沒有一條吃光名額", all(v <= search._PER_CONV for v in per.values()), str(per))
    check("標題一條對話只查一次", len(titles) == len(set(titles)), str(titles))


def test_head_scan_noise_still_gets_cwd() -> None:
    """檔頭全是維運雜訊時，仍要在同一次掃描裡拿到 cwd。

    先前 `_first_user_record` 找不到開場白就回 None，外層再叫
    `_any_record_with_cwd` 把同一個檔頭重開重讀一遍——而「全是雜訊」
    正是最常見的那種檔案。
    """
    print("\n[檔頭單次掃描]")
    with tempfile.TemporaryDirectory() as d:
        jf = Path(d) / "noise.jsonl"
        jf.write_text("\n".join([
            _rec("user", "/compact 壓縮一下", cwd="C:\\Users\\you\\專案"),
            _rec("user", "剛才那一步還沒收尾"),
            _rec("user", "<command-name>something</command-name>"),
        ]), encoding="utf-8")
        got = sessions._head_scan(jf)

    check("有回傳而不是 None", got is not None)
    if got:
        check("標題留空", got["text"] == "", repr(got["text"]))
        check("仍拿得到 cwd", (got["rec"] or {}).get("cwd", "").endswith("專案"),
              str((got["rec"] or {}).get("cwd")))

    # 有像樣開場白時照舊優先用它
    with tempfile.TemporaryDirectory() as d:
        jf = Path(d) / "ok.jsonl"
        jf.write_text("\n".join([
            _rec("user", "/compact", cwd="C:\\somewhere"),
            _rec("user", "幫我看一下這份報告", cwd="C:\\Users\\you"),
        ]), encoding="utf-8")
        got = sessions._head_scan(jf)
    check("有開場白就用開場白", got is not None and got["text"] == "幫我看一下這份報告",
          str(got))

    # 整個檔什麼都沒有才回 None
    with tempfile.TemporaryDirectory() as d:
        jf = Path(d) / "empty.jsonl"
        jf.write_text("這不是 json\n{}\n", encoding="utf-8")
        check("完全撈不到才回 None", sessions._head_scan(jf) is None)


if __name__ == "__main__":
    t0 = time.time()
    test_history_head()
    test_calendar_list_cap()
    test_search_spreads_across_convs()
    test_head_scan_noise_still_gets_cwd()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}"
          f"（{time.time() - t0:.1f} 秒）")
    sys.exit(1 if FAILED else 0)
