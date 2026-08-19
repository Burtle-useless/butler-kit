"""全域設定：環境清洗、監聽位址、逾時與模型常數。

本模組必須是整個服務**最先被 import** 的一個——模組載入時就執行環境變數清洗，
晚一步 engine 建起 CC 子進程就會繼承到污染的憑證設定。
"""
from __future__ import annotations

import ipaddress
import os
import socket
import subprocess
from pathlib import Path
from typing import Final

# ── 環境變數清洗（必須在任何 CC 相關 import 之前）────────────────────────────
# 洗掉「上層 Claude session 洩漏的環境變數」：本服務多半是從桌面 app 的 CC 殼啟動的，
# 會繼承 ANTHROPIC_BASE_URL（指向 app 的本機代理）與 CLAUDE_CODE_* 等 session 變數，
# CC 子進程因此改走別人的短命憑證，token 一輪換就整批 401、重新登入也救不回
# （cc-bot 2026-07-21 實案，症狀完全不指向環境變數，卡了數小時）。
# 開機即清，CC 一律用本機 claude 登入憑證，不受啟動來源污染。
_LEAKED_KEYS: Final[tuple[str, ...]] = (
    "ANTHROPIC_BASE_URL", "CLAUDECODE", "CLAUDE_AGENT_SDK_VERSION",
)
_purged: list[str] = [
    k for k in os.environ
    if k in _LEAKED_KEYS or k.startswith("CLAUDE_CODE_")
]
for _k in _purged:
    os.environ.pop(_k, None)

# 清洗會連 CLAUDE_CODE_ENABLE_ASK_USER_QUESTION_TOOL 一起掃掉，於是子 session
# 完全沒有 AskUserQuestion 工具——助理遇到需要決定的岔路只能自己猜，App 端做好的
# 選項面板永遠不會出現。
# 這裡**明確設 1** 而不是把它加進清洗白名單：白名單只在「有繼承到」時有效，
# 而從桌面捷徑／工作排程啟動時這個變數根本不存在，放行等於沒放行。
os.environ["CLAUDE_CODE_ENABLE_ASK_USER_QUESTION_TOOL"] = "1"

# ── 路徑 ─────────────────────────────────────────────────────────────────────
SERVER_DIR: Final[Path] = Path(__file__).resolve().parent
DATA_DIR: Final[Path] = SERVER_DIR / "data"
SESSION_FILE: Final[Path] = DATA_DIR / "session.json"
DEVICES_FILE: Final[Path] = DATA_DIR / "devices.json"
SCHEDULES_FILE: Final[Path] = DATA_DIR / "schedules.json"
DEFAULT_CWD: Final[Path] = Path(os.environ.get("BUTLER_CWD") or Path.home())

# 助理專屬的那條對話。App 的助理分頁固定看這一條，其餘 conv_id 都屬於工作區分頁。
# 兩邊是**不同用途**：助理有人格與生活工具（行事曆、記帳），工作區分頁純工作。
# options.py 依這個常數決定套哪一套 system prompt 與哪些自製工具。
PRIMARY_CONV: Final[str] = "main"

# ── 人格 ─────────────────────────────────────────────────────────────────────
# 檔名（不含副檔名），對應 server/personas/<名字>.txt。
# 語氣沒有標準答案，所以人格是**純文字檔**不是程式碼——改語氣不該需要碰 Python。
# 寫法見 docs/persona.md。
PERSONA: Final[str] = (os.environ.get("BUTLER_PERSONA") or "default").strip()
WORK_PERSONA: Final[str] = (os.environ.get("BUTLER_WORK_PERSONA") or "work").strip()

# 助理的「失憶自救」筆記檔。留空＝不啟用這條規則。
#
# 伺服器重啟會殺掉 CC 進程，resume 回來時最後一個回合接不回 context——
# 逐字稿裡有，模型腦子裡沒有。而失憶的人不知道自己失憶了，「重啟後」這個
# 條件抓不到，唯一可靠的信號是使用者說「你剛剛才查過」。
# 設了這個路徑，助理被這樣質疑時會先去讀檔案再回話。
NOTES_FILE: Final[str] = (os.environ.get("BUTLER_NOTES_FILE") or "").strip()

CLAUDE_CLI: Final[str] = (
    os.environ.get("CLAUDE_CLI") or os.path.expandvars(r"%APPDATA%\npm\claude.cmd")
)

# ── 監聽位址（安全地基，見計畫風險 #7）──────────────────────────────────────
PORT: Final[int] = int(os.environ.get("BUTLER_PORT") or 47362)
# 開發旗標：允許綁 127.0.0.1。預設關閉，只在本機開發期手動開。
DEV_MODE: Final[bool] = (os.environ.get("BUTLER_DEV") or "0").strip() == "1"

# Tailscale 用 CGNAT 網段 100.64.0.0/10 配發節點位址
_TAILNET: Final[ipaddress.IPv4Network] = ipaddress.ip_network("100.64.0.0/10")


def _probe_local_ips() -> list[str]:
    """列出本機所有 IPv4 位址（不發封包，只查本機介面）。"""
    try:
        _, _, addrs = socket.gethostbyname_ex(socket.gethostname())
        return addrs
    except OSError:
        return []


def _probe_tailscale_cli() -> str | None:
    """備援：直接問 tailscale CLI。socket 查不到時（介面未註冊到 hostname）用這條。"""
    exe = os.path.expandvars(r"%ProgramFiles%\Tailscale\tailscale.exe")
    if not Path(exe).exists():
        return None
    try:
        out = subprocess.run(
            [exe, "ip", "-4"], capture_output=True, text=True, timeout=5,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    addr = out.stdout.strip().splitlines()
    return addr[0].strip() if addr else None


def find_tailnet_ip() -> str | None:
    """找出本機的 tailnet 位址，找不到回 None。"""
    for ip in _probe_local_ips():
        try:
            if ipaddress.ip_address(ip) in _TAILNET:
                return ip
        except ValueError:
            continue
    cli_ip = _probe_tailscale_cli()
    if cli_ip:
        try:
            if ipaddress.ip_address(cli_ip) in _TAILNET:
                return cli_ip
        except ValueError:
            pass
    return None


def resolve_bind_host() -> str:
    """決定 uvicorn 綁定位址。

    只回 tailnet 位址或（開發模式下的）loopback，**永遠不回 0.0.0.0**——
    引擎跑在 bypassPermissions 底下，任何能打到這個 port 的人都能對這台電腦
    下任意指令。綁 0.0.0.0 在公用 Wi-Fi 上等於把整台電腦交出去。
    找不到 tailnet 位址就拒絕啟動，不做任何 fallback。
    """
    ip = find_tailnet_ip()
    if ip:
        return ip
    if DEV_MODE:
        return "127.0.0.1"
    raise RuntimeError(
        "找不到 Tailscale 位址（100.64.0.0/10），拒絕啟動。\n"
        "  → 確認 Tailscale 已安裝並登入：tailscale status\n"
        "  → 本機開發請設環境變數 BUTLER_DEV=1（只綁 127.0.0.1）"
    )


# ── 模型與引擎 ───────────────────────────────────────────────────────────────
DEFAULT_MODEL: Final[str] = os.environ.get("DEFAULT_MODEL") or "claude-sonnet-4-6"
# 訂閱方案：決定高階模型（Opus/Fable/Mythos）是否自動拿到 1M context。
# 填 max 或 team 才會要 1M；填錯或留空就走預設 context，不會壞掉只是拿不到。
ACCOUNT_PLAN: Final[str] = (os.environ.get("BUTLER_PLAN") or "").strip().lower()
FALLBACK_MODEL: Final[str] = "claude-sonnet-4-6"      # 主模型過載時的備援
DEFAULT_EFFORT: Final[str] = os.environ.get("DEFAULT_EFFORT") or "medium"
MAX_BUFFER_SIZE: Final[int] = 64 * 1024 * 1024        # stream-json 解析 buffer 上限

# client 進程池：cc-bot 用 8，本服務刻意壓到 3。
# 兩套引擎共用同一個 Claude 帳號，加起來的 Node 子進程數與 rate limit 都是共用的。
MAX_CLIENTS: Final[int] = 3
CLIENT_IDLE_TIMEOUT: Final[int] = 900                 # 閒置逾時：超過即回收該對話的 client
INACTIVITY_TIMEOUT: Final[int] = 600                  # CC 連續無輸出超過此秒數才視為卡死
MAX_EMPTY_RETRY: Final[int] = 3                       # 空回覆重試上限
MAX_AUTO_CONTINUE: Final[int] = 2                     # 未打完成標記時的自動續跑上限
NOTIFY_AFTER_SEC: Final[int] = 60                     # 超過此秒數的任務，完成時推播

# ── 安全 ─────────────────────────────────────────────────────────────────────
# 破壞性指令確認：cc-bot 預設關（人坐在電腦前看得到螢幕），
# 本服務預設**開**——使用情境是人在外面、電腦在家，沒有人在螢幕前把關。
CONFIRM_ENABLED: Final[bool] = (os.environ.get("CONFIRM_DANGEROUS") or "1").strip() == "1"
# 刻意小於 INACTIVITY_TIMEOUT，確認等待期間才不會被誤判成卡死
CONFIRM_TIMEOUT_SEC: Final[float] = 300.0

# ── 傳輸 ─────────────────────────────────────────────────────────────────────
# 全域共用一份（不是每裝置一份），所有對話的事件都排在同一條 ring 上。
# 從 2000 提到 6000 是 DELTA_COALESCE_MS 生效後的配套：先前 delta 實際上兩秒才送
# 一則，一個長回合幾百則就到頂；改成按時間窗送之後，同樣的回合會產生數倍事件量，
# 沿用 2000 會讓斷線續傳的視窗縮到只剩一兩分鐘。一則事件約數百 bytes，6000 則
# 也才幾 MB，用記憶體換續傳可靠度很划算。
RING_BUFFER_SIZE: Final[int] = 6000
SSE_KEEPALIVE_SEC: Final[int] = 15                    # 心跳間隔，防中間裝置掐斷閒置連線
# delta 合併窗。200ms＝每秒五次，人眼看起來已經是連續打字，
# 而事件量只有 100ms 的一半。這個值先前**定義了卻沒有人讀**，見 runner._DeltaBuffer。
DELTA_COALESCE_MS: Final[int] = 200


def startup_banner() -> str:
    """啟動摘要，讓「清洗了什麼、綁在哪裡」在日誌第一行就看得到。"""
    purged = f"已清洗 {len(_purged)} 個繼承變數" + (f"：{', '.join(_purged)}" if _purged else "")
    return f"[butler] {purged}"
