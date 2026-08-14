"""ConsoleFrontend：把引擎事件印在終端機的假前端。

存在的唯一理由是**驗證分層**：如果 engine 真的與傳輸層解耦，那麼不啟動任何
HTTP 服務、不接任何手機，光靠這支就該能跑完一個完整回合。
這是 Phase 1 的成功條件之一，跑不過就代表 engine 沒抽乾淨。

用法：
    set PYTHONPATH=C:\\Users\\you\\Desktop\\Claude\\butler\\server
    python dev_console.py "列出目前目錄的檔案"
"""
from __future__ import annotations

import asyncio
import os
import sys

import config  # noqa: F401  # 必須最先 import：模組載入時執行環境變數清洗
from engine import get_state
from engine.turn import handle_turn
from protocol import AskRequest, AskResponse, Event

# ANSI 色碼，讓事件流在終端機上讀得出結構
DIM, CYAN, YELLOW, RED, GREEN, RESET = (
    "\033[2m", "\033[36m", "\033[33m", "\033[31m", "\033[32m", "\033[0m"
)


class ConsoleFrontend:
    """實作 protocol.Frontend。"""

    def __init__(self) -> None:
        self.counts: dict[str, int] = {}
        self._nl_pending = False

    async def emit(self, ev: Event) -> None:
        """永不拋例外——前端的任何問題都不可以拖垮回合。"""
        try:
            self.counts[ev.type] = self.counts.get(ev.type, 0) + 1
            d = ev.data
            if ev.type == "thinking.delta":
                self._w(f"{DIM}{d.get('d', '')}{RESET}")
            elif ev.type == "text.delta":
                self._w(d.get("d", ""))
            elif ev.type == "tool.call":
                mark = f"{RED}⚠ {RESET}" if d.get("dangerous") else ""
                self._line(f"{mark}{d.get('icon', '')} {CYAN}{d.get('tool')}{RESET} "
                           f"{DIM}{d.get('summary', '')}{RESET}")
            elif ev.type == "step.commit":
                if d.get("think_digest"):
                    self._line(f"{DIM}💭 {d['think_digest']}{RESET}")
                if d.get("text"):
                    self._line(f"{d['text']}")
            elif ev.type == "status":
                self._line(f"{DIM}[{d.get('elapsed')}s · {d.get('model')} · "
                           f"tools={d.get('tools')}]{RESET}")
            elif ev.type == "turn.start":
                self._line(f"{GREEN}▶ 回合開始{RESET} {DIM}seq={ev.seq}{RESET}")
            elif ev.type == "turn.end":
                self._line(f"{GREEN}■ 回合結束{RESET} ok={d.get('ok')} "
                           f"{d.get('elapsed')}s")
            elif ev.type == "reply.final":
                self._line(f"\n{GREEN}{'─' * 60}{RESET}")
                self._line(f"{GREEN}最終回覆：{RESET}\n{d.get('markdown', '')}")
            elif ev.type == "error":
                self._line(f"{RED}✖ [{d.get('kind')}] {d.get('detail')}{RESET}")
        except Exception:
            pass   # 契約要求：emit 永不拋

    async def ask(self, req: AskRequest) -> AskResponse | None:
        """終端機版的提問。逾時回 None，呼叫端會當成取消（fail-closed）。"""
        self._line(f"\n{YELLOW}{'=' * 60}{RESET}")
        self._line(f"{YELLOW}❓ {req.title}{RESET}")
        if req.body:
            self._line(f"   {req.body}")
        if req.raw:
            self._line(f"{RED}指令原文（完整、未截斷）：{RESET}")
            self._line(f"{RED}{req.raw}{RESET}")
        for i, c in enumerate(req.choices, 1):
            self._line(f"   [{i}] {c.label}  {DIM}{c.detail}{RESET}")
        self._line(f"{YELLOW}{'=' * 60}{RESET}")
        try:
            raw = await asyncio.wait_for(
                asyncio.to_thread(input, "選擇編號> "), timeout=req.timeout_sec
            )
        except (asyncio.TimeoutError, EOFError):
            self._line(f"{RED}（逾時／無輸入 → 視為取消）{RESET}")
            return None
        try:
            return AskResponse(choice_id=req.choices[int(raw.strip()) - 1].id)
        except (ValueError, IndexError):
            return None

    def _w(self, s: str) -> None:
        sys.stdout.write(s)
        sys.stdout.flush()
        self._nl_pending = True

    def _line(self, s: str) -> None:
        if self._nl_pending:
            sys.stdout.write("\n")
            self._nl_pending = False
        print(s, flush=True)


async def main() -> int:
    prompt = " ".join(sys.argv[1:]) or "用一句話說明你現在的工作目錄是哪裡"
    print(config.startup_banner())
    fe = ConsoleFrontend()
    # 用 BUTLER_CONV 指定對話 id：人格測試要用乾淨的新對話，
    # 不然上一題的脈絡會影響這一題的判斷（例如上一題動過工具，這題就傾向也動）
    state = get_state(os.environ.get("BUTLER_CONV") or "dev-console")
    print(f"{DIM}cwd={state.cwd} · session={state.session_id or '(新對話)'}{RESET}\n")
    # 走完整回合管線（含空回覆重試、續跑、反問、錯誤善後）
    await handle_turn(prompt, state, fe)
    print(f"\n{DIM}事件統計：{fe.counts}{RESET}")
    from engine import client_pool
    await client_pool.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
