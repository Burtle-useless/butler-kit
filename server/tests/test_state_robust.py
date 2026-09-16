"""session.json 的持久化不可以因為一次讀檔失敗就把整份對應清掉。

先前 `_load_map` 任何例外都回 `{}`，`persist` 是「讀整份→改一筆→整份寫回」：
別的執行緒讀檔、主執行緒 `os.replace` 換檔的瞬間撞到 Windows 檔案鎖，下一次 persist
就只剩一條對話。這裡釘住：
  - 撞鎖：重試到底仍失敗要往上拋，不能回空 dict
  - JSON 壞掉：隔離舊檔（留證據），從空的開始，不炸
  - 記憶體是唯一真相：persist 一條不會動到其他條
"""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

import config  # noqa: F401
from engine import state as state_mod
from engine.state import ConvState

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}", flush=True)
    if not cond:
        FAILED.append(name)


def fresh_dir() -> Path:
    d = Path(tempfile.mkdtemp(prefix="butler-state-"))
    config.SESSION_FILE = d / "session.json"  # type: ignore[misc]
    state_mod._TITLES_FILE = d / "titles.json"
    state_mod.reset_cache()
    return d


def test_persist_keeps_others() -> None:
    print("\n[persist 只動自己那條]")
    d = fresh_dir()
    (d / "session.json").write_text(json.dumps({
        "main": {"session_id": "s-main", "cwd": str(Path.home())},
        "c1": {"session_id": "s-c1", "cwd": str(Path.home())},
    }), encoding="utf-8")
    st = ConvState(conv_id="c2", cwd=Path.home(), session_id="s-c2")
    state_mod.persist(st)
    data = json.loads((d / "session.json").read_text(encoding="utf-8"))
    check("三條都在", set(data) == {"main", "c1", "c2"}, str(sorted(data)))
    check("舊的沒被改", data["main"]["session_id"] == "s-main")


def test_lock_failure_does_not_wipe() -> None:
    print("\n[讀檔撞鎖：往上拋，絕不寫回空的]")
    d = fresh_dir()
    original = json.dumps({"main": {"session_id": "s-main", "cwd": str(Path.home())}})
    (d / "session.json").write_text(original, encoding="utf-8")

    def boom(path, encoding="utf-8", errors=None):
        raise PermissionError("locked")

    orig = state_mod.read_text_with_retry
    state_mod.read_text_with_retry = boom  # type: ignore[assignment]
    raised = False
    try:
        try:
            state_mod.persist(ConvState(conv_id="c9", cwd=Path.home(), session_id="s-c9"))
        except PermissionError:
            raised = True
    finally:
        state_mod.read_text_with_retry = orig  # type: ignore[assignment]
    check("persist 失敗會出聲", raised)
    check("檔案原封不動", (d / "session.json").read_text(encoding="utf-8") == original)
    # 鎖解開之後照常運作，而且沒有殘留的空快取
    state_mod.reset_cache()
    check("解鎖後讀得到原本的對話", state_mod.get_state("main").session_id == "s-main")


def test_corrupt_file_quarantined() -> None:
    print("\n[JSON 壞掉：隔離舊檔，從空的開始]")
    d = fresh_dir()
    (d / "session.json").write_text("{not json", encoding="utf-8")
    st = state_mod.get_state("c1")
    check("壞檔不炸", st.conv_id == "c1")
    quarantined = list(d.glob("session.json.corrupt-*"))
    check("壞檔被改名留證據", len(quarantined) == 1, str(quarantined))
    state_mod.persist(ConvState(conv_id="c1", cwd=Path.home(), session_id="s-c1"))
    data = json.loads((d / "session.json").read_text(encoding="utf-8"))
    check("新檔正常寫入", data.get("c1", {}).get("session_id") == "s-c1", str(data))


def test_titles_same_rules() -> None:
    print("\n[標題檔同一套規則]")
    d = fresh_dir()
    state_mod.set_title("c1", "第一條")
    state_mod.set_title("c2", "第二條")
    titles = json.loads((d / "titles.json").read_text(encoding="utf-8"))
    check("兩條都在", titles == {"c1": "第一條", "c2": "第二條"}, str(titles))
    check("讀回來一致", state_mod.get_title("c1") == "第一條")
    state_mod.delete_conversation("c1")
    check("刪除只動那條",
          state_mod.get_title("c1") is None and state_mod.get_title("c2") == "第二條")


def main() -> int:
    test_persist_keeps_others()
    test_lock_failure_does_not_wipe()
    test_corrupt_file_quarantined()
    test_titles_same_rules()
    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(main())
