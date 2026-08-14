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
import os
import secrets
import time

from fastapi import Header, HTTPException

import config
from util import atomic_write_text


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def _load() -> dict:
    try:
        return json.loads(config.DEVICES_FILE.read_text(encoding="utf-8"))
    except Exception:
        return {"devices": {}, "revoked": []}


def _save(data: dict) -> None:
    atomic_write_text(config.DEVICES_FILE, json.dumps(data, ensure_ascii=False, indent=2))


def ensure_token() -> tuple[str, bool]:
    """取得 device token，回傳 (明文或空字串, 是否為本次新生成)。

    環境變數 BUTLER_TOKEN 優先，方便開發期固定。
    否則：devices.json 已有註冊裝置就**不再生成**——早期版本每次啟動都生一組新的塞進去，
    常駐之後會無限累積，而且舊 token 全部繼續有效，等於每重啟一次就多發一把鑰匙。
    伺服器只存雜湊，所以已有裝置時拿不回明文，回空字串由呼叫端決定怎麼顯示。
    """
    env = (os.environ.get("BUTLER_TOKEN") or "").strip()
    if env:
        return env, False
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


def verify(token: str) -> bool:
    """比對 token。用 compare_digest 避免時序側channel。"""
    if not token:
        return False
    env = (os.environ.get("BUTLER_TOKEN") or "").strip()
    if env and hmac.compare_digest(token, env):
        return True
    data = _load()
    h = _hash(token)
    if h in data.get("revoked", []):
        return False
    dev = data.get("devices", {}).get(h)
    if dev is None:
        return False
    dev["last_seen"] = time.time()
    _save(data)
    return True


def revoke(token_hash: str) -> bool:
    """撤銷一台裝置。手機遺失時從另一台已授權裝置呼叫。

    應用層撤銷與 Tailscale 的裝置移除**兩層都要做**——ACL 變更有傳播延遲，
    而且你可能剛好連不上 admin console。
    """
    data = _load()
    if token_hash not in data.get("devices", {}):
        return False
    data.setdefault("revoked", []).append(token_hash)
    data["devices"].pop(token_hash, None)
    _save(data)
    return True


async def require_token(authorization: str = Header(default="")) -> str:
    """FastAPI 依賴：檢查 Authorization: Bearer <token>。"""
    token = authorization.removeprefix("Bearer ").strip()
    if not verify(token):
        raise HTTPException(status_code=401, detail="invalid or revoked device token")
    return token
