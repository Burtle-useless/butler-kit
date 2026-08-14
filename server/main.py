"""服務進入點。

啟動順序有意義：config 必須最先 import（環境清洗），
接著確認監聽位址（拿不到 tailnet 位址就拒絕啟動），最後才起 uvicorn。
"""
from __future__ import annotations

import socket
import sys
import time

import config  # noqa: F401  # 必須最先 import：模組載入時執行環境變數清洗


def _port_is_free(host: str, port: int) -> bool:
    """預檢查 port，只為了給出比 uvicorn 的 traceback 友善的訊息。

    HTTP 服務綁定 port 本身就是天然的單一實例鎖，不需要另外開一個鎖 socket。
    """
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 0)
        try:
            s.bind((host, port))
            return True
        except OSError:
            return False


def _wait_for_tailnet(max_wait: int = 90) -> str | None:
    """等 Tailscale 就緒。

    開機自啟時這段是必要的：butler 可能比 Tailscale 服務先起來，
    當下解析不到 tailnet 位址就會拒絕啟動，自啟等於失效。
    這裡只是「等它出現」，並沒有放寬綁定規則——等不到照樣不啟動，
    絕不退而求其次綁 0.0.0.0。
    """
    deadline = time.time() + max_wait
    announced = False
    while time.time() < deadline:
        ip = config.find_tailnet_ip()
        if ip:
            return ip
        if not announced:
            print("[butler] 等 Tailscale 就緒中…", flush=True)
            announced = True
        time.sleep(3)
    return None


def main() -> int:
    print(config.startup_banner(), flush=True)
    if not config.DEV_MODE and config.find_tailnet_ip() is None:
        _wait_for_tailnet()
    try:
        host = config.resolve_bind_host()
    except RuntimeError as e:
        print(f"[butler] {e}", file=sys.stderr)
        return 1

    if not _port_is_free(host, config.PORT):
        print(f"[butler] {host}:{config.PORT} 已被佔用——多半是另一個實例還在跑。",
              file=sys.stderr)
        return 1

    import uvicorn
    from transport.auth import device_count, ensure_token

    token, is_new = ensure_token()
    print(f"[butler] 監聽 http://{host}:{config.PORT}", flush=True)
    # 常駐之後這份日誌會一直留在 butler.log 裡，所以明文 token 只在「首次生成」印一次。
    # 之後只報數量——真的忘了就刪掉 data/devices.json 重新配對，比讓明文躺在檔案裡好。
    if is_new:
        print(f"[butler] 首次啟動，device token（只會顯示這一次）: {token}", flush=True)
    else:
        print(f"[butler] 已註冊 {device_count()} 台裝置"
              f"{'（使用 BUTLER_TOKEN 環境變數）' if token else ''}", flush=True)
    if host.startswith("127."):
        print("[butler] 開發模式（BUTLER_DEV=1），只綁 loopback，手機連不到。", flush=True)

    uvicorn.run(
        "transport.app:app",
        host=host,
        port=config.PORT,
        log_level="info",
        access_log=False,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
