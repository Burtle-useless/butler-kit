"""事件流一接通就要先送一段，不能等第一個心跳。

實測手機經 Cloudflare 通道打 /v1/stream，回應標頭 15 秒多才到，跟第一個心跳同時——
通道在後面有第一個位元組之前不會把回應交出去。App 冷啟動那條連線沒有要補的事件，
伺服器於是 15 秒一個字都不送，手機就停在「斷線」畫面白等，看起來像根本沒在連。

TestClient 測不了這件事：它要等整個回應收完才交出來，而事件流永遠不會收完。所以這裡在
本機起一個真的 uvicorn，量「送出請求 → 標頭後面的第一個位元組」多久。心跳是 15 秒，
門檻給 3 秒，分得開。
"""
from __future__ import annotations

import socket
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402,F401
import uvicorn  # noqa: E402

from transport import app as app_mod  # noqa: E402
from transport.auth import require_token  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def first_body_byte(port: int, budget: float) -> tuple[float | None, bytes]:
    """送一個冷啟動那種請求（不帶續傳游標），回傳第一個 body 位元組到達的秒數與那一段。"""
    t0 = time.monotonic()
    sock = socket.create_connection(("127.0.0.1", port), timeout=budget)
    sock.settimeout(budget)
    sock.sendall(b"GET /v1/stream HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n")
    buf = b""
    try:
        while True:
            chunk = sock.recv(4096)
            if not chunk:
                return None, buf
            buf += chunk
            end = buf.find(b"\r\n\r\n")
            if end >= 0 and len(buf) > end + 4:
                return time.monotonic() - t0, buf[end + 4:]
    except socket.timeout:
        return None, buf
    finally:
        sock.close()


def test_first_byte() -> None:
    print("\n[/v1/stream 一接通就有第一段]")
    app_mod.app.dependency_overrides[require_token] = lambda: "test"
    port = free_port()
    # lifespan 關掉：不開 CLI、不啟動回收器，只要路由
    server = uvicorn.Server(uvicorn.Config(
        app_mod.app, host="127.0.0.1", port=port, lifespan="off", log_level="warning",
    ))
    th = threading.Thread(target=server.run, daemon=True)
    th.start()
    for _ in range(100):
        if server.started:
            break
        time.sleep(0.05)
    check("uvicorn 起來了", server.started)
    if not server.started:
        return
    try:
        dt, first = first_body_byte(port, budget=8.0)
        check("第一個位元組在 3 秒內到（心跳是 15 秒）", dt is not None and dt < 3.0,
              f"{dt:.2f}s" if dt is not None else "8 秒內沒收到")
        # 回應是 chunked：前面那行是這一塊的長度（十六進位），拿掉才是 SSE 本體
        head, _, rest = first.partition(b"\r\n")
        body = rest if head and all(c in b"0123456789abcdefABCDEF" for c in head) else first
        check("第一段是註解（App 與網頁都會略過）", body.lstrip().startswith(b":"), repr(first[:40]))
    finally:
        server.should_exit = True
        th.join(timeout=5)


def main() -> int:
    test_first_byte()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
