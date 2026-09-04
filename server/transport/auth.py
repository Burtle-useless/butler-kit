"""裝置認證。

Tailscale 給的是網路層身分：上了 tailnet 就等於進門。它**沒給**的是
「這台手機還在你手上嗎」——手機被拿走時它仍是那台已授權裝置。
所以每個請求都要帶 device token，且要能從另一台裝置撤銷。

Phase 1 只做單一 token（啟動時生成、印在日誌）。
QR 配對、Keystore、多裝置管理留到 Phase 5。
"""
from __future__ import annotations

import hashlib
import hmac
import json
import secrets
import threading
import time

from fastapi import Header, HTTPException

import config
from util import atomic_write_text, read_text_with_retry

# devices.json 的存取要互斥。沒有這道鎖時有兩個災情：
#   ① verify 與 revoke 交錯——verify 在 revoke 寫入**之前**載入舊資料、在其
#      **之後**整份寫回，撤銷掉的裝置就這樣靜默復活。手機遺失後從另一台撤銷，
#      被偷的那台只要還在送 SSE 重連請求就有機會把自己寫回來。
#   ② 在 Windows 上更直接：只要有人正開著 devices.json 在讀，`os.replace`
#      就會 PermissionError（WinError 5），撤銷不是失效而是整個拋例外。
#      2026-08-17 以 tests/test_devices.py 實測到。
# 所以**讀跟寫都要進鎖**，光鎖寫入擋不住第二種。
# 用 RLock 是因為 revoke／ensure_token 要把「讀-改-寫」整段圈起來，
# 而它們內部呼叫的 _load／_save 自己也會取鎖。
_LOCK = threading.RLock()

# 最後活動時間只放記憶體，不落磁碟。這個欄位是給人看的，不是安全邊界，
# 卻讓**每一個**請求都要整份讀寫一次 JSON——那既是上面那個競態的來源，
# 也是 SSE 重連時無謂的磁碟 I/O。重啟後歸零可以接受。
_LAST_SEEN: dict[str, float] = {}


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def hash_of(token: str) -> str:
    """明文 token 的雜湊。給撤銷介面比對「這是不是你自己這台」用。"""
    return _hash(token)


# devices.json 的快取：(mtime, 內容)。`verify` 在每個請求（含每次 SSE 重連）上
# 被叫，先前每次都同步讀檔、拖住整個事件迴圈；檔案只在配對／撤銷時才變，
# 看 mtime 沒變就直接用上次的。
_cache: tuple[float, dict] | None = None


def _load() -> dict:
    global _cache
    with _LOCK:
        try:
            mtime = config.DEVICES_FILE.stat().st_mtime
        except OSError:
            mtime = -1.0
        if _cache is not None and _cache[0] == mtime:
            return _cache[1]
        try:
            # 用重試版讀：讀不到就回預設空清單，而 ensure_token 看到空清單會
            # 生一把新 token 存回去——已註冊的裝置就這樣全部被踢掉。
            data = json.loads(read_text_with_retry(config.DEVICES_FILE))
        except Exception:
            return {"devices": {}, "revoked": []}
        _cache = (mtime, data)
        return data


def _save(data: dict) -> None:
    with _LOCK:
        atomic_write_text(config.DEVICES_FILE,
                          json.dumps(data, ensure_ascii=False, indent=2))


def ensure_token() -> tuple[str, bool]:
    """取得 device token，回傳 (明文或空字串, 是否為本次新生成)。

    環境變數 BUTLER_TOKEN 優先，方便開發期固定。
    否則：devices.json 已有註冊裝置就**不再生成**——早期版本每次啟動都生一組新的塞進去，
    常駐之後會無限累積，而且舊 token 全部繼續有效，等於每重啟一次就多發一把鑰匙。
    伺服器只存雜湊，所以已有裝置時拿不回明文，回空字串由呼叫端決定怎麼顯示。
    """
    env = config.butler_token()
    if env:
        return env, False
    with _LOCK:
        data = _load()
        if data.get("devices"):
            return "", False
        plain = secrets.token_urlsafe(24)
        data["devices"][_hash(plain)] = {
            "name": "first", "created": time.time(), "last_seen": None,
        }
        _save(data)
    return plain, True


def device_count() -> int:
    return len(_load().get("devices", {}))


def list_devices() -> list[dict]:
    """列出已授權裝置，給撤銷介面用。

    **只回雜湊，不回也回不了明文**——伺服器從來沒存過明文。
    雜湊本身不是憑證，拿到它撤銷不了別人也登入不了。
    """
    data = _load()
    out = []
    for h, dev in (data.get("devices") or {}).items():
        out.append({
            "hash": h,
            "short": h[:8],
            "name": dev.get("name") or "",
            "created": dev.get("created"),
            "last_seen": _LAST_SEEN.get(h),
        })
    return sorted(out, key=lambda d: d.get("created") or 0)


def verify(token: str) -> bool:
    """比對 token。用 compare_digest 避免時序側channel。"""
    if not token:
        return False
    env = config.butler_token()
    # 比雜湊不比明文。`compare_digest` 收兩個 str 時要求**兩邊都是 ASCII**，
    # 只要有人送 `Authorization: Bearer 中文` 就拋 TypeError——那不是 401 而是 500，
    # 等於未認證的請求就能把任何端點打爆並在日誌裡留下 traceback。
    # 雜湊出來一律是 64 個 hex 字元，順便讓比較長度固定，定時安全性不變。
    if env and hmac.compare_digest(_hash(token), _hash(env)):
        return True
    data = _load()
    h = _hash(token)
    if h in data.get("revoked", []):
        return False
    if h not in data.get("devices", {}):
        return False
    _LAST_SEEN[h] = time.time()      # 只記在記憶體，不寫檔（見上面 _LAST_SEEN）
    return True


def revoke(token_hash: str) -> bool:
    """撤銷一台裝置。手機遺失時從另一台已授權裝置呼叫。

    應用層撤銷與 Tailscale 的裝置移除**兩層都要做**——ACL 變更有傳播延遲，
    而且你可能剛好連不上 admin console。
    """
    with _LOCK:
        data = _load()
        if token_hash not in data.get("devices", {}):
            return False
        data.setdefault("revoked", []).append(token_hash)
        data["devices"].pop(token_hash, None)
        _save(data)
    _LAST_SEEN.pop(token_hash, None)
    return True


async def require_token(authorization: str = Header(default="")) -> str:
    """FastAPI 依賴：檢查 Authorization: Bearer <token>。"""
    token = authorization.removeprefix("Bearer ").strip()
    if not verify(token):
        raise HTTPException(status_code=401, detail="invalid or revoked device token")
    return token
