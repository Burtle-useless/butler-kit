"""接回舊 session 前的存在性檢查。

沒有這道檢查，逐字稿被刪掉的對話會**永遠回不了話**：CLI resume 不到就
exit 1，而 stderr 那句「No conversation found with session ID」不會進到
SDK 的例外訊息，Python 這側只看得到「Command failed with exit code 1」。
錯誤分類器因此判成 UNKNOWN——不丟 client、不清 session——下一次照樣
帶著同一個壞 id 去撞。（2026-08-12 以 _diag_resume.py 實測確認。）
"""
from __future__ import annotations

import sys
import tempfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from engine import history                        # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def test_session_exists() -> None:
    print("\n[session 存在性]")
    with tempfile.TemporaryDirectory() as d:
        home = Path(d)
        proj = home / ".claude" / "projects" / "C--Users-you"
        proj.mkdir(parents=True)
        sid = "a614efdb-63d2-4ead-b6c7-7d87e5ffa275"
        (proj / f"{sid}.jsonl").write_text("{}", encoding="utf-8")
        with patch.object(Path, "home", staticmethod(lambda: home)):
            check("逐字稿還在就認得出來", history.session_exists(sid))
            check("逐字稿不在回 False", not history.session_exists("00000000-dead"))
    # 沒有 session 的新對話：不該去碰檔案系統，也不該被當成「接得回」
    check("None 直接回 False", not history.session_exists(None))
    check("空字串直接回 False", not history.session_exists(""))


def _rec(role: str, text: str) -> str:
    import json
    return json.dumps(
        {"type": role, "message": {"content": [{"type": "text", "text": text}]}},
        ensure_ascii=False,
    )


def test_history_hides_ops_messages() -> None:
    """重建歷史時只留人真的說過的話。

    CC 的 user turn 是個大雜燴：補跑提示、斜線指令、背景任務通知都寫在裡面，
    照單全收就會在手機上畫成一顆顆使用者訊息氣泡——使用者看到的是自己「說」了
    一整片英文 XML。（2026-08-14 回報，附了兩張截圖。）
    """
    print("\n[歷史過濾維運產物]")
    with tempfile.TemporaryDirectory() as d:
        home = Path(d)
        proj = home / ".claude" / "projects" / "C--Users-you"
        proj.mkdir(parents=True)
        sid = "b7f0c1a2-1111-2222-3333-444455556666"
        lines = [
            _rec("user", "幫我看一下那個檔案"),
            _rec("assistant", "看完了"),
            _rec("user", "<task-notification>\n<task-id>abc</task-id>\n</task-notification>"),
            _rec("user", "剛才這一輪中途觸發了自動壓縮，你現在看到的前文是摘要而不是原文。"),
            _rec("user", "[Image: original 1440x3088, displayed at 933x2000."),
            _rec("user", "<system-reminder>別忘了某件事</system-reminder>"),
            _rec("user", "剛才那一步還沒收尾。"),
            # 使用者本人提到這些詞不該被誤殺——判準是開頭，不是包含
            _rec("user", "那個 <task-notification> 是什麼東西"),
        ]
        (proj / f"{sid}.jsonl").write_text("\n".join(lines), encoding="utf-8")
        with patch.object(Path, "home", staticmethod(lambda: home)), \
                patch.object(history, "_load_map", lambda: {"c1": {"session_id": sid}}):
            out = history.load_history("c1")
    texts = [m["text"] for m in out]
    check("使用者的話留著", texts[:2] == ["幫我看一下那個檔案", "看完了"], str(texts))
    check("背景任務通知被擋掉", not any(t.startswith("<task-notification>") for t in texts))
    check("壓縮核對提示被擋掉", not any(t.startswith("剛才這一輪") for t in texts))
    check("讀圖尺寸註記被擋掉", not any(t.startswith("[Image:") for t in texts))
    check("system-reminder 被擋掉", not any(t.startswith("<system-reminder>") for t in texts))
    check("補跑提示被擋掉", not any(t.startswith("剛才那一步") for t in texts))
    check("只是提到關鍵字的不算維運產物",
          "那個 <task-notification> 是什麼東西" in texts, str(texts))
    # 背景任務通知不畫成使用者的話，但要變成一行 role=system 的「助理接手」說明——
    # 重開 App 才看得出助理為什麼突然多講一段
    notes = [m for m in out if m.get("role") == "system"]
    check("通知變成一行 system 說明", len(notes) == 1 and "助理接手" in notes[0]["text"],
          str(notes))
    check("對話本身只剩三則", len([m for m in out if m.get("role") != "system"]) == 3,
          str(len(texts)))


if __name__ == "__main__":
    test_session_exists()
    test_history_hides_ops_messages()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    sys.exit(1 if FAILED else 0)
