"""附件是結構化欄位，不是拼進訊息本文的路徑。

早期是 App 自己把「我傳了這些檔案給你：<一串路徑>」拼進本文再送出，於是那串
電腦路徑變成使用者自己氣泡裡的文字。現在路徑只在**送給模型的那一份**出現
（跟時間戳同一個道理），畫面照 attachments 欄位畫縮圖與檔案卡。

順帶守著一條安全性：attachments 的路徑是客戶端給的，直接信任等於讓任何配對過的
裝置指定一個路徑叫模型去讀——那是整台電腦的任意檔案。
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402,F401
from engine.turn import stamp  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


def test_stamp() -> None:
    print("\n[給模型的那一份才有路徑]")
    a = [{"name": "a.jpg", "path": r"C:\up\a.jpg", "bytes": 1, "mime": "image/jpeg"}]
    out = stamp("看這張", "手機", a)
    check("原文還在", "看這張" in out, out)
    check("時間戳還在", out.startswith("["), out[:20])
    check("路徑接在後面給模型讀", r"C:\up\a.jpg" in out, out)
    check("有講這是附件", "附件" in out, out)

    check("沒有附件就跟以前一模一樣",
          stamp("看這張", "手機") == stamp("看這張", "手機", []), "")
    check("附件沒有 path 就當沒有",
          "附件" not in stamp("x", "手機", [{"name": "a"}]), "")

    # 只有附件沒有文字：仍然要成立（丟一張圖說「看這個」也可能只丟圖）
    only = stamp("", "手機", a)
    check("只有附件也組得出來", r"C:\up\a.jpg" in only, only)


def test_clean() -> None:
    print("\n[路徑必須落在上傳目錄底下]")
    from transport.app import _clean_attachments
    from transport.files import UPLOAD_DIR

    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    good = UPLOAD_DIR / "t_att_ok.txt"
    good.write_text("x", encoding="utf-8")
    try:
        out = _clean_attachments([{"name": "ok.txt", "path": str(good)}])
        check("上傳目錄裡的留下", len(out) == 1 and out[0]["path"] == str(good.resolve()),
              str(out))
        check("大小自己補上", out and out[0]["bytes"] == 1, str(out))

        # 這條是安全性：客戶端指定的路徑不能指到任意檔案
        outside = _clean_attachments([{"name": "x", "path": r"C:\Windows\win.ini"}])
        check("上傳目錄外的丟掉", outside == [], str(outside))
        up = _clean_attachments(
            [{"name": "x", "path": str(UPLOAD_DIR / ".." / "session.json")}])
        check("往上跳的丟掉", up == [], str(up))
        check("不存在的檔丟掉",
              _clean_attachments([{"name": "x", "path": str(UPLOAD_DIR / "nope")}]) == [])
        check("不是清單就回空", _clean_attachments("x") == [] and _clean_attachments(None) == [])
        check("清單有上限", len(_clean_attachments(
            [{"name": "n", "path": str(good)}] * 50)) <= 20)
    finally:
        good.unlink(missing_ok=True)


async def test_event() -> None:
    print("\n[回音事件帶附件、本文不帶路徑]")
    from engine.worker import Worker
    from protocol import Event

    seen: list[Event] = []

    class FE:
        async def emit(self, ev):
            seen.append(ev)

        async def ask(self, req):
            return None

    w = Worker(lambda _c: FE(), pending_file=Path(config.DATA_DIR) / "t_att_pending.json")
    a = [{"name": "a.jpg", "path": r"C:\up\a.jpg", "bytes": 3, "mime": "image/jpeg"}]
    try:
        await w.submit("att-1", "看這張", "手機", a)
        msg = [e for e in seen if e.type == "user.message"]
        check("發了回音事件", len(msg) == 1, str(len(msg)))
        d = msg[0].data if msg else {}
        check("本文沒有路徑", "C:\\up" not in str(d.get("text")), str(d.get("text")))
        check("附件在自己的欄位", d.get("attachments") == a, str(d.get("attachments")))
        pend = w.pending_of("att-1")
        check("排隊清單也帶附件", pend and pend[0]["attachments"] == a, str(pend))
    finally:
        await w.shutdown()
        (Path(config.DATA_DIR) / "t_att_pending.json").unlink(missing_ok=True)


async def main() -> int:
    test_stamp()
    test_clean()
    await test_event()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
