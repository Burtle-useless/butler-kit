"""接管改用複印本之後的迴歸測試。

盯著的是「兩邊寫同一份逐字稿」這個真實發生過的狀況：驗複印本確實是獨立的
另一個檔、sessionId 改乾淨了、內容一字不差，而且原始那筆不會又冒回清單。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from engine import sessions                                    # noqa: E402


def _write_session(root: Path, sid: str, lines: list[dict]) -> Path:
    proj = root / "C--Users-you"
    proj.mkdir(parents=True, exist_ok=True)
    jf = proj / f"{sid}.jsonl"
    with jf.open("w", encoding="utf-8", newline="\n") as f:
        for rec in lines:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    return jf


def test_fork_makes_independent_copy(tmp: Path) -> list[str]:
    fails: list[str] = []
    old = "11111111-2222-3333-4444-555555555555"
    rows = [
        {"type": "user", "sessionId": old, "uuid": "u1", "cwd": "C:\\x",
         "message": {"content": [{"type": "text", "text": "早安"}]}},
        {"type": "assistant", "sessionId": old, "uuid": "u2", "parentUuid": "u1",
         "message": {"content": [{"type": "text", "text": "早"}]}},
    ]
    src = _write_session(tmp, old, rows)

    with patch.object(sessions, "_projects_dir", lambda: tmp):
        new = sessions.fork_session(old)

    if not new:
        return ["fork_session 回 None"]
    if new == old:
        fails.append("複印本沿用了同一個 id")

    dst = src.with_name(f"{new}.jsonl")
    if not dst.exists():
        return [*fails, "複印本的檔案不存在"]
    if not src.exists():
        fails.append("原始檔被動到了（應該原封不動）")

    got = [json.loads(x) for x in dst.read_text(encoding="utf-8").splitlines()]
    if len(got) != len(rows):
        fails.append(f"行數不對：{len(got)} != {len(rows)}")
    if any(r.get("sessionId") != new for r in got):
        fails.append("複印本裡還有沒改到的 sessionId")
    # 除了 sessionId 以外每個欄位都要一模一樣，否則歷史就變了
    if [r.get("uuid") for r in got] != ["u1", "u2"]:
        fails.append("uuid 鏈被改動")
    if got[0]["message"]["content"][0]["text"] != "早安":
        fails.append("訊息內容被改動")

    # 原始檔不能被污染
    orig = [json.loads(x) for x in src.read_text(encoding="utf-8").splitlines()]
    if any(r.get("sessionId") != old for r in orig):
        fails.append("原始檔的 sessionId 被改掉了")

    # 時間戳要跟原檔一致。差一點點就會讓複印本變成 projects 目錄裡最新的一筆，
    # 桌面那邊 `claude --continue` 就會接到它上面去（實際發生過）。
    if abs(dst.stat().st_mtime - src.stat().st_mtime) > 1:
        fails.append("複印本的 mtime 沒有跟著原檔，會被 --continue 挑走")
    return fails


def test_fork_keeps_unparsable_lines(tmp: Path) -> list[str]:
    """壞行要照抄。丟掉會讓 uuid 鏈斷開，比留著一行讀不懂的更糟。"""
    old = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    proj = tmp / "C--Users-you"
    proj.mkdir(parents=True, exist_ok=True)
    jf = proj / f"{old}.jsonl"
    jf.write_text(
        json.dumps({"type": "user", "sessionId": old, "uuid": "u1"}) + "\n"
        + "{ 這行不是合法 JSON\n"
        + json.dumps({"type": "user", "sessionId": old, "uuid": "u2"}) + "\n",
        encoding="utf-8", newline="\n",
    )
    with patch.object(sessions, "_projects_dir", lambda: tmp):
        new = sessions.fork_session(old)
    if not new:
        return ["fork_session 回 None"]
    got = (proj / f"{new}.jsonl").read_text(encoding="utf-8").splitlines()
    fails: list[str] = []
    if len(got) != 3:
        fails.append(f"行數不對：{len(got)} != 3（壞行應該照抄）")
    if "這行不是合法 JSON" not in got[1]:
        fails.append("壞行沒有被保留")
    return fails


def test_fork_missing_session(tmp: Path) -> list[str]:
    with patch.object(sessions, "_projects_dir", lambda: tmp):
        if sessions.fork_session("99999999-0000-0000-0000-000000000000") is not None:
            return ["不存在的 session 應該回 None"]
    return []


def test_adopted_session_stays_hidden(tmp: Path) -> list[str]:
    """接管過的原始 session 不可以又出現在「電腦上的 session」清單裡。"""
    old = "77777777-8888-9999-aaaa-bbbbbbbbbbbb"
    _write_session(tmp, old, [
        {"type": "user", "sessionId": old, "uuid": "u1", "cwd": "C:\\x",
         "message": {"content": [{"type": "text", "text": "接管我"}]}},
    ])
    # butler 存的是複印本的 id，原始 id 只在 forked_from 裡
    fake_map = {"cabc": {"session_id": "fork-of-it", "forked_from": old}}
    with patch.object(sessions, "_projects_dir", lambda: tmp), \
            patch.object(sessions, "_load_map", lambda: fake_map):
        rows = sessions.scan_sessions(50, "", 0)
    if any(r.get("session_id") == old for r in rows):
        return ["接管過的原始 session 又出現在清單裡"]
    return []


def main() -> int:
    import shutil
    import tempfile

    tests = [
        test_fork_makes_independent_copy,
        test_fork_keeps_unparsable_lines,
        test_fork_missing_session,
        test_adopted_session_stays_hidden,
    ]
    bad = 0
    for fn in tests:
        tmp = Path(tempfile.mkdtemp(prefix="butler_fork_"))
        try:
            fails = fn(tmp)
        except Exception as e:            # noqa: BLE001
            fails = [f"炸了：{e!r}"]
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        if fails:
            bad += 1
            print(f"[FAIL] {fn.__name__}")
            for m in fails:
                print(f"        {m}")
        else:
            print(f"[ok]   {fn.__name__}")
    print("全過" if not bad else f"{bad} 個測試失敗")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
