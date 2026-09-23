"""課程工作區：課表上每門課自動開資料夾、資料夾名寫回課表；換學期整批收進封存。

資料夾要人手動建的話，學期中加進課表的課就一直沒有工作區：課程清單列不出來、
課表格子點不進去，靠資料夾名找課的記錄流程也對不到它們。

**資料目錄與課程目錄都要在 import app 之前指到暫存**（理由同 test_courses_api）。
"""
from __future__ import annotations

import asyncio
import json
import os
import sys
import tempfile
from datetime import date
from pathlib import Path
from unittest.mock import patch

os.environ.setdefault("BUTLER_DATA_DIR", tempfile.mkdtemp(prefix="butler-ws-data-"))
# 課程是選配功能，沒開的話課程路由不掛、改課表也不補工作區。這支測的就是它，要打開
os.environ.setdefault("BUTLER_COURSES", "1")
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402

ROOT = Path(tempfile.mkdtemp(prefix="butler-ws-"))
config.COURSES_DIR = ROOT          # type: ignore[misc]

from fastapi.testclient import TestClient  # noqa: E402

from agenda import store  # noqa: E402
from courses import semester, workspaces  # noqa: E402
from engine import state as state_mod  # noqa: E402
from engine.state import ConvState  # noqa: E402
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


PERIODS = [
    {"no": 1, "start": "08:30", "end": "09:20"},
    {"no": 2, "start": "09:25", "end": "10:15"},
    {"no": 3, "start": "10:25", "end": "11:15"},
    {"no": 4, "start": "11:20", "end": "12:10"},
    {"no": 5, "start": "13:30", "end": "14:20"},
    {"no": 6, "start": "14:25", "end": "15:15"},
]

# 一份課表：兩門有資料夾（手建的短名）、三門沒有
AGENDA = {
    "courses": [
        {"id": "c1", "name": "微積分（一）", "day": 2, "from_period": 1, "to_period": 1,
         "teacher": "陳老師", "room": "A203", "note": "必修"},
        {"id": "c2", "name": "微積分（一）", "day": 3, "from_period": 5, "to_period": 6,
         "teacher": "陳老師", "room": "A203", "note": "必修"},
        {"id": "c3", "name": "通識：生活中的天文學", "day": 2, "from_period": 5,
         "to_period": 6, "teacher": "", "room": "", "note": ""},
        {"id": "c4", "name": "資料結構", "day": 2, "from_period": 2, "to_period": 4,
         "teacher": "王大明", "room": "C101", "note": "選修 2 學分"},
        {"id": "c5", "name": "線性代數", "day": 4, "from_period": 5, "to_period": 6,
         "teacher": "", "room": "", "note": ""},
        {"id": "c6", "name": "體育（二）", "day": 4, "from_period": 3, "to_period": 4,
         "teacher": "", "room": "體育館", "note": ""},
    ],
    "periods": PERIODS,
}


def reset() -> None:
    import shutil
    shutil.rmtree(ROOT, ignore_errors=True)
    ROOT.mkdir()
    for name in ("微積分一", "天文學"):
        (ROOT / name / "raw").mkdir(parents=True)
        (ROOT / name / "index.md").write_text(f"# {name}\n", encoding="utf-8")
    (ROOT / "_交接.md").write_text("x", encoding="utf-8")
    store.AGENDA_FILE.parent.mkdir(parents=True, exist_ok=True)
    store.AGENDA_FILE.write_text(json.dumps(AGENDA, ensure_ascii=False), encoding="utf-8")


def test_names() -> None:
    print("\n[資料夾名]")
    fn = workspaces.folder_name
    check("通識分類拿掉", fn("通識：生活中的天文學") == "生活中的天文學")
    check("括號拿掉內容留著", fn("體育（二）") == "體育二", fn("體育（二）"))
    check("半形括號與空白", fn("Python 程式設計 (一)") == "Python程式設計一")
    # 冒號另有意思（通識分類），這裡不放
    check("Windows 不收的字元", fn('A/B\\C*D?"E<F>G|H') == "ABCDEFGH", fn('A/B\\C*D?"E<F>G|H'))
    check("開頭的底線拿掉（不然會被當元資料）", fn("_秘密") == "秘密")
    check("推不出東西回空字串", fn("  ") == "")


def test_config_guard() -> None:
    print("\n[測試碰不到正式的課程資料夾]")
    real = Path.home() / "courses"
    with patch.dict(os.environ, {}, clear=False):
        os.environ.pop("BUTLER_COURSES_DIR", None)
        got = config._courses_dir()
    check("資料目錄不是正式那份 → 課程目錄落在它底下", got == config.DATA_DIR / "courses", str(got))
    check("不會是正式那份", got != real)
    with patch.dict(os.environ, {"BUTLER_COURSES_DIR": str(ROOT)}):
        check("明講就照明講的", config._courses_dir() == ROOT)


def test_sync() -> None:
    print("\n[課表上每門課都有工作區]")
    reset()
    out = workspaces.sync()
    made = sorted(out["created"])
    check("補建三門（含體育）", made == sorted(["資料結構", "線性代數", "體育二"]), str(made))
    names = [d.name for d in workspaces.course_dirs()]
    check("元資料檔不算課", "_交接.md" not in names)
    rows = {c["id"]: c.get("folder") for c in store.list_kind("courses")}
    check("微積分兩堂寫回同一個手建的資料夾", rows["c1"] == rows["c2"] == "微積分一", str(rows))
    check("通識課對到手取的短名", rows["c3"] == "天文學", str(rows))
    check("新課寫回新資料夾", rows["c4"] == "資料結構" and rows["c6"] == "體育二", str(rows))

    idx = courses_api.parse_index((ROOT / "資料結構" / "index.md").read_text(encoding="utf-8"))
    check("index 標題是課名", idx["title"] == "資料結構", idx["title"])
    check("老師教室備註", idx["info"].get("老師") == "王大明" and idx["info"].get("教室") == "C101"
          and idx["info"].get("備註") == "選修 2 學分", str(idx["info"]))
    check("時段帶節次與時間", idx["info"].get("時段") == "週三 第2-4節（09:25-12:10）",
          idx["info"].get("時段", ""))
    check("理解進度表在（空的）", idx["progress"] == [] and idx["counts"] == {"懂": 0, "半懂": 0, "不會": 0})
    log_md = (ROOT / "資料結構" / "log.md").read_text(encoding="utf-8")
    check("log 表頭是四欄", "| 時間 | 主題 | 他問了什麼 | 我的判讀 |" in log_md)
    check("raw／notes 都在", (ROOT / "體育二" / "raw").is_dir() and (ROOT / "體育二" / "notes").is_dir())
    check("手建的資料夾不被動到", (ROOT / "微積分一" / "index.md").read_text(encoding="utf-8") == "# 微積分一\n")

    again = workspaces.sync()
    check("第二次什麼都不做", again == {"created": [], "stamped": 0}, str(again))

    # 他把資料夾改了名：課表寫的那個不在了 → 重新對。還對得上就接上改過的名字，
    # 對不上才另開一個——不會卡在一個不存在的名字上
    (ROOT / "線性代數").rename(ROOT / "代數")
    third = workspaces.sync()
    row = next(c for c in store.list_kind("courses") if c["id"] == "c5")
    check("改名後還對得上：接上新名字、不另開", third["created"] == [] and row["folder"] == "代數",
          f"{third} {row.get('folder')}")
    (ROOT / "代數").rename(ROOT / "期末專案")
    fourth = workspaces.sync()
    check("對不上了：另開一個", fourth["created"] == ["線性代數"], str(fourth))

    print("\n[課程根目錄不在就什麼都不做]")
    config.COURSES_DIR = ROOT / "nope"      # type: ignore[misc]
    try:
        check("不建根目錄", workspaces.sync() == {"created": [], "stamped": 0}
              and not (ROOT / "nope").exists())
    finally:
        config.COURSES_DIR = ROOT           # type: ignore[misc]


def test_agenda_hook() -> None:
    print("\n[從 App 加課就開工作區]")
    reset()
    r = client.post("/v1/agenda/courses", json={
        "name": "統計學（一）", "day": 0, "from_period": 1, "to_period": 2, "room": "B305",
    })
    check("加課 200", r.status_code == 200, r.text[:120])
    check("資料夾馬上就在", (ROOT / "統計學一" / "index.md").is_file())
    cid = r.json()["id"]
    row = next(c for c in store.list_kind("courses") if c["id"] == cid)
    check("課表那筆寫回資料夾名", row.get("folder") == "統計學一", str(row))
    r = client.get("/v1/courses")
    names = [c["name"] for c in r.json()["courses"]]
    check("課程清單列得出每一門", {"統計學一", "資料結構", "體育二", "微積分一"} <= set(names), str(names))
    check("清單上的課對得到課表格子", next(
        c for c in r.json()["courses"] if c["name"] == "統計學一")["agenda_ids"] == [cid])


def test_labels() -> None:
    print("\n[學期代號]")
    g = semester.guess_label
    check("九月是上學期", g(date(2026, 9, 23)) == "115-1")
    check("一月還是上學期（期末）", g(date(2027, 1, 10)) == "115-1")
    check("三月是下學期", g(date(2027, 3, 1)) == "115-2")
    check("八月起算新學年", g(date(2027, 8, 1)) == "116-1")
    n = semester.next_label
    check("上 → 下", n("115-1") == "115-2")
    check("下 → 隔年上", n("115-2") == "116-1")


def test_switch() -> None:
    print("\n[換學期]")
    reset()
    workspaces.sync()
    store.set_semester("115-1")
    st = semester.status()
    check("面板：現在與下一個", st["current"] == "115-1" and st["next"] == "115-2", str(st))
    check("面板：會搬的資料夾", "體育二" in st["folders"] and len(st["folders"]) == 5, str(st["folders"]))

    for bad, why in (("115-1", "同一學期"), ("下學期", "亂寫")):
        try:
            semester.switch(bad, {})
            check(f"擋下{why}", False)
        except semester.SemesterError:
            check(f"擋下{why}", True)

    # 搬到一半失敗 → 已經搬的搬回來、課表不動
    real_rename = Path.rename

    def flaky(self: Path, target):  # noqa: ANN001
        if self.name == "天文學":
            raise PermissionError(13, "另一個程式正在使用")
        return real_rename(self, target)

    with patch.object(Path, "rename", flaky):
        try:
            semester.switch("115-2", {})
            check("搬不動就整個失敗", False)
        except semester.SemesterError as e:
            check("搬不動就整個失敗", "天文學" in str(e), str(e))
    check("已經搬走的搬回來了", len(workspaces.course_dirs()) == 5,
          str([d.name for d in workspaces.course_dirs()]))
    check("課表沒被清", len(store.list_kind("courses")) == 6)
    check("學期沒變", store.get_semester() == "115-1")

    convs = {"course:微積分一": {"session_id": "s1", "past_sessions": ["s0"]}}
    out = semester.switch("115-2", convs)
    dest = ROOT / "_封存" / "115-1"
    check("回報", out["archived"] == "115-1" and out["next"] == "115-2" and len(out["moved"]) == 5, str(out))
    check("資料夾都進封存", sorted(p.name for p in dest.iterdir() if p.is_dir()) == sorted(out["moved"]))
    check("課程清單空了", workspaces.course_dirs() == [])
    snap = json.loads((dest / "課表.json").read_text(encoding="utf-8"))
    check("課表另存一份", len(snap["courses"]) == 6 and snap["semester"] == "115-1" and snap["periods"])
    check("對話對應另存一份", json.loads((dest / "課程對話.json").read_text(encoding="utf-8")) == convs)
    check("課表清空、節次表留著", store.list_kind("courses") == [] and store.get_periods())
    check("學期換了", store.get_semester() == "115-2")
    check("元資料留在原地", (ROOT / "_交接.md").is_file())
    check("面板看得到封存過哪學期", semester.status()["archived"] == ["115-1"])

    # 下學期又有「微積分」：開新的資料夾，不會接上學期的
    store.add_course(name="微積分（二）", day=2, from_period=1)
    workspaces.sync()
    check("下學期的課開新資料夾", [d.name for d in workspaces.course_dirs()] == ["微積分二"])


def test_switch_api() -> None:
    print("\n[換學期端點：課程對話一起收掉]")
    reset()
    workspaces.sync()
    store.set_semester("115-1")
    st = state_mod.get_state("course:微積分一")
    st.session_id = "11111111-2222-3333-4444-555555555555"
    state_mod.persist(st)
    other = state_mod.get_state("c-keep")
    other.session_id = "66666666-2222-3333-4444-555555555555"
    state_mod.persist(other)

    r = client.get("/v1/courses/semester")
    check("GET 不被當成課名", r.status_code == 200 and r.json()["current"] == "115-1", r.text[:120])

    from transport import app as core
    with patch.object(core.worker, "is_running", lambda cid: cid == "course:微積分一"):
        r = client.post("/v1/courses/semester", json={"next": "115-2"})
    check("有課程對話在跑 → 409", r.status_code == 409 and "微積分一" in r.json()["detail"], r.text[:160])
    check("409 時什麼都沒搬", len(workspaces.course_dirs()) == 5)

    r = client.post("/v1/courses/semester", json={"next": "115-2"})
    check("換成功", r.status_code == 200 and r.json()["conversations"] == 1, r.text[:200])
    recs = state_mod.records_with_prefix("course:")
    check("伺服器忘掉課程對話", recs == {}, str(recs))
    check("別的對話不受影響", state_mod.get_state("c-keep").session_id is not None)
    saved = json.loads((ROOT / "_封存" / "115-1" / "課程對話.json").read_text(encoding="utf-8"))
    check("封存夾記得那條對話的 session", saved["course:微積分一"]["session_id"].startswith("1111"), str(saved))
    r = client.post("/v1/courses/semester", json={"next": "亂寫"})
    check("代號亂寫 → 400", r.status_code == 400)


def main() -> int:
    test_names()
    test_config_guard()
    test_sync()
    test_agenda_hook()
    test_labels()
    test_switch()
    test_switch_api()
    print(f"\n{'全部通過' if not FAILED else '失敗：' + ', '.join(FAILED)}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
