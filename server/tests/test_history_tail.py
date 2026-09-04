"""歷史只讀逐字稿的尾巴，結果必須跟整份解析一模一樣。

畫面要的是最後 60 則，而逐字稿會長到幾百 MB（這台機器上有兩份 220MB 的）。
先前是從頭解析到尾、最後才切 `out[-limit:]`，切一次對話要 2~3 秒。

真實資料的對照跑過一次（五條對話全部一字不差，220MB 那條 3276ms → 44ms），
這裡用合成資料把行為釘住：`_TAIL_STEPS` 改成很小的值，就不必為了測試寫出
一個幾 MB 的檔案。
"""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

import config  # noqa: F401

from engine import history as h

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def rec(role: str, text: str, pad: int = 0) -> str:
    """一則逐字稿記錄。[pad] 是用來灌大檔案的填充。"""
    body: dict = {
        "type": role,
        "timestamp": "2026-08-23T12:00:00.000Z",
        "message": {"content": [{"type": "text", "text": text}]},
    }
    if pad:
        # 填充放在模型看不到的欄位，不影響解析結果
        body["_pad"] = "x" * pad
    return json.dumps(body, ensure_ascii=False)


def write_log(lines: list[str]) -> Path:
    f = tempfile.NamedTemporaryFile(
        "w", suffix=".jsonl", delete=False, encoding="utf-8",
    )
    f.write("\n".join(lines) + "\n")
    f.close()
    return Path(f.name)


def install(path: Path) -> None:
    """把 history 找檔案的兩條路都指到這份假逐字稿。"""
    h._load_map = lambda: {"test": {"session_id": "fake"}}   # type: ignore[assignment]
    h._session_file = lambda sid: path                       # type: ignore[assignment]


def whole_file_answer(path: Path, limit: int = 60) -> list[dict]:
    """舊實作的等價寫法，當對照組。"""
    lines, _ = h._tail_lines(path, None)
    return h._drop_answered_asks(h._parse_lines(lines, 0)[-limit:])


def test_tail_matches_whole() -> None:
    """尾巴裝不下 60 則、必須往前翻的情況。"""
    print("\n[往前翻到湊滿]")
    # 每則塞 500 bytes 填充，200 則約 100KB——遠超過下面設的第一段尾巴
    lines = [rec("user" if i % 2 == 0 else "assistant", f"第 {i} 句", pad=500)
             for i in range(200)]
    path = write_log(lines)
    install(path)
    h._TAIL_STEPS = (2048, 16384, None)          # type: ignore[assignment]
    got = h.load_history("test")
    check("拿到 60 則", len(got) == 60, str(len(got)))
    check("跟整份解析一致", got == whole_file_answer(path))
    check("最後一則是最新的", got[-1]["text"] == "第 199 句", got[-1]["text"])
    path.unlink()


def test_first_partial_line_dropped() -> None:
    """從中間切下去的第一行是半截的，不能讓它壞掉整份結果。"""
    print("\n[切在一行中間]")
    lines = [rec("user" if i % 2 == 0 else "assistant", f"第 {i} 句", pad=300)
             for i in range(40)]
    path = write_log(lines)
    install(path)
    # 刻意選一個一定會切在行中間的大小
    h._TAIL_STEPS = (1500, None)                 # type: ignore[assignment]
    got = h.load_history("test", limit=5)
    check("湊得滿 5 則", len(got) == 5, str(len(got)))
    check("內容跟整份解析一致", got == whole_file_answer(path, 5))
    check("沒有半截的文字", all(x["text"].startswith("第 ") for x in got),
          str([x["text"] for x in got]))
    path.unlink()


def test_short_file_reads_whole() -> None:
    """檔案比第一段還小的時候，讀的就是整份。"""
    print("\n[小檔案]")
    path = write_log([rec("user", "只有一句")])
    install(path)
    h._TAIL_STEPS = (4 << 20, None)              # type: ignore[assignment]
    got = h.load_history("test")
    check("讀得到", len(got) == 1 and got[0]["text"] == "只有一句", str(got))
    path.unlink()


def test_head_still_reads_from_front() -> None:
    """取標題那條路要從檔頭讀，不能被尾巴那套影響。"""
    print("\n[取開頭]")
    lines = [rec("user" if i % 2 == 0 else "assistant", f"第 {i} 句")
             for i in range(50)]
    path = write_log(lines)
    install(path)
    h._TAIL_STEPS = (100, None)                  # type: ignore[assignment]
    got = h.load_history("test", head=3)
    check("拿到 3 則", len(got) == 3, str(len(got)))
    check("而且是最前面那 3 則", got[0]["text"] == "第 0 句", got[0]["text"])
    path.unlink()


def test_meta_dropped() -> None:
    """CLI 標了 isMeta 的那些不是人打的，一則都不可以畫進歷史。

    2026-08-26 使用者在對話裡看到整份 SKILL.md 頂著「你」的標籤
    顯示成他自己說的話。載入 skill 時 CLI 就是這樣把 SKILL.md 塞進 user turn，
    而那則帶著 `isMeta: true`。
    """
    print("\n[isMeta 的注入]")
    meta = json.dumps({
        "type": "user", "isMeta": True,
        "timestamp": "2026-08-23T12:00:00.000Z",
        "message": {"content": [{
            "type": "text",
            "text": "Base directory for this skill: C:\\x\\3d-print\n\n# 3D Print Skill",
        }]},
    }, ensure_ascii=False)
    cont = json.dumps({
        "type": "user", "isMeta": True,
        "timestamp": "2026-08-23T12:00:00.000Z",
        "message": {"content": [
            {"type": "text", "text": "Continue from where you left off."}]},
    }, ensure_ascii=False)
    path = write_log([rec("user", "早安"), meta, cont, rec("assistant", "40 度")])
    install(path)
    h._TAIL_STEPS = (100, None)                  # type: ignore[assignment]
    got = h.load_history("test")
    check("只剩真的兩則", len(got) == 2, str([i["text"][:12] for i in got]))
    check("skill 全文不在裡面",
          not any("3D Print Skill" in i["text"] for i in got))
    check("續跑提示也不在", not any("Continue from" in i["text"] for i in got))
    path.unlink()


def main() -> int:
    test_tail_matches_whole()
    test_first_partial_line_dropped()
    test_short_file_reads_whole()
    test_head_still_reads_from_front()
    test_meta_dropped()
    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(main())
