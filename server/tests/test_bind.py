"""監聽位址怎麼決定。

這裡守的是一條安全紅線：引擎跑在 bypassPermissions 底下，打得到這個 port
的人就能對整台電腦下任意指令。所以「綁哪裡」不能是隨便設設的參數——
萬用位址一律拒絕，而且要拒絕得很吵（raise，不是靜默改綁別的）。

另一半是「別把人綁死在 Tailscale 上」：走 Cloudflare Tunnel 之類通道的人
該綁 127.0.0.1，而他們往往電腦上**也裝了** Tailscale，所以明確指定之後
不可以再回頭偵測 tailnet。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config                                   # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def _with(dev: bool, bind: str, tailnet: str | None):
    """暫時換掉三個模組層設定，回傳還原用的 callable。

    config 在 import 時就把環境變數讀成常數了，測試改環境變數沒有用，
    只能直接換模組屬性。
    """
    old = (config.DEV_MODE, config.BIND_HOST, config.find_tailnet_ip)
    config.DEV_MODE = dev
    config.BIND_HOST = bind
    config.find_tailnet_ip = lambda: tailnet

    def restore() -> None:
        config.DEV_MODE, config.BIND_HOST, config.find_tailnet_ip = old

    return restore


def _resolve(dev: bool = False, bind: str = "", tailnet: str | None = None):
    """跑一次 resolve_bind_host，回傳 (位址, 錯誤訊息)，其中一個必為 None。"""
    restore = _with(dev, bind, tailnet)
    try:
        return config.resolve_bind_host(), None
    except RuntimeError as e:
        return None, str(e)
    finally:
        restore()


def test_wildcard_rejected() -> None:
    print("\n[萬用位址一律拒絕]")
    for bad in ("0.0.0.0", "::", "[::]", "*", "0.0.0.0".upper()):
        host, err = _resolve(bind=bad)
        check(f"{bad} 被擋下", host is None and err is not None,
              f"got={host!r}")
    # 擋下來之後訊息要講得出替代方案，不然人只會改成別的方式硬開
    _, err = _resolve(bind="0.0.0.0")
    check("錯誤訊息有指路", err is not None and "127.0.0.1" in err)


def test_explicit_bind() -> None:
    print("\n[明確指定就照它走]")
    host, _ = _resolve(bind="127.0.0.1")
    check("走通道的人綁 loopback", host == "127.0.0.1", f"got={host!r}")

    # 這條是重點：裝了 Tailscale 又指定 loopback 的組合很常見（tunnel + tailscale
    # 並存），偵測到 tailnet 就改綁它的話等於沒放寬
    host, _ = _resolve(bind="127.0.0.1", tailnet="100.64.1.2")
    check("指定之後不回頭找 tailnet", host == "127.0.0.1", f"got={host!r}")

    host, _ = _resolve(bind="192.168.1.10")
    check("區網位址放行（是使用者明講的選擇）", host == "192.168.1.10", f"got={host!r}")


def test_dev_wins() -> None:
    print("\n[開發旗標優先於一切]")
    host, _ = _resolve(dev=True, bind="192.168.1.10", tailnet="100.64.1.2")
    check("DEV_MODE 蓋過指定位址與 tailnet", host == "127.0.0.1", f"got={host!r}")


def test_default_still_tailscale() -> None:
    print("\n[沒設的話維持原本的 Tailscale 行為]")
    host, _ = _resolve(tailnet="100.64.1.2")
    check("自動找到 tailnet 就綁它", host == "100.64.1.2", f"got={host!r}")

    host, err = _resolve(tailnet=None)
    check("找不到就拒絕啟動", host is None and err is not None)
    # 舊訊息只講 Tailscale，等於告訴人「這服務只能那樣用」
    check("錯誤訊息提得到通道那條路",
          err is not None and "BUTLER_BIND" in err and "Tunnel" in err)


def test_lan_warning() -> None:
    print("\n[哪些位址該警告]")
    check("loopback 不必警告", not config.is_lan_bind("127.0.0.1"))
    check("tailnet 不必警告", not config.is_lan_bind("100.64.1.2"))
    check("區網要警告", config.is_lan_bind("192.168.1.10"))
    check("認不出來的一律警告", config.is_lan_bind("my-pc.local"))


def main() -> int:
    test_wildcard_rejected()
    test_explicit_bind()
    test_dev_wins()
    test_default_still_tailscale()
    test_lan_warning()
    print(f"\n{'FAILED: ' + ', '.join(FAILED) if FAILED else 'ALL PASS'}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
