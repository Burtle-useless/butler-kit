package dev.butlerkit.app.ui

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.butlerkit.app.data.AgendaRepo
import dev.butlerkit.app.net.Alarm
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.CalEvent
import dev.butlerkit.app.net.Course
import dev.butlerkit.app.net.LedgerEntry
import dev.butlerkit.app.net.Period
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 日常頁：行事曆、課表、鬧鐘、記帳。
 *
 * 這幾樣的資料都在電腦上，助理可以直接改（它有對應的工具）；這一頁是「你自己動手」
 * 的那一半。兩邊寫的是同一份資料，所以助理設的鬧鐘會出現在這裡，
 * 你在這裡記的帳它問起來也答得出來。
 */
private enum class Sub(val label: String) {
    Cal("行事曆"), Course("課表"), Alarms("鬧鐘"), Money("記帳")
}

@Composable
fun AgendaScreen(client: ButlerClient) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by AgendaRepo.data.collectAsState()
    val error by AgendaRepo.error.collectAsState()
    var sub by remember { mutableStateOf(Sub.Cal) }

    Column(Modifier.fillMaxSize()) {
        SubTabs(sub) { sub = it }
        error?.let {
            Text(
                it, color = Palette.Danger, fontSize = Type.Meta,
                modifier = Modifier.fillMaxWidth()
                    .background(Palette.DangerSoft)
                    .clickable { AgendaRepo.clearError() }
                    .padding(horizontal = Space.Screen, vertical = 8.dp),
            )
        }
        when (sub) {
            Sub.Cal -> CalendarPane(ctx, scope, client, data.events)
            Sub.Course -> CoursePane(ctx, scope, client, data.courses, data.periods)
            Sub.Alarms -> AlarmPane(ctx, scope, client, data.alarms)
            Sub.Money -> MoneyPane(ctx, scope, client, data.ledger, data.categories)
        }
    }
}

@Composable
private fun SubTabs(current: Sub, onPick: (Sub) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Palette.Surface)
            .padding(horizontal = Space.Screen, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sub.entries.forEach { s ->
            val on = s == current
            Text(
                s.label,
                color = if (on) Palette.Bg else Palette.TextDim,
                fontSize = Type.Meta,
                modifier = Modifier
                    .background(if (on) Palette.Accent else Palette.SurfaceHi, Radii.Chip)
                    .clickable { onPick(s) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

// ── 行事曆 ───────────────────────────────────────────────────────────────────
private val WEEK_HEAD = listOf("日", "一", "二", "三", "四", "五", "六")

/**
 * 行事曆：月曆網格 ＋ 選中那天的清單。
 *
 * 原本是一條把所有行程按時間排下來的清單，每列寫「08/20 15:00」。使用者實測回報
 * 「誰知道哪天是哪天、哪天又有幾個排程，全部擠在一起」——問題在於**清單沒有空間感**：
 * 日期是要用讀的，兩件事隔幾天要用算的，某天空著更是完全看不出來。月曆把時間攤成
 * 平面，這三件事都變成用看的。
 *
 * 格子底下的點＝那天有幾件事，這是他明講要的資訊；超過三件改寫數字，因為點畫到
 * 第四顆就數不清了。
 */
@Composable
private fun CalendarPane(
    ctx: Context, scope: CoroutineScope, client: ButlerClient, events: List<CalEvent>,
) {
    val today = remember { todayStr() }
    var month by remember { mutableStateOf(today.substring(0, 7)) }
    var picked by remember { mutableStateOf(today) }
    var title by remember { mutableStateOf("") }
    var time by remember { mutableStateOf(nextHour()) }

    val byDay = remember(events) { events.groupBy { it.start.take(10) } }
    val cells = remember(month) { monthCells(month) }
    val dayEvents = byDay[picked].orEmpty().sortedBy { it.start }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.Screen),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card {
                MonthHeader(
                    month = month,
                    showToday = month != today.substring(0, 7),
                    onPrev = { month = shiftMonth(month, -1) },
                    onNext = { month = shiftMonth(month, 1) },
                    onToday = { month = today.substring(0, 7); picked = today },
                )
                // 網格自己一個 Column：Card 的列距是給卡片內各區塊用的，
                // 套到週與週之間會把月曆拉得又高又鬆
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        WEEK_HEAD.forEach { w ->
                            Text(
                                w, color = Palette.TextFaint, fontSize = Type.Tiny,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.weight(1f).padding(bottom = 4.dp),
                            )
                        }
                    }
                    cells.chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth()) {
                            week.forEach { d ->
                                DayCell(
                                    date = d, month = month, today = today, picked = picked,
                                    evs = byDay[d].orEmpty(), modifier = Modifier.weight(1f),
                                ) { picked = d }
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                fmtDayTitle(picked, today),
                color = Palette.Text, fontSize = Type.Body, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (dayEvents.isEmpty()) {
            item {
                Text("這天沒有排程。", color = Palette.TextFaint, fontSize = Type.Meta)
            }
        }
        items(dayEvents, key = { it.id }) { e ->
            Row(
                Modifier.fillMaxWidth().background(Palette.Surface, Radii.Card)
                    .padding(Space.Inner),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 已經知道是哪一天了，這裡只需要時間
                Text(
                    e.start.substring(11),
                    color = if (e.done) Palette.TextFaint else Palette.Accent,
                    fontSize = Type.Meta, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        e.title,
                        color = if (e.done) Palette.TextFaint else Palette.Text,
                        fontSize = Type.Body,
                    )
                    if (e.note.isNotBlank()) {
                        Text(e.note, color = Palette.TextDim, fontSize = Type.Tiny)
                    }
                }
                // 打勾＝完成（保留紀錄），叉叉＝刪掉。分開兩顆是因為
                // 「做完了」跟「取消了」對之後回頭看是不同的意思
                IconBtn(if (e.done) "↺" else "✓") {
                    scope.launch {
                        AgendaRepo.patch(ctx, client, "events", e.id,
                            JSONObject().put("done", !e.done))
                    }
                }
                IconBtn("✕") {
                    scope.launch { AgendaRepo.remove(ctx, client, "events", e.id) }
                }
            }
        }

        item {
            Card {
                // 加到哪天不必再選一次——他剛剛才點過格子。日期選擇器留在這裡
                // 只會變成「兩個地方都能改日期，而且可能互相矛盾」
                Text(
                    "加到${fmtDayTitle(picked, today)}",
                    color = Palette.Text, fontSize = Type.Body,
                )
                Field(title, "要做什麼") { title = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickerChip(time, Modifier.weight(1f)) {
                        pickTime(ctx, time) { t -> time = t }
                    }
                    Box(Modifier.weight(1f))
                }
                ActionButton("排進行事曆", title.isNotBlank()) {
                    scope.launch {
                        val ok = AgendaRepo.add(ctx, client, "events", JSONObject().apply {
                            put("title", title.trim())
                            put("start", "${picked}T$time")
                        })
                        if (ok) title = ""
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(
    month: String, showToday: Boolean,
    onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit,
) = Row(
    Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(
        "‹", color = Palette.TextDim, fontSize = 22.sp,
        modifier = Modifier.clickable(onClick = onPrev).padding(horizontal = 10.dp),
    )
    Text(
        "${month.substring(0, 4)} 年 ${month.substring(5).trimStart('0')} 月",
        color = Palette.Text, fontSize = Type.Body, fontWeight = FontWeight.Medium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.weight(1f),
    )
    // 翻遠了才給回程的路：本月時這顆按鈕沒有作用，擺著只是干擾
    if (showToday) {
        Text(
            "今天", color = Palette.Accent, fontSize = Type.Tiny,
            modifier = Modifier.background(Palette.SurfaceHi, Radii.Chip)
                .clickable(onClick = onToday)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
    Text(
        "›", color = Palette.TextDim, fontSize = 22.sp,
        modifier = Modifier.clickable(onClick = onNext).padding(horizontal = 10.dp),
    )
}

@Composable
private fun DayCell(
    date: String, month: String, today: String, picked: String,
    evs: List<CalEvent>, modifier: Modifier, onPick: () -> Unit,
) {
    val on = date == picked
    val inMonth = date.startsWith(month)
    Column(
        modifier.height(46.dp).clickable(onClick = onPick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(26.dp)
                .background(if (on) Palette.Accent else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.substring(8).trimStart('0'),
                color = when {
                    on -> Palette.Bg
                    !inMonth -> Palette.TextFaint.copy(alpha = 0.45f)
                    date == today -> Palette.Accent
                    else -> Palette.Text
                },
                fontSize = Type.Meta,
                fontWeight = if (on || date == today) FontWeight.Bold else FontWeight.Normal,
            )
        }
        if (evs.isNotEmpty()) {
            // 整天的事都做完就轉灰：一眼看得出哪幾天還欠著
            val c = if (evs.any { !it.done }) Palette.Accent else Palette.TextFaint
            if (evs.size <= 3) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    repeat(evs.size) { Box(Modifier.size(4.dp).background(c, CircleShape)) }
                }
            } else {
                Text("${evs.size}", color = c, fontSize = 9.sp)
            }
        }
    }
}

// ── 課表 ─────────────────────────────────────────────────────────────────────
/**
 * 星期標籤。索引就是伺服器的 day 編號（0=週一 6=週日），課表與鬧鐘共用這一份。
 *
 * 行事曆那邊的 [WEEK_HEAD] 是週日起始，兩者刻意不一致：月曆的慣例是週日在最左邊，
 * 而「星期幾」當成編號用時 0=週一 比較順（平日就是 0 到 4）。改任何一邊之前先想清楚
 * 動的是哪一種。
 */
private val WEEK = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 課表：一排星期切換 ＋ 那天的課依節次排下來 ＋ 可改的節次時間表。
 *
 * 為什麼不是七乘十二的網格：手機寬度分成七欄之後每格只剩四十幾 dp，課名一個字都塞不
 * 進去。而課表最常被問的是「今天接下來上什麼」，那本來就是單日的事。星期列上的小點
 * 負責補回「哪幾天有課」這個一眼可見的資訊。
 *
 * 節次時間預設是常見的排法，不是他學校的——所以擺在同一頁可以改，而不是寫死在程式裡。
 */
@Composable
private fun CoursePane(
    ctx: Context, scope: CoroutineScope, client: ButlerClient,
    courses: List<Course>, periods: List<Period>,
) {
    var day by remember { mutableStateOf(todayDay()) }
    var name by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var from by remember { mutableStateOf(1) }
    var to by remember { mutableStateOf(1) }
    var editing by remember { mutableStateOf(false) }
    // 節次表的草稿。key 帶 periods：伺服器那份變了（助理改過、或另一台裝置改過）
    // 就重置，不然畫面上會留著一份對不上的舊表。
    var draft by remember(periods) { mutableStateOf(periods) }

    val byDay = remember(courses) { courses.groupBy { it.day } }
    val dayCourses = byDay[day].orEmpty().sortedBy { it.fromPeriod }
    val span = remember(periods) { periods.associateBy { it.no } }
    val maxNo = periods.maxOfOrNull { it.no } ?: 12

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.Screen),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card {
                Row(Modifier.fillMaxWidth()) {
                    WEEK.forEachIndexed { i, w ->
                        WeekCell(
                            label = w, on = i == day, today = i == todayDay(),
                            count = byDay[i].orEmpty().size,
                            modifier = Modifier.weight(1f),
                        ) { day = i }
                    }
                }
            }
        }

        item {
            Text(
                if (day == todayDay()) "今天（週${WEEK[day]}）" else "週${WEEK[day]}",
                color = Palette.Text, fontSize = Type.Body, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (dayCourses.isEmpty()) {
            item { Text("這天沒課。", color = Palette.TextFaint, fontSize = Type.Meta) }
        }
        items(dayCourses, key = { it.id }) { c ->
            Row(
                Modifier.fillMaxWidth().background(Palette.Surface, Radii.Card)
                    .padding(Space.Inner),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(58.dp)) {
                    Text(
                        periodLabel(c), color = Palette.Accent,
                        fontSize = Type.Meta, fontWeight = FontWeight.Medium,
                    )
                    // 節次換算成幾點幾分：光看「第三節」還是得心算
                    Text(
                        span[c.fromPeriod]?.start.orEmpty(),
                        color = Palette.TextFaint, fontSize = Type.Tiny,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(c.name, color = Palette.Text, fontSize = Type.Body)
                    val who = listOf(c.teacher, c.room).filter { it.isNotBlank() }
                    if (who.isNotEmpty()) {
                        Text(
                            who.joinToString("・"),
                            color = Palette.TextDim, fontSize = Type.Tiny,
                        )
                    }
                    if (c.note.isNotBlank()) {
                        Text(c.note, color = Palette.TextFaint, fontSize = Type.Tiny)
                    }
                }
                IconBtn("✕") {
                    scope.launch { AgendaRepo.remove(ctx, client, "courses", c.id) }
                }
            }
        }

        item {
            Card {
                // 加到哪一天不必再選——他剛剛才點過上面的星期。同 CalendarPane 的理由
                Text("加到週${WEEK[day]}", color = Palette.Text, fontSize = Type.Body)
                Field(name, "課程名稱") { name = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Field(teacher, "老師") { teacher = it } }
                    Box(Modifier.weight(1f)) { Field(room, "教室") { room = it } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PeriodStepper("第", from, maxNo) {
                        from = it
                        // 開始被推到結束之後，把結束一起帶著走，不要留下反向的區間
                        if (to < it) to = it
                    }
                    Text("到", color = Palette.TextDim, fontSize = Type.Tiny)
                    PeriodStepper("第", to, maxNo) { to = it.coerceAtLeast(from) }
                }
                Text(
                    periodTimeHint(span, from, to),
                    color = Palette.TextFaint, fontSize = Type.Tiny,
                )
                Field(note, "備註（可留白）") { note = it }
                ActionButton("加到課表", name.isNotBlank()) {
                    scope.launch {
                        val ok = AgendaRepo.add(ctx, client, "courses", JSONObject().apply {
                            put("name", name.trim())
                            put("day", day)
                            put("from_period", from)
                            put("to_period", to)
                            put("teacher", teacher.trim())
                            put("room", room.trim())
                            put("note", note.trim())
                        })
                        // 老師與教室不清空：同一天連著加好幾堂課時，多半是同一間教室
                        if (ok) { name = ""; note = "" }
                    }
                }
            }
        }

        item {
            Card {
                Row(
                    Modifier.fillMaxWidth().clickable { editing = !editing },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("節次時間", color = Palette.Text, fontSize = Type.Body)
                        Text(
                            "第一節 ${span[1]?.start ?: "?"} 開始，共 $maxNo 節",
                            color = Palette.TextFaint, fontSize = Type.Tiny,
                        )
                    }
                    Text(
                        if (editing) "▴" else "▾",
                        color = Palette.TextDim, fontSize = 18.sp,
                    )
                }
                if (editing) {
                    Text(
                        "每個學校不一樣，這是預設值，改成你們的。",
                        color = Palette.TextFaint, fontSize = Type.Tiny,
                    )
                    draft.forEach { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "第${p.no}節", color = Palette.TextDim,
                                fontSize = Type.Meta, modifier = Modifier.width(52.dp),
                            )
                            PickerChip(p.start, Modifier.weight(1f)) {
                                pickTime(ctx, p.start) { t ->
                                    draft = draft.map {
                                        if (it.no == p.no) it.copy(start = t) else it
                                    }
                                }
                            }
                            PickerChip(p.end, Modifier.weight(1f)) {
                                pickTime(ctx, p.end) { t ->
                                    draft = draft.map {
                                        if (it.no == p.no) it.copy(end = t) else it
                                    }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            PickerChip("加一節", Modifier.fillMaxWidth()) {
                                draft = draft + nextPeriod(draft)
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            PickerChip("刪最後一節", Modifier.fillMaxWidth()) {
                                // 至少留一節：伺服器會擋空陣列，先在這裡擋掉
                                if (draft.size > 1) draft = draft.dropLast(1)
                            }
                        }
                    }
                    ActionButton("存節次時間", draft != periods) {
                        scope.launch {
                            val body = JSONArray()
                            draft.forEach {
                                body.put(JSONObject().apply {
                                    put("no", it.no)
                                    put("start", it.start)
                                    put("end", it.end)
                                })
                            }
                            if (AgendaRepo.savePeriods(ctx, client, body)) editing = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekCell(
    label: String, on: Boolean, today: Boolean, count: Int,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Column(
        modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = when {
                on -> Palette.Bg
                today -> Palette.Accent
                else -> Palette.TextDim
            },
            fontSize = Type.Meta,
            modifier = Modifier.size(32.dp)
                .background(if (on) Palette.Accent else Palette.SurfaceHi, CircleShape)
                .padding(top = 7.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        // 那天有沒有課。點而不是數字：這一列的作用是挑日子，幾堂課點進去再看
        Box(
            Modifier.padding(top = 4.dp).size(4.dp)
                .background(
                    if (count > 0) Palette.Accent else Color.Transparent, CircleShape,
                ),
        )
    }
}

/** 節次加減。上限是節次表實際有幾節，不是寫死的數字。 */
@Composable
private fun PeriodStepper(label: String, value: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.TextDim, fontSize = Type.Tiny)
        IconBtn("−") { if (value > 1) onChange(value - 1) }
        Text(
            "$value", color = Palette.Accent, fontSize = Type.Body,
            fontWeight = FontWeight.Bold,
        )
        IconBtn("＋") { if (value < max) onChange(value + 1) }
        Text("節", color = Palette.TextDim, fontSize = Type.Tiny)
    }
}

/** "3" 或 "3-4"。 */
private fun periodLabel(c: Course): String =
    if (c.fromPeriod == c.toPeriod) "第${c.fromPeriod}節"
    else "${c.fromPeriod}-${c.toPeriod}節"

/** 選好的節次換算成幾點到幾點。節次表沒有那一節時留白而不是顯示 null。 */
private fun periodTimeHint(span: Map<Int, Period>, from: Int, to: Int): String {
    val s = span[from]?.start ?: return ""
    val e = span[to]?.end ?: return s
    return "$s – $e"
}

/**
 * 接在最後一節後面的新一節：中間空十分鐘下課，長度沿用最後一節。
 *
 * 沿用長度而不是固定五十分鐘：改過節次表的人多半整份都是同一種長度，
 * 猜錯的話他還得再點一次時間選擇器。
 */
private fun nextPeriod(rows: List<Period>): Period {
    val last = rows.maxByOrNull { it.no }
        ?: return Period(1, "08:00", "08:50")
    val len = (minsOf(last.end) - minsOf(last.start)).coerceAtLeast(10)
    val start = addMin(last.end, 10)
    return Period(last.no + 1, start, addMin(start, len))
}

private fun minsOf(hhmm: String): Int =
    hhmm.substring(0, 2).toInt() * 60 + hhmm.substring(3, 5).toInt()

/** 時刻加減分鐘。跨過午夜就停在 23:59——課表不會排到隔天，那種輸入是手滑。 */
private fun addMin(hhmm: String, min: Int): String {
    val total = minsOf(hhmm) + min
    if (total >= 24 * 60) return "23:59"
    return "${two(total / 60)}:${two(total % 60)}"
}

/** 今天是星期幾，0=週一。Calendar 是 1=週日，換算過。 */
private fun todayDay(): Int =
    (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7

// ── 鬧鐘 ─────────────────────────────────────────────────────────────────────
@Composable
private fun AlarmPane(
    ctx: Context, scope: CoroutineScope, client: ButlerClient, alarms: List<Alarm>,
) {
    var time by remember { mutableStateOf("07:00") }
    var label by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(setOf<Int>()) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.Screen),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        time, color = Palette.Accent, fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { pickTime(ctx, time) { time = it } },
                    )
                    Text(
                        "  點一下改時間", color = Palette.TextFaint, fontSize = Type.Tiny,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WEEK.forEachIndexed { i, w ->
                        val on = i in days
                        Text(
                            w,
                            color = if (on) Palette.Bg else Palette.TextDim,
                            fontSize = Type.Tiny,
                            modifier = Modifier.size(32.dp)
                                .background(
                                    if (on) Palette.Accent else Palette.SurfaceHi, CircleShape,
                                )
                                .clickable {
                                    days = if (on) days - i else days + i
                                }
                                .padding(top = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                Text(
                    if (days.isEmpty()) "一天都沒選＝只響一次" else "每週響",
                    color = Palette.TextFaint, fontSize = Type.Tiny,
                )
                Field(label, "叫你做什麼（可留白）") { label = it }
                ActionButton("設定鬧鐘", true) {
                    scope.launch {
                        val ok = AgendaRepo.add(ctx, client, "alarms", JSONObject().apply {
                            put("time", time)
                            put("label", label.trim())
                            put("days", JSONArray(days.sorted()))
                        })
                        if (ok) { label = ""; days = emptySet() }
                    }
                }
            }
        }
        if (alarms.isEmpty()) {
            item { Empty("沒有鬧鐘。") }
        }
        items(alarms, key = { it.id }) { a ->
            Row(
                Modifier.fillMaxWidth().background(Palette.Surface, Radii.Card)
                    .padding(Space.Inner),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        a.time,
                        color = if (a.enabled) Palette.Text else Palette.TextFaint,
                        fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        repeatText(a),
                        color = if (a.enabled) Palette.Accent else Palette.TextFaint,
                        fontSize = Type.Tiny,
                    )
                    if (a.label.isNotBlank()) {
                        Text(a.label, color = Palette.TextDim, fontSize = Type.Meta)
                    }
                }
                Switch(
                    checked = a.enabled,
                    onCheckedChange = { on ->
                        scope.launch {
                            AgendaRepo.patch(ctx, client, "alarms", a.id,
                                JSONObject().put("enabled", on))
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.Bg,
                        checkedTrackColor = Palette.Accent,
                    ),
                )
                IconBtn("✕") {
                    scope.launch { AgendaRepo.remove(ctx, client, "alarms", a.id) }
                }
            }
        }
    }
}

private fun repeatText(a: Alarm): String = when {
    a.days.size == 7 -> "每天"
    a.days.isNotEmpty() -> a.days.sorted().joinToString("") { WEEK[it] }
    a.date != null -> "${a.date} 響一次"
    else -> "響一次"
}

// ── 記帳 ─────────────────────────────────────────────────────────────────────
@Composable
private fun MoneyPane(
    ctx: Context, scope: CoroutineScope, client: ButlerClient,
    ledger: List<LedgerEntry>, categories: List<String>,
) {
    var amount by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("餐飲") }
    var note by remember { mutableStateOf("") }
    var income by remember { mutableStateOf(false) }

    val month = remember(ledger) { thisMonth() }
    val rows = ledger.filter { it.ts.startsWith(month) }
    val spent = rows.filter { !it.income }.sumOf { it.amount }
    val got = rows.filter { it.income }.sumOf { it.amount }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.Screen),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            // 月結直接在本地算：明細本來就整批拉下來了，為了一個加總再打一次 API
            // 只會讓數字跟清單短暫對不起來
            Card {
                Text("這個月", color = Palette.TextDim, fontSize = Type.Tiny)
                Text(
                    "支出 ${fmtMoney(spent)}",
                    color = Palette.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    "收入 ${fmtMoney(got)}　結餘 ${fmtMoney(got - spent)}",
                    color = if (got - spent >= 0) Palette.Ok else Palette.Danger,
                    fontSize = Type.Meta,
                )
            }
        }
        item {
            Card {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Field(amount, "金額", KeyboardType.Number) { s ->
                            amount = s.filter { it.isDigit() || it == '.' }
                        }
                    }
                    Text(
                        if (income) "收入" else "支出",
                        color = if (income) Palette.Ok else Palette.Text,
                        fontSize = Type.Meta,
                        modifier = Modifier
                            .background(Palette.SurfaceHi, Radii.Chip)
                            .clickable { income = !income }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
                // 分類清單由伺服器給：固定清單才加總得起來，自由輸入會讓
                // 「餐飲」「吃飯」「伙食」變成三個分類
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    categories.forEach { c ->
                        val on = c == cat
                        Text(
                            c,
                            color = if (on) Palette.Bg else Palette.TextDim,
                            fontSize = Type.Tiny,
                            modifier = Modifier
                                .background(
                                    if (on) Palette.Accent else Palette.SurfaceHi, Radii.Chip,
                                )
                                .clickable { cat = c }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
                Field(note, "買了什麼（可留白）") { note = it }
                ActionButton("記一筆", amount.toDoubleOrNull() != null) {
                    scope.launch {
                        val ok = AgendaRepo.add(ctx, client, "ledger", JSONObject().apply {
                            put("amount", amount.toDouble())
                            put("category", cat)
                            put("note", note.trim())
                            put("income", income)
                        })
                        if (ok) { amount = ""; note = "" }
                    }
                }
            }
        }
        if (ledger.isEmpty()) {
            item { Empty("還沒記過帳。跟助理說「午餐 120」它會直接記進來。") }
        }
        items(ledger, key = { it.id }) { r ->
            Row(
                Modifier.fillMaxWidth().background(Palette.Surface, Radii.Card)
                    .padding(Space.Inner),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${r.category}${if (r.note.isBlank()) "" else "・" + r.note}",
                        color = Palette.Text, fontSize = Type.Meta,
                    )
                    Text(fmtStamp(r.ts), color = Palette.TextFaint, fontSize = Type.Tiny)
                }
                Text(
                    (if (r.income) "+" else "−") + fmtMoney(r.amount),
                    color = if (r.income) Palette.Ok else Palette.Text,
                    fontSize = Type.Body, fontWeight = FontWeight.Bold,
                )
                IconBtn("✕") {
                    scope.launch { AgendaRepo.remove(ctx, client, "ledger", r.id) }
                }
            }
        }
    }
}

// ── 共用小元件 ───────────────────────────────────────────────────────────────
@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Palette.Surface, Radii.Card).padding(Space.Inner),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun Field(
    value: String,
    hint: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth()
            .background(Palette.SurfaceHi, Radii.Chip)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        textStyle = TextStyle(fontSize = Type.Meta, color = Palette.Text),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        cursorBrush = SolidColor(Palette.Accent),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(hint, color = Palette.TextFaint, fontSize = Type.Meta)
                }
                inner()
            }
        },
    )
}

@Composable
private fun PickerChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, color = Palette.Text, fontSize = Type.Meta,
        modifier = modifier
            .background(Palette.SurfaceHi, Radii.Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

@Composable
private fun ActionButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (enabled) Palette.Bg else Palette.TextFaint,
        fontSize = Type.Meta,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
            .background(if (enabled) Palette.Accent else Palette.SurfaceHi, Radii.Chip)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

@Composable
private fun IconBtn(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = Palette.TextDim, fontSize = 18.sp) }
}

@Composable
private fun Empty(text: String) {
    Column {
        HorizontalDivider(color = Palette.Line, thickness = 0.6.dp)
        // 空清單是唯一有空間讓桌寵出場的地方——正在用的頁面塞它只會擋路
        Column(
            Modifier.fillMaxWidth().height(150.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PetFace(PetMood.Idle, 54.dp)
            Text(
                text, color = Palette.TextFaint, fontSize = Type.Meta,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

// ── 格式與選擇器 ─────────────────────────────────────────────────────────────
/** "2026-08-20T15:00" → "08/20 15:00"；今年的年份不用一直看 */
private fun fmtStamp(s: String): String =
    if (s.length < 16) s else "${s.substring(5, 7)}/${s.substring(8, 10)} ${s.substring(11)}"

private fun fmtMoney(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.2f", v)

private fun two(n: Int) = if (n < 10) "0$n" else "$n"

private fun thisMonth(): String {
    val c = Calendar.getInstance()
    return "${c.get(Calendar.YEAR)}-${two(c.get(Calendar.MONTH) + 1)}"
}

private fun todayStr(): String {
    val c = Calendar.getInstance()
    return "${c.get(Calendar.YEAR)}-${two(c.get(Calendar.MONTH) + 1)}-" +
        two(c.get(Calendar.DAY_OF_MONTH))
}

/** 新行程預設排在下一個整點：多數行程是「等一下要做的事」。 */
private fun nextHour(): String {
    val c = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }
    return "${two(c.get(Calendar.HOUR_OF_DAY))}:00"
}

/** 月份加減。用 Calendar 而不是自己算，跨年那一格才不會錯。 */
private fun shiftMonth(m: String, delta: Int): String {
    val c = monthStart(m).apply { add(Calendar.MONTH, delta) }
    return "${c.get(Calendar.YEAR)}-${two(c.get(Calendar.MONTH) + 1)}"
}

private fun monthStart(m: String): Calendar = Calendar.getInstance().apply {
    clear()
    set(m.substring(0, 4).toInt(), m.substring(5, 7).toInt() - 1, 1)
}

/**
 * 攤平成整週的格子（"YYYY-MM-DD"），從當月 1 號往前補到週日、往後補到週六。
 *
 * 前後月那幾格照樣給日期而不是留白：月底月初的行程常常黏在一起，
 * 留白會讓那一週看起來斷掉。週數依當月實際需要算，不固定六列——
 * 固定的話短月份下面會多掛一整排空格。
 */
private fun monthCells(m: String): List<String> {
    val c = monthStart(m)
    val dim = c.getActualMaximum(Calendar.DAY_OF_MONTH)
    val lead = c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
    c.add(Calendar.DAY_OF_MONTH, -lead)
    val total = (lead + dim + 6) / 7 * 7
    return buildList {
        repeat(total) {
            add(
                "${c.get(Calendar.YEAR)}-${two(c.get(Calendar.MONTH) + 1)}-" +
                    two(c.get(Calendar.DAY_OF_MONTH)),
            )
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
    }
}

/** "2026-08-11" → "8月11日"；就是今天的話直接講「今天」。 */
private fun fmtDayTitle(date: String, today: String): String =
    if (date == today) "今天"
    else "${date.substring(5, 7).trimStart('0')}月${date.substring(8).trimStart('0')}日"

/** 用系統原生對話框而不是 Compose 版：使用者已經認得它，而且少寫一百行。 */
private fun pickTime(ctx: Context, current: String, onPick: (String) -> Unit) {
    val h = current.substring(0, 2).toInt()
    val mi = current.substring(3, 5).toInt()
    TimePickerDialog(ctx, { _, hh, mm ->
        onPick("${two(hh)}:${two(mm)}")
    }, h, mi, true).show()
}
