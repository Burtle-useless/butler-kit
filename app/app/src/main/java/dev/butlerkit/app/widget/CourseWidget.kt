package dev.butlerkit.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.net.AgendaData
import dev.butlerkit.app.net.Course
import dev.butlerkit.app.net.Period
import dev.butlerkit.app.net.parseAgenda
import dev.butlerkit.app.ui.gridDays
import dev.butlerkit.app.ui.gridPeriods
import dev.butlerkit.app.ui.layoutDay
import dev.butlerkit.app.ui.shortName
import java.time.LocalDate
import java.time.LocalTime

/**
 * 課表。**一張 widget 兩種長相，看桌面上被拉到多大**：
 *
 *  - 三格寬以下是**今日清單**：今天的課由上往下排，正在上的那堂帶進度條，
 *    課與課之間的空堂插一列「下課中 · 下一堂 13:10」把「現在」釘在時間軸上。
 *    今天上完了就把剩下的高度拿來放明天的預覽，不留一片白。
 *  - 四格寬且夠高就變成**整週網格**：左邊節次軸、一天一欄，連續節次併成一格。
 *    今天那欄的欄首與正在上的格子是朱紅的，節次軸上「現在」那一節也是——
 *    整張紙只有黑與紅，紅的就是現在。
 *
 * 用 [SizeMode.Responsive] 而不是放兩張 widget：使用者要的是「拉大就看整週」，
 * 不是在小工具清單裡多挑一個。
 *
 * 資料來源是 [Prefs.agendaCache]——那份快取本來是為了重開機排鬧鐘而存的，
 * widget 直接沿用：**widget 沒有等網路的餘裕**（系統只給幾秒出畫面），
 * 而且沒網路時顯示上次的課表，遠比顯示一個轉圈圈有用。
 *
 * 排版邏輯（哪幾天、哪幾節、連續節次合併）跟 App 內的週課表共用
 * `ui/CourseGrid.kt` 那幾支，不另抄一份——抄了就是兩份會走鐘的課表。
 */
class CourseWidget : GlanceAppWidget() {

    /**
     * **要實際尺寸，不要門檻值。** 第一版用 `SizeMode.Responsive`，結果 [LocalSize]
     * 拿到的是門檻那組數字而不是桌面上真正的大小：週格的每節高度由高度均分，
     * 均分的永遠是 180dp，3×3 那張下半截整片空著。`Exact` 每次尺寸變了都重組一次，
     * 拉大拉小畫面都跟著填滿。
     */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = Prefs(context)
        provideContent {
            // 在 provideGlance 這一層讀快取會把值寫死進 composition，之後資料再怎麼變
            // 畫面都不動（Glance 的 session 是常駐的，不會為了更新重跑 provideGlance）。
            // 改成讓 composition 自己訂閱，寫入的那一刻它就重組
            val raw by remember { prefs.watch(Prefs.KEY_AGENDA) }
                .collectAsState(initial = prefs.agendaCache)
            val data = remember(raw) { parseAgenda(raw) ?: AgendaData() }
            val size = LocalSize.current
            if (size.width >= SIZE_WEEK.width && size.height >= SIZE_WEEK.height) {
                WeekContent(context, data, size)
            } else {
                ListContent(context, data, size)
            }
        }
    }
}

class CourseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CourseWidget()
}

/**
 * 超過這個尺寸就畫整週。實測（Pixel 7 模擬器，五欄桌面）三格寬是 265dp，
 * 四格寬約 350；四欄桌面三格約 270、四格約 360。300 把兩種桌面的
 * 「三格＝清單、四格＝整週」都切得開。第一版寫 250 是憑一格 70dp 估的，錯了。
 */
private val SIZE_WEEK = DpSize(300.dp, 180.dp)

/**
 * 「還有 25 分」或「到 12:10」。三節連上的課剩兩個多小時時寫「還有 140 分」
 * 是要人心算的；超過一小時直接講幾點下課。
 */
internal fun remainText(nowMin: Int, endMin: Int): String {
    val left = endMin - nowMin
    return if (left <= 60) "還有 $left 分" else "到 ${hhmmText(endMin)}"
}

/** [WidgetFrame] 自己吃掉的高度：上下 padding 12+12、標題列約 20、標題下的 Spacer 6。 */
private val FRAME_OVERHEAD = 50.dp

private val WEEK = listOf("一", "二", "三", "四", "五", "六", "日")

/** 一堂課攤平成畫面要的樣子。null 的起訖代表節次時間表裡查不到這節。 */
private data class Row2(
    val course: Course,
    val startMin: Int?,
    val endMin: Int?,
)

/** 下一次上課是什麼時候。dayGap 是「幾天後」，dayIdx 是那天的星期（0=週一）。 */
internal data class NextCourse(
    val course: Course,
    val dayGap: Int,
    val dayIdx: Int,
    val startMin: Int?,
)

// ── 今日清單 ────────────────────────────────────────────────────────────────

/**
 * 清單一列的高度（課名 13sp ＋ 小字 10sp ＋ 上下 padding），用來從 widget 高度
 * 反推放得下幾列。以前寫死 4 列，3×3 那張下半截整片空著；現在 2×2 給 2 列、
 * 拉高就多給，讓高度自己決定。
 */
private val LIST_ROW_H = 38.dp

/**
 * 清單最多幾列。上限 6 是 [GLANCE_MAX_CHILDREN] 算出來的：清單那層 Column 除了
 * 課之外還有「下課中」那一列與「還有 N 堂」，6＋2 剛好在 10 以內；下限 2 是
 * 最小尺寸也至少要看得到兩堂。
 */
internal fun listRows(height: Dp): Int =
    ((height - FRAME_OVERHEAD - 16.dp) / LIST_ROW_H).toInt().coerceIn(2, 6)

@Composable
private fun ListContent(ctx: Context, data: AgendaData, size: DpSize) {
    // dayOfWeek.value 是 1=週一…7=週日，伺服器用 0=週一…6=週日，差一
    val today = LocalDate.now().dayOfWeek.value - 1
    val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
    val periodStart = data.periods.associate { it.no to hhmm(it.start) }
    val periodEnd = data.periods.associate { it.no to hhmm(it.end) }

    val rows = data.courses
        .filter { it.day == today }
        .map { Row2(it, periodStart[it.fromPeriod], periodEnd[it.toPeriod]) }
        // 查不到時間的排最後：沒有時間就沒有先後，硬給 0 會讓它插到第一節前面
        .sortedWith(compareBy({ it.startMin ?: Int.MAX_VALUE }, { it.course.fromPeriod }))

    val ongoing = rows.firstOrNull {
        it.startMin != null && it.endMin != null && nowMin in it.startMin until it.endMin
    }
    val next = rows.firstOrNull { it.startMin != null && it.startMin > nowMin }
    val anyPast = rows.any { it.endMin != null && nowMin >= it.endMin }
    val head = when {
        rows.isEmpty() -> ""
        ongoing != null -> "上課中"
        next != null -> "下一堂 ${hhmmText(next.startMin!!)}"
        else -> "今天上完了"
    }
    val maxRows = listRows(size.height)

    WidgetFrame(ctx, "週${WEEK[today]} · 今日課表", head, TAB_COURSE, SUB_COURSE) {
        if (rows.isEmpty()) {
            EmptyLine(if (data.courses.isEmpty()) "還沒排課表" else "今天沒課")
        } else {
            // 清單自己包一層 Column：外層的格子數才不會被列數吃掉（見 GLANCE_MAX_CHILDREN）。
            // 這裡刻意**不用** LazyColumn：實測課從有變成沒有時它不會重畫，
            // 桌面上會留著已經刪掉的課（新增得到、刪除看不到，因為 lazy 的內容是
            // 靠 RemoteViewsAdapter 另外送的）。一天的課本來就放不滿一張 widget，
            // 直接攤平成幾個 Row 反而誠實。
            Column {
                rows.take(maxRows).forEach { r ->
                    // 「現在」插在下一堂的前面。正在上課時不插——那一列本身就是現在
                    if (ongoing == null && r === next) NowLine(r.startMin!!, anyPast)
                    CourseRow(r, nowMin, isNext = r === next)
                }
                if (rows.size > maxRows) {
                    Text(
                        text = "還有 ${rows.size - maxRows} 堂",
                        style = TextStyle(fontSize = 10.sp, color = W.Faint),
                        modifier = GlanceModifier.padding(top = 2.dp),
                    )
                }
            }
        }
        // 清單後面接明天的預覽。兩種情況會出現：今天上完了（那張畫面本來整片空白，
        // 正好是最想知道「那明天呢」的時候），或是今天課少、清單底下還空著兩列以上
        // ——使用者對第一版的抱怨就是「空白太多」，一天三堂課的 3×3 有三分之二是白的。
        // 今天還有課而且只剩一列空位時不放：擠進去只會跟今天的課混在一起。
        // 緊接在清單下方而不是釘在底部：釘底部會在中間留一段空，看起來像少了東西；
        // 由上往下排、白留在最底下，那只是紙的邊
        val room = maxRows - rows.size.coerceAtMost(maxRows)
        val done = ongoing == null && next == null
        if (done || room >= 2) {
            nextCourse(data.courses, periodStart, today)?.let {
                PreviewBlock(it, data.courses, periodStart, room.coerceIn(1, 3))
            }
        }
        Spacer(GlanceModifier.defaultWeight())
    }
}

/**
 * 「下課中 · 下一堂 13:10」。這一列就是時間軸上的「現在」——它上面的課都上完了、
 * 下面的還沒開始，一眼就看得出自己在哪。
 *
 * **不寫現在幾點。** widget 只在節次邊界重畫（見 [WidgetTick]），寫了時鐘就會
 * 停在上次重畫的那一分鐘，一個錯的時鐘比沒有時鐘糟得多。
 */
@Composable
private fun NowLine(nextStart: Int, anyPast: Boolean) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(GlanceModifier.width(3.dp).height(1.dp).background(W.Accent))
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = (if (anyPast) "下課中" else "上課前") + " · 下一堂 ${hhmmText(nextStart)}",
            maxLines = 1,
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = W.Accent),
        )
        Spacer(GlanceModifier.width(8.dp))
        Spacer(GlanceModifier.defaultWeight().height(1.dp).background(W.Accent))
    }
}

@Composable
private fun CourseRow(r: Row2, nowMin: Int, isNext: Boolean) {
    val s = r.startMin
    val e = r.endMin
    // 直接算「還剩幾分鐘」而不是先給一個 ongoing 布林：後面要用到這個數字，
    // 分兩步寫的話得在 e 已經確定非空的地方再問一次 e != null
    val remain = if (s != null && e != null && nowMin in s until e) e - nowMin else null
    val ongoing = remain != null
    val past = e != null && nowMin >= e

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            // 列與列的間隔做在 padding 裡而不是列尾插一個 Spacer：插了就是**每列吃掉
            // 兩個容器格子**，六堂課加上「現在」那列就滿了 10 格上限，
            // 「還有 N 堂」會從尾端被靜默丟掉（見 GLANCE_MAX_CHILDREN）。
            .padding(top = 3.dp, bottom = 4.dp)
            .let { if (ongoing) it.background(W.Now).cornerRadius(6.dp) else it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Stripe(if (ongoing) W.Accent else if (past) W.Faint else W.Line)
        Spacer(GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = r.course.name,
                maxLines = 1,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = if (ongoing) FontWeight.Bold else FontWeight.Normal,
                    // 上完的課壓成灰的：一眼掃過去只剩還沒上的那些是亮的
                    color = if (past) W.Faint else W.Text,
                ),
            )
            Text(
                text = subtitle(r),
                maxLines = 1,
                style = TextStyle(fontSize = 10.sp, color = if (past) W.Faint else W.Dim),
            )
            // 正在上的那堂底下一條進度：上到哪了不用讀數字，看長度就知道
            if (ongoing && s != null && e != null) {
                LinearProgressIndicator(
                    progress = ((nowMin - s).toFloat() / (e - s)).coerceIn(0f, 1f),
                    modifier = GlanceModifier.fillMaxWidth().height(3.dp).padding(top = 2.dp),
                    color = W.Accent,
                    backgroundColor = W.Line,
                )
            }
        }
        // 只有「正在上」跟「一小時內要上」才配得上右邊這格。
        // 下一堂在五小時後的話寫「300 分後」不是資訊，是噪音
        val badge = when {
            remain != null -> remainText(nowMin, e!!)
            isNext && s != null && s - nowMin <= 60 -> "${s - nowMin} 分後"
            else -> null
        }
        if (badge != null) {
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = badge,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = if (ongoing) FontWeight.Bold else FontWeight.Normal,
                    color = if (ongoing) W.Accent else W.Dim,
                ),
                modifier = GlanceModifier.padding(end = 4.dp),
            )
        }
    }
}

/** 「08:10-10:00 · A101 · 王」。教室與老師沒填就整段不出現，不留空的分隔點。 */
private fun subtitle(r: Row2): String {
    val time = when {
        r.startMin != null && r.endMin != null -> "${hhmmText(r.startMin)}-${hhmmText(r.endMin)}"
        r.startMin != null -> hhmmText(r.startMin)
        // 節次時間表沒填時退回「第 N 節」，總比一片空白讓人以為資料掉了好
        r.course.fromPeriod == r.course.toPeriod -> "第${r.course.fromPeriod}節"
        else -> "第${r.course.fromPeriod}-${r.course.toPeriod}節"
    }
    return listOf(time, r.course.room, r.course.teacher)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

/**
 * 今天上完之後的預覽：「明天」一行標題，底下列那天最早的幾堂。
 *
 * 以前只有一行「下次 明天 08:10 微積分」，畫面剩下的三分之二全是白的；
 * 那塊白正好是最想知道「明天怎麼排」的時候。
 */
@Composable
private fun PreviewBlock(n: NextCourse, courses: List<Course>, periodStart: Map<Int, Int?>, room: Int) {
    val list = previewCourses(courses, periodStart, n.dayIdx)
    Column(modifier = GlanceModifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = dayLabel(n),
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = W.Faint),
            )
            if (list.size > room) {
                Spacer(GlanceModifier.width(6.dp))
                Text(text = "${list.size} 堂", style = TextStyle(fontSize = 10.sp, color = W.Faint))
            }
        }
        list.take(room).forEach { c ->
            val t = periodStart[c.fromPeriod]?.let { hhmmText(it) } ?: "第${c.fromPeriod}節"
            Text(
                text = "$t  ${c.name}",
                maxLines = 1,
                style = TextStyle(fontSize = 11.sp, color = W.Dim),
                modifier = GlanceModifier.padding(top = 2.dp),
            )
        }
    }
}

/** 某一天的課，照時間排；查不到時間的排最後。 */
internal fun previewCourses(courses: List<Course>, periodStart: Map<Int, Int?>, dayIdx: Int): List<Course> =
    courses.filter { it.day == dayIdx }
        .sortedWith(compareBy({ periodStart[it.fromPeriod] ?: Int.MAX_VALUE }, { it.fromPeriod }))

/**
 * 今天之後最近的一堂課。找滿七天：只有週一有課的話，週一當天上完要能指回下週一。
 *
 * periodStart 查不到時間的課排在同一天的最後——沒有時間就沒有先後可言。
 */
internal fun nextCourse(
    courses: List<Course>,
    periodStart: Map<Int, Int?>,
    todayIdx: Int,
): NextCourse? {
    for (gap in 1..7) {
        val d = (todayIdx + gap) % 7
        val first = courses.filter { it.day == d }.minWithOrNull(
            compareBy({ periodStart[it.fromPeriod] ?: Int.MAX_VALUE }, { it.fromPeriod }),
        ) ?: continue
        return NextCourse(first, gap, d, periodStart[first.fromPeriod])
    }
    return null
}

/**
 * 「明天」／「週三」／「下週一」。
 *
 * 兩天以上一律講星期幾而不是「3 天後」——看課表的人腦子裡本來就是按星期在排的，
 * 「週三」不用換算，「3 天後」要。
 */
internal fun dayLabel(n: NextCourse): String = when (n.dayGap) {
    1 -> "明天"
    7 -> "下週${WEEK[n.dayIdx]}"
    else -> "週${WEEK[n.dayIdx]}"
}

/** 「明天 08:10 微積分」。 */
internal fun nextCourseText(n: NextCourse): String {
    val time = n.startMin?.let { " ${hhmmText(it)}" } ?: ""
    return "${dayLabel(n)}$time ${n.course.name}"
}

// ── 整週網格 ────────────────────────────────────────────────────────────────

/** 節次軸的寬。兩位數的節次號 9sp 放得下就好。 */
private val AXIS_W = 22.dp

/** 星期列的高。 */
private val DAY_HEAD_H = 16.dp

/** 一節的最小高度：9sp 一行字加上下 padding。再矮字就被裁掉一半。 */
private val PERIOD_MIN_H = 16.dp

/**
 * 節次軸上該標哪一節。回 (節次, 是否正在這一節裡)：
 * 下課時間標的是**下一節**但用淡一階的樣式——剛下課的人想知道的是接下來那節，
 * 不是剛結束那節。全部上完或還沒開始到第一節都回 null。
 */
internal fun axisMark(periods: List<Period>, range: IntRange, nowMin: Int): Pair<Int, Boolean>? {
    val inRange = periods.filter { it.no in range }.sortedBy { it.no }
    for (p in inRange) {
        val s = hhmm(p.start) ?: continue
        val e = hhmm(p.end) ?: continue
        if (nowMin in s until e) return p.no to true
        if (nowMin < s) return p.no to false
    }
    return null
}

@Composable
private fun WeekContent(ctx: Context, data: AgendaData, size: DpSize) {
    val today = LocalDate.now().dayOfWeek.value - 1
    val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
    val periodStart = data.periods.associate { it.no to hhmm(it.start) }
    val periodEnd = data.periods.associate { it.no to hhmm(it.end) }

    val days = gridDays(data.courses)
    val range = gridPeriods(data.courses, data.periods.maxOfOrNull { it.no } ?: 0)
    val nP = range.last - range.first + 1
    // 一節的高度由 widget 高度均分。夠高時每節可以放兩行字，最矮也保住一行
    val per = maxOf(PERIOD_MIN_H, (size.height - FRAME_OVERHEAD - DAY_HEAD_H) / nP)
    val mark = axisMark(data.periods, range, nowMin)

    val todays = data.courses.filter { it.day == today }
    val ongoing = todays.firstOrNull { c ->
        val s = periodStart[c.fromPeriod] ?: return@firstOrNull false
        val e = periodEnd[c.toPeriod] ?: return@firstOrNull false
        nowMin in s until e
    }
    val next = todays
        .mapNotNull { c -> periodStart[c.fromPeriod]?.takeIf { it > nowMin }?.let { it to c } }
        .minByOrNull { it.first }
    val head = when {
        data.courses.isEmpty() -> ""
        ongoing != null -> "上課中 · ${remainText(nowMin, periodEnd[ongoing.toPeriod]!!)}"
        next != null -> "下一堂 ${hhmmText(next.first)}"
        todays.isEmpty() -> "今天沒課"
        else -> "今天上完了"
    }

    WidgetFrame(ctx, "課表", head, TAB_COURSE, SUB_COURSE) {
        if (data.courses.isEmpty()) {
            EmptyLine("還沒排課表")
            return@WidgetFrame
        }
        // 星期列
        Row(modifier = GlanceModifier.fillMaxWidth().height(DAY_HEAD_H)) {
            Spacer(GlanceModifier.width(AXIS_W))
            days.forEach { d ->
                val isToday = d == today
                Text(
                    text = WEEK[d],
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(
                        fontSize = 10.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) W.Accent else W.Dim,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
        // 網格：一欄一個 Column，格子用固定高度堆出來（Glance 沒有 Canvas 也沒有 Grid）
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Column(modifier = GlanceModifier.width(AXIS_W)) {
                Stacked(range.map { no -> { AxisCell(no, per, mark) } })
            }
            days.forEach { d ->
                val slots = layoutDay(data.courses.filter { it.day == d }, range.first, range.last)
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Stacked(slots.map { s -> {
                        val c = s.course
                        if (c == null) {
                            Spacer(GlanceModifier.height(per * s.span))
                        } else {
                            val isToday = d == today
                            val e = periodEnd[c.toPeriod]
                            GridCell(
                                text = shortName(c.name) + if (s.clash > 0) " +${s.clash}" else "",
                                height = per * s.span,
                                lines = s.span.coerceIn(1, 3),
                                state = when {
                                    !isToday -> CellState.OTHER
                                    c === ongoing -> CellState.NOW
                                    e != null && nowMin >= e -> CellState.PAST
                                    else -> CellState.TODAY
                                },
                            )
                        }
                    } })
                }
            }
        }
    }
}

/**
 * 一層容器最多放 [GLANCE_MAX_CHILDREN] 個子元素，超過會**靜默被丟掉**。
 * 節次一天可以到 14 節，所以超過就每十個包一層 Column——巢狀的每一層各自算。
 */
@Composable
private fun Stacked(items: List<@Composable () -> Unit>) {
    if (items.size <= GLANCE_MAX_CHILDREN) {
        items.forEach { it() }
    } else {
        items.chunked(GLANCE_MAX_CHILDREN).forEach { chunk -> Column { chunk.forEach { it() } } }
    }
}

@Composable
private fun AxisCell(no: Int, height: Dp, mark: Pair<Int, Boolean>?) {
    val (color, weight) = when {
        mark?.first != no -> W.Faint to FontWeight.Normal
        mark.second -> W.Accent to FontWeight.Bold          // 正在這一節裡
        else -> W.Dim to FontWeight.Bold                    // 下課中，這是接下來那節
    }
    Box(modifier = GlanceModifier.height(height).width(AXIS_W), contentAlignment = Alignment.Center) {
        Text(
            text = "$no",
            style = TextStyle(fontSize = 9.sp, fontWeight = weight, color = color, textAlign = TextAlign.Center),
        )
    }
}

private enum class CellState { OTHER, TODAY, PAST, NOW }

/**
 * 有課的一格。外層 Box 定高度、內層 Box 帶底色與圓角，外層留 1dp 讓相鄰格子
 * 之間有縫——Glance 沒有 border，縫就是格線。
 */
@Composable
private fun GridCell(text: String, height: Dp, lines: Int, state: CellState) {
    val bg: ColorProvider
    val fg: ColorProvider
    val weight: FontWeight
    when (state) {
        CellState.OTHER -> { bg = W.CellOther; fg = W.Dim; weight = FontWeight.Normal }
        CellState.TODAY -> { bg = W.CourseNow; fg = W.Text; weight = FontWeight.Normal }
        CellState.PAST -> { bg = W.CellOther; fg = W.Faint; weight = FontWeight.Normal }
        CellState.NOW -> { bg = W.Now; fg = W.Accent; weight = FontWeight.Bold }
    }
    // 格子夠高（一節 22dp 以上，大約是 4×4 那種尺寸）字就放大一號；
    // 小尺寸維持 9sp，不然五欄擠不下
    val fontSize = if (height / lines >= 22.dp) 10.sp else 9.sp
    Box(modifier = GlanceModifier.fillMaxWidth().height(height).padding(1.dp)) {
        Box(
            modifier = GlanceModifier.fillMaxSize().background(bg).cornerRadius(4.dp)
                .padding(horizontal = 3.dp, vertical = 2.dp),
        ) {
            Text(
                text = text,
                maxLines = lines,
                style = TextStyle(fontSize = fontSize, fontWeight = weight, color = fg),
            )
        }
    }
}
