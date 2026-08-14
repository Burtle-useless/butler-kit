package dev.butlerkit.app.net

import org.json.JSONObject

/**
 * 行事曆／鬧鐘／記帳／課表的資料模型。
 *
 * 時間一律是伺服器給的裸字串（沒有時區、沒有 epoch），電腦跟手機在同一個時區，
 * 轉成 Instant 再轉回來只會在夏令時間與 UTC 之間繞出誤差。
 * 真的需要毫秒時間戳的只有排鬧鐘那一處，在 AlarmScheduler 裡就地算。
 */
data class CalEvent(
    val id: String,
    val title: String,
    /** "2026-08-20T15:00" */
    val start: String,
    val end: String?,
    val note: String,
    /** 提前幾分鐘提醒，0 = 不提醒 */
    val remindMin: Int,
    val done: Boolean,
)

data class Alarm(
    val id: String,
    /** "07:30" */
    val time: String,
    val label: String,
    /** 每週重複哪幾天，0=週一 6=週日。空的代表只響一次 */
    val days: List<Int>,
    /** 只響一次時指定的日期 "2026-08-20"，null 代表「下一個這個時間」 */
    val date: String?,
    val enabled: Boolean,
)

data class LedgerEntry(
    val id: String,
    val amount: Double,
    val category: String,
    val note: String,
    val ts: String,
    val income: Boolean,
)

/**
 * 課表上的一堂課。
 *
 * 一週上兩次的同一門課是**兩筆**（同名不同 day）。理由見 store.py 的課表區塊：
 * 每個操作都是針對「某天某節」，資料就照那個形狀存。
 */
data class Course(
    val id: String,
    val name: String,
    /** 0=週一 6=週日，跟 [Alarm.days] 同一套編號 */
    val day: Int,
    val fromPeriod: Int,
    val toPeriod: Int,
    val teacher: String,
    val room: String,
    val note: String,
)

/** 第幾節是幾點到幾點。每個學校不一樣，所以這是可編輯的資料而不是常數。 */
data class Period(
    val no: Int,
    /** "08:10" */
    val start: String,
    val end: String,
)

data class AgendaData(
    val events: List<CalEvent> = emptyList(),
    val alarms: List<Alarm> = emptyList(),
    val ledger: List<LedgerEntry> = emptyList(),
    val courses: List<Course> = emptyList(),
    val periods: List<Period> = emptyList(),
    val categories: List<String> = emptyList(),
)

data class MonthSummary(
    val month: String,
    val expense: Double,
    val income: Double,
    val net: Double,
    val count: Int,
    /** 由多到少，伺服器已排好，這裡保序 */
    val byCategory: List<Pair<String, Double>>,
)

/**
 * 從伺服器回應（或 Prefs 裡的快取）解析出資料。
 *
 * 解析獨立成函式而不是塞在 ButlerClient 裡：開機重排鬧鐘時沒有網路也沒有 client，
 * 讀的是同一份 JSON 字串的快取，兩邊必須走同一套解析。
 */
fun parseAgenda(text: String): AgendaData? = runCatching {
    val o = JSONObject(text)
    fun <T> arr(k: String, f: (JSONObject) -> T): List<T> =
        o.optJSONArray(k)?.let { a -> (0 until a.length()).map { f(a.getJSONObject(it)) } }
            ?: emptyList()
    AgendaData(
        events = arr("events") { it.toCalEvent() },
        alarms = arr("alarms") { it.toAlarm() },
        ledger = arr("ledger") { it.toLedgerEntry() },
        courses = arr("courses") { it.toCourse() },
        periods = arr("periods") { it.toPeriod() }.sortedBy { it.no },
        categories = o.optJSONArray("categories")?.let { a ->
            (0 until a.length()).map { a.getString(it) }
        } ?: emptyList(),
    )
}.getOrNull()

internal fun JSONObject.toCalEvent() = CalEvent(
    id = getString("id"),
    title = optString("title"),
    start = optString("start"),
    end = optString("end").takeIf { it.isNotBlank() && it != "null" },
    note = optString("note"),
    remindMin = optInt("remind_min", 0),
    done = optBoolean("done"),
)

internal fun JSONObject.toAlarm() = Alarm(
    id = getString("id"),
    time = optString("time"),
    label = optString("label"),
    days = optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
        ?: emptyList(),
    date = optString("date").takeIf { it.isNotBlank() && it != "null" },
    enabled = optBoolean("enabled", true),
)

internal fun JSONObject.toCourse() = Course(
    id = getString("id"),
    name = optString("name"),
    day = optInt("day", 0),
    fromPeriod = optInt("from_period", 1),
    // 舊資料或手寫 JSON 可能少了 to_period；退回 from_period 代表「只有一節」，
    // 而不是 0——那會讓這堂課在畫面上完全畫不出來
    toPeriod = optInt("to_period", optInt("from_period", 1)),
    teacher = optString("teacher"),
    room = optString("room"),
    note = optString("note"),
)

internal fun JSONObject.toPeriod() = Period(
    no = optInt("no", 0),
    start = optString("start"),
    end = optString("end"),
)

internal fun JSONObject.toLedgerEntry() = LedgerEntry(
    id = getString("id"),
    amount = optDouble("amount", 0.0),
    category = optString("category"),
    note = optString("note"),
    ts = optString("ts"),
    income = optBoolean("income"),
)
