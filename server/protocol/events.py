"""事件模型：引擎對外只發「語意事件」，不發渲染好的字串。

cc-bot 為了塞進 Discord 2000 字上限而生的整套渲染機制（動畫、翻頁、狀態列組字串）
在這裡全部不存在——手機端 LazyColumn 天生就是往下累積，怎麼畫由前端決定。

`seq` / `conv_id` / `turn_id` 三個欄位從第一版就必須全帶上：
少 seq 就不能斷線續傳、少 conv_id 就不能多對話共用一條連線、
少 turn_id 就沒辦法把空回覆重試畫成「重試 #2」而不是看起來像 AI 精神錯亂。
補上去要同時動 Server、RingBuffer、Room schema 與 UI 四處，代價極高。
"""
from __future__ import annotations

import itertools
import time
from dataclasses import dataclass, field
from typing import Any, Final, Literal

EventType = Literal[
    "turn.start",       # 回合開始：{"prompt": 使用者原文}
    "turn.end",         # 回合結束：{"ok": bool, "elapsed": float}
    "thinking.delta",   # 思考逐字：{"d": str}
    "text.delta",       # 回覆逐字：{"d": str}
    "step.commit",      # 一則 AssistantMessage 定稿：{"text": str, "think_digest": str}
    "tool.call",        # 工具呼叫：{"tool","summary","raw","dangerous","icon"}
    "reply.final",      # 本回合最終回覆：{"markdown": str, "files": list,
                        #   "pending_ask": bool}
                        #   pending_ask=True 代表後面緊接著 ask.request，
                        #   這輪還沒結束，手機端不要推「做完了」

    "ask.request",      # 需要使用者決定：{"ask_id", "kind", "title", "body", "raw", "choices"}
    "ask.resolved",     # 已有答案（供其他裝置同步）：{"ask_id", "choice_id"}
    "status",           # 心跳狀態：{"elapsed","model","effort","ctx_tokens","bg","phase"}
                        #   phase="compacting" 代表這段時間在整理記憶，不是在回話
    "error",            # 錯誤：{"kind","detail","retryable","attempt","max"}
    "notify",           # 長任務完成，觸發推播：{"title","body"}
    "seq.gap",          # 續傳斷層，叫前端改拉 snapshot：{"from","to"}
    "user.message",     # 使用者訊息回音（多裝置同步的基礎）：{"text","msg_id","queued"}
    "message.taken",    # 排著的訊息被讀進這一輪了：{"msg_ids": list[str]}
    "message.dropped",  # 排著的訊息隨停止一起取消：{"msg_ids": list[str]}
    "agenda.changed",   # 行事曆／鬧鐘／記帳／課表有變動，叫 App 重拉並重排鬧鐘：{"what": str}
    "file.offer",       # 助理要傳檔案給手機：{"file_id","name","bytes","note"}
]

# 這幾類事件量大且可合併，弱網下合併後再送，避免逐字事件把手機淹掉
COALESCABLE: Final[frozenset[str]] = frozenset({"thinking.delta", "text.delta"})


@dataclass(frozen=True, slots=True)
class Event:
    """一則事件。data 內容自由演進，外層結構固定。"""

    seq: int            # 全域單調遞增，對應 SSE 的 id: 欄位，續傳錨點
    conv_id: str        # 對話 id，一條連線承載多個對話靠它分流
    turn_id: str        # 回合 id，UI 靠它把重試分組
    type: EventType
    ts: float
    data: dict[str, Any] = field(default_factory=dict)

    def to_sse(self) -> dict[str, str]:
        """轉成 sse-starlette 需要的欄位形狀。"""
        import json
        payload = {"conv_id": self.conv_id, "turn_id": self.turn_id, **self.data}
        return {
            "id": str(self.seq),
            "event": self.type,
            "data": json.dumps(payload, ensure_ascii=False),
        }


class SeqGen:
    """全域事件序號產生器。

    刻意不從 0 起算而是用啟動時間戳當高位——服務重啟後序號不會倒退，
    手機拿著舊的 Last-Event-ID 重連時才不會誤判成「這些我都收過了」而丟掉新事件。
    """

    def __init__(self) -> None:
        self._it = itertools.count(int(time.time() * 1000))

    def next(self) -> int:
        return next(self._it)


_seq = SeqGen()


def make_event(
    conv_id: str,
    turn_id: str,
    type_: EventType,
    **data: Any,
) -> Event:
    """建立一則事件並自動編號。"""
    return Event(
        seq=_seq.next(),
        conv_id=conv_id,
        turn_id=turn_id,
        type=type_,
        ts=time.time(),
        data=data,
    )
