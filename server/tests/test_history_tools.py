"""重建歷史時的工具軌跡。

這批釘的是「回看歷史時看不到助理做了什麼」——不會報錯、不會慢，只是安靜地
少了一半內容。2026-08-17 使用者回報思考不見（當時只修了思考），
2026-08-25 回報工具軌跡不見，是同一個判斷失誤的另一半。

最重要的是 `test_tools_come_from_textless_records`：實測那份 220MB 的逐字稿，
12335 次工具呼叫**全部**落在「沒有文字」的 assistant record 裡。累積工具的
程式碼只要放到「沒文字就跳過」後面一行，抽出來就永遠是空的——而且不報錯。
"""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from engine import history  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def _user(text: str) -> str:
    return json.dumps(
        {"type": "user", "message": {"content": [{"type": "text", "text": text}]}},
        ensure_ascii=False,
    )


def _asst(*blocks: dict) -> str:
    return json.dumps(
        {"type": "assistant", "message": {"content": list(blocks)}}, ensure_ascii=False
    )


def _tool(name: str, **inp: object) -> dict:
    return {"type": "tool_use", "name": name, "input": inp, "id": "tu_x"}


def _parse(lines: list[str]) -> list[dict]:
    """把幾行逐字稿走完整條 load_history 的路。"""
    with tempfile.TemporaryDirectory() as d:
        home = Path(d)
        proj = home / ".claude" / "projects" / "C--Users-you"
        proj.mkdir(parents=True)
        sid = "cccc1111-2222-3333-4444-555566667777"
        (proj / f"{sid}.jsonl").write_text("\n".join(lines), encoding="utf-8")
        with patch.object(Path, "home", staticmethod(lambda: home)), \
                patch.object(history, "_load_map", lambda: {"c1": {"session_id": sid}}):
            return history.load_history("c1")


def test_tools_come_from_textless_records() -> None:
    """工具呼叫住在沒有文字的 record 裡，要累積到下一則有文字的訊息上。"""
    print("\n[工具從無文字的 record 累積]")
    got = _parse([
        _user("幫我看一下"),
        _asst({"type": "thinking", "thinking": "先讀檔", "signature": "s"}),
        _asst(_tool("Read", file_path="C:/a/b.py")),
        _asst(_tool("Bash", command="ls -la", description="列出檔案")),
        _asst({"type": "text", "text": "看完了。"}),
    ])
    check("三則 record 併成一則回覆", len(got) == 2, str(len(got)))
    reply = got[-1]
    tools = reply.get("tools") or []
    check("兩次工具都在", len(tools) == 2, str(len(tools)))
    check("順序是舊到新", [t["tool"] for t in tools] == ["Read", "Bash"],
          str([t["tool"] for t in tools]))
    check("摘要走 tool_info（Read 只留檔名）",
          tools[0]["summary"] == "b.py", tools[0]["summary"])
    check("Bash 摘要帶上意圖說明",
          tools[1]["summary"].startswith("列出檔案"), tools[1]["summary"])
    check("思考照樣在", reply.get("think") == "先讀檔", str(reply.get("think")))


def test_tools_do_not_leak_across_turns() -> None:
    """上一回合的工具不可以掛到下一回合的回覆上。"""
    print("\n[工具不跨回合]")
    got = _parse([
        _user("第一件事"),
        _asst(_tool("Read", file_path="C:/a.py")),
        _asst({"type": "text", "text": "做好了"}),
        _user("第二件事"),
        _asst({"type": "text", "text": "這件不用動手"}),
    ])
    check("兩則回覆", len([m for m in got if m["role"] == "assistant"]) == 2)
    first, second = [m for m in got if m["role"] == "assistant"]
    check("第一則帶著它的工具", len(first.get("tools") or []) == 1)
    check("第二則沒有工具", "tools" not in second, str(second.get("tools")))


def test_dangerous_flag_survives() -> None:
    """破壞性指令的旗標要一起還原——畫面靠它決定強制展開紅框。"""
    print("\n[破壞性旗標]")
    got = _parse([
        _user("清掉"),
        _asst(_tool("Bash", command="rm -rf C:/tmp/x")),
        _asst({"type": "text", "text": "清好了"}),
    ])
    tools = got[-1].get("tools") or []
    check("有抓到那條指令", len(tools) == 1, str(tools))
    check("標成危險", bool(tools and tools[0]["dangerous"]), str(tools))
    check("原文完整留著", bool(tools and "rm -rf" in tools[0]["raw"]), str(tools))


def test_volume_guards() -> None:
    """體積護欄：指令原文有上限、每則工具數有上限。

    歷史一次回 60 則，而 raw 在即時事件裡是刻意不截斷的。少了這兩道，
    一個長回合就能讓 snapshot 膨脹到讓人等不下去（2026-08-23 已經痛過一次）。
    """
    print("\n[體積護欄]")
    long_cmd = "echo " + "x" * 5000
    many = [_tool("Read", file_path=f"C:/f{i}.py") for i in range(history._TOOLS_MAX + 10)]
    got = _parse([
        _user("跑很多"),
        _asst(_tool("Bash", command=long_cmd)),
        _asst(*many),
        _asst({"type": "text", "text": "跑完了"}),
    ])
    tools = got[-1].get("tools") or []
    check("每則工具數封頂", len(tools) == history._TOOLS_MAX, str(len(tools)))
    check("指令原文截斷",
          len(tools[0]["raw"]) <= history._TOOL_RAW_MAX + 1, str(len(tools[0]["raw"])))
    check("截斷有省略號", tools[0]["raw"].endswith("…"), tools[0]["raw"][-20:])


def test_user_side_never_gets_tools() -> None:
    """使用者訊息不該有 tools 欄位——那側根本不會有工具。"""
    print("\n[使用者側乾淨]")
    got = _parse([
        _asst(_tool("Read", file_path="C:/a.py")),
        _user("我說話"),
    ])
    users = [m for m in got if m["role"] == "user"]
    check("使用者訊息沒有 tools", all("tools" not in m for m in users), str(users))


def main() -> int:
    test_tools_come_from_textless_records()
    test_tools_do_not_leak_across_turns()
    test_dangerous_flag_survives()
    test_volume_guards()
    test_user_side_never_gets_tools()
    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(main())
