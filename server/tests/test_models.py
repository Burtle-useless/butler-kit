"""模型清單：官方 /v1/models 為主、CLI 清單為輔；合併、別名、試跑標記、快取。"""
from __future__ import annotations

import asyncio
import json
import sys
import tempfile
import time
from pathlib import Path

import config  # noqa: F401
from engine import models, oauth_api, sdk_update
from engine.errors import classify

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}", flush=True)
    if not cond:
        FAILED.append(name)


ALL = ["low", "medium", "high", "xhigh", "max"]

# CLI 2.1.280 的 initialize 清單（實際回應的樣子）
CLI_280 = [
    {"value": "default", "resolvedModel": "claude-opus-5-5[1m]",
     "displayName": "Default (recommended)",
     "description": "Opus 5.5 with 1M context · Best for everyday, complex tasks",
     "supportsEffort": True, "supportedEffortLevels": ALL},
    {"value": "opus[1m]", "resolvedModel": "claude-opus-5-5[1m]",
     "displayName": "Opus (1M context)",
     "description": "Opus 5.5 with 1M context · Best for everyday, complex tasks",
     "supportsEffort": True, "supportedEffortLevels": ALL},
    {"value": "claude-fable-5-1[1m]", "resolvedModel": "claude-fable-5-1",
     "displayName": "Fable", "description": "Fable 5.1 · Most capable",
     "supportsEffort": True, "supportedEffortLevels": ALL},
    {"value": "sonnet", "resolvedModel": "claude-sonnet-5", "displayName": "Sonnet",
     "description": "Sonnet 5 · Efficient for routine tasks",
     "supportsEffort": True, "supportedEffortLevels": ALL},
    {"value": "haiku", "resolvedModel": "claude-haiku-4-5-20251001", "displayName": "Haiku",
     "description": "Haiku 4.5 · Fastest for quick answers"},
]

# 舊版 CLI（2.1.259）還不認得 Opus 5.5：它的 opus 別名指到 Opus 5
CLI_259 = [dict(m) for m in CLI_280]
CLI_259[0]["resolvedModel"] = CLI_259[1]["resolvedModel"] = "claude-opus-5[1m]"


def api_model(mid: str, name: str, created: str, ctx: int = 1_000_000,
              effort: list[str] | None = None) -> dict:
    eff: dict = {"supported": effort is None or bool(effort)}
    for lv in ALL:
        eff[lv] = {"supported": lv in (ALL if effort is None else effort)}
    return {"type": "model", "id": mid, "display_name": name,
            "created_at": created + "T00:00:00Z", "max_input_tokens": ctx,
            "max_tokens": 128000, "capabilities": {"effort": eff, "batch": {"supported": True}}}


# 官方 /v1/models 的回應（12 顆，順序照官方給的：新到舊）
API = {"data": [
    api_model("claude-opus-5-5", "Claude Opus 5.5", "2026-09-21"),
    api_model("claude-fable-5-1", "Claude Fable 5.1", "2026-08-28"),
    api_model("claude-opus-5", "Claude Opus 5", "2026-07-24"),
    api_model("claude-sonnet-5", "Claude Sonnet 5", "2026-06-29"),
    api_model("claude-fable-5", "Claude Fable 5", "2026-06-07"),
    api_model("claude-opus-4-8", "Claude Opus 4.8", "2026-05-28"),
    api_model("claude-opus-4-7", "Claude Opus 4.7", "2026-04-14"),
    api_model("claude-sonnet-4-6", "Claude Sonnet 4.6", "2026-02-17"),
    api_model("claude-opus-4-6", "Claude Opus 4.6", "2026-02-04"),
    api_model("claude-opus-4-5-20251101", "Claude Opus 4.5", "2025-11-24", ctx=200_000),
    api_model("claude-haiku-4-5-20251001", "Claude Haiku 4.5", "2025-10-15", ctx=200_000,
              effort=[]),
    api_model("claude-sonnet-4-5-20250929", "Claude Sonnet 4.5", "2025-09-29", effort=[]),
], "has_more": False}


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


def set_cli_version(v: str) -> None:
    """假裝已經問過 CLI 版本（catalog 只看快取，不開子行程）。"""
    sdk_update._cli_cache.clear()
    sdk_update._cli_cache[str(sdk_update.cli_path())] = v


def use_api(payload: dict | None) -> None:
    oauth_api.get_json = lambda url, headers=None, timeout=10: payload  # type: ignore[assignment]


async def main() -> int:
    real_get = oauth_api.get_json
    # 背景試跑不准真的開 CLI：一律換成查表，查不到就是「說不準」
    results: dict[str, tuple[bool | None, str]] = {}

    async def fake_probe(value: str) -> tuple[bool | None, str]:
        return results.get(value, (None, ""))

    saved_probe = models._probe
    models._probe = fake_probe  # type: ignore[assignment]
    set_cli_version("2.1.280")

    print("\n[沒連過線：內建後備不是空的]")
    fresh()
    check("後備只列別名", models.values() == ["opus[1m]", "sonnet", "haiku"], str(models.values()))
    check("來源標成 fallback", models.source() == "fallback")
    check("claude- 開頭的舊值仍算合法", models.is_known("claude-opus-5"))
    check("亂打的值不合法", not models.is_known("gpt-9"))

    print("\n[只有 CLI 清單（官方清單拿不到）：照 CLI 的用]")
    d = fresh()
    use_api(None)
    client = FakeClient({"models": CLI_280, "commands": []})
    ok = await models.refresh_from(client)
    await asyncio.gather(*models._tasks, return_exceptions=True)
    check("拿到了", ok)
    check("清單就是 CLI 的", models.values() == [m["value"] for m in CLI_280], str(models.values()))
    check("別名解析成實際 id", models.resolve("default") == "claude-opus-5-5[1m]")
    check("haiku 不支援思考等級", models.efforts_for("haiku") == [])
    check("快取檔寫了", (d / "models.json").exists())
    check("來源標成 cli", models.source() == "cli")
    ok2 = await models.refresh_from(client)
    check("一小時內不再問 CLI", ok2 is False and client.calls == 1, str(client.calls))

    print("\n[官方清單：合併成主力＋更多模型]")
    d = fresh()
    use_api(API)
    await models.refresh_from(FakeClient({"models": CLI_280}))
    await asyncio.gather(*models._tasks, return_exceptions=True)
    cat = models.catalog()
    main_ = [m for m in cat if m.tier == "main"]
    more = [m for m in cat if m.tier == "more"]
    check("來源標成 api", models.source() == "api")
    check("主力是各系列最新、照 CLI 的系列順序",
          [m.name for m in main_] == ["Opus 5.5", "Fable 5.1", "Sonnet 5", "Haiku 4.5"],
          str([m.name for m in main_]))
    check("主力的值：有 1M 的系列帶 [1m]、Sonnet 不帶",
          [m.value for m in main_] == ["claude-opus-5-5[1m]", "claude-fable-5-1[1m]",
                                      "claude-sonnet-5", "claude-haiku-4-5-20251001"],
          str([m.value for m in main_]))
    check("更多模型新到舊", [m.name for m in more][:3] == ["Opus 5", "Fable 5", "Opus 4.8"],
          str([m.name for m in more]))
    check("12 顆都在", len(cat) == 12, str(len(cat)))
    by = {m.resolved: m for m in cat}
    check("舊 Opus 撐 1M 也帶 [1m]", by["claude-opus-5"].value == "claude-opus-5[1m]")
    check("只撐 200K 的不帶 [1m]", by["claude-opus-4-5-20251101"].value == "claude-opus-4-5-20251101")
    check("主力說明是中文系列說明", main_[0].description == models._TAGLINE["opus"])
    check("更多模型的說明是發布月份", by["claude-opus-4-8"].description == "2026 年 5 月發布",
          by["claude-opus-4-8"].description)
    check("思考等級照官方給", models.efforts_for("claude-opus-5-5[1m]") == ALL)
    check("官方說不支援的就不給", models.efforts_for("claude-sonnet-4-5-20250929") == [])

    print("\n[各種舊值都對得回同一顆]")
    check("CLI 別名 opus[1m] → Opus 5.5", models.find("opus[1m]").name == "Opus 5.5")
    check("default → Opus 5.5", models.find("default").name == "Opus 5.5")
    check("系列名 opus → 主力 Opus", models.find("opus").name == "Opus 5.5")
    check("系列名 sonnet → Sonnet 5", models.resolve("sonnet") == "claude-sonnet-5")
    check("不帶 [1m] 的舊 id", models.find("claude-opus-5").name == "Opus 5")
    check("帶 [1m] 的 id", models.find("claude-opus-5[1m]").name == "Opus 5")
    check("記帳用的 id 不帶後綴", models.resolve("opus[1m]") == "claude-opus-5-5")
    check("完整 id 合法", models.is_known("claude-opus-4-7"))
    check("思考等級驗證", models.is_effort_known("xhigh") and not models.is_effort_known("ultra"))

    print("\n[重啟：先讀快取頂著]")
    models.reset_for_tests()
    models._load_cache()
    check("快取讀回來一樣", [m.value for m in models.catalog()] == [m.value for m in cat])
    check("來源標成 cache", models.source() == "cache")
    raw = json.loads((d / "models.json").read_text(encoding="utf-8"))
    check("快取記得官方清單的時間", raw.get("api_fetched_at", 0) > time.time() - 60)

    print("\n[官方清單拿不到：沿用現有的，短時間內不重試]")
    use_api(None)
    models._cat.api_at = 0.0
    check("過期了", models.api_stale())
    check("失敗回 False", models.refresh_api() is False)
    check("清單沒被清掉", len(models.catalog()) == 12)
    check("剛失敗過不馬上重試", not models.api_stale())

    print("\n[官方多了一級思考等級：照樣收]")
    fresh()
    extra = json.loads(json.dumps(API))
    extra["data"][0]["capabilities"]["effort"]["ultra"] = {"supported": True}
    use_api(extra)
    models.refresh_api()
    check("新等級合法", models.is_effort_known("ultra"))
    check("那顆模型列得出來", "ultra" in models.efforts_for("claude-opus-5-5[1m]"))

    print("\n[舊 CLI 跑不動新模型：挑出要試跑的、記下結果]")
    fresh()
    use_api(API)
    set_cli_version("2.1.259")
    await models.refresh_from(FakeClient({"models": CLI_259}))
    await asyncio.gather(*models._tasks, return_exceptions=True)
    cand = models._probe_candidates("2.1.259")
    check("只試比 CLI 認得的最新一顆還新的", [m.resolved for m in cand] == ["claude-opus-5-5"],
          str([m.resolved for m in cand]))
    text = ("API Error: 400 Claude Code 2.1.259 does not support this model; "
            "version 2.1.280 or newer is required. Run 'claude update'")
    check("錯誤分類成要更新", classify(text) == "MODEL_NEEDS_UPDATE")
    check("回合撞牆時記下要求的版本", models.note_rejected("claude-opus-5-5[1m]", text)
          == (False, "2.1.280"))
    m55 = models.find("claude-opus-5-5")
    check("這顆標成不可用", not m55.available and m55.requires_cli == "2.1.280",
          f"{m55.available} {m55.requires_cli}")
    check("記過就不再試", models._probe_candidates("2.1.259") == [])
    check("別的錯誤不記", models.note_rejected("claude-opus-5[1m]", "overloaded") == (None, ""))
    set_cli_version("2.1.280")
    check("換了新版 CLI，舊結果不算數", models.find("claude-opus-5-5").available)
    check("新版 CLI 要重試", [m.resolved for m in models._probe_candidates("2.1.280")]
          == ["claude-opus-5-5"])

    print("\n[背景試跑：跑得動、說不準]")
    results["claude-opus-5-5[1m]"] = (True, "")
    n = await models.probe_new()
    check("試了一顆", n == 1)
    check("跑得動就記 ok", models._cat.probes["claude-opus-5-5"]["ok"] is True)
    check("不再重試", models._probe_candidates("2.1.280") == [])
    models._cat.probes.clear()
    results.clear()
    await models.probe_new()
    check("說不準的不記", "claude-opus-5-5" not in models._cat.probes)

    print("\n[過載備援不能跟主模型同一顆]")
    from engine.options import _fallback_for
    check("主模型是 Sonnet 5 就不給備援", _fallback_for("claude-sonnet-5") is None)
    check("別名也認得", _fallback_for("sonnet") is None)
    check("其他模型照給", _fallback_for("claude-opus-5-5[1m]") == config.FALLBACK_MODEL)

    oauth_api.get_json = real_get  # type: ignore[assignment]
    models._probe = saved_probe  # type: ignore[assignment]
    sdk_update._cli_cache.clear()
    print(f"\n{'=' * 50}")
    if FAILED:
        print(f"FAILED {len(FAILED)}: {FAILED}")
        return 1
    print("全部通過")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
