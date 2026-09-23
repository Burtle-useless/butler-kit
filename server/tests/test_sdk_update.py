"""SDK 一鍵更新：版本比較、改名讓位、失敗退回、背景工作的狀態。

不碰真的套件：`package_dir`／`installed_sdk`／`cli_version`／`_pip`／子行程全換成假的，
在暫存資料夾裡演一個假的 site-packages。"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
from unittest.mock import patch

import config
from engine import sdk_update as su

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}", flush=True)
    if not cond:
        FAILED.append(name)


def fake_site() -> tuple[Path, Path]:
    """假的 site-packages：claude_agent_sdk/（含 _bundled/claude.exe）＋ dist-info。"""
    site = Path(tempfile.mkdtemp(prefix="butler-site-"))
    pkg = site / "claude_agent_sdk"
    (pkg / "_bundled").mkdir(parents=True)
    (pkg / "__init__.py").write_text("__version__ = '0.2.152'\n", encoding="utf-8")
    (pkg / "_bundled" / "claude.exe").write_bytes(b"OLD-CLI")
    dist = site / "claude_agent_sdk-0.2.152.dist-info"
    dist.mkdir()
    (dist / "METADATA").write_text("Version: 0.2.152\n", encoding="utf-8")
    return site, pkg


def main() -> int:
    print("\n[版本比較]")
    check("新版", su.newer("0.2.158", "0.2.152"))
    check("同版不算新", not su.newer("0.2.152", "0.2.152"))
    check("位數不同也比得對", su.newer("0.2.160", "0.2.99"))
    check("認不得的當不新", not su.newer("abc", "0.2.152") and not su.newer("0.2.158", ""))

    print("\n[改名讓位]")
    d = Path(tempfile.mkdtemp(prefix="butler-park-"))
    exe = d / "claude.exe"
    exe.write_bytes(b"x")
    p1 = su.park(exe, "2.1.259")
    check("改成帶版本的舊檔名", p1 is not None and p1.name == "claude-2.1.259.old.exe"
          and not exe.exists(), str(p1))
    exe.write_bytes(b"y")
    p2 = su.park(exe, "2.1.259")
    check("撞名就加序號", p2 is not None and p2.name == "claude-2.1.259-1.old.exe", str(p2))
    check("沒有檔案回 None", su.park(d / "nope.exe", "x") is None)

    # ── 把會碰真套件的東西換掉 ──
    site, pkg = fake_site()
    saved = (su.package_dir, su.installed_sdk, su.cli_version, su._pip, su.WORK_DIR,
             subprocess.run)
    su.package_dir = lambda: pkg                      # type: ignore[assignment]
    su.installed_sdk = lambda: "0.2.152"              # type: ignore[assignment]
    su.cli_version = lambda: "2.1.259"                # type: ignore[assignment]
    su.WORK_DIR = Path(tempfile.mkdtemp(prefix="butler-work-"))
    pip_calls: list[str] = []
    fail_at: dict[str, str] = {}

    def fake_pip(args: list[str], timeout: float) -> None:
        pip_calls.append(args[0])
        if args[0] == "download":
            dest = Path(args[args.index("-d") + 1])
            (dest / "claude_agent_sdk-0.2.158-py3-none-win_amd64.whl").write_bytes(b"whl")
        elif args[0] == "install":
            # 模擬 pip：舊的拿掉、新的放上去
            (pkg / "__init__.py").write_text("__version__ = '0.2.158'\n", encoding="utf-8")
            (pkg / "_bundled" / "claude.exe").write_bytes(b"NEW-CLI")
            (site / "claude_agent_sdk-0.2.152.dist-info").rename(
                site / "claude_agent_sdk-0.2.158.dist-info")
            if fail_at.get("step") == "install":
                raise RuntimeError("pip install 失敗：磁碟滿了")
        elif args[0] == "uninstall":
            for f in pkg.rglob("*"):
                if f.is_file() and not f.name.endswith(".old.exe"):
                    f.unlink()
            for dd in site.glob("claude_agent_sdk-*.dist-info"):
                for f in dd.iterdir():
                    f.unlink()
                dd.rmdir()

    class Done:
        def __init__(self, rc: int, out: str) -> None:
            self.returncode, self.stdout, self.stderr = rc, out, ""

    def fake_run(cmd, *a, **kw):  # type: ignore[no-untyped-def]
        if "-c" in cmd:                                   # 試跑新版
            return Done(1, "版本不對") if fail_at.get("step") == "smoke" else Done(0, "SMOKE_OK\n")
        return saved[5](cmd, *a, **kw)

    su._pip = fake_pip                                    # type: ignore[assignment]
    su.subprocess.run = fake_run                          # type: ignore[assignment]
    try:
        print("\n[SDK 自帶的 CLI 檔名照平台]")
        saved_cli = config.CLAUDE_CLI
        config.CLAUDE_CLI = ""                            # type: ignore[misc]
        try:
            with patch.object(sys, "platform", "win32"):
                check("Windows 是 claude.exe", su.cli_path() == pkg / "_bundled" / "claude.exe",
                      str(su.cli_path()))
            with patch.object(sys, "platform", "linux"):
                check("其他平台是 claude", su.cli_path() == pkg / "_bundled" / "claude",
                      str(su.cli_path()))
        finally:
            config.CLAUDE_CLI = saved_cli                 # type: ignore[misc]

        print("\n[試跑沒過：整套退回舊版]")
        fail_at["step"] = "smoke"
        try:
            su._run("0.2.158")
            raised = False
        except RuntimeError as e:
            raised = "試跑沒過" in str(e)
        check("丟出原因", raised)
        check("CLI 換回舊的", (pkg / "_bundled" / "claude.exe").read_bytes() == b"OLD-CLI")
        check("讓位的舊檔收回去了", not list((pkg / "_bundled").glob("*.old.exe")))
        check("程式檔是舊版", "0.2.152" in (pkg / "__init__.py").read_text(encoding="utf-8"))
        check("dist-info 是舊版", (site / "claude_agent_sdk-0.2.152.dist-info").is_dir()
              and not (site / "claude_agent_sdk-0.2.158.dist-info").exists())
        check("有解除新版", "uninstall" in pip_calls)

        print("\n[安裝失敗：同樣退回]")
        fail_at["step"] = "install"
        pip_calls.clear()
        try:
            su._run("0.2.158")
        except RuntimeError:
            pass
        check("CLI 換回舊的", (pkg / "_bundled" / "claude.exe").read_bytes() == b"OLD-CLI")
        check("程式檔是舊版", "0.2.152" in (pkg / "__init__.py").read_text(encoding="utf-8"))

        print("\n[成功：新版留著、舊 CLI 讓位等重啟後刪]")
        fail_at.clear()
        pip_calls.clear()
        su._run("0.2.158")
        check("順序：下載→安裝", pip_calls == ["download", "install"], str(pip_calls))
        check("新 CLI 就位", (pkg / "_bundled" / "claude.exe").read_bytes() == b"NEW-CLI")
        parked = list((pkg / "_bundled").glob("*.old.exe"))
        check("舊 CLI 改名留著", [p.name for p in parked] == ["claude-2.1.259.old.exe"])
        check("備份清掉了", not list(su.WORK_DIR.glob("backup-*")))
        check("重啟後清得掉", su.cleanup_parked() == 1
              and not list((pkg / "_bundled").glob("*.old.exe")))

        print("\n[已經是最新就不做]")
        try:
            su._run("0.2.152")
            same = False
        except RuntimeError as e:
            same = "不用更新" in str(e)
        check("同版拒絕", same)

        print("\n[背景工作：狀態、完成才重啟、同時只能一個]")
        gate = threading.Event()
        saved_run = su._run
        outcome: dict[str, str] = {}

        def slow_run(target: str) -> None:
            gate.wait(5)
            if outcome.get("fail"):
                raise RuntimeError("下載失敗")

        restarted: list[bool] = []
        su._run = slow_run                                # type: ignore[assignment]
        try:
            check("開始", su.start("0.2.158", lambda: restarted.append(True)))
            check("跑的時候是 running", su.job().state == "running" and su.running())
            check("第二次開不起來", not su.start("0.2.158", lambda: None))
            gate.set()
            for _ in range(50):
                if su.job().state != "running":
                    break
                time.sleep(0.05)
            check("完成", su.job().state == "done", su.job().state)
            check("完成才叫重啟", restarted == [True])
            gate.clear()
            outcome["fail"] = "1"
            restarted.clear()
            su.start("0.2.158", lambda: restarted.append(True))
            gate.set()
            for _ in range(50):
                if su.job().state != "running":
                    break
                time.sleep(0.05)
            check("失敗記原因", su.job().state == "failed" and "下載失敗" in su.job().error)
            check("失敗不重啟", restarted == [])

            # 裝好了但重啟不了（沒有重啟腳本、不是 Windows）：不能停在 done，
            # 不然手機會一直顯示「正在重新啟動」
            outcome.clear()
            gate.clear()

            def cannot_restart() -> None:
                raise RuntimeError("這台電腦沒辦法自動重啟服務")

            su.start("0.2.158", cannot_restart)
            gate.set()
            for _ in range(50):
                if su.job().state not in ("running", "done"):
                    break
                time.sleep(0.05)
            check("重啟不了標成 manual", su.job().state == "manual", su.job().state)
            check("說明要手動重啟", "手動" in su.job().step, su.job().step)
        finally:
            su._run = saved_run                           # type: ignore[assignment]
    finally:
        (su.package_dir, su.installed_sdk, su.cli_version, su._pip, su.WORK_DIR,
         su.subprocess.run) = saved                       # type: ignore[assignment]

    print("\n[更新後的重啟：做不到就拋，讓工作標成 manual]")
    from transport import system_api
    spawned: list[bool] = []
    with patch.object(system_api, "_spawn_restart", lambda: spawned.append(True)), \
            patch.object(system_api, "_restart_peers", lambda: None):
        with patch.object(sys, "platform", "linux"):
            check("非 Windows 不能自己重啟", not system_api.restart_supported())
            try:
                system_api._after_sdk_update()
                raised = False
            except RuntimeError:
                raised = True
            check("拋出來", raised)
            check("沒去叫重啟腳本", spawned == [])
        with patch.object(sys, "platform", "win32"):
            system_api._after_sdk_update()
            check("Windows 有腳本就叫重啟", spawned == [True] and system_api.RESTART_PS1.is_file())

    # 其他服務重啟失敗（powershell 逾時、WMI 出錯）跟自己能不能重啟是兩回事：
    # 拋上去的話工作會被標成 manual，App 叫人手動重啟，其實這邊正在重啟
    spawned.clear()

    def peer_fails() -> None:
        raise subprocess.TimeoutExpired("powershell.exe", 60)

    with patch.object(system_api, "_spawn_restart", lambda: spawned.append(True)), \
            patch.object(system_api, "_restart_peers", peer_fails), \
            patch.object(sys, "platform", "win32"):
        try:
            system_api._after_sdk_update()
            raised = False
        except Exception:  # noqa: BLE001
            raised = True
        check("其他服務重啟失敗不往上拋", not raised)
        check("照樣重啟自己", spawned == [True])

    # WMI／wscript 只有 Windows 有：其他平台設了 PEER_RESTART 也不去跑
    peer_file = Path(tempfile.mkdtemp(prefix="butler-peer-")) / "restart_peer.vbs"
    peer_file.write_text("' x", encoding="utf-8")
    ran: list[object] = []
    with patch.object(config, "PEER_RESTART", peer_file), \
            patch.object(system_api.subprocess, "run", lambda *a, **kw: ran.append(a)), \
            patch.object(sys, "platform", "linux"):
        system_api._restart_peers()
        check("非 Windows 不叫其他服務重啟", ran == [])

    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(main())
