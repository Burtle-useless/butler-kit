package dev.butlerkit.app.widget

import androidx.glance.appwidget.SizeMode
import androidx.glance.LocalSize
import androidx.compose.ui.unit.Dp
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
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.net.AgendaData
import dev.butlerkit.app.net.Alarm
import dev.butlerkit.app.net.CalEvent
import dev.butlerkit.app.net.parseAgenda
import java.time.LocalDate
import java.time.LocalTime

/**
 * 今天的行程 ＋ 下一個鬧鐘。
 *
 * 鬧鐘釘在最底下而不是混進清單裡排序：它是「會把你吵醒的東西」，跟行程不同類，
 * 混在一起排時間會讓人分不出哪一條真的會響。
 */
class TodayWidget : GlanceAppWidget() {

    /** 要實際高度算放得下幾列（理由同 CourseWidget）；空出來的列拿去放「接下來」。 */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = Prefs(context)
        provideContent {
            // 理由同 CourseWidget：在這一層讀就等於把值寫死進 composition
            val raw by remember { prefs.watch(Prefs.KEY_AGENDA) }
                .collectAsState(initial = prefs.agendaCache)
            val data = remember(raw) { parseAgenda(raw) ?: AgendaData() }
            Content(context, data, LocalSize.current.height)
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

/**
 * 清單最多列幾件。這層 Column 的另一格留給「還有 N 件」，剛好在
 * [GLANCE_MAX_CHILDREN] 之內；一件一格是靠 [EventRow] 把間距做進 padding 換來的。
 */
private const val MAX_ROWS = 8

/** 一件行程一列的高度（左邊那條 28dp 的色條加上下 padding），用來從 widget 高度反推放得下幾列。 */
private val EVENT_ROW_H = 35.dp

/** 外框（標題列與上下 padding）加底下那行鬧鐘吃掉的高度。 */
private val TODAY_OVERHEAD = 84.dp

/** 放得下幾列（至少兩列）。 */
internal fun todayRows(height: Dp): Int =
    ((height - TODAY_OVERHEAD) / EVENT_ROW_H).toInt().coerceIn(2, MAX_ROWS)

@Composable
private fun Content(ctx: Context, data: AgendaData, height: Dp) {
    val today = LocalDate.now().toString()          // "2026-08-20"
    val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }

    val events = data.events
        .filter { it.start.startsWith(today) }
        .sortedBy { it.start }
    val alarm = nextAlarm(data.alarms)

    val undone = events.count { !it.done && (startMin(it) ?: 0) >= nowMin }
    val head = when {
        events.isEmpty() -> ""
        undone == 0 -> "都過了"
        else -> "還有 $undone 件"
    }

    WidgetFrame(ctx, "今天", head, TAB_DAILY, SUB_CAL) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            val rows = todayRows(height)
            if (events.isEmpty()) {
                EmptyLine("今天沒安排")
            } else {
                // 這裡刻意**不用** LazyColumn。CourseWidget 已經記錄過這個實測結論
                // （見那支的 Content）：lazy 的內容是靠 RemoteViewsAdapter 另外送的，
                // 行程從有變成沒有時它不會重畫，桌面上會一直留著已經刪掉的那筆——
                // 新增看得到、刪除看不到，而且完全沒有錯誤可查。攤平成幾個 Row 就正常。
                //
                // 攤平之後換這層要守 GLANCE_MAX_CHILDREN，所以有 MAX_ROWS 上限。
                events.take(rows).forEach { EventRow(it, nowMin) }
                if (events.size > rows) {
                    Text(
                        text = "還有 ${events.size - rows} 件",
                        style = TextStyle(fontSize = 10.sp, color = W.Faint),
                        modifier = GlanceModifier.padding(top = 2.dp),
                    )
                }
            }
            // 今天的事少、底下還空著兩列以上：接著列之後幾天的。一整片空白的 widget
            // 看起來像壞掉，而「明天有什麼」正是看完今天之後接著想知道的
            val room = rows - events.size.coerceAtMost(rows) - (if (events.isEmpty()) 1 else 0)
            if (room >= 2) {
                val later = upcomingEvents(data.events, LocalDate.now(), room - 1)
                if (later.isNotEmpty()) UpcomingBlock(later)
            }
        }
        if (alarm != null) {
            Spacer(GlanceModifier.height(4.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "鬧鐘 ${alarm.second}",
                    style = TextStyle(fontSize = 11.sp, color = W.Warn),
                )
                if (alarm.first.label.isNotBlank()) {
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        text = alarm.first.label,
                        maxLines = 1,
                        style = TextStyle(fontSize = 11.sp, color = W.Faint),
                    )
                }
            }
        }
    }
}

@Composable
private fun EventRow(e: CalEvent, nowMin: Int) {
    val s = startMin(e)
    val past = e.done || (s != null && s < nowMin)
    val soon = !e.done && s != null && s >= nowMin && s - nowMin <= 60

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            // 間隔做進 padding，不在列尾插 Spacer——一插就每列吃兩格
            // （理由同 CourseWidget 的 CourseRow）
            .padding(top = 3.dp, bottom = 4.dp)
            .let { if (soon) it.background(W.Now) else it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Stripe(if (soon) W.Accent else if (past) W.Faint else W.Line)
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = if (s != null) hhmmText(s) else "—",
            style = TextStyle(
                fontSize = 11.sp,
                color = if (soon) W.Accent else if (past) W.Faint else W.Dim,
            ),
        )
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = e.title,
            maxLines = 1,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = if (soon) FontWeight.Bold else FontWeight.Normal,
                color = if (past) W.Faint else W.Text,
                // 做完的畫刪除線而不是只調灰：時間過了跟做完了是兩件事，
                // 都用灰色的話分不出「錯過了」還是「處理掉了」
                textDecoration = if (e.done) TextDecoration.LineThrough else TextDecoration.None,
            ),
        )
    }
}

/** 從 "2026-08-20T15:00" 取出當天第幾分鐘。 */
private fun startMin(e: CalEvent): Int? =
    e.start.substringAfter('T', "").takeIf { it.isNotBlank() }?.let { hhmm(it) }

/**
 * 下一個會響的鬧鐘，連同它離現在多久的文字表示。
 *
 * 這裡只算到「今天／明天／星期幾」的粒度——widget 上不需要秒級精度，
 * 真正會響的排程是 AlarmScheduler 在管的，這裡重算一份只是為了顯示。
 *
 * 時間從參數進來而不是在函式裡呼叫 now()：否則測試只能在特定時刻跑才會過。
 */
internal fun nextAlarm(
    alarms: List<Alarm>,
    nowDate: LocalDate = LocalDate.now(),
    nowTime: LocalTime = LocalTime.now(),
): Pair<Alarm, String>? {
    val now = nowTime.hour * 60 + nowTime.minute
    val todayIdx = nowDate.dayOfWeek.value - 1

    var best: Pair<Alarm, Int>? = null    // 鬧鐘與「還有幾分鐘」
    for (a in alarms) {
        if (!a.enabled) continue
        val t = hhmm(a.time) ?: continue
        val delta = when {
            a.days.isNotEmpty() -> a.days.minOfOrNull { d ->
                // 週幾的距離。今天但時間已過就要等下週的同一天
                val dayGap = ((d - todayIdx) % 7 + 7) % 7
                val raw = dayGap * 1440 + t - now
                if (raw < 0) raw + 7 * 1440 else raw
            }
            a.date != null -> runCatching {
                val d = LocalDate.parse(a.date)
                val gap = (d.toEpochDay() - nowDate.toEpochDay()).toInt()
                (gap * 1440 + t - now).takeIf { it >= 0 }
            }.getOrNull()
            // 沒指定星期也沒指定日期＝「下一個這個時間」
            else -> if (t >= now) t - now else t - now + 1440
        } ?: continue
        val b = best
        if (b == null || delta < b.second) best = a to delta
    }

    val (a, delta) = best ?: return null
    // 用「從今天 00:00 起算的總分鐘」除以一天，不是拿 delta 直接除——delta 是
    // 「還有多久」，23 小時後除出來是 0，會被寫成今天，但那明明是明天早上
    val prefix = when (val dayOff = (now + delta) / 1440) {
        0 -> ""
        1 -> "明天 "
        else -> "$dayOff 天後 "
    }
    return a to "$prefix${a.time}"
}

/** 今天之後、還沒做完的行程，由近到遠取前 [limit] 件。 */
internal fun upcomingEvents(events: List<CalEvent>, today: LocalDate, limit: Int): List<CalEvent> {
    val t = today.toString()
    return events.filter { !it.done && it.start.take(10) > t }
        .sortedBy { it.start }
        .take(limit.coerceAtLeast(0))
}

/** 「明天」「週五」「10/3」。一週內講星期幾，再遠直接給日期。 */
internal fun eventDayLabel(start: String, today: LocalDate): String {
    val d = runCatching { LocalDate.parse(start.take(10)) }.getOrNull() ?: return ""
    val gap = d.toEpochDay() - today.toEpochDay()
    return when {
        gap == 1L -> "明天"
        gap in 2..6 -> "週" + "一二三四五六日"[d.dayOfWeek.value - 1]
        else -> "${d.monthValue}/${d.dayOfMonth}"
    }
}

/** 「接下來」：之後幾天的行程，一件一行（哪天、幾點、什麼事）。 */
@Composable
private fun UpcomingBlock(list: List<CalEvent>) {
    val today = LocalDate.now()
    Column(modifier = GlanceModifier.padding(top = 8.dp, start = 6.dp)) {
        Text(
            text = "接下來",
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = W.Faint),
        )
        list.forEach { e ->
            val time = e.start.substringAfter('T', "").take(5)
            Text(
                text = "${eventDayLabel(e.start, today)} $time  ${e.title}",
                maxLines = 1,
                style = TextStyle(fontSize = 11.sp, color = W.Dim),
                modifier = GlanceModifier.padding(top = 2.dp),
            )
        }
    }
}
