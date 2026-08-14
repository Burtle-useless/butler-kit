"""CC 錯誤分類與善後策略。

分類的目的不是為了漂亮的錯誤訊息，是為了決定**善後動作**：
哪些該重試、哪些該丟掉 client 重生、哪些該清掉 session 重來。
把這三件事搞錯，症狀會是「重新登入也救不回的 401」或「越重試越糟」。
"""
from __future__ import annotations

import asyncio

ErrKind = str


def classify(err: str) -> ErrKind:
    """把例外訊息歸類。比對字串是刻意的——SDK 不提供結構化錯誤碼。"""
    s = (err or "").lower()
    if "exceeded maximum buffer size" in s or "failed to decode json" in s:
        return "INPUT_TOO_LARGE"
    if "prompt is too long" in s or ("400" in s and "too long" in s):
        return "CONTEXT_FULL"
    if "529" in s or "overloaded" in s:
        return "OVERLOADED"
    if "429" in s or "rate_limit" in s or "rate limit" in s:
        return "RATE_LIMIT"
    if "failed to start claude" in s or "winerror 267" in s or "目錄名稱無效" in s:
        return "STARTUP"
    if "401" in s or "credential" in s or "authentication" in s or "unauthorized" in s:
        return "AUTH"
    if "control request timeout" in s or "initialize" in s:
        return "INIT_TIMEOUT"
    return "UNKNOWN"


# 值得自動重試的（暫時性問題）
RETRYABLE: frozenset[str] = frozenset({"OVERLOADED", "RATE_LIMIT", "INIT_TIMEOUT"})

# 必須清掉 session 重來的——context 撐爆時 resume 回去只會再爆一次
RESET_SESSION: frozenset[str] = frozenset({"CONTEXT_FULL"})

# 必須丟棄長駐 client 的。
# AUTH 尤其重要：401 多半是「client 進程帶著過期憑證出生」，
# 那個進程不會自己去撿新權杖，不丟掉就會永遠 401——重新登入也沒用。
DROP_CLIENT: frozenset[str] = frozenset({"AUTH", "CONTEXT_FULL", "STARTUP", "INIT_TIMEOUT"})

USER_FACING: dict[str, str] = {
    "INPUT_TOO_LARGE": "這次的輸入太大，我吃不下。分批給我或先讓我讀檔案。",
    "CONTEXT_FULL": "這個對話太長了，我先把它壓縮過再繼續。",
    "OVERLOADED": "Anthropic 那邊現在滿載，我等一下再試。",
    "RATE_LIMIT": "用量到上限了，要等額度回復。",
    "AUTH": "登入憑證失效了，要在電腦上重新 claude /login。",
    "STARTUP": "Claude Code 起不來，多半是工作目錄不存在。",
    "TIMEOUT": "太久沒有任何回應，我把這回合中止了。",
    "INIT_TIMEOUT": "Claude Code 初始化卡住了，我重開一個試試。",
    "UNKNOWN": "出了點狀況。",
}


class CCError(Exception):
    """已分類的 CC 錯誤。

    刻意用普通 class 而不是 dataclass：`@dataclass(slots=True)` 會重建類別物件，
    使 `__post_init__` 裡 zero-arg 的 `super()` 指向**舊**的類別，
    實例化時直接拋 "obj is not an instance or subtype of type"。
    Exception 子類就照傳統寫法寫，別為了風格改。
    """

    def __init__(self, kind: ErrKind, raw: str) -> None:
        self.kind = kind
        self.raw = raw
        super().__init__(f"{kind}: {raw[:200]}")

    @property
    def user_msg(self) -> str:
        return USER_FACING.get(self.kind, USER_FACING["UNKNOWN"])

    @property
    def retryable(self) -> bool:
        return self.kind in RETRYABLE

    @property
    def should_drop_client(self) -> bool:
        return self.kind in DROP_CLIENT

    @property
    def should_reset_session(self) -> bool:
        return self.kind in RESET_SESSION


def wrap(exc: BaseException) -> CCError:
    """把任意例外轉成已分類的 CCError。"""
    if isinstance(exc, CCError):
        return exc
    # TimeoutError 的 str() 是空字串，交給 classify 只會得到 UNKNOWN，
    # 而它其實是最常見的一種失敗（CC 連續無輸出達 INACTIVITY_TIMEOUT）。
    if isinstance(exc, asyncio.TimeoutError):
        return CCError(kind="TIMEOUT", raw="連續無輸出超過逾時上限")
    return CCError(kind=classify(str(exc)), raw=f"{type(exc).__name__}: {exc}")
