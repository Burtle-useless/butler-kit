"""用量記帳與反向傳檔。

跟 test_agenda.py 一樣：**先把模組的檔案路徑改掉再 import 其他東西**，
不然這支測試會蓋掉真實的 usage.json 與 outbox.json。
"""
from __future__ import annotations

import asyncio
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import outbox                       # noqa: E402
from engine import local_usage      # noqa: E402
from engine import usage            # noqa: E402

TMP = Path(tempfile.mkdtemp())
usage.USAGE_FILE = TMP / "usage.json"
outbox.OUTBOX_FILE = TMP / "outbox.json"
local_usage.PROJECTS_DIR = TMP / "projects"
local_usage.STATE_FILE = TMP / "local_usage.json"

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


def test_usage_record() -> None:
    print("\n[用量記帳]")
    usage.record("claude-opus-5", {
        "input_tokens": 100, "output_tokens": 50,
        "cache_read_input_tokens": 900, "cache_creation_input_tokens": 20,
    }, 0.12)
    usage.record("claude-opus-5", {"input_tokens": 10, "output_tokens": 5}, 0.01)
    usage.record("claude-haiku-4-5-20251001", {"input_tokens": 7, "output_tokens": 3}, 0.001)

    rep = usage.report(14)
    check("三個回合都記到", rep["today"]["turns"] == 3, str(rep["today"]))
    check("輸入 token 累加", rep["today"]["in"] == 117, str(rep["today"]["in"]))
    check("快取讀取分開記", rep["today"]["cache_read"] == 900)
    check("成本累加", abs(rep["today"]["cost"] - 0.131) < 1e-6, str(rep["today"]["cost"]))
    check("本月等於今天（只跑過今天）", rep["month"]["turns"] == 3)

    # 圖要畫得出來，就得補滿沒用過的日子——缺天數的話橫軸會被壓縮，
    # 「昨天沒用」看起來會變成「昨天不存在」
    check("逐日補滿 14 天", len(rep["days"]) == 14, str(len(rep["days"])))
    check("最後一天是今天", rep["days"][-1]["turns"] == 3)
    check("前面的日子補零", rep["days"][0]["turns"] == 0)

    models = {m["model"]: m for m in rep["models"]}
    check("兩個模型分開記", len(models) == 2, str(list(models)))
    check("貴的排前面", rep["models"][0]["model"] == "claude-opus-5")
    check("模型的回合數對", models["claude-opus-5"]["turns"] == 2)


def test_usage_robust() -> None:
    print("\n[用量記帳不能拖垮回合]")
    before = usage.report(1)["today"]["turns"]
    # SDK 偶爾不給 usage（例如回合中途被取消）。記帳失敗絕不能往外拋。
    usage.record("m", None, None)
    usage.record("m", {"input_tokens": "壞掉的值"}, None)  # type: ignore[dict-item]
    after = usage.report(1)["today"]["turns"]
    check("usage 缺席仍記一筆", after >= before + 1, f"{before}->{after}")

    r = client.get("/v1/usage?span=3")
    check("端點回得來", r.status_code == 200, str(r.status_code))
    check("span 有生效", len(r.json()["days"]) == 3)


def _cc_line(req: str, tokens: int) -> str:
    """一行逐字稿裡的 assistant 紀錄。時間用現在，免得被 KEEP_DAYS 裁掉。"""
    import json
    from datetime import datetime, timezone
    ts = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    return json.dumps({
        "type": "assistant", "requestId": req, "timestamp": ts,
        "message": {"model": "claude-opus-5",
                    "usage": {"input_tokens": tokens, "output_tokens": 0}},
    }, ensure_ascii=False)


def _total_in(data: dict) -> int:
    return sum(int(d.get("in", 0)) for d in data["days"].values())


def test_local_usage_partial_line() -> None:
    """正在被寫入的逐字稿，前面寫完的部分要算得到。

    先前 `_scan_file` 是文字模式 `for line in f` 配 `f.tell()`，而 TextIOWrapper
    被迭代過就禁用 tell，中途 break 呼叫它一律拋
    `OSError: telling position disabled by next() call`——例外被 except OSError
    接住，整批已讀資料連同位移一起丟掉。也就是說**只要 CC 還在跑，那個 session
    的用量就完全計不到**，要等它閒下來才補算。
    """
    print("\n[本機用量：寫到一半的逐字稿]")
    proj = local_usage.PROJECTS_DIR / "專案A"
    proj.mkdir(parents=True, exist_ok=True)
    jf = proj / "s1.jsonl"
    # 三行寫完 + 第四行還在寫（沒有換行符）
    done = "\n".join(_cc_line(f"r{i}", 100) for i in range(3)) + "\n"
    # newline="" 是必要的：Windows 上 write_text 預設會把 \n 換成 \r\n，
    # 位移就對不上了。真實的 CC 逐字稿是 \n。
    jf.write_text(done + _cc_line("r3", 100), encoding="utf-8", newline="")

    data = local_usage.scan()
    check("寫完的三行有算到", _total_in(data) == 300, f"實際 {_total_in(data)}")
    off = data["files"]["專案A/s1.jsonl"]["offset"]
    check("位移停在最後一個完整行", off == len(done.encode()), f"off={off}")

    # CC 把那行寫完，又多寫一行
    jf.write_text(done + _cc_line("r3", 100) + "\n" + _cc_line("r4", 100) + "\n",
                  encoding="utf-8", newline="")
    data = local_usage.scan()
    check("補寫的兩行接著算", _total_in(data) == 500, f"實際 {_total_in(data)}")


def test_local_usage_rewrite_no_double_count() -> None:
    """逐字稿被 auto-compact 就地改寫後不可以重複計數。

    CC 壓縮時會把舊內容換成摘要，檔案因此變小。先前的處理是把位移歸零重掃，
    但 days 是全域累加的桶、沒有記錄各檔已計入多少，於是同一段對話被算第二次——
    每壓縮一次，該檔涵蓋的所有日期就再加一輪，而且永久留在 local_usage.json 裡。
    """
    print("\n[本機用量：逐字稿被改寫]")
    proj = local_usage.PROJECTS_DIR / "專案B"
    proj.mkdir(parents=True, exist_ok=True)
    jf = proj / "s2.jsonl"
    jf.write_text("\n".join(_cc_line(f"b{i}", 1000) for i in range(5)) + "\n",
                  encoding="utf-8", newline="")
    before = _total_in(local_usage.scan())
    check("五筆先算進來", before >= 5000, f"實際 {before}")

    # 壓縮：檔案變小，內容換成兩筆新的
    jf.write_text("\n".join(_cc_line(f"c{i}", 1000) for i in range(2)) + "\n",
                  encoding="utf-8", newline="")
    after = _total_in(local_usage.scan())
    # 舊的 5000 要被扣掉、換成新的 2000，其他檔案的量不受影響
    check("舊的貢獻被扣掉而不是疊加", after == before - 5000 + 2000,
          f"{before} -> {after}")
    check("沒有變成負數", after >= 0, f"實際 {after}")


def test_outbox_validation() -> None:
    print("\n[傳檔的路徑驗證]")
    for bad, why in [
        ("", "空路徑"),
        ("報告.pdf", "相對路徑"),
        (str(TMP / "根本沒這個檔.txt"), "不存在的檔"),
        (str(TMP), "資料夾"),
    ]:
        try:
            outbox.offer(bad)
            check(f"擋下{why}", False, "沒擋下來")
        except outbox.OutboxError as e:
            check(f"擋下{why}", True, str(e)[:40])

    big = TMP / "big.bin"
    big.write_bytes(b"x" * 16)
    saved = outbox.MAX_OFFER_BYTES
    outbox.MAX_OFFER_BYTES = 8
    try:
        outbox.offer(str(big))
        check("擋下超大檔", False, "沒擋下來")
    except outbox.OutboxError as e:
        check("擋下超大檔", "share.ps1" in str(e), str(e)[:60])
    finally:
        outbox.MAX_OFFER_BYTES = saved


def test_download() -> None:
    print("\n[登記與下載]")
    src = TMP / "報告.txt"
    src.write_text("內容不能在傳輸中被改壞", encoding="utf-8")
    item = outbox.offer(str(src), "這是報告")
    check("登記拿到 id", item["file_id"].startswith("f"), item["file_id"])
    check("大小正確", item["bytes"] == src.stat().st_size)

    r = client.get(f"/v1/files/{item['file_id']}")
    check("下載得到", r.status_code == 200, str(r.status_code))
    check("內容一模一樣", r.content == src.read_bytes())

    r = client.get("/v1/files")
    rows = r.json()["files"]
    check("清單看得到", any(f["file_id"] == item["file_id"] for f in rows))
    check("備註有帶著", rows[0]["note"] == "這是報告", str(rows[0])[:60])
    check("原檔還在就不是 gone", rows[0]["gone"] is False)

    r = client.get("/v1/files/f不存在的id")
    check("查無此筆回 404", r.status_code == 404, str(r.status_code))

    # 登記只是指向磁碟上的檔案，原檔被刪是正常情況——要跟「查無此筆」分開，
    # 手機才能顯示「原檔已經不在了」而不是一個看不懂的錯誤
    src.unlink()
    r = client.get(f"/v1/files/{item['file_id']}")
    check("原檔沒了回 410", r.status_code == 410, str(r.status_code))
    check("清單標記成 gone",
          client.get("/v1/files").json()["files"][0]["gone"] is True)


def test_send_file_tool() -> None:
    print("\n[助理的 send_file 工具]")
    from engine import file_tools as ft

    seen: list[dict] = []
    ft.set_offer_hook(lambda item: asyncio.sleep(0, result=seen.append(item)))

    # 工具是每個對話一份（server_for），這裡直接建一支綁 work-1 的來驗來源有帶上
    send_file = ft.make_send_file("work-1")

    async def run() -> None:
        src = TMP / "圖.png"
        src.write_bytes(b"\x89PNG fake")
        r = await send_file.handler({"path": str(src), "note": "做好的圖"})
        check("傳檔成功", not r.get("is_error"), str(r)[:80])
        check("有通知手機", len(seen) == 1 and seen[0]["name"] == "圖.png", str(seen)[:80])
        check("mime 有猜出來", seen[0]["mime"] == "image/png", str(seen[0].get("mime")))
        # 少了這個，卡片會掉進使用者當下正在看的那個對話
        check("帶著發起的對話", seen[0]["conv_id"] == "work-1", str(seen[0].get("conv_id")))

        r = await send_file.handler({"path": "隨便亂寫"})
        check("路徑亂寫會被擋", r.get("is_error") is True)
        check("錯誤訊息看得懂", "絕對路徑" in r["content"][0]["text"],
              r["content"][0]["text"][:50])
        check("被擋下就不通知", len(seen) == 1, str(len(seen)))

    asyncio.run(run())


if __name__ == "__main__":
    test_usage_record()
    test_usage_robust()
    test_local_usage_partial_line()
    test_local_usage_rewrite_no_double_count()
    test_outbox_validation()
    test_download()
    test_send_file_tool()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    sys.exit(1 if FAILED else 0)
