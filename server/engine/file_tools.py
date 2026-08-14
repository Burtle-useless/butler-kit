"""給助理的傳檔工具（in-process MCP）：把電腦上的檔案送到手機。

反方向（手機傳上來）走的是 HTTP 上傳，不需要工具——那是使用者主動的動作。
這一邊必須是工具，因為發起者是模型：它做完一張圖、一份報告之後，
要能自己決定「這個他會想拿到手機上」。

工具本身不搬檔案也不管網路，只在 outbox 登記一筆並敲一下掛勾，
真正的下載端點在 transport 那一層。
"""
from __future__ import annotations

import json
from collections.abc import Awaitable, Callable
from typing import Any

from claude_agent_sdk import create_sdk_mcp_server, tool

import outbox

# 登記完要通知手機（發推播、刷新清單）。engine 不該認識 transport，
# 一樣只留掛勾，由 app.py 啟動時注入。
_on_offer: Callable[[dict[str, Any]], Awaitable[None]] | None = None


def set_offer_hook(fn: Callable[[dict[str, Any]], Awaitable[None]]) -> None:
    global _on_offer
    _on_offer = fn


@tool(
    "send_file",
    "把這台電腦上的一個檔案傳到他手機上。做好的圖、報告、匯出的資料想給他，就用這個。"
    "他會收到通知，檔案存在 App 的「助理傳來的檔案」裡等他下載。"
    "只傳單一檔案；整個資料夾請先壓成 zip 再傳。",
    {
        "type": "object",
        "properties": {
            "path": {"type": "string", "description": "檔案在這台電腦上的完整絕對路徑"},
            "note": {"type": "string", "description": "這是什麼，一句話，可省略"},
        },
        "required": ["path"],
    },
)
async def send_file(args: dict[str, Any]) -> dict[str, Any]:
    try:
        item = outbox.offer(args["path"], args.get("note", ""))
    except outbox.OutboxError as e:
        return {"content": [{"type": "text", "text": str(e)}], "is_error": True}
    if _on_offer is not None:
        await _on_offer(item)
    return {"content": [{"type": "text", "text": json.dumps(
        {"sent": item["name"], "bytes": item["bytes"]}, ensure_ascii=False)}]}


ALL_TOOLS = [send_file]

SERVER_NAME = "files"

SERVER = create_sdk_mcp_server(name=SERVER_NAME, version="1.0.0", tools=ALL_TOOLS)

QUALIFIED = [f"mcp__{SERVER_NAME}__{t.name}" for t in ALL_TOOLS]
