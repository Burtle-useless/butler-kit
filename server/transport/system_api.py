"""服務本身的控制台：看狀態、從手機按重啟。

**為什麼要有這個。** 助理改完伺服器程式之後要重啟才會生效，而它以前是自己去跑
重啟腳本——那等於自殺：它的行程是伺服器的子孫，重啟一定連它一起收掉，
使用者看到的就是講到一半突然斷線、不知道發生什麼事。

把按鈕交給使用者之後，斷線變成一件他自己按下去、預期會發生的事。助理的工作
改成「說一句要重啟才生效」，剩下的由這裡處理。
"""
from __future__ import annotations

import asyncio
import subprocess
from datetime import datetime
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, HTTPException

import config
from .auth import require_token

router = APIRouter(prefix="/v1/system", tags=["system"])

# 這個行程什麼時候起來的。用模組載入時間就夠準：它在 uvicorn 起來前一刻執行，
# 而我們要回答的是「這份程式碼跑多久了」，不是行程建立的精確毫秒。
STARTED_AT = datetime.now()

# 重啟腳本在專案根目錄，也就是 server/ 的上一層。
RESTART_PS1: Path = config.SERVER_DIR.parent / "restart_butler.ps1"


def _uptime_text(sec: float) -> str:
    """把秒數講成人看得懂的長度。控制台要的是「跑多久了」不是幾點啟動。"""
    if sec < 60:
        return f"{int(sec)} 秒"
    if sec < 3600:
        return f"{int(sec // 60)} 分鐘"
    hours = int(sec // 3600)
    if hours < 24:
        mins = int((sec % 3600) // 60)
        return f"{hours} 小時 {mins} 分" if mins else f"{hours} 小時"
    return f"{hours // 24} 天 {hours % 24} 小時"


def _git_head() -> dict[str, str]:
    """磁碟上最新的一筆 commit。

    拿不到就回空字串：git 不在、不是 repo、或這是打包出去的複本，
    都不該讓控制台整頁壞掉。
    """
    root = config.SERVER_DIR.parent
    try:
        out = subprocess.run(
            ["git", "log", "-1", "--format=%h|%s"],
            cwd=root, capture_output=True, text=True, encoding="utf-8",
            timeout=5, check=False,
        )
        if out.returncode != 0:
            return {"commit": "", "subject": ""}
        commit, _, subject = (out.stdout or "").strip().partition("|")
        return {"commit": commit, "subject": subject}
    except (OSError, subprocess.SubprocessError):
        return {"commit": "", "subject": ""}


# 這個行程啟動時，磁碟上的程式碼是哪一版。放在模組層而不是每次查：
# 這才是「正在跑的版本」的定義——之後 git 再怎麼變，跑的都還是這一份。
BOOT_HEAD: dict[str, str] = _git_head()


@router.get("")
async def status(_: str = Depends(require_token)) -> dict[str, Any]:
    """服務現況。`git` 要開子行程，包 to_thread 免得卡住事件迴圈。

    回兩個版本而不是一個。原本只回「現在的 HEAD」，那答的是**磁碟上**的版本，
    不是**正在跑**的版本：改完程式碼一 commit，控制台立刻顯示新的雜湊，
    可是行程裡跑的還是舊的那份——那行字於是專門在使用者最需要它的時候說謊
    （「我到底按過重啟了沒？」）。現在跑的那份看 [BOOT_HEAD]，它在行程啟動時
    就固定了；兩者不同就是「有東西還沒生效」。
    """
    import os

    now = datetime.now()
    latest = await asyncio.to_thread(_git_head)
    return {
        "pid": os.getpid(),
        "started_at": STARTED_AT.strftime("%Y-%m-%dT%H:%M:%S"),
        "uptime": _uptime_text((now - STARTED_AT).total_seconds()),
        "port": config.PORT,
        "commit": BOOT_HEAD["commit"],
        "subject": BOOT_HEAD["subject"],
        "latest_commit": latest["commit"],
        "latest_subject": latest["subject"],
    }


@router.post("/restart")
async def restart(_: str = Depends(require_token)) -> dict[str, Any]:
    """重啟服務。

    這個回應會**趕在服務停掉之前**送出去：重啟腳本自己會先等到沒有人在串流
    （連續數秒沒有新事件）才動手，那段時間足夠把這則回應寫回連線。
    手機端因此能顯示「重啟中」，而不是看到一個沒有回應的請求。

    腳本用 PowerShell 跑，而且它開頭會把自己重新投遞成獨立行程（脫離這棵
    行程樹）再退出——否則它在殺掉伺服器的瞬間會把自己也殺掉，重啟就只做了
    一半。這裡不必也不該等它結束。
    """
    if not RESTART_PS1.is_file():
        raise HTTPException(status_code=500, detail=f"找不到重啟腳本：{RESTART_PS1}")

    def _spawn() -> None:
        subprocess.Popen(
            [
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", str(RESTART_PS1),
            ],
            cwd=str(RESTART_PS1.parent),
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            # 不繼承這邊的控制台，也不要因為父行程被殺就跟著死
            creationflags=subprocess.CREATE_NEW_PROCESS_GROUP,
        )

    await asyncio.to_thread(_spawn)
    return {
        "ok": True,
        "note": "重啟中。等現在這輪講完才會動手，之後連線會自己接回來。",
    }
