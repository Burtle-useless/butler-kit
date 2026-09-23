"""服務本身的控制台：看狀態、從手機按重啟。

**為什麼要有這個。** 助理改完伺服器程式之後要重啟才會生效，而它以前是自己去跑
重啟腳本——那等於自殺：它的行程是伺服器的子孫，重啟一定連它一起收掉，
使用者看到的就是講到一半突然斷線、不知道發生什麼事。

把按鈕交給使用者之後，斷線變成一件他自己按下去、預期會發生的事。助理的工作
改成「說一句要重啟才生效」，剩下的由這裡處理。
"""
from __future__ import annotations

import asyncio
import logging
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, HTTPException

import config
from .auth import require_token

log = logging.getLogger(__name__)

router = APIRouter(prefix="/v1/system", tags=["system"])

# 這個行程什麼時候起來的。用模組載入時間就夠準：它在 uvicorn 起來前一刻執行，
# 而我們要回答的是「這份程式碼跑多久了」，不是行程建立的精確毫秒。
STARTED_AT = datetime.now()

# 重啟腳本在專案根目錄，也就是 server/ 的上一層。
RESTART_PS1: Path = config.SERVER_DIR.parent / "restart_butler.ps1"


def busy_convs() -> list[str]:
    """現在有哪幾條對話正在跑。

    延遲 import：`app.py` 在模組層掛這個 router，反過來在模組層 import 它會繞成
    一圈。

    這個問題以前是用猜的——重啟腳本每兩秒問一次 `latest_seq`，連續四秒沒變就
    當作沒人在忙。那是個代理指標，而且它猜錯的時候代價很大：按下重啟時
    若有一條對話正在讀 PDF，腳本等滿 60 秒上限後照樣硬幹，那一輪就被腰斬。
    這裡回的是事實。
    """
    from .app import worker
    return [c for c, t in worker.running.items() if t is not None and not t.done()]


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
    不是**正在跑**的版本：助理改完程式碼一 commit，控制台立刻顯示新的雜湊，
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
        # 按重啟之前要知道會不會踩到正在跑的事。這個數字直接決定按鈕的文案：
        # 沒事在跑就是「約半分鐘」，有事在跑就得先說清楚它會被切斷。
        "busy_convs": busy_convs(),
        # 課程頁是選配的，App 靠這個決定要不要顯示那個分頁——沒開的話
        # 底下的導覽列就少一格，而不是點進去看到一片空白
        "courses": config.COURSES_ENABLED,
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

    busy = busy_convs()
    await asyncio.to_thread(_spawn_restart)
    return {
        "ok": True,
        # 有事在跑就要說：腳本會先等，等不到就硬幹，而那整段時間畫面上什麼都
        # 不會發生。不講的話按鈕看起來就是壞的——按下去沒有任何反饋，
        # 其實是它在等。
        "busy_convs": busy,
        "note": (
            f"助理還在做 {len(busy)} 件事，腳本會等它們告一段落才動手，"
            "最多等一分鐘。"
            if busy else
            "重啟中，之後連線會自己接回來。"
        ),
    }


def _spawn_restart() -> None:
    """把重啟腳本投遞出去就走，不等它（見 restart）。"""
    subprocess.Popen(
        [
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", str(RESTART_PS1),
        ],
        cwd=str(RESTART_PS1.parent),
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        # 不繼承這邊的控制台，也不要因為父行程被殺就跟著死。
        # CREATE_NO_WINDOW：伺服器自己是隱藏跑的，沒有控制台可繼承，
        # 子行程於是自己開一個**看得見的**——按下重新啟動就跳一個黑窗出來。
        # 腳本裡 WMI 那層也要各自設一次（見 restart_butler.ps1），
        # 這個旗標只管得到直接生的這一個
        creationflags=(
            subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW
        ),
    )


def _restart_peers() -> None:
    """重啟共用這個 Python 環境的其他服務（config.PEER_RESTART，沒設就什麼都不做）。

    透過 WMI 建立行程，讓它掛在 WmiPrvSE 底下、不在這棵行程樹裡：這個服務自己的
    重啟腳本會把自己的子孫整串收掉，直接生的話對方重啟到一半就會被一起殺掉。

    WMI 與 wscript 只有 Windows 有，其他平台設了也不做。"""
    peer = config.PEER_RESTART
    if sys.platform != "win32" or peer is None or not peer.is_file():
        return
    cmd = f"wscript.exe //B \"{peer}\""
    ps = ("Invoke-CimMethod -ClassName Win32_Process -MethodName Create "
          f"-Arguments @{{CommandLine='{cmd}'}} | Out-Null")
    subprocess.run(["powershell.exe", "-NoProfile", "-Command", ps], timeout=60,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                   creationflags=subprocess.CREATE_NO_WINDOW)


def restart_supported() -> bool:
    """這台電腦能不能自己重啟服務：重啟腳本是 PowerShell，脫離行程樹靠的也是
    Windows 的行程旗標（見 _spawn_restart）。其他平台要自己重啟。"""
    return sys.platform == "win32" and RESTART_PS1.is_file()


def _after_sdk_update() -> None:
    """SDK 更新驗證通過：先重啟共用環境的其他服務，再重啟這個服務自己。

    自己重啟不了就拋出去：sdk_update 會把工作標成 manual，App 才知道要到電腦上
    手動重啟，而不是一直等一個不會發生的重啟。

    其他服務重啟失敗只記下來、不往上拋：那跟這個服務自己能不能重啟是兩回事。
    拋上去的話，這邊明明已經在重啟，工作卻被標成 manual，App 會叫人去電腦上手動重啟。"""
    try:
        _restart_peers()
    except Exception:  # noqa: BLE001 — 逾時、WMI 失敗都一樣，別擋住自己的重啟
        log.exception("重啟其他服務失敗")
    if not restart_supported():
        raise RuntimeError("這台電腦沒辦法自動重啟服務")
    _spawn_restart()


@router.get("/sdk")
async def sdk_status(_: str = Depends(require_token)) -> dict[str, Any]:
    """SDK／CLI 版本、有沒有新版、更新進度，以及哪些模型要新版才能用。"""
    from engine import models, sdk_update

    installed = sdk_update.installed_sdk()
    cli = await asyncio.to_thread(sdk_update.cli_version)
    latest = await asyncio.to_thread(sdk_update.latest)
    job = sdk_update.job()
    return {
        "sdk": installed,
        "cli": cli,
        "latest": (latest or {}).get("version", ""),
        "latest_released": (latest or {}).get("released", ""),
        "update_available": bool(latest) and sdk_update.newer(latest["version"], installed),
        "job": {"state": job.state, "step": job.step, "target": job.target,
                "error": job.error},
        # 清單上有、這版 CLI 跑不動的模型：工具頁拿來說明「為什麼要更新」
        "blocked_models": [m.name for m in models.catalog() if not m.available],
        # 更新完會不會自己重啟。不會的話 App 先講清楚「裝好要手動重啟」
        "auto_restart": restart_supported(),
    }


@router.post("/sdk/update")
async def sdk_update_start(_: str = Depends(require_token)) -> dict[str, Any]:
    """開始更新到 PyPI 上的最新版。背景跑，進度用 GET /v1/system/sdk 看；
    驗證通過後自動重啟（共用環境的其他服務，接著這個服務自己）。"""
    from engine import sdk_update

    latest = await asyncio.to_thread(sdk_update.latest)
    if not latest:
        raise HTTPException(status_code=503, detail="查不到最新版本，網路可能斷了")
    target = latest["version"]
    if not sdk_update.newer(target, sdk_update.installed_sdk()):
        raise HTTPException(status_code=400, detail="已經是最新版")
    if not sdk_update.start(target, _after_sdk_update):
        raise HTTPException(status_code=409, detail="已經在更新了")
    return {"ok": True, "target": target}
