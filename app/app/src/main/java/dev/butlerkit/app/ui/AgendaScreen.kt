package dev.butlerkit.app.ui

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay
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
 *
 * [Sub.tint] 是這個子分頁的重點色，見 [Accents]。四頁的結構其實很像（一排選擇 ＋
 * 一份清單 ＋ 一個新增表單），沒有顏色的話切過去只有那排 chip 會動，容易以為沒切成功。
 */
private enum class Sub(val label: String, val tint: Color) {
    Cal("行事曆", Accents.Cal),
    Course("課表", Accents.Course),
    Alarms("鬧鐘", Accents.Alarm),
    Money("記帳", Accents.Money),
}

/**
 * [initialSub] 是從桌面 widget 點進來時要落在哪個子分頁（值見 widget 那邊的
 * `SUB_COURSE`／`SUB_CAL`）。用字串不用 [Sub]：那是這個畫面的內部狀態，
 * 為了讓 widget 指定一個分頁而把它公開，等於把這頁的實作細節變成對外介面。
 *
 * 切過去之後一定要呼叫 [onSubConsumed] 讓呼叫端把它清成 null，否則這個值會留著——
 * 之後每次離開日常頁再回來都被強制拉回課表，使用者自己選的分頁按不住。
 */
@Composable
fun AgendaScreen(
    client: ButlerClient,
    initialSub: String? = null,
    onSubConsumed: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by AgendaRepo.data.collectAsState()
    val error by AgendaRepo.error.collectAsState()
    // rememberSaveable：切到別的分頁再回來要停在原本那一格。切分頁會把這整頁移出
    // 組合樹（見 MainActivity 的 SaveableStateProvider），普通 remember 會歸零回
    // 行事曆。enum 本身是 Serializable，autoSaver 存得動，不必自己寫 Saver。
    var sub by rememberSaveable { mutableStateOf(Sub.Cal) }

    // 認得的才切。不認得就留在預設分頁——widget 舊版送來的字串不該讓畫面變空白
    LaunchedEffect(initialSub) {
        if (initialSub == null) return@LaunchedEffect
        when (initialSub) {
            "course" -> sub = Sub.Course
            "cal" -> sub = Sub.Cal
        }
        onSubConsumed()
    }

    // 右上角這行日期四個分頁都用得上（記帳看「這個月」、鬧鐘看「星期幾」），
    // 放在標題列各分頁就不必各自再寫一次。
    // 走 rememberToday 而不是 `remember { Calendar.getInstance() }`——後者是
    // 「進這個分頁的那一刻」，跨午夜之後標題會一直停在昨天（見 rememberToday）
    val today = rememberToday()

    Column(Modifier.fillMaxSize()) {
        PageTitle("日常") {
            Text(
                "${today.substring(5, 7).trimStart('0')} 月 " +
                    "${today.substring(8, 10).trimStart('0')} 日" +
                    " 週${WEEK[todayDay()]}",
                color = Palette.TextFaint, fontSize = Type.Meta,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        SubTabs(sub) { sub = it }
        error?.let {
            Text(
                // 點下去要真的做事。原本只是把這行紅字關掉，而載入失敗時資料是空的，
                // 關掉之後畫面就是一片空白加一句「還沒有行程」——看起來像沒東西，
                // 其實是沒拿到，而且再也沒有任何地方能叫它重試。
                "$it（點一下重試）", color = Palette.Danger, fontSize = Type.Meta,
                modifier = Modifier.fillMaxWidth()
                    .background(Palette.DangerSoft)
                    .clickable {
                        AgendaRepo.clearError()
                        scope.launch { AgendaRepo.refresh(ctx, client) }
                    }
                    .padding(horizontal = Space.Screen, vertical = 8.dp),
            )
        }
        when (sub) {
            Sub.Cal -> CalendarPane(ctx, scope, client, data.events, today)
            Sub.Course -> CoursePane(ctx, scope, client, data.courses, data.periods, today)
            Sub.Alarms -> AlarmPane(ctx, scope, client, data.alarms)
            Sub.Money -> MoneyPane(ctx, scope, client, data.ledger, data.categories)
        }
    }
}

/**
 * 子分頁：膠囊。選中的用該頁的 [Sub.tint] 實色，沒選中的只有一圈邊框——
 * 舊版沒選中的也有實心底，四顆一起亮著看不出哪顆是現在這頁。
 */
@Composable
private fun SubTabs(current: Sub, onPick: (Sub) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.Screen, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sub.entries.forEach { s ->
            val on = s == current
            Text(
                s.label,
                color = if (on) Palette.Bg else Palette.TextDim,
                fontSize = Type.Body,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f)
                    .background(if (on) s.tint else Color.Transparent, Radii.Chip)
                    .border(
                        if (on) 0.dp else 1.dp,
                        if (on) Color.Transparent else Palette.Line,
                        Radii.Chip,
                    )
                    .clickable { onPick(s) }
                    .padding(vertical = 9.dp),
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
    today: String,
) {
    // 這三個都用 rememberSaveable：翻到下個月、點好某一天之後切去別的分頁，
    // 回來要停在原處。普通 remember 會整組歸零回本月與今天（見 MainActivity）。
    var month by rememberSaveable { mutableStateOf(today.substring(0, 7)) }
    var picked by rememberSaveable { mutableStateOf(today) }
    // 使用者自己點過日子沒有？沒點過就跟著「今天」走，跨午夜時一起翻頁。
    // 點過就別動他選的——他可能正在看下週的安排，日期在手裡自己跳掉更糟。
    // 這個也要一起存，否則切回來會忘記他點過，下一次跨午夜就把他選的日子蓋掉。
    var pickedByHand by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(today) {
        if (!pickedByHand) {
            picked = today
            month = today.substring(0, 7)
        }
    }
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
            // 月曆卡用比較窄的內縮：它自己已經是一個七欄網格，再加 16dp 會把整塊
            // 撐到快半個螢幕，而下面的清單才是真正要看的東西
            Card(pad = 10.dp) {
                MonthHeader(
                    month = month,
                    showToday = month != today.substring(0, 7),
                    onPrev = { month = shiftMonth(month, -1) },
                    onNext = { month = shiftMonth(month, 1) },
                    // 按「今天」等於交回自動跟隨：之後跨午夜會自己翻到新的一天
                    onToday = {
                        month = today.substring(0, 7); picked = today; pickedByHand = false
                    },
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
                                ) { picked = d; pickedByHand = true }
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionHead(
                fmtDayTitle(picked, today),
                hint = if (dayEvents.isEmpty()) null else "${dayEvents.size} 件",
                tint = Accents.Cal,
            )
        }
        if (dayEvents.isEmpty()) {
            item {
                Text(
                    "這天沒有排程。",
                    color = Palette.TextFaint, fontSize = Type.Body,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
        items(dayEvents, key = { it.id }) { e ->
            EventRow(
                time = e.start.substring(11), title = e.title, note = e.note,
                done = e.done, tint = Accents.Cal,
                onToggle = {
                    scope.launch {
                        AgendaRepo.patch(ctx, client, "events", e.id,
                            JSONObject().put("done", !e.done))
                    }
                },
                onRemove = {
                    scope.launch { AgendaRepo.remove(ctx, client, "events", e.id) }
                },
            )
        }

        item {
            // 表單預設收起來。舊版整份攤在頁面上，等於每次滑到底都要跳過一個
            // 三欄的輸入區才看得到別的東西——而新增行程一天做不到一次
            AddPanel(
                label = "加到${fmtDayTitle(picked, today)}",
                tint = Accents.Cal,
                canSubmit = title.isNotBlank(),
                submitText = "排進行事曆",
                onSubmit = {
                    scope.launch {
                        val ok = AgendaRepo.add(ctx, client, "events", JSONObject().apply {
                            put("title", title.trim())
                            put("start", "${picked}T$time")
                        })
                        if (ok) title = ""
                    }
                },
            ) {
                // 加到哪天不必再選一次——他剛剛才點過格子。日期選擇器留在這裡
                // 只會變成「兩個地方都能改日期，而且可能互相矛盾」
                Field(title, "要做什麼") { title = it }
                PickerChip(time, Modifier.fillMaxWidth()) {
                    pickTime(ctx, time) { t -> time = t }
                }
            }
        }
    }
}

/** 行事曆的一列。已經知道是哪一天了，前導欄只需要時間。 */
@Composable
private fun EventRow(
    time: String, title: String, note: String, done: Boolean, tint: Color,
    onToggle: () -> Unit, onRemove: () -> Unit,
) {
    StripeRow(
        tint = tint,
        dimmed = done,
        leadWidth = 70.dp,
        lead = {
            Text(
                time,
                color = if (done) Palette.TextFaint else tint,
                fontSize = Type.Title, fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        },
        actions = {
            // 打勾＝完成（保留紀錄），叉叉＝刪掉。分開兩顆是因為
            // 「做完了」跟「取消了」對之後回頭看是不同的意思
            if (done) {
                IconBtn(Icons.Filled.Undo, "改回未完成", onClick = onToggle)
            } else {
                IconBtn(Icons.Filled.Check, "標記完成", tint = tint, onClick = onToggle)
            }
            IconBtn(Icons.Filled.Close, "刪掉這件事", onClick = onRemove)
        },
    ) {
        Text(
            title,
            color = if (done) Palette.TextFaint else Palette.Text,
            fontSize = Type.Body,
            fontWeight = FontWeight.Medium,
        )
        if (note.isNotBlank()) {
            Text(
                note, color = Palette.TextDim, fontSize = Type.Meta,
                modifier = Modifier.padding(top = 2.dp),
            )
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
    IconBtn(Icons.Filled.ChevronLeft, "上個月", onClick = onPrev)
    Text(
        "${month.substring(0, 4)} 年 ${month.substring(5).trimStart('0')} 月",
        color = Palette.Text, fontSize = Type.Title, fontWeight = FontWeight.Bold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.weight(1f),
    )
    // 翻遠了才給回程的路：本月時這顆按鈕沒有作用，擺著只是干擾
    if (showToday) {
        Text(
            "今天", color = Palette.Accent, fontSize = Type.Tiny,
            // 膠囊本身只有 25dp 高，不改外觀但把可點範圍撐到 48dp
            modifier = Modifier.minimumInteractiveComponentSize()
                .clip(Radii.Chip)
                .background(Palette.SurfaceHi)
                .clickable(onClick = onToday)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
    IconBtn(Icons.Filled.ChevronRight, "下個月", onClick = onNext)
}

@Composable
private fun DayCell(
    date: String, month: String, today: String, picked: String,
    evs: List<CalEvent>, modifier: Modifier, onPick: () -> Unit,
) {
    val on = date == picked
    val inMonth = date.startsWith(month)
    // 高度要放得下「圓圈 ＋ 底下的點或數字」。壓太扁的話數字那行會被裁掉一半，
    // 看起來像格子上沾到雜訊
    Column(
        // 字級放大時格子要能長高，否則日期數字會被裁掉半截
        modifier.heightIn(min = 42.dp).clickable(onClick = onPick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(25.dp)
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
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.padding(top = 3.dp),
                ) {
                    repeat(evs.size) { Box(Modifier.size(5.dp).background(c, CircleShape)) }
                }
            } else {
                // lineHeight 一定要壓掉：10sp 的中文預設行高會撐到 14dp 以上，
                // 剛好超出格子剩下的空間，數字就被裁成一個看不懂的小尖角
                Text(
                    "${evs.size}", color = c, fontSize = 11.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 2.dp),
                )
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
    courses: List<Course>, periods: List<Period>, today: String,
) {
    var day by remember { mutableStateOf(todayDay()) }
    // 同 CalendarPane：沒手動選過星期就跟著今天走。停在課表過夜的話，
    // 「加到週X」會照著昨天那一天寫，跟行事曆寫進昨天是同一個毛病。
    var dayByHand by remember { mutableStateOf(false) }
    LaunchedEffect(today) { if (!dayByHand) day = todayDay() }
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
            Card(pad = 10.dp) {
                Row(Modifier.fillMaxWidth()) {
                    WEEK.forEachIndexed { i, w ->
                        WeekCell(
                            label = w, on = i == day, today = i == todayDay(),
                            count = byDay[i].orEmpty().size, tint = Accents.Course,
                            modifier = Modifier.weight(1f),
                        ) { day = i; dayByHand = true }
                    }
                }
            }
        }

        item {
            SectionHead(
                if (day == todayDay()) "今天（週${WEEK[day]}）" else "週${WEEK[day]}",
                hint = if (dayCourses.isEmpty()) null else "${dayCourses.size} 堂",
                tint = Accents.Course,
            )
        }

        if (dayCourses.isEmpty()) {
            item {
                Text(
                    "這天沒課。", color = Palette.TextFaint, fontSize = Type.Body,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
        items(dayCourses, key = { it.id }) { c ->
            StripeRow(
                tint = Accents.Course,
                lead = {
                    Text(
                        periodLabel(c), color = Accents.Course,
                        fontSize = Type.Body, fontWeight = FontWeight.Bold,
                    )
                    // 節次換算成幾點幾分：光看「第三節」還是得心算
                    Text(
                        span[c.fromPeriod]?.start.orEmpty(),
                        color = Palette.TextFaint, fontSize = Type.Tiny,
                    )
                },
                actions = {
                    IconBtn(Icons.Filled.Close, "刪掉這堂課") {
                        scope.launch { AgendaRepo.remove(ctx, client, "courses", c.id) }
                    }
                },
            ) {
                Text(
                    c.name, color = Palette.Text, fontSize = Type.Body,
                    fontWeight = FontWeight.Medium,
                )
                val who = listOf(c.teacher, c.room).filter { it.isNotBlank() }
                if (who.isNotEmpty()) {
                    Text(
                        who.joinToString("・"),
                        color = Palette.TextDim, fontSize = Type.Meta,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (c.note.isNotBlank()) {
                    Text(c.note, color = Palette.TextFaint, fontSize = Type.Meta)
                }
            }
        }

        item {
            // 加到哪一天不必再選——他剛剛才點過上面的星期。同 CalendarPane 的理由
            AddPanel(
                label = "加到週${WEEK[day]}",
                tint = Accents.Course,
                canSubmit = name.isNotBlank(),
                submitText = "加到課表",
                onSubmit = {
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
                },
            ) {
                Field(name, "課程名稱") { name = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Field(teacher, "老師") { teacher = it } }
                    Box(Modifier.weight(1f)) { Field(room, "教室") { room = it } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PeriodStepper("第", from, maxNo, Accents.Course) {
                        from = it
                        // 開始被推到結束之後，把結束一起帶著走，不要留下反向的區間
                        if (to < it) to = it
                    }
                    Text("到", color = Palette.TextDim, fontSize = Type.Meta)
                    PeriodStepper("第", to, maxNo, Accents.Course) {
                        to = it.coerceAtLeast(from)
                    }
                }
                Text(
                    periodTimeHint(span, from, to),
                    color = Palette.TextFaint, fontSize = Type.Meta,
                )
                Field(note, "備註（可留白）") { note = it }
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
                        "每個地方不一樣，這是預設值，改成你們的。",
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
                    ActionButton("存節次時間", draft != periods, Accents.Course) {
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
    label: String, on: Boolean, today: Boolean, count: Int, tint: Color,
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
                today -> tint
                else -> Palette.TextDim
            },
            fontSize = Type.Body,
            fontWeight = if (on || today) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.size(34.dp)
                .background(if (on) tint else Palette.SurfaceHi, CircleShape)
                .padding(top = 7.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        // 那天有沒有課。點而不是數字：這一列的作用是挑日子，幾堂課點進去再看
        Box(
            Modifier.padding(top = 5.dp).size(5.dp)
                .background(if (count > 0) tint else Color.Transparent, CircleShape),
        )
    }
}

/** 節次加減。上限是節次表實際有幾節，不是寫死的數字。 */
@Composable
private fun PeriodStepper(
    label: String, value: Int, max: Int, tint: Color, onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.TextDim, fontSize = Type.Meta)
        IconBtn(Icons.Filled.Remove, "少一節") { if (value > 1) onChange(value - 1) }
        Text(
            "$value", color = tint, fontSize = Type.Title,
            fontWeight = FontWeight.Bold,
        )
        IconBtn(Icons.Filled.Add, "多一節") { if (value < max) onChange(value + 1) }
        Text("節", color = Palette.TextDim, fontSize = Type.Meta)
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

/**
 * "08:10" → 490（當天第幾分鐘）。解不出來回 null。
 *
 * **不能用 `substring(0, 2).toInt()`**，那假設一定是零填充的 `HH:mm`，而這兩件事都不成立：
 * [Period] 的起訖預設是**空字串**（`net/Agenda.kt`），節次表又是使用者手打的，
 * `8:10` 這種寫法很常見。硬切的下場是 `StringIndexOutOfBoundsException` 或
 * `NumberFormatException`——不是顯示錯，是整個 App 當場閃退。
 * widget 那邊的 `hhmm()` 早就有這道防呆，App 這邊一直漏掉。
 */
private fun minsOrNull(t: String): Int? {
    val p = t.trim().split(":")
    if (p.size != 2) return null
    val h = p[0].toIntOrNull() ?: return null
    val m = p[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

/** 同 [minsOrNull]，解不出來當 00:00。用在「算出來還是要給個數字」的地方。 */
private fun minsOf(hhmm: String): Int = minsOrNull(hhmm) ?: 0

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
            AddPanel(
                label = "設一個鬧鐘",
                tint = Accents.Alarm,
                canSubmit = true,
                submitText = "設定鬧鐘",
                onSubmit = {
                    scope.launch {
                        val ok = AgendaRepo.add(ctx, client, "alarms", JSONObject().apply {
                            put("time", time)
                            put("label", label.trim())
                            put("days", JSONArray(days.sorted()))
                        })
                        if (ok) { label = ""; days = emptySet() }
                    }
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        time, color = Accents.Alarm, fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { pickTime(ctx, time) { time = it } },
                    )
                    Text(
                        "  點一下改時間", color = Palette.TextFaint, fontSize = Type.Meta,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WEEK.forEachIndexed { i, w ->
                        val on = i in days
                        Text(
                            w,
                            color = if (on) Palette.Bg else Palette.TextDim,
                            fontSize = Type.Meta,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.size(34.dp)
                                .background(
                                    if (on) Accents.Alarm else Palette.SurfaceHi, CircleShape,
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
                    color = Palette.TextFaint, fontSize = Type.Meta,
                )
                Field(label, "叫你做什麼（可留白）") { label = it }
            }
        }
        item {
            SectionHead(
                "鬧鐘",
                hint = alarms.count { it.enabled }.takeIf { it > 0 }?.let { "$it 個開著" },
                tint = Accents.Alarm,
            )
        }
        if (alarms.isEmpty()) {
            item { Empty("沒有鬧鐘。") }
        }
        items(alarms, key = { it.id }) { a ->
            StripeRow(
                tint = Accents.Alarm,
                dimmed = !a.enabled,
                // 26sp 的「07:20」實寬約 80dp，加上前導欄的左右內縮要 102dp。
                // 給不夠寬不會報錯，而是把時間折成「07:2 / 0」兩行
                leadWidth = 102.dp,
                lead = {
                    Text(
                        a.time,
                        color = if (a.enabled) Palette.Text else Palette.TextFaint,
                        fontSize = 26.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                },
                actions = {
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
                            checkedTrackColor = Accents.Alarm,
                        ),
                    )
                    IconBtn(Icons.Filled.Close, "刪掉這個鬧鐘") {
                        scope.launch { AgendaRepo.remove(ctx, client, "alarms", a.id) }
                    }
                },
            ) {
                Text(
                    repeatText(a),
                    color = if (a.enabled) Accents.Alarm else Palette.TextFaint,
                    fontSize = Type.Meta, fontWeight = FontWeight.Medium,
                )
                if (a.label.isNotBlank()) {
                    Text(
                        a.label, color = Palette.TextDim, fontSize = Type.Body,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

private fun repeatText(a: Alarm): String = when {
    a.days.size == 7 -> "每天"
    // getOrNull 而不是 WEEK[it]：這裡是**唯一**用外部資料當索引的地方，
    // 伺服器的 _need_days 雖然擋掉 0..6 以外的值，手改過 agenda.json 就繞得過。
    // 一個 7（ISO 慣例的週日）會讓整個鬧鐘分頁畫不出來——為了一筆髒資料
    // 讓所有鬧鐘都消失，代價完全不成比例。
    a.days.isNotEmpty() -> a.days.sorted().joinToString("") { WEEK.getOrNull(it) ?: "?" }
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
                Text("這個月花了", color = Palette.TextDim, fontSize = Type.Meta)
                Text(
                    fmtMoney(spent),
                    color = Palette.Text, fontSize = Type.Metric,
                    fontWeight = FontWeight.Bold,
                )
                Row {
                    // 收入與結餘拆成兩塊：擠在一行時中間那個全形空白撐不出分界，
                    // 兩個數字會讀成一個
                    Text(
                        "收入 ${fmtMoney(got)}",
                        color = Palette.TextDim, fontSize = Type.Meta,
                        modifier = Modifier.padding(end = 14.dp),
                    )
                    Text(
                        "結餘 ${fmtMoney(got - spent)}",
                        color = if (got - spent >= 0) Accents.Money else Palette.Danger,
                        fontSize = Type.Meta, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        item {
            AddPanel(
                label = "記一筆",
                tint = Accents.Money,
                canSubmit = amount.toDoubleOrNull() != null,
                submitText = "記一筆",
                onSubmit = {
                    scope.launch {
                        val ok = AgendaRepo.add(ctx, client, "ledger", JSONObject().apply {
                            put("amount", amount.toDouble())
                            put("category", cat)
                            put("note", note.trim())
                            put("income", income)
                        })
                        if (ok) { amount = ""; note = "" }
                    }
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Field(amount, "金額", KeyboardType.Number) { s ->
                            amount = s.filter { it.isDigit() || it == '.' }
                        }
                    }
                    Text(
                        if (income) "收入" else "支出",
                        color = if (income) Palette.Bg else Palette.Text,
                        fontSize = Type.Body,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .background(
                                if (income) Accents.Money else Palette.SurfaceHi, Radii.Field,
                            )
                            .clickable { income = !income }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
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
                            fontSize = Type.Meta,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .background(
                                    if (on) Accents.Money else Palette.SurfaceHi, Radii.Chip,
                                )
                                .clickable { cat = c }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                Field(note, "買了什麼（可留白）") { note = it }
            }
        }
        item {
            // 標題說「本月」而且真的只列本月。原本 hint 算的是 rows（本月）、
            // 底下卻 items(ledger)（全部歷史），於是「明細 3 筆」下面躺著幾十筆，
            // 跟上面那張月結卡的金額也對不起來——三個數字互相矛盾。
            // 這一頁沒有月份切換器（month 是寫死的 thisMonth），所以「列全部歷史」
            // 本來就不是誰設計過的行為，只是漏了 filter。要翻舊帳跟助理講就好。
            SectionHead(
                "本月明細",
                hint = rows.size.takeIf { it > 0 }?.let { "$it 筆" },
                tint = Accents.Money,
            )
        }
        if (rows.isEmpty()) {
            item {
                Empty(
                    if (ledger.isEmpty()) "還沒記過帳。跟助理說「午餐 120」它會直接記進來。"
                    else "這個月還沒記過帳。",
                )
            }
        }
        items(rows, key = { it.id }) { r ->
            StripeRow(
                // 收入用綠、支出維持分頁色：這一頁唯一需要一眼分辨的就是錢的方向
                tint = if (r.income) Palette.Ok else Accents.Money,
                leadWidth = 0.dp,
                lead = {},
                actions = {
                    Text(
                        (if (r.income) "+" else "−") + fmtMoney(r.amount),
                        color = if (r.income) Palette.Ok else Palette.Text,
                        fontSize = Type.Head, fontWeight = FontWeight.Bold,
                    )
                    IconBtn(Icons.Filled.Close, "刪掉這筆") {
                        scope.launch { AgendaRepo.remove(ctx, client, "ledger", r.id) }
                    }
                },
            ) {
                Text(
                    "${r.category}${if (r.note.isBlank()) "" else "・" + r.note}",
                    color = Palette.Text, fontSize = Type.Body,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    fmtStamp(r.ts), color = Palette.TextFaint, fontSize = Type.Meta,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

// 共用小元件（Card／Field／StripeRow／AddPanel …）搬到 Components.kt，
// 工具頁與聊天頁要用同一份，留在這裡的話兩邊只能各寫一份長得像的

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

/**
 * 「今天」，跨過午夜會自己變。
 *
 * 原本各處寫的是 `remember { todayStr() }`——沒有 key，等於「進這個分頁的那一天」，
 * 之後永遠不動。停在日常分頁把 App 放著過夜就會出事，而且**不只是顯示錯**：
 * 行事曆的「加到今天」用的是同一個值，按下去會把行程寫進昨天，產生錯誤資料。
 *
 * 每分鐘醒一次而不是算到午夜精準排一發：省下時區、日光節約、使用者手動改系統時間
 * 這三種校正，成本是一天 1440 次字串比較。
 */
@Composable
private fun rememberToday(): String {
    var today by remember { mutableStateOf(todayStr()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            val now = todayStr()
            if (now != today) today = now
        }
    }
    return today
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
    // 解不出來就開在 08:00。current 可能是空字串（新增的節次還沒填時間），
    // 硬切會閃退——而使用者按下這顆按鈕正是為了把它填好，那時閃退最說不過去。
    val t = minsOrNull(current) ?: (8 * 60)
    TimePickerDialog(ctx, { _, hh, mm ->
        onPick("${two(hh)}:${two(mm)}")
    }, t / 60, t % 60, true).show()
}
