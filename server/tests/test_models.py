"""模型清單跟著官方走：從 CLI 的 initialize 回應取、快取、驗證。"""
from __future__ import annotations

import asyncio
import json
import sys
import tempfile
import time
from pathlib import Path

import config  # noqa: F401
from engine import models

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}", flush=True)
    if not cond:
        FAILED.append(name)


OFFICIAL = [
    {"value": "default", "resolvedModel": "claude-opus-5[1m]",
     "displayName": "Default (recommended)",
     "description": "Opus 5 with 1M context", "supportsEffort": True,
     "supportedEffortLevels": ["low", "medium", "high", "xhigh", "max"]},
    {"value": "claude-fable-5[1m]", "resolvedModel": "claude-fable-5",
     "displayName": "Fable", "description": "Fable 5", "supportsEffort": True,
     "supportedEffortLevels": ["low", "medium", "high", "xhigh", "max"]},
    {"value": "sonnet", "resolvedModel": "claude-sonnet-5", "displayName": "Sonnet",
     "description": "Sonnet 5", "supportsEffort": True,
     "supportedEffortLevels": ["low", "medium", "high", "xhigh", "max"]},
    {"value": "haiku", "resolvedModel": "claude-haiku-4-5-20251001", "displayName": "Haiku",
     "description": "Haiku 4.5"},
]

# CLI 清單（2.1.259 為止）還沒收錄、由 _KNOWN_EXTRA 補的那顆
EXTRA = "claude-fable-5-1[1m]"
# 官方清單照舊 + 補充插在 Fable 正後面
WITH_EXTRA = ["default", "claude-fable-5[1m]", EXTRA, "sonnet", "haiku"]


class FakeClient:
    def __init__(self, payload: dict | None, fail: bool = False) -> None:
        self.payload = payload
        self.fail = fail
        self.calls = 0

    async def get_server_info(self) -> dict | None:
        self.calls += 1
        if self.fail:
            raise RuntimeError("boom")
        return self.payload


def fresh() -> Path:
    d = Path(tempfile.mkdtemp(prefix="butler-models-"))
    models.CATALOG_FILE = d / "models.json"
    models.reset_for_tests()
    return d


async def main() -> int:
    print("\n[沒連過線：內建後備不是空的]")
    fresh()
    check("後備清單以別名為主、Fable 例外", models.values()[0] == "default" and
          [v for v in models.values() if v.startswith("claude-")]
          == ["claude-fable-5[1m]", EXTRA],
          str(models.values()))
    check("後備清單有 Fable", any(m.name == "Fable" for m in models.catalog()))
    check("補充插在 Fable 正後面（後備）", models.values().index(EXTRA)
          == models.values().index("claude-fable-5[1m]") + 1, str(models.values()))
    check("來源標成 fallback", models.source() == "fallback")
    check("claude- 開頭的舊值仍算合法", models.is_known("claude-opus-5"))
    check("亂打的值不合法", not models.is_known("gpt-9"))

    print("\n[從 CLI 拿到就用官方的，並落快取]")
    d = fresh()
    client = FakeClient({"models": OFFICIAL, "commands": []})
    ok = await models.refresh_from(client)
    check("拿到了", ok)
    check("清單換成官方的＋補充插在 Fable 後", models.values() == WITH_EXTRA,
          str(models.values()))
    check("顯示名帶過來", models.find("default").name == "Default (recommended)")
    check("解析成實際 id", models.resolve("default") == "claude-opus-5[1m]")
    check("完整 id 也找得到", models.find("claude-sonnet-5") is not None)
    check("思考等級照模型給", models.efforts_for("sonnet") == ["low", "medium", "high", "xhigh", "max"])
    check("haiku 不支援思考等級", models.efforts_for("haiku") == [])
    check("快取檔寫了", (d / "models.json").exists())
    check("來源標成 cli", models.source() == "cli")
    ok2 = await models.refresh_from(client)
    check("一小時內不再問", ok2 is False and client.calls == 1, str(client.calls))

    print("\n[重啟：先讀快取頂著]")
    cache = json.loads((d / "models.json").read_text(encoding="utf-8"))
    models.reset_for_tests()
    models._load_cache()
    check("快取讀回來", models.values() == WITH_EXTRA, str(models.values()))
    check("來源標成 cache", models.source() == "cache")
    check("快取記得時間", cache.get("fetched_at", 0) > time.time() - 60)

    print("\n[CLI 出錯或回空：沿用現有的，不拋]")
    models._cat.fetched_at = 0.0
    ok3 = await models.refresh_from(FakeClient(None, fail=True))
    check("失敗不拋、回 False", ok3 is False)
    check("清單沒被清掉", models.values() == WITH_EXTRA)
    ok4 = await models.refresh_from(FakeClient({"models": []}))
    check("空清單不覆蓋", ok4 is False and models.values() == WITH_EXTRA)

    print("\n[未來 CLI 收錄了 5.1：補充自動退場，不重複]")
    fresh()
    future = OFFICIAL[:2] + [
        {"value": EXTRA, "resolvedModel": "claude-fable-5-1",
         "displayName": "Fable 5.1", "description": "官方收錄版", "supportsEffort": True,
         "supportedEffortLevels": ["low", "medium", "high", "xhigh", "max"]},
    ] + OFFICIAL[2:]
    await models.refresh_from(FakeClient({"models": future, "commands": []}))
    check("同一顆只出現一次", models.values().count(EXTRA) == 1, str(models.values()))
    check("用的是官方那筆", models.find(EXTRA).description == "官方收錄版")
    check("5.1 找得到、解析對", models.resolve(EXTRA) == "claude-fable-5-1")

    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
