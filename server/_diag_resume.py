"""實測：resume 一個不存在的 session id 會拿到什麼錯誤訊息。

目的是決定 engine/errors.py 該用什麼字串分類——猜字串等於沒做。
獨立進程執行，不碰正在跑的服務。
"""
from __future__ import annotations

import asyncio

from claude_agent_sdk import ClaudeAgentOptions, ClaudeSDKClient

FAKE = "00000000-0000-0000-0000-000000000000"


async def main() -> None:
    opts = ClaudeAgentOptions(
        cwd="C:\\Users\\you",
        permission_mode="bypassPermissions",
        resume=FAKE,
    )
    client = ClaudeSDKClient(opts)
    try:
        await client.connect()
        await client.query("嗨")
        async for msg in client.receive_response():
            print("MSG:", type(msg).__name__, repr(msg)[:300])
    except BaseException as exc:  # noqa: BLE001 - 就是要看原始例外長相
        print("EXC_TYPE:", type(exc).__name__)
        print("EXC_STR:", str(exc)[:2000])
    finally:
        try:
            await client.disconnect()
        except BaseException:
            pass


asyncio.run(main())
