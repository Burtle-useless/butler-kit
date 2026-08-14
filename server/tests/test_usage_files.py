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
from engine import usage            # noqa: E402

TMP = Path(tempfile.mkdtemp())
usage.USAGE_FILE = TMP / "usage.json"
outbox.OUTBOX_FILE = TMP / "outbox.json"

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
        check("擋下超大檔", "檔案分享服務" in str(e), str(e)[:60])
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

    async def run() -> None:
        src = TMP / "圖.png"
        src.write_bytes(b"\x89PNG fake")
        r = await ft.send_file.handler({"path": str(src), "note": "做好的圖"})
        check("傳檔成功", not r.get("is_error"), str(r)[:80])
        check("有通知手機", len(seen) == 1 and seen[0]["name"] == "圖.png", str(seen)[:80])
        check("mime 有猜出來", seen[0]["mime"] == "image/png", str(seen[0].get("mime")))

        r = await ft.send_file.handler({"path": "隨便亂寫"})
        check("路徑亂寫會被擋", r.get("is_error") is True)
        check("錯誤訊息看得懂", "絕對路徑" in r["content"][0]["text"],
              r["content"][0]["text"][:50])
        check("被擋下就不通知", len(seen) == 1, str(len(seen)))

    asyncio.run(run())


if __name__ == "__main__":
    test_usage_record()
    test_usage_robust()
    test_outbox_validation()
    test_download()
    test_send_file_tool()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    sys.exit(1 if FAILED else 0)
