package dev.butlerkit.app.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * 課程頁的資料：伺服器直接讀課程資料夾（`BUTLER_COURSES_DIR`，預設 `~/courses`）底下
 * `<課>/` 給的形狀
 * （`server/transport/courses_api.py`）。清單一筆是 [CourseInfo]，點進一門課
 * 拿到的全貌是 [CourseDetail]。
 *
 * 兩件事是資料層明訂、這裡不能走鐘的：
 *  - 程度只有「懂／半懂／不會」三種（[LEVELS]），不加第四級、不換成分數。
 *  - 「依據」欄一定要顯示——絕大多數會是「提問推斷」，那是從他問了什麼推出來的，
 *    不是考出來的。不標的話他會把它當成績。
 *
 * 解析全部走 `opt*`：伺服器多給一個欄位或少給一個都不該讓整頁空白。
 */
val LEVELS = listOf("懂", "半懂", "不會")

data class CourseInfo(
    /** 資料夾名，也是對話 id 去掉 `course:` 前綴的那段。 */
    val name: String,
    val convId: String,
    /** index.md 的標題，比資料夾名完整（「微積分（一）」對「微積分一」）。 */
    val title: String,
    val teacher: String,
    val room: String,
    val slot: String,
    val note: String,
    val counts: Map<String, Int>,
    val logCount: Int,
    val lastLog: String,
    /** 這門課在課表上是哪幾格（agenda 的 course id），一週兩次就有兩個。 */
    val agendaIds: List<String>,
) {
    fun count(level: String): Int = counts[level] ?: 0

    companion object {
        /** 清單還沒拉到時用名字湊一個空殼——從通知點進來不該因為離線而進不去。 */
        fun bare(name: String) = CourseInfo(
            name = name, convId = "course:$name", title = name,
            teacher = "", room = "", slot = "", note = "",
            counts = emptyMap(), logCount = 0, lastLog = "", agendaIds = emptyList(),
        )
    }
}

data class ProgressRow(
    val topic: String,
    val level: String,
    val basis: String,
    val stuck: String,
    val updated: String,
)

data class LogRow(
    val time: String,
    val topic: String,
    val asked: String,
    val verdict: String,
)

/** index.md 裡「理解進度」以外的每一個 `##` 段落，原文照給。 */
data class MdSection(val title: String, val md: String)

data class CourseFile(
    /** 相對於課程資料夾的路徑，也是下載時要帶的那個。 */
    val path: String,
    val name: String,
    /** raw／notes／對話 之一。 */
    val dir: String,
    val size: Long,
    val mtime: String,
)

data class CourseDetail(
    val info: CourseInfo,
    /** index.md 頂上那張表：老師／教室／時段／備註……鍵就是表格第一欄。 */
    val fields: Map<String, String>,
    val progress: List<ProgressRow>,
    val sections: List<MdSection>,
    val indexMd: String,
    val log: List<LogRow>,
    val logMd: String,
    val files: List<CourseFile>,
)

data class CoursesList(val root: String, val courses: List<CourseInfo>)

/** 課程資訊表要照這個順序畫；JSON 物件的 key 順序靠不住。其餘的排最後。 */
val FIELD_ORDER = listOf("老師", "教室", "時段", "備註")

fun orderedFields(fields: Map<String, String>): List<Pair<String, String>> {
    val head = FIELD_ORDER.mapNotNull { k -> fields[k]?.let { k to it } }
    val rest = fields.keys.filter { it !in FIELD_ORDER }.sorted().map { it to fields.getValue(it) }
    return head + rest
}

private fun JSONArray?.strings(): List<String> =
    List(this?.length() ?: 0) { i -> this!!.optString(i) }

private fun <T> JSONArray?.objs(f: (JSONObject) -> T): List<T> =
    List(this?.length() ?: 0) { i -> f(this!!.getJSONObject(i)) }

private fun JSONObject?.counts(): Map<String, Int> =
    LEVELS.associateWith { this?.optInt(it) ?: 0 }

fun parseCourseInfo(o: JSONObject): CourseInfo = CourseInfo(
    name = o.optString("name"),
    convId = o.optString("conv_id"),
    title = o.optString("title").ifBlank { o.optString("name") },
    teacher = o.optString("teacher"),
    room = o.optString("room"),
    slot = o.optString("slot"),
    note = o.optString("note"),
    counts = o.optJSONObject("counts").counts(),
    logCount = o.optInt("log_count"),
    lastLog = o.optString("last_log"),
    agendaIds = o.optJSONArray("agenda_ids").strings(),
)

fun parseCoursesList(text: String): CoursesList? = runCatching {
    val o = JSONObject(text)
    CoursesList(o.optString("root"), o.optJSONArray("courses").objs(::parseCourseInfo))
}.getOrNull()

fun parseCourseDetail(text: String): CourseDetail? = runCatching {
    val o = JSONObject(text)
    val io = o.optJSONObject("info")
    val fields = io?.keys()?.asSequence()?.associateWith { io.optString(it) } ?: emptyMap()
    CourseDetail(
        info = parseCourseInfo(o),
        fields = fields,
        progress = o.optJSONArray("progress").objs {
            ProgressRow(
                it.optString("topic"), it.optString("level"), it.optString("basis"),
                it.optString("stuck"), it.optString("updated"),
            )
        },
        sections = o.optJSONArray("sections").objs { MdSection(it.optString("title"), it.optString("md")) },
        indexMd = o.optString("index_md"),
        log = o.optJSONArray("log").objs {
            LogRow(it.optString("time"), it.optString("topic"), it.optString("asked"), it.optString("verdict"))
        },
        logMd = o.optString("log_md"),
        files = o.optJSONArray("files").objs {
            CourseFile(
                it.optString("path"), it.optString("name"), it.optString("dir"),
                it.optLong("size"), it.optString("mtime"),
            )
        },
    )
}.getOrNull()

/** 換學期面板：[current] 這學期、[next] 預設的下一個、[folders] 會被收起來的課、[archived] 封存過的學期。 */
data class SemesterInfo(
    val current: String,
    val next: String,
    val folders: List<String>,
    val archived: List<String>,
)

/** 換完的結果：收了哪學期、換成哪學期、搬了哪幾門、收掉幾條課程對話、封存在哪。 */
data class SemesterResult(
    val archived: String,
    val next: String,
    val moved: List<String>,
    val conversations: Int,
    val dest: String,
)

private fun JSONObject.strings(key: String): List<String> =
    optJSONArray(key)?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()

fun parseSemesterInfo(o: JSONObject) = SemesterInfo(
    current = o.optString("current"),
    next = o.optString("next"),
    folders = o.strings("folders"),
    archived = o.strings("archived"),
)

fun parseSemesterResult(o: JSONObject) = SemesterResult(
    archived = o.optString("archived"),
    next = o.optString("next"),
    moved = o.strings("moved"),
    conversations = o.optInt("conversations"),
    dest = o.optString("dest"),
)
