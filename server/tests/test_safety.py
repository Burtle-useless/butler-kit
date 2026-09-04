"""破壞性動作的確認防線。**這一支守的是「該跳確認的有沒有真的跳」。**

這道防線由兩個各自獨立的東西組成，缺一個就整條是空的：

  `safety.needs_confirm`      判斷這次呼叫該不該攔
  `options.PRETOOL_MATCHER`   決定 hook 會不會**被呼叫到**

第二個是隱形的那一半。matcher 漏掉一個工具名，needs_confirm 寫得再對也不會執行，
而症狀只是「破壞性指令沒跳確認就跑了」——沒有例外、沒有 log、沒有任何指向 matcher
的線索。所以這裡兩邊都釘，而且釘的是**同一個常數**，不是各自抄一份字串。

2026-08-27 建立，起因是有一支新的 MCP 工具要進 matcher；那支工具後來拿掉了，
這道「兩邊釘同一個常數」的檢查留著。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config                                            # noqa: E402
from engine import safety                                # noqa: E402
from engine.options import PRETOOL_MATCHER               # noqa: E402
from engine.safety import confirm_prompt, needs_confirm  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def force_confirm_on() -> None:
    """把總開關強制打開再測。

    **這台機器上它實際是關的**：`launch_butler.vbs` 寫死了 `CONFIRM_DANGEROUS=0`，
    是使用者自己關的（TODO 有一條「要改 0→1 才會啟用生物辨識確認」）。測試在
    butler 底下跑會繼承那個環境變數。

    不強制打開的話這整份測試會**全綠地什麼都沒驗到**——needs_confirm 第一行就
    因為總開關而回 False，每一條「該攔的」都會通過，然後在真的開啟確認的那天
    才發現判斷邏輯是錯的。2026-08-27 建檔時就先撞上這件事。
    """
    config.CONFIRM_ENABLED = True                   # type: ignore[misc]


def test_matcher() -> None:
    print("\n[hook 抓不抓得到那支工具]")
    # 這條是整份測試的重點。needs_confirm 判斷得再準，工具名沒進 matcher
    # 的話 hook 不會被呼叫，破壞性指令就這樣直接送出去了
    check("matcher 命中 Bash",
          re.fullmatch(PRETOOL_MATCHER, "Bash") is not None)
    check("matcher 命中 PowerShell",
          re.fullmatch(PRETOOL_MATCHER, "PowerShell") is not None)
    check("matcher 命中 AskUserQuestion",
          re.fullmatch(PRETOOL_MATCHER, "AskUserQuestion") is not None)
    # 反面：唯讀工具不該進 hook。進了不會出錯，但每次呼叫都多繞一圈
    for t in ("Read", "Glob", "Grep"):
        check(f"matcher 不碰 {t}",
              re.fullmatch(PRETOOL_MATCHER, t) is None)


def test_bash() -> None:
    print("\n[Bash／PowerShell 照舊]")
    check("rm -rf 會攔", needs_confirm("Bash", {"command": "rm -rf /tmp/x"}))
    check("git push 會攔", needs_confirm("Bash", {"command": "git push origin main"}))
    check("Remove-Item 會攔",
          needs_confirm("PowerShell", {"command": "Remove-Item -Recurse x"}))
    check("一般指令不攔", not needs_confirm("Bash", {"command": "ls -la"}))
    # `confirm` 這個字裡有 firm，詞界比對沒寫好就會誤判
    check("confirm 不誤判", not needs_confirm("Bash", {"command": "echo confirm"}))
    # 沒進名單的工具一律放行，否則讀檔寫檔全都要按一次
    check("Read 不攔", not needs_confirm("Read", {"file_path": "x.txt"}))


def test_fail_closed() -> None:
    print("\n[判斷壞掉時要往安全的那邊倒]")
    # tool_input 不是 dict 就會在 .get 那裡炸。炸了要當成危險，不是當成安全——
    # 不然「弄壞判斷函式」就成了解除安全鎖的後門
    check("input 型別不對時攔下來", needs_confirm("Bash", "rm -rf /"))  # type: ignore[arg-type]

    # 總開關關掉時全放行。這是設計不是漏洞——但它必須是唯一一個能全放行的路徑。
    #
    # **這條描述的正是這台機器現在的狀態**（launch_butler.vbs 設了
    # CONFIRM_DANGEROUS=0）。也就是說：破壞性指令不會跳確認，直接就跑了。
    # 那是使用者自己選的，這裡不改它，只把後果釘在測試裡讓它是明講的
    try:
        config.CONFIRM_ENABLED = False              # type: ignore[misc]
        check("總開關關掉後 rm -rf 不攔",
              not needs_confirm("Bash", {"command": "rm -rf /"}))
    finally:
        force_confirm_on()
    check("總開關復原了", config.CONFIRM_ENABLED is True)


def test_prompt() -> None:
    print("\n[確認框上寫什麼]")
    cmd = "rm -rf /var/log && echo done"
    t2, b2, r2 = confirm_prompt("Bash", {"command": cmd}, 5)
    # **指令全文不可截斷或摘要。** 攻擊面正是「說明講 A、指令做 B」，
    # 一截尾就把破壞性尾段推出視野
    check("Bash 顯示指令全文", r2 == cmd, r2)
    check("Bash 標題帶工具名", "Bash" in t2, t2)
    check("Bash 說明照舊", "破壞性操作" in b2)
    check("逾時分鐘數有帶進去", "5 分鐘" in b2, b2)


def test_deny_reasons() -> None:
    print("\n[擋下來之後跟模型說什麼]")
    # 這兩句話的用途是「讓模型停手」，不是解釋。少了「不要換方法繞過」
    # 這種明確指令，模型會自己改寫指令再送一次
    check("取消時叫它別重試",
          "不要改寫指令重試" in safety.DENY_CANCEL_REASON
          and "不要換方法繞過" in safety.DENY_CANCEL_REASON)
    check("委派提問時叫它停下來",
          "不要自問自答" in safety.ASK_DELEGATED_REASON)
    d = safety.deny("測試用")
    check("deny 的形狀對",
          d["hookSpecificOutput"]["permissionDecision"] == "deny"
          and d["hookSpecificOutput"]["permissionDecisionReason"] == "測試用")


if __name__ == "__main__":
    force_confirm_on()
    test_matcher()
    test_bash()
    test_fail_closed()
    test_prompt()
    test_deny_reasons()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    sys.exit(1 if FAILED else 0)
