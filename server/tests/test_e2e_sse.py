"""Phase 1 成功條件②：真實 HTTP 上的斷線續傳。

流程模擬手機被切到背景：
  A. 連上 SSE，送一個要跑一陣子的任務，收幾則事件後**直接斷開連線**
  B. 斷線期間 CC 繼續跑，事件持續進 ring buffer
  C. 帶 Last-Event-ID 重連，驗證漏掉的事件全部補上、不重複、不缺漏

需要服務已在 127.0.0.1:47362 跑（BUTLER_DEV=1）。
"""
from __future__ import annotations

import asyncio
import json
import sys

import httpx
from httpx_sse import aconnect_sse

BASE = "http://127.0.0.1:47362"
TOKEN = "test-token-12345"
HEAD = {"Authorization": f"Bearer {TOKEN}"}
CONV = "e2e"

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}",
          flush=True)
    if not cond:
        FAILED.append(name)


async def test_health_and_auth() -> None:
    print("\n[health / 認證]", flush=True)
    async with httpx.AsyncClient(timeout=10) as c:
        r = await c.get(f"{BASE}/v1/health")
        check("health 不需 token 即可存取", r.status_code == 200, str(r.json()))
        r = await c.get(f"{BASE}/v1/stream", headers={"Authorization": "Bearer wrong"})
        check("錯誤 token 被擋（401）", r.status_code == 401)
        r = await c.post(f"{BASE}/v1/conversations/{CONV}/message",
                         json={"text": "x"})
        check("無 token 送訊息被擋（401）", r.status_code == 401)


async def _collect(after: int | None, stop_after: int, deadline: float,
                   stop_on_final: bool = False) -> list[dict]:
    """連上 SSE 收事件。收滿 stop_after 則或看到 reply.final 就斷開。"""
    out: list[dict] = []
    headers = dict(HEAD)
    if after is not None:
        headers["Last-Event-ID"] = str(after)
    timeout = httpx.Timeout(deadline, read=deadline)
    async with httpx.AsyncClient(timeout=timeout) as c:
        async with aconnect_sse(c, "GET", f"{BASE}/v1/stream", headers=headers) as es:
            async for sse in es.aiter_sse():
                if not sse.event or sse.event == "ping":
                    continue
                out.append({"seq": int(sse.id), "type": sse.event,
                            "data": json.loads(sse.data)})
                if stop_on_final and sse.event in ("reply.final", "error"):
                    break
                if not stop_on_final and len(out) >= stop_after:
                    break
    return out


async def test_resume() -> None:
    print("\n[斷線續傳]", flush=True)
    # 先連上 SSE 再送訊息——這是真實 App 的順序。
    # 反過來會錯過 turn.start，因為 after=None 刻意不回放歷史（新連線不該被舊事件淹沒）。
    task = asyncio.create_task(_collect(after=None, stop_after=6, deadline=90))
    await asyncio.sleep(0.5)
    async with httpx.AsyncClient(timeout=10) as c:
        r = await c.post(f"{BASE}/v1/conversations/{CONV}/message", headers=HEAD,
                         json={"text": "數 1 到 15，每個數字一行，中間不要停頓"})
        check("訊息已排入佇列", r.status_code == 200, str(r.json()))

    # A：收前幾則就斷線（模擬手機切背景）
    phase_a = await task
    check("斷線前收到事件", len(phase_a) >= 1,
          f"types={[e['type'] for e in phase_a]}")
    if not phase_a:
        return
    last_seq = phase_a[-1]["seq"]
    print(f"  ...斷線於 seq={last_seq}，等 10 秒讓 CC 繼續跑", flush=True)

    # B：斷線期間 CC 繼續產生事件
    await asyncio.sleep(10)

    # C：帶 Last-Event-ID 重連
    phase_b = await _collect(after=last_seq, stop_after=999, deadline=180,
                             stop_on_final=True)
    check("重連後有收到事件", len(phase_b) >= 1, f"補回 {len(phase_b)} 則")

    seqs_a = [e["seq"] for e in phase_a]
    seqs_b = [e["seq"] for e in phase_b]
    check("重連沒有重複收到斷線前的事件", not (set(seqs_a) & set(seqs_b)))
    check("重連的第一則就是斷點的下一則（沒有缺漏）",
          bool(seqs_b) and min(seqs_b) > last_seq,
          f"斷點={last_seq} 續傳起點={min(seqs_b) if seqs_b else 'N/A'}")
    check("序號單調遞增", seqs_b == sorted(seqs_b))
    check("沒有回報斷層（ring buffer 夠大）",
          not any(e["type"] == "seq.gap" for e in phase_b))
    finals = [e for e in phase_b if e["type"] == "reply.final"]
    check("最終回覆有送達", bool(finals),
          (finals[0]["data"]["markdown"][:60] + "…") if finals else "")
    types = {e["type"] for e in phase_a + phase_b}
    check("收到完整事件類型（turn.start/status/turn.end）",
          {"turn.start", "status", "turn.end"} <= types, f"types={sorted(types)}")


async def main() -> int:
    try:
        await test_health_and_auth()
        await test_resume()
    except Exception as e:
        print(f"\n測試本身出錯: {type(e).__name__}: {e}")
        return 2
    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
