"""對話搜尋：關鍵字掃 butler 自己的 session 逐字稿。

對照 cc-bot 的 /search，但先做關鍵字版。語意搜尋（e5 向量）要多載一個
1024 維模型＋建索引，等關鍵字版證明不夠用再說。
"""
from __future__ import annotations

import json
from pathlib import Path

from .state import _load_map, get_title


def _iter_texts(jf: Path):
    """逐行抽出 session jsonl 裡的 user/assistant 純文字。"""
    with jf.open(encoding="utf-8") as f:
        for line in f:
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            if rec.get("type") not in ("user", "assistant"):
                continue
            msg = rec.get("message") or {}
            content = msg.get("content")
            if isinstance(content, str):
                yield rec["type"], content
            elif isinstance(content, list):
                for block in content:
                    if isinstance(block, dict) and block.get("type") == "text":
                        yield rec["type"], block.get("text", "")


def search(query: str, limit: int = 20) -> list[dict]:
    """關鍵字搜尋所有 butler 對話，回 [{conv_id, title, role, snippet}]。"""
    q = query.strip().lower()
    if not q:
        return []
    claude_home = Path.home() / ".claude" / "projects"
    out: list[dict] = []
    for conv_id, rec in _load_map().items():
        sid = rec.get("session_id")
        if not sid:
            continue
        for jf in claude_home.glob(f"*/{sid}.jsonl"):
            try:
                for role, text in _iter_texts(jf):
                    low = text.lower()
                    pos = low.find(q)
                    if pos < 0:
                        continue
                    # 命中點前後各取一段當摘要，避免回整篇
                    start = max(0, pos - 30)
                    snippet = text[start:pos + len(q) + 50].replace("\n", " ")
                    out.append({
                        "conv_id": conv_id,
                        "title": get_title(conv_id) or conv_id,
                        "role": role,
                        "snippet": ("…" if start > 0 else "") + snippet + "…",
                    })
                    if len(out) >= limit:
                        return out
            except OSError:
                pass
            break
    return out
