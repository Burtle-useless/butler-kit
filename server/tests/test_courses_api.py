"""課程端點：直接讀資料夾——列課、對照課表、解析 index／log、檔案清單與下載、擋路徑穿越。

**資料目錄與課程目錄都要在 import app 之前指到暫存**：agenda.store 在 import 時
就把 AGENDA_FILE 綁死在 config.DATA_DIR 底下，之後改 config 沒用。
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
from pathlib import Path

os.environ.setdefault("BUTLER_DATA_DIR", tempfile.mkdtemp(prefix="butler-courses-data-"))
# 課程是選配功能，沒開的話課程路由根本不掛。這支測的就是它，要打開
os.environ.setdefault("BUTLER_COURSES", "1")
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402

ROOT = Path(tempfile.mkdtemp(prefix="butler-courses-"))
config.COURSES_DIR = ROOT          # type: ignore[misc]

from fastapi.testclient import TestClient  # noqa: E402

from agenda.store import AGENDA_FILE  # noqa: E402
from transport import courses_api  # noqa: E402
from transport.app import app  # noqa: E402
from transport.auth import require_token  # noqa: E402

app.dependency_overrides[require_token] = lambda: "test"
client = TestClient(app)

FAILED: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{'  ' + extra if extra else ''}")
    if not cond:
        FAILED.append(name)


INDEX_MD = """# 微積分（一）

| 項目 | 內容 |
|---|---|
| 老師 | 陳老師 |
| 教室 | A203 |
| 時段 | 週三 第1節、週四 第5-6節 |
| 備註 | 必修 3 學分 |

> 課表以伺服器的 agenda.json 為準。

---

## 理解進度

程度欄只填三種：`懂` / `半懂` / `不會`。

| 主題 | 程度 | 依據 | 卡在哪 | 最後更新 |
|---|---|---|---|---|
| 極限的 ε-δ 定義 | 半懂 | 提問推斷 | 為什麼要先給 ε | 09-07 |
| 連鎖律 | 懂 | 答對檢核 | | 09-07 |
| 隱函數微分 | 不會 | 提問推斷 | dy/dx 兩邊都有 y | 09-07 |
| 壞列 | 超懂 | | | |

## 評分方式

| 項目 | 佔比 |
|---|---|
| 期中考 | 40% |
| 期末考 | 60% |

## 待辦

<!-- 作業 -->
"""

LOG_MD = """# 微積分（一） 提問紀錄

| 時間 | 主題 | 他問了什麼 | 我的判讀 |
|---|---|---|---|
| 09-07 10:02 | 極限 | ε-δ 的 ε 是誰先給的 | 半懂，順序搞反 |
| 09-07 10:15 | 連鎖律 | 外導乘內導的內導是什麼 | 講完自己算對了 |
"""

# 真實的 115-1 課表課名（agenda.json 那份），對照真實的資料夾名
AGENDA = {"courses": [
    {"id": "c1", "name": "微積分（一）", "day": 2, "from_period": 1, "to_period": 1},
    {"id": "c2", "name": "微積分（一）", "day": 3, "from_period": 5, "to_period": 6},
    {"id": "c3", "name": "線性代數", "day": 0, "from_period": 7, "to_period": 9},
    {"id": "c4", "name": "通識：生活中的天文學", "day": 2, "from_period": 5, "to_period": 6},
    {"id": "c5", "name": "通識：音樂欣賞與創作", "day": 3, "from_period": 7, "to_period": 8},
    {"id": "c6", "name": "普通化學與實驗（一）", "day": 1, "from_period": 2, "to_period": 4},
    {"id": "c7", "name": "社會關懷：社區服務學習", "day": 4, "from_period": 1, "to_period": 2},
    {"id": "c8", "name": "Python 程式設計", "day": 0, "from_period": 2, "to_period": 4},
    {"id": "c9", "name": "體育（二）", "day": 4, "from_period": 3, "to_period": 4},
    {"id": "c10", "name": "英文簡報", "day": 0, "from_period": 5, "to_period": 6},
], "periods": []}

PAIRS = [
    ("Python程式設計", "Python 程式設計"),
    ("普通化學實驗一", "普通化學與實驗（一）"),
    ("微積分一", "微積分（一）"),
    ("服務學習", "社會關懷：社區服務學習"),
    ("天文學", "通識：生活中的天文學"),
    ("音樂欣賞", "通識：音樂欣賞與創作"),
    ("線性代數", "線性代數"),
    ("英文簡報", "英文簡報"),
]


def setup() -> None:
    AGENDA_FILE.parent.mkdir(parents=True, exist_ok=True)
    AGENDA_FILE.write_text(json.dumps(AGENDA, ensure_ascii=False), encoding="utf-8")
    calc = ROOT / "微積分一"
    (calc / "raw").mkdir(parents=True)
    (calc / "notes").mkdir()
    (calc / "index.md").write_text(INDEX_MD, encoding="utf-8")
    (calc / "log.md").write_text(LOG_MD, encoding="utf-8")
    (calc / "raw" / "第一週講義.pdf").write_bytes(b"%PDF-1.4 fake")
    (calc / "notes" / "week1.md").write_text("# 筆記", encoding="utf-8")
    (calc / "raw" / ".DS_Store").write_bytes(b"x")
    phys = ROOT / "線性代數"
    phys.mkdir()
    (phys / "index.md").write_text(
        "# 線性代數\n\n## 理解進度\n\n| 主題 | 程度 | 依據 | 卡在哪 | 最後更新 |\n"
        "|---|---|---|---|---|\n", encoding="utf-8",
    )
    (ROOT / "_meta").mkdir()
    (ROOT / "_export.json").write_text("{}", encoding="utf-8")
    (ROOT / "交接稿.md").write_text("x", encoding="utf-8")   # 散在根目錄的檔不是課


def main() -> int:
    setup()

    print("名稱對照（真實的八門課）")
    for folder, agenda_name in PAIRS:
        check(f"{folder} ↔ {agenda_name}", courses_api.names_match(folder, agenda_name))
    check("音樂欣賞 不對到 天文學那門",
          not courses_api.names_match("音樂欣賞", "通識：生活中的天文學"))
    check("天文學 不對到 音樂欣賞那門",
          not courses_api.names_match("天文學", "通識：音樂欣賞與創作"))
    check("線性代數 不對到 體育", not courses_api.names_match("線性代數", "體育（二）"))
    check("單字不對照", not courses_api.names_match("一", "微積分（一）"))
    check("空字串不對照", not courses_api.names_match("", "線性代數"))

    print("md 解析")
    idx = courses_api.parse_index(INDEX_MD)
    check("標題", idx["title"] == "微積分（一）", idx["title"])
    check("老師", idx["info"].get("老師") == "陳老師")
    check("時段", idx["info"].get("時段", "").startswith("週三"))
    check("進度三列（壞程度那列略過）", len(idx["progress"]) == 3, str(idx["progress"]))
    check("進度欄位", idx["progress"][0] == {
        "topic": "極限的 ε-δ 定義", "level": "半懂", "basis": "提問推斷",
        "stuck": "為什麼要先給 ε", "updated": "09-07",
    }, str(idx["progress"][:1]))
    check("計數", idx["counts"] == {"懂": 1, "半懂": 1, "不會": 1}, str(idx["counts"]))
    titles = [s["title"] for s in idx["sections"]]
    check("其餘段落不含理解進度", "理解進度" not in titles and "評分方式" in titles, str(titles))
    grading = next(s for s in idx["sections"] if s["title"] == "評分方式")
    check("段落內文是原文", "| 期中考 | 40% |" in grading["md"])
    empty = courses_api.parse_index("")
    check("空檔不炸", empty["title"] == "" and empty["counts"] == {"懂": 0, "半懂": 0, "不會": 0})
    logs = courses_api.parse_log(LOG_MD)
    check("log 兩筆", len(logs) == 2 and logs[1]["topic"] == "連鎖律", str(logs))
    check("log 欄位", logs[0] == {
        "time": "09-07 10:02", "topic": "極限", "asked": "ε-δ 的 ε 是誰先給的",
        "verdict": "半懂，順序搞反",
    })

    print("GET /v1/courses")
    r = client.get("/v1/courses")
    check("200", r.status_code == 200, str(r.status_code))
    body = r.json()
    names = [c["name"] for c in body["courses"]]
    # 列課之前會先把課表上沒資料夾的課補上（courses.workspaces），
    # 所以不再只有手建的兩門：課表上九個課名、九個資料夾
    check("略過 _ 開頭與散檔", "_meta" not in names and "交接稿.md" not in names, str(names))
    check("課表上每個課名都有資料夾", len(names) == 9 and {"線性代數", "微積分一", "體育二"} <= set(names),
          str(names))
    calc = next(c for c in body["courses"] if c["name"] == "微積分一")
    check("conv_id 帶前綴", calc["conv_id"] == "course:微積分一")
    check("標題取自 index", calc["title"] == "微積分（一）")
    check("老師教室", calc["teacher"] == "陳老師" and calc["room"] == "A203")
    check("對到課表兩格", sorted(calc["agenda_ids"]) == ["c1", "c2"], str(calc["agenda_ids"]))
    check("計數", calc["counts"] == {"懂": 1, "半懂": 1, "不會": 1})
    check("log 數與最後時間", calc["log_count"] == 2 and calc["last_log"] == "09-07 10:15")
    phys = next(c for c in body["courses"] if c["name"] == "線性代數")
    check("空進度全零", phys["counts"] == {"懂": 0, "半懂": 0, "不會": 0} and phys["log_count"] == 0)
    check("線性代數對到 c3", phys["agenda_ids"] == ["c3"], str(phys["agenda_ids"]))

    print("GET /v1/courses/{name}")
    r = client.get("/v1/courses/微積分一")
    check("中文路徑 200", r.status_code == 200, str(r.status_code))
    d = r.json()
    check("progress 三列", len(d["progress"]) == 3)
    check("log 兩筆", len(d["log"]) == 2)
    check("index_md 原文", d["index_md"] == INDEX_MD)
    check("sections", any(s["title"] == "評分方式" for s in d["sections"]))
    paths = sorted(f["path"] for f in d["files"])
    check("檔案清單（隱藏檔不列）", paths == ["notes/week1.md", "raw/第一週講義.pdf"], str(paths))
    f0 = next(f for f in d["files"] if f["dir"] == "raw")
    check("檔案欄位", f0["name"] == "第一週講義.pdf" and f0["size"] == 13 and bool(f0["mtime"]))
    check("不存在 404", client.get("/v1/courses/量子力學").status_code == 404)
    check("_ 開頭 404", client.get("/v1/courses/_meta").status_code == 404)
    check(".. 404", client.get("/v1/courses/..").status_code in (404, 400))
    # 磁碟代號：Windows 上 root / "F:" 會變成 F 槽本身（黑名單的寫法讀得到整顆槽）
    drive = Path(tempfile.gettempdir()).drive or "C:"
    check("磁碟代號當課名 404", client.get(f"/v1/courses/{drive}").status_code == 404)
    check("磁碟代號的檔案端點 404", client.get(
        f"/v1/courses/{drive}/file", params={"path": "Windows/win.ini"},
    ).status_code == 404)
    check("磁碟代號的清單端點 404", client.get(f"/v1/courses/{drive}/files").status_code == 404)

    print("GET /v1/courses/{name}/files 與 /file")
    r = client.get("/v1/courses/微積分一/files")
    check("files 200", r.status_code == 200 and len(r.json()["files"]) == 2)
    r = client.get("/v1/courses/微積分一/file", params={"path": "raw/第一週講義.pdf"})
    check("下載 200", r.status_code == 200 and r.content == b"%PDF-1.4 fake", str(r.status_code))
    check("Content-Disposition 帶檔名", "filename" in r.headers.get("content-disposition", ""))
    check("穿越到別門課擋下", client.get(
        "/v1/courses/微積分一/file", params={"path": "../線性代數/index.md"},
    ).status_code == 404)
    check("穿越出 courses 擋下", client.get(
        "/v1/courses/微積分一/file", params={"path": "../../../../etc/passwd"},
    ).status_code == 404)
    check("目錄不是檔", client.get(
        "/v1/courses/微積分一/file", params={"path": "raw"},
    ).status_code == 404)
    check("隱藏檔擋下", client.get(
        "/v1/courses/微積分一/file", params={"path": "raw/.DS_Store"},
    ).status_code == 404)
    check("index.md 本身可下載", client.get(
        "/v1/courses/微積分一/file", params={"path": "index.md"},
    ).status_code == 200)

    print("課程資料夾整個不在")
    config.COURSES_DIR = ROOT / "nope"       # type: ignore[misc]
    r = client.get("/v1/courses")
    check("回空清單不 500", r.status_code == 200 and r.json()["courses"] == [])
    check("詳情 404", client.get("/v1/courses/微積分一").status_code == 404)
    config.COURSES_DIR = ROOT                # type: ignore[misc]

    print()
    if FAILED:
        print(f"FAILED: {len(FAILED)} → {FAILED}")
        return 1
    print("ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
