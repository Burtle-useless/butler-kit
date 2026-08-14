"""一次性的輕量 meta 查詢（標題生成等），不留 session 檔。

對照 cc-bot 的 _ask_haiku / _purge_title_shell / _generate_title。
"""
from __future__ import annotations

import re
from pathlib import Path

from claude_agent_sdk import ClaudeAgentOptions, ClaudeSDKClient, ResultMessage

import config

from .runner import iter_messages

TITLE_PROMPT = (
    "用不超過 12 個字幫這段對話取一個貼切的中文短標題，"
    "只輸出標題本身，不要引號不要句號。對話開頭："
)

# 錯誤樣態不准當標題——cc-bot 曾把「Failed to authenticate. API Error: 401」
# 存成標題還改了頻道名，之後每回合狀態列都掛著它，看起來像持續壞掉。
_ERRORISH = re.compile(r"(?i)failed to authenticate|api error|oauth|\b401\b|\b429\b|\b529\b")


def _session_has_body(jf: Path) -> bool:
    """session 檔是否含對話本體（而不是只有 auto-title 的空殼）。"""
    try:
        with jf.open(encoding="utf-8") as f:
            for line in f:
                if '"type":"user"' in line or '"type":"assistant"' in line:
                    return True
    except OSError:
        pass
    return False


def _purge_title_shell(meta_sid: str | None) -> None:
    """清掉 meta 查詢殘留的空殼 session 檔。

    已設 no-session-persistence 抑制對話本體，但 CLI 內建 auto-title 偶爾會搶在
    行程結束前寫入一行，留下只有標題、無本體的空殼。只刪「這次查詢自己的 sid」
    且確認無本體才刪——真實對話一定有本體，絕不會被誤刪。
    """
    if not meta_sid:
        return
    for jf in (Path.home() / ".claude" / "projects").glob(f"*/{meta_sid}.jsonl"):
        try:
            if not _session_has_body(jf):
                jf.unlink()
        except OSError:
            pass
        break


async def ask_haiku(prompt: str) -> str:
    """一次性、不留 session 檔的輕量呼叫（標題生成用）。"""
    opts = ClaudeAgentOptions(
        cwd=str(config.DEFAULT_CWD),
        cli_path=config.CLAUDE_CLI,
        model="claude-haiku-4-5-20251001",
        permission_mode="bypassPermissions",
        extra_args={"no-session-persistence": None},
    )
    out, meta_sid = "", None
    async with ClaudeSDKClient(opts) as c:
        await c.query(prompt)
        async for msg in iter_messages(c):
            if isinstance(msg, ResultMessage):
                out = msg.result or ""
                meta_sid = msg.session_id
                break
    _purge_title_shell(meta_sid)
    return out.strip()


async def generate_title(first_message: str) -> str | None:
    """由第一則訊息生成短標題。失敗或長得像錯誤訊息就回 None。"""
    try:
        raw = await ask_haiku(TITLE_PROMPT + first_message[:500])
    except Exception:
        return None
    title = (raw or "").strip().splitlines()[0] if raw else ""
    title = title.strip("「」\"'*#＊ 　")[:24]
    if not title or _ERRORISH.search(title):
        return None
    return title
