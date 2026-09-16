package dev.butlerkit.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.net.AgendaData
import dev.butlerkit.app.net.Course
import dev.butlerkit.app.net.parseAgenda
import java.time.LocalDate
import java.time.LocalTime

/**
 * 今天的課。
 *
 * 資料來源是 [Prefs.agendaCache]——那份快取本來是為了重開機排鬧鐘而存的，
 * widget 直接沿用：**widget 沒有等網路的餘裕**（系統只給幾秒出畫面），
 * 而且沒網路時顯示上次的課表，遠比顯示一個轉圈圈有用。
 *
 * 版面的兩個決定：
 *
 * 一、**時間跟教室併成課名底下那一行小字**。第一版把開始時間單獨放右邊，
 * 結果右半邊整片空著、又看不出幾點下課。擠成一行之後右邊空出來給狀態用。
 *
 * 二、**右邊只有正在上跟馬上要上的課才有字**。每列都掛個時間差會讓一整排都在喊，
 * 反而看不出哪一堂跟現在有關；留白本身就是在指路。
 */
class CourseWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = Prefs(context)
        provideContent {
            // 在 provideGlance 這一層讀快取會把值寫死進 composition，之後資料再怎麼變
            // 畫面都不動（Glance 的 session 是常駐的，不會為了更新重跑 provideGlance）。
            // 改成讓 composition 自己訂閱，寫入的那一刻它就重組
            val raw by remember { prefs.watch(Prefs.KEY_AGENDA) }
                .collectAsState(initial = prefs.agendaCache)
            val data = remember(raw) { parseAgenda(raw) ?: AgendaData() }
            Content(context, data)
        }
    }
}

class CourseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CourseWidget()
}

/**
 * 一張 widget 放得下的列數。超過的用一行「還有 N 堂」帶過，硬塞只會被裁掉半列。
 *
 * 這個數字同時受 [GLANCE_MAX_CHILDREN] 約束。外層 Column 已經固定用掉四格
 * （標題 Row、標題下的 Spacer、可壓縮的 weight Spacer、「還有 N 堂」或「下次」），
 * 所以課最多六列。取 4 是版面決定（再多字就小到看不清），不是上限本身——
 * 但改大之前要先數過那四格，不然多出來的東西會**整個不見而且不報錯**。
 */
private const val MAX_ROWS = 4

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

@Composable
private fun Content(ctx: Context, data: AgendaData) {
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
    val head = when {
        rows.isEmpty() -> ""
        ongoing != null -> "上課中"
        next != null -> "下一堂 ${hhmmText(next.startMin!!)}"
        else -> "今天上完了"
    }

    WidgetFrame(ctx, "今日課表", head, TAB_DAILY, SUB_COURSE) {
        if (rows.isEmpty()) {
            EmptyLine(if (data.courses.isEmpty()) "還沒排課表" else "今天沒課")
        } else {
            // 這裡刻意**不用** LazyColumn：實測課從有變成沒有時它不會重畫，
            // 桌面上會留著已經刪掉的課（新增得到、刪除看不到，因為 lazy 的內容是
            // 靠 RemoteViewsAdapter 另外送的）。一天的課本來就放不滿一張 widget，
            // 直接攤平成幾個 Row 反而誠實。
            rows.take(MAX_ROWS).forEach { CourseRow(it, nowMin, isNext = it === next) }
            if (rows.size > MAX_ROWS) {
                Text(
                    text = "還有 ${rows.size - MAX_ROWS} 堂",
                    style = TextStyle(fontSize = 10.sp, color = W.Faint),
                    modifier = GlanceModifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        // 今天已經沒有要上的課了，才把下一次上課釘在底下。還有課的時候這行只是雜訊，
        // 而課全上完的那張畫面本來整片空白，正好是最想知道「那明天呢」的時候
        if (ongoing == null && next == null) {
            nextCourse(data.courses, periodStart, today)?.let { NextLine(it) }
        }
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
            // 兩個容器格子**，四堂課加上標題那兩格就滿了 10 格上限，
            // 「還有 N 堂」跟底部的「下次」會從尾端被靜默丟掉（見 GLANCE_MAX_CHILDREN）。
            .padding(top = 3.dp, bottom = 4.dp)
            .let { if (ongoing) it.background(W.CourseNow) else it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 課表在 App 裡是紫的，widget 也要是紫的
        Stripe(if (ongoing) W.Course else if (past) W.Faint else W.Line)
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
        }
        // 只有「正在上」跟「一小時內要上」才配得上右邊這格。
        // 下一堂在五小時後的話寫「300 分後」不是資訊，是噪音
        val badge = when {
            remain != null -> "還有 $remain 分"
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
                    color = if (ongoing) W.Course else W.Dim,
                ),
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

@Composable
private fun NextLine(n: NextCourse) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "下次",
            style = TextStyle(fontSize = 10.sp, color = W.Faint),
        )
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = nextCourseText(n),
            maxLines = 1,
            style = TextStyle(fontSize = 11.sp, color = W.Dim),
        )
    }
}

private val WEEK = listOf("一", "二", "三", "四", "五", "六", "日")

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
 * 「明天 08:10 微積分」。
 *
 * 兩天以上一律講星期幾而不是「3 天後」——看課表的人腦子裡本來就是按星期在排的，
 * 「週三」不用換算，「3 天後」要。
 */
internal fun nextCourseText(n: NextCourse): String {
    val day = when (n.dayGap) {
        1 -> "明天"
        7 -> "下週${WEEK[n.dayIdx]}"
        else -> "週${WEEK[n.dayIdx]}"
    }
    val time = n.startMin?.let { " ${hhmmText(it)}" } ?: ""
    return "$day$time ${n.course.name}"
}
