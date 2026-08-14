"""長駐 ClaudeSDKClient 池。

每個對話維持一個活進程，活躍期間不關——同進程同 session、不 fork、不留碎片；
閒置逾時才回收釋放記憶體，下次以 resume 接回。
"""
from __future__ import annotations

import asyncio
import logging
import time

from claude_agent_sdk import ClaudeSDKClient

import config
from protocol import Frontend

from .history import session_exists
from .options import build_options
from .state import ConvState, eff_effort, eff_model

log = logging.getLogger(__name__)

_clients: dict[str, ClaudeSDKClient] = {}
_used: dict[str, float] = {}        # 最後使用時間（閒置回收與 LRU 判斷）
_sigs: dict[str, tuple] = {}        # 設定指紋（變了就重建）


def client_sig(state: ConvState) -> tuple:
    """長駐 client 的設定指紋。

    不含 session_id——sid 會在正常對話中由 client 自己產出，納入會造成無謂重建。
    cwd／生效 model／生效 effort 任一改變即代表要用新設定重建。
    用「生效值」（含帳號預設層）：改帳號預設時，未單獨覆寫的對話下次會自動重建。

    _no_think 也納入：關思考重試時指紋自然不符 → client 以新 thinking 設定重建，
    且重建會帶 resume 接回同一個 session，對話歷史不中斷。旗標還原後同理換回來。
    """
    return (str(state.cwd), eff_model(state), eff_effort(state), state._no_think)


async def drop(conv_id: str) -> None:
    """關閉並移除某對話的長駐 client。永不拋例外。"""
    c = _clients.pop(conv_id, None)
    _used.pop(conv_id, None)
    _sigs.pop(conv_id, None)
    if c is not None:
        try:
            await c.disconnect()
        except Exception:
            pass


async def acquire(state: ConvState, frontend: Frontend) -> ClaudeSDKClient:
    """取得該對話的長駐 client；無、或設定指紋已變，則（丟棄後）新建並連線。

    只有首次連線才帶 resume 接回舊 session；之後同一 client 多輪都在同進程同 session。
    """
    cid = state.conv_id
    c = _clients.get(cid)
    if c is not None and _sigs.get(cid) == client_sig(state):
        _used[cid] = time.time()
        return c
    if c is not None:                      # 設定已變 → 丟棄舊的，用新設定重建
        await drop(cid)
    # 進程池上限：滿了先淘汰最久未用的（LRU）。session 不受影響，
    # 被淘汰的對話下次有訊息時自動 resume 接回，只是多付一次進程啟動時間。
    while len(_clients) >= config.MAX_CLIENTS and _used:
        await drop(min(_used, key=lambda k: _used[k]))
    options = build_options(state, frontend)
    if state.session_id:
        if session_exists(state.session_id):
            options.resume = state.session_id  # 僅首次連線需要接回
        else:
            # 逐字稿被刪或清過 → resume 會讓 CLI 直接 exit 1，而且錯誤訊息
            # 看不出是 session 的問題（見 history.session_exists），這條對話
            # 會就此永遠回不了話。寧可開新 session 斷脈絡，也不要卡死。
            log.warning("session %s 已不存在，改開新的（對話 %s）", state.session_id, cid)
            state.session_id = None
    c = ClaudeSDKClient(options)
    await c.connect()
    _clients[cid] = c
    _sigs[cid] = client_sig(state)
    _used[cid] = time.time()
    return c


async def reaper() -> None:
    """背景工作：定期回收閒置過久的 client，釋放記憶體與 VRAM。"""
    while True:
        await asyncio.sleep(config.CLIENT_IDLE_TIMEOUT)
        now = time.time()
        for cid in [k for k, t0 in _used.items() if now - t0 > config.CLIENT_IDLE_TIMEOUT]:
            await drop(cid)


async def shutdown() -> None:
    """服務收工：關掉所有 client，避免留下孤兒 node 進程。"""
    for cid in list(_clients):
        await drop(cid)


def stats() -> dict[str, int]:
    return {"clients": len(_clients), "max": config.MAX_CLIENTS}
