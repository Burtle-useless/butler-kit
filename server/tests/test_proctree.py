"""CLI 關閉後的行程善後（engine.proctree）。

實測服務底下會殘留好幾天前的 CLI 行程樹：SDK 的 close()
在呼叫端被取消時會跳過 terminate／kill，而 Windows 的 terminate 也只殺 claude.exe 本身。
這裡真的開行程：父行程（代表 CLI）開一個子行程（代表 MCP），先把父行程殺掉、
子行程留著，確認 reap 只收殘留的那個、而且不碰清單以外的行程。
"""
from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402,F401
from engine import proctree  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def test_pure() -> None:
    print("\n[樹與白名單（假資料）]")
    procs = [
        (10, 1, "claude.exe"), (11, 10, "weather-mcp.exe"), (12, 11, "python.exe"),
        (13, 10, "cmd.exe"), (14, 13, "node.exe"), (15, 1, "chrome.exe"),
        (16, 13, "chrome.exe"),
    ]
    tree = proctree.descendants(10, procs)
    check("整棵樹都在", sorted(p.pid for p in tree) == [10, 11, 12, 13, 14, 16])
    check("根在最前面", tree[0].pid == 10)
    check("不相干的行程不在樹裡", all(p.pid != 15 for p in tree))
    check("MCP 算輔助行程", proctree.is_helper("weather-mcp.exe") and proctree.is_helper("Notes-MCP.exe"))
    check("CLI 與直譯器算", all(proctree.is_helper(n) for n in ("claude.exe", "node.exe", "python.exe", "cmd.exe")))
    check("使用者的程式不算", not any(proctree.is_helper(n) for n in ("chrome.exe", "notepad.exe", "explorer.exe", "Code.exe")))
    check("找不到根就是空的", proctree.descendants(999, procs) == [])


def _spawn_tree() -> tuple[subprocess.Popen, int]:
    """開一個父行程，它再開一個會活 60 秒的子行程；回傳父行程與子行程的 pid。"""
    code = (
        "import subprocess, sys, time\n"
        "c = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])\n"
        "print(c.pid, flush=True)\n"
        "time.sleep(60)\n"
    )
    parent = subprocess.Popen([sys.executable, "-c", code], stdout=subprocess.PIPE, text=True)
    child_pid = int(parent.stdout.readline().strip())
    return parent, child_pid


def test_real() -> None:
    if sys.platform != "win32":
        print("\n[真行程] 非 Windows，略過")
        return
    print("\n[真行程：父行程先死，子行程還活著]")
    parent, child_pid = _spawn_tree()
    try:
        time.sleep(0.5)
        tree = proctree.descendants(parent.pid)
        pids = [p.pid for p in tree]
        check("快照抓得到父子兩層", parent.pid in pids and child_pid in pids, str(pids))
        check("讀得到建立時間", all(p.created for p in tree))
        # 模擬 SDK 只 terminate 了 CLI 本身
        parent.kill()
        parent.wait(5)
        t0 = time.monotonic()
        killed = proctree.reap(tree, grace=1.0)
        # venv 的 python.exe 是啟動器、會再開一個真正的直譯器，所以一個「行程」是兩層；
        # 要看的是殘留的子行程在收掉的清單裡、而且整棵樹沒有活口
        check("收掉殘留的子行程", child_pid in [p.pid for p in killed], str([p.pid for p in killed]))
        check("有等寬限期", time.monotonic() - t0 >= 0.9)
        time.sleep(0.3)
        check("整棵樹沒有活口", not any(proctree._alive(p) for p in tree))
    finally:
        parent.kill()

    print("\n[真行程：都自己結束了就什麼都不做]")
    parent, child_pid = _spawn_tree()
    time.sleep(0.5)
    tree = proctree.descendants(parent.pid)
    subprocess.run(["taskkill", "/T", "/F", "/PID", str(parent.pid)], capture_output=True)
    parent.wait(5)
    check("回傳空清單", proctree.reap(tree, grace=1.0) == [])

    print("\n[建立時間不同的同一個 pid 不動]")
    fake = proctree.Proc(pid=4, ppid=0, name="python.exe", created=1)   # System 行程的 pid，建立時間對不上
    check("pid 重用不誤殺", proctree.reap([fake], grace=0.2) == [])


def main() -> int:
    test_pure()
    test_real()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
