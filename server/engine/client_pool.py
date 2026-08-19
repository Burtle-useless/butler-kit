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
_locks: dict[str, asyncio.Lock] = {}   # 每個對話一把，見 [_lock_for]


def _lock_for(cid: str) -> asyncio.Lock:
    """取得某對話的建立鎖。

    `acquire` 中間有 `await c.connect()`，那是個把控制權交還事件迴圈的中斷點：
    同一對話的兩則訊息若同時進來（送出＋排隊的下一則、或 SSE 重連觸發的補跑），
    兩邊都會看到 `_clients` 還沒有東西而各建一個 client，後寫入的把先寫入的
    從字典裡擠掉——**被擠掉的那個進程沒有人會再去 disconnect 它**，
    node 進程就這樣留在背景吃記憶體直到服務重啟。

    一個對話一把鎖而不是一把全域鎖：全域鎖會讓「某個對話的 CLI 卡在 connect」
    連帶凍住所有其他對話。代價是 `MAX_CLIENTS` 在多個對話同時首連時可能短暫
    多出一兩個——那只是多佔一點記憶體，reaper 會收，比整組卡死好談。

    這個函式本身沒有 await，所以在單一事件迴圈裡是原子的，不需要再包一層鎖。
    """
    lk = _locks.get(cid)
    if lk is None:
        lk = _locks[cid] = asyncio.Lock()
    return lk


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
    # 整段進鎖，connect 期間才不會有第二個呼叫也開一個進程（見 [_lock_for]）。
    # 已有可用 client 的快路徑也要進來，但那條路上沒有 await，鎖是立刻拿到的。
    async with _lock_for(cid):
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
                log.warning("session %s 已不存在，改開新的（對話 %s）",
                            state.session_id, cid)
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
