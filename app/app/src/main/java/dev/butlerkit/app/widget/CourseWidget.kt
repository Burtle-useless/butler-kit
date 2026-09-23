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
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
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
import dev.butlerkit.app.R
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
 *  - 三格寬以下是**今日清單**：今天的課由上往下排，每列固定寫教室。
 *  - 四格寬且夠高就變成**整週網格**：左邊節次軸、一天一欄，連續節次併成一格，
 *    格子裡課名底下一行教室。
 *
 * 「現在」怎麼畫（教室一定要看得到、下課時下一堂要亮起來、上課中看得出還剩多少）：
 *  - **上課中**那堂整列（格）墊淡的強調色，已經過去的那一段再疊一層深一階的色塊——
 *    清單是由左往右填，週格是由上往下填（跟時間軸同方向）。沒填到的那段就是還剩的，
 *    不用讀數字也看得出上到哪；旁邊再寫「還有 25 分」。
 *  - **下課中**（或今天還沒開始上）下一堂那列（格）整個亮起來、教室用強調色，
 *    寫「25 分後開始」——走出教室要往哪裡去，看這一列就夠了。
 *  - 上完的壓灰，還沒輪到的平常樣子。
 *
 * 用 [SizeMode.Exact] 而不是兩張 widget：拉大就看整週，不必另外挑一張。
 * 資料來源是 [Prefs.agendaCache]：widget 沒有等網路的餘裕，沒網路時顯示上次的課表
 * 遠比轉圈圈有用。排版邏輯（哪幾天、哪幾節、連續節次合併）跟 App 的週課表共用
 * `ui/CourseGrid.kt`，不另抄一份。
 *
 * 色塊要跟得上時間：[WidgetTick] 在上課中與上課前一小時每 5 分鐘重畫一次
 * （不喚醒手機，螢幕一亮就補畫），其餘時間只在節次交界重畫。
 */
class CourseWidget : GlanceAppWidget() {

    /**
     * **要實際尺寸，不要門檻值。** `SizeMode.Responsive` 下 [LocalSize] 拿到的是門檻
     * 那組數字，週格的每節高度由高度均分，均分的永遠是 180dp。`Exact` 每次尺寸變了
     * 都重組一次，拉大拉小都跟著填滿——上課中那條色塊的長度也要靠它算。
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
 * 「三格＝清單、四格＝整週」都切得開。
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

/** 「25 分後開始」；一小時以上講幾點開始，理由同 [remainText]。 */
internal fun startsInText(nowMin: Int, startMin: Int): String {
    val left = startMin - nowMin
    return if (left <= 60) "$left 分後開始" else "${hhmmText(startMin)} 開始"
}

/** 這堂課上到幾成（0..1）。還沒開始或已經結束都在範圍外，夾住。 */
internal fun progressOf(nowMin: Int, startMin: Int, endMin: Int): Float =
    if (endMin <= startMin) 0f
    else ((nowMin - startMin).toFloat() / (endMin - startMin)).coerceIn(0f, 1f)

/** [WidgetFrame] 自己吃掉的高度：上下 padding 12+12、標題列約 20、標題下的 Spacer 8。 */
private val FRAME_OVERHEAD = 52.dp

/** [WidgetFrame] 左右的 padding（14 各一邊），算清單那條色塊的全長要扣掉。 */
private val FRAME_SIDE = 28.dp

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

/** 一堂課此刻的狀態。清單與週格共用。 */
private enum class Phase { PAST, LATER, NEXT, NOW }

// ── 今日清單 ────────────────────────────────────────────────────────────────

/** 清單一列的高度（含列與列之間的 4dp）。用來從 widget 高度反推放得下幾列。 */
private val LIST_ROW_H = 48.dp
private val ROW_GAP = 4.dp

/**
 * 清單最多幾列。上限 6 是 [GLANCE_MAX_CHILDREN] 算出來的：清單那層 Column 除了
 * 課之外還有「還有 N 堂」，6＋1 在 10 以內；下限 2 是最小尺寸也至少要看得到兩堂。
 */
internal fun listRows(height: Dp): Int =
    ((height - FRAME_OVERHEAD - 8.dp) / LIST_ROW_H).toInt().coerceIn(2, 6)

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
    val next = if (ongoing != null) null
    else rows.firstOrNull { it.startMin != null && it.startMin > nowMin }
    val anyPast = rows.any { it.endMin != null && nowMin >= it.endMin }
    val head = when {
        rows.isEmpty() -> ""
        ongoing != null -> "上課中"
        next != null -> if (anyPast) "下課中" else "上課前"
        else -> "今天上完了"
    }
    val maxRows = listRows(size.height)
    // 上課中那堂一定要在畫面上：課多到放不下時，從它（或下一堂）前一堂開始列，
    // 上完很久的那幾堂讓位——人要看的是現在跟接下來
    val focus = rows.indexOfFirst { it === ongoing || it === next }
    val from = if (focus <= 0 || rows.size <= maxRows) 0
    else (focus - 1).coerceAtMost(rows.size - maxRows).coerceAtLeast(0)
    val shown = rows.drop(from).take(maxRows)
    val rowW = size.width - FRAME_SIDE

    WidgetFrame(ctx, "週${WEEK[today]} · 今日課表", head, TAB_COURSE, SUB_COURSE) {
        if (rows.isEmpty()) {
            EmptyLine(if (data.courses.isEmpty()) "還沒排課表" else "今天沒課")
        } else {
            // 清單自己包一層 Column：外層的格子數才不會被列數吃掉（見 GLANCE_MAX_CHILDREN）。
            // 刻意**不用** LazyColumn：實測課從有變成沒有時它不會重畫（lazy 的內容是
            // 靠 RemoteViewsAdapter 另外送的），桌面上會留著已經刪掉的課。
            Column {
                shown.forEach { r ->
                    val phase = when {
                        r === ongoing -> Phase.NOW
                        r === next -> Phase.NEXT
                        r.endMin != null && nowMin >= r.endMin -> Phase.PAST
                        else -> Phase.LATER
                    }
                    CourseRow(r, nowMin, phase, rowW)
                }
                val hidden = rows.size - shown.size
                if (hidden > 0) {
                    Text(
                        text = if (from > 0) "前面還有 $from 堂上完了" else "還有 $hidden 堂",
                        style = TextStyle(fontSize = 10.sp, color = W.Faint),
                        modifier = GlanceModifier.padding(top = 2.dp),
                    )
                }
            }
        }
        // 清單後面接下一個上課日的預覽。兩種情況會出現：今天上完了（那張畫面本來整片
        // 空白，正好是最想知道「那明天呢」的時候），或是今天課少、底下還空著兩列以上。
        // 緊接在清單下方而不是釘在底部：空白留在最底下就好
        val room = maxRows - shown.size
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
 * 一堂課一列：左邊起訖時間、中間課名與一行狀態、右邊教室。固定高度——上課中那條
 * 色塊要鋪滿整列，高度不固定的話 Glance 量不出它該多高。
 *
 * 色塊的長度用 dp 算（widget 寬 × 上到幾成）：Glance 的 weight 只能均分，
 * 給不出「佔 37%」這種比例，所以要 [SizeMode.Exact] 拿到真正的寬度。
 */
@Composable
private fun CourseRow(r: Row2, nowMin: Int, phase: Phase, rowW: Dp) {
    val s = r.startMin
    val e = r.endMin
    val lit = phase == Phase.NOW || phase == Phase.NEXT
    val past = phase == Phase.PAST
    val bg = when (phase) {
        Phase.NOW -> W.Now
        Phase.NEXT -> W.Next
        else -> null
    }
    // 外層帶列距、內層是那張圓角卡。列距做在外層的 padding 裡而不是列尾插 Spacer：
    // 插了就是每列吃兩格，六堂課就滿了 10 格上限（見 GLANCE_MAX_CHILDREN）
    Box(modifier = GlanceModifier.fillMaxWidth().height(LIST_ROW_H).padding(bottom = ROW_GAP)) {
        Box(
            modifier = GlanceModifier.fillMaxSize().cornerRadius(10.dp)
                .let { if (bg != null) it.background(bg) else it },
        ) {
            if (phase == Phase.NOW && s != null && e != null) {
                val fill = rowW * progressOf(nowMin, s, e)
                if (fill > 0.dp) {
                    Row(modifier = GlanceModifier.fillMaxSize()) {
                        Spacer(GlanceModifier.width(fill).fillMaxHeight().background(W.NowFill))
                    }
                }
            }
            Row(
                modifier = GlanceModifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 起訖：開始時間大一點，結束時間小一行
                Column(modifier = GlanceModifier.width(40.dp)) {
                    Text(
                        text = s?.let { hhmmText(it) } ?: "第${r.course.fromPeriod}節",
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = if (lit) FontWeight.Bold else FontWeight.Normal,
                            color = if (past) W.Faint else if (lit) W.Accent else W.Dim,
                        ),
                    )
                    if (e != null) {
                        Text(text = hhmmText(e), style = TextStyle(fontSize = 10.sp, color = W.Faint))
                    }
                }
                Column(modifier = GlanceModifier.defaultWeight().padding(start = 4.dp)) {
                    Text(
                        text = shortName(r.course),
                        maxLines = 1,
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = if (lit) FontWeight.Bold else FontWeight.Normal,
                            color = if (past) W.Faint else W.Text,
                        ),
                    )
                    Text(
                        text = statusLine(r, nowMin, phase),
                        maxLines = 1,
                        style = TextStyle(
                            fontSize = 10.sp,
                            fontWeight = if (lit) FontWeight.Bold else FontWeight.Normal,
                            color = if (lit) W.Accent else if (past) W.Faint else W.Dim,
                        ),
                    )
                }
                // 教室固定在右邊：去上課最要緊的就是這個。上課中與下一堂用強調色
                if (r.course.room.isNotBlank()) {
                    Text(
                        text = r.course.room,
                        maxLines = 1,
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = if (lit) FontWeight.Bold else FontWeight.Medium,
                            color = if (lit) W.Accent else if (past) W.Faint else W.Dim,
                            textAlign = TextAlign.End,
                        ),
                        modifier = GlanceModifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

/** 課名底下那一行：上課中講還剩多久、下一堂講多久後開始，其餘寫老師與備註。 */
private fun statusLine(r: Row2, nowMin: Int, phase: Phase): String = when {
    phase == Phase.NOW && r.endMin != null -> remainText(nowMin, r.endMin)
    phase == Phase.NEXT && r.startMin != null -> "下一堂 · ${startsInText(nowMin, r.startMin)}"
    else -> listOf(r.course.teacher, r.course.note).filter { it.isNotBlank() }.joinToString(" · ")
        .ifBlank { if (phase == Phase.PAST) "上完了" else "" }
}

/**
 * 今天上完之後的預覽：「明天」一行標題，底下列那天最早的幾堂（時間、課名、教室）。
 */
@Composable
private fun PreviewBlock(n: NextCourse, courses: List<Course>, periodStart: Map<Int, Int?>, room: Int) {
    val list = previewCourses(courses, periodStart, n.dayIdx)
    Column(modifier = GlanceModifier.padding(top = 8.dp, start = 8.dp)) {
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
            val where = c.room.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
            Text(
                text = "$t  ${shortName(c)}$where",
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
 * 「明天」／「週三」／「下週一」。兩天以上一律講星期幾而不是「3 天後」——
 * 看課表的人腦子裡本來就是按星期在排的。
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
    val next = if (ongoing != null) null else todays
        .mapNotNull { c -> periodStart[c.fromPeriod]?.takeIf { it > nowMin }?.let { it to c } }
        .minByOrNull { it.first }
    val head = when {
        data.courses.isEmpty() -> ""
        ongoing != null -> "上課中 · ${remainText(nowMin, periodEnd[ongoing.toPeriod]!!)}"
        next != null -> "下一堂 ${hhmmText(next.first)}" +
            (next.second.room.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
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
                            val cs = periodStart[c.fromPeriod]
                            val ce = periodEnd[c.toPeriod]
                            val phase = when {
                                !isToday -> null
                                c === ongoing -> Phase.NOW
                                c === next?.second -> Phase.NEXT
                                ce != null && nowMin >= ce -> Phase.PAST
                                else -> Phase.LATER
                            }
                            GridCell(
                                name = shortName(c) + if (s.clash > 0) " +${s.clash}" else "",
                                room = c.room,
                                height = per * s.span,
                                phase = phase,
                                progress = if (phase == Phase.NOW && cs != null && ce != null) {
                                    progressOf(nowMin, cs, ce)
                                } else {
                                    0f
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

/** 一行 9～10sp 的字佔的高度（含行距），用來算格子裡放得下幾行。 */
private val CELL_LINE_H = 12.dp

/**
 * 有課的一格：課名，底下一行教室（放得下的話）。外層 Box 定高度、內層 Box 帶底色與圓角，
 * 外層留 1dp 讓相鄰格子之間有縫——Glance 沒有 border，縫就是格線。
 *
 * [phase] null＝不是今天的課。上課中那格由上往下疊一塊深一階的色塊，長度＝上到幾成。
 */
@Composable
private fun GridCell(name: String, room: String, height: Dp, phase: Phase?, progress: Float) {
    val bg: ColorProvider
    val fg: ColorProvider
    val weight: FontWeight
    when (phase) {
        null -> { bg = W.CellOther; fg = W.Dim; weight = FontWeight.Normal }
        Phase.LATER -> { bg = W.CourseNow; fg = W.Text; weight = FontWeight.Normal }
        Phase.PAST -> { bg = W.CellOther; fg = W.Faint; weight = FontWeight.Normal }
        Phase.NEXT -> { bg = W.Next; fg = W.Accent; weight = FontWeight.Bold }
        Phase.NOW -> { bg = W.Now; fg = W.Accent; weight = FontWeight.Bold }
    }
    val inner = height - 2.dp
    val lines = ((inner - 4.dp) / CELL_LINE_H).toInt().coerceAtLeast(1)
    val showRoom = room.isNotBlank() && lines >= 2
    // 格子夠高（兩節以上的大尺寸）字就放大一號；小尺寸維持 9sp，不然五欄擠不下
    val fontSize = if (height >= 44.dp) 10.sp else 9.sp
    Box(modifier = GlanceModifier.fillMaxWidth().height(height).padding(1.dp)) {
        Box(modifier = GlanceModifier.fillMaxSize().background(bg).cornerRadius(5.dp)) {
            if (phase == Phase.NOW && progress > 0f) {
                Column(modifier = GlanceModifier.fillMaxSize()) {
                    Spacer(GlanceModifier.fillMaxWidth().height(inner * progress).background(W.NowFill))
                }
            }
            Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 2.dp)) {
                Text(
                    text = name,
                    maxLines = if (showRoom) lines - 1 else lines,
                    style = TextStyle(fontSize = fontSize, fontWeight = weight, color = fg),
                )
                if (showRoom) {
                    val hot = phase == Phase.NOW || phase == Phase.NEXT
                    Text(
                        text = room,
                        maxLines = 1,
                        style = TextStyle(
                            fontSize = 8.sp,
                            fontWeight = if (hot) FontWeight.Bold else FontWeight.Normal,
                            color = if (hot) W.Accent else W.Faint,
                        ),
                    )
                }
            }
        }
    }
}
