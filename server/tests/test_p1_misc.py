"""P1 雜項迴歸：上傳碰撞、原子寫入撞讀者、空白標題、非 ASCII token。

這幾條沒有共同的主題，共通點是「都會在真實使用中發生，而且全部靜默出錯」——
覆蓋掉的圖、沒存進去的行程、永遠沒有標題的對話、被打成 500 的認證。
"""
from __future__ import annotations

import asyncio
import sys
import tempfile
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from engine import meta                      # noqa: E402
from transport import auth, files            # noqa: E402
from util import atomic_write_text, read_text_with_retry   # noqa: E402

files.UPLOAD_DIR = Path(tempfile.mkdtemp()) / "uploads"   # type: ignore[misc]

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def test_upload_collision() -> None:
    """同一秒的同名上傳不可以互相覆蓋。

    時間戳只到秒，手機一次多選送兩張 IMG_0001.jpg 就會撞。原本是 write_bytes
    直接覆寫——訊息裡的路徑於是指到另一張圖，助理讀到的是錯的檔案。
    """
    print("\n[同秒同名上傳]")
    paths = [files.save_upload("IMG_0001.jpg", f"第{i}張".encode()) for i in range(3)]
    check("三個檔名互不相同", len({str(p) for p in paths}) == 3,
          str([p.name for p in paths]))
    check("三份內容都還在",
          [p.read_bytes().decode() for p in paths] == ["第0張", "第1張", "第2張"])
    check("副檔名沒有被序號擠掉", all(p.suffix == ".jpg" for p in paths),
          str([p.name for p in paths]))
    # 沒有副檔名的檔案也不能爆
    a = files.save_upload("README", b"a")
    b = files.save_upload("README", b"b")
    check("無副檔名的同名檔也分得開", a != b and b.read_bytes() == b"b")


def test_atomic_write_survives_readers() -> None:
    """有人正開著目標檔在讀時，原子寫入不可以失敗。

    **這是 Windows 專屬的坑**：`MoveFileEx` 對正被開啟的目標檔會直接拒絕
    （PermissionError / WinError 5），POSIX 不會。而這個專案所有讀取函式都在鎖外
    read_text，所以「寫入時剛好有人在讀」是日常，不是邊角案例。
    2026-08-17 在 devices.json 上實測到：撤銷裝置時因為有 4 條 verify 執行緒在讀，
    撤銷不是失效而是整個拋例外。
    """
    print("\n[原子寫入撞上讀者]")
    target = Path(tempfile.mkdtemp()) / "state.json"
    atomic_write_text(target, "初始內容")
    stop = threading.Event()
    read_errors: list[str] = []

    def keep_reading() -> None:
        # 讀的是 read_text_with_retry 而不是裸 read_text——專案裡所有讀取點
        # 現在都走它，測試要測真實路徑。裸讀在這個壓力下必失敗，那也是為什麼
        # 「讀不到就當壞檔」這個假設會把好檔案隔離掉。
        while not stop.is_set():
            try:
                read_text_with_retry(target)
            except OSError as e:
                read_errors.append(repr(e))
            # 真實負載下讀取是零星的（HTTP 請求、每 60 秒一次的掃描），
            # 不是無間隔的 tight loop。1ms 間隔換算下來每秒仍有約 4000 次讀，
            # 遠高於實際，但至少讓檔案有空檔可以被換掉。
            time.sleep(0.001)

    threads = [threading.Thread(target=keep_reading) for _ in range(4)]
    for t in threads:
        t.start()
    failures: list[str] = []
    try:
        for i in range(200):
            try:
                atomic_write_text(target, f"第 {i} 次寫入")
            except OSError as e:
                failures.append(repr(e))
    finally:
        stop.set()
        for t in threads:
            t.join(timeout=5)

    check("200 次寫入全部成功", not failures,
          f"{len(failures)} 次失敗，第一個：{failures[0] if failures else ''}")
    check("讀者從頭到尾都拿得到檔案", not read_errors,
          f"{len(read_errors)} 次讀取失敗")
    check("最後的內容是完整的", target.read_text(encoding="utf-8") == "第 199 次寫入")


def test_blank_title_is_none() -> None:
    """標題生成拿到空白回覆要回 None，不可以 IndexError。

    `"   "` 這種回覆會讓 `if raw` 為真、`strip().splitlines()` 卻是空清單，
    `[0]` 當場炸掉——而那行在 try 外面，例外一路穿出去讓標題端點 500，
    該對話從此不會再有標題（生成只在第一則訊息之後跑一次）。
    """
    print("\n[空白標題]")
    original = meta.ask_haiku
    cases = [("   ", "全空白"), ("", "空字串"), ("\n\n", "只有換行")]
    try:
        for raw, why in cases:
            async def fake(_prompt: str, _r: str = raw) -> str:
                return _r
            meta.ask_haiku = fake            # type: ignore[assignment]
            try:
                got = asyncio.run(meta.generate_title("隨便一段對話"))
                check(f"{why} → None", got is None, repr(got))
            except Exception as e:
                check(f"{why} → None", False, f"拋了 {e!r}")
        # 正常回覆還是要生得出標題
        async def ok(_prompt: str) -> str:
            return "「幫忙訂便當」\n多出來的第二行"
        meta.ask_haiku = ok                  # type: ignore[assignment]
        got = asyncio.run(meta.generate_title("x"))
        check("正常回覆仍取得到標題", got == "幫忙訂便當", repr(got))
    finally:
        meta.ask_haiku = original            # type: ignore[assignment]


def test_non_ascii_token_is_401() -> None:
    """非 ASCII 的 token 要當成無效，不可以拋例外。

    `hmac.compare_digest` 收兩個 str 時要求兩邊都是 ASCII，否則 TypeError。
    設了 BUTLER_TOKEN 時，任何人送 `Authorization: Bearer 中文` 就能把**任何**
    端點打成 500 並在日誌裡留下 traceback——未認證就做得到。
    """
    print("\n[非 ASCII token]")
    import os
    old = os.environ.get("BUTLER_TOKEN")
    os.environ["BUTLER_TOKEN"] = "正確的中文token"
    try:
        for bad, why in [("中文", "中文"), ("token🔑", "emoji"), ("ｆｕｌｌ", "全形")]:
            try:
                check(f"{why} 判為無效", auth.verify(bad) is False)
            except Exception as e:
                check(f"{why} 判為無效", False, f"拋了 {e!r}")
        # 非 ASCII 的正確 token 仍然要驗得過（設定得出來就要用得了）
        check("非 ASCII 的正確 token 仍可通過", auth.verify("正確的中文token") is True)
    finally:
        if old is None:
            os.environ.pop("BUTLER_TOKEN", None)
        else:
            os.environ["BUTLER_TOKEN"] = old


if __name__ == "__main__":
    t0 = time.time()
    test_upload_collision()
    test_atomic_write_survives_readers()
    test_blank_title_is_none()
    test_non_ascii_token_is_401()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}"
          f"（{time.time() - t0:.1f} 秒）")
    sys.exit(1 if FAILED else 0)
