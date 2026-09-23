package dev.butlerkit.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.butlerkit.app.data.AgendaRepo
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.Course
import dev.butlerkit.app.net.CourseInfo
import dev.butlerkit.app.net.Period
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

// ── 課表 ─────────────────────────────────────────────────────────────────────
//
// 檔名還叫 AgendaCourse 是歷史：課表原本只是日常頁的一個分頁，後來課程頁也要用
// 當課程工作區的入口（[CourseScreen]）。內容沒變，只是多了「點一格進那門課」。

/**
 * 課表：週一覽／單日 ＋ 可改的節次時間表 ＋ 加課。
 *
 * 週一覽是預設——課表總要有一張一覽圖；單日那面回答「今天接下來上什麼」。
 * 節次時間預設是常見的排法，不是他學校的——所以擺在同一頁可以改，而不是寫死在程式裡。
 *
 * [resolve] 把課表上的一格對到課程資料夾（對不到＝那門課還沒建資料夾），
 * [onOpenCourse] 是進工作區。[lead]／[extra] 讓呼叫端在課表卡前後插自己的東西
 * （課程頁放「現在這堂」與課程清單）——整頁是一個 LazyColumn，不能在外面再包一層捲動。
 */
@Composable
internal fun CoursePane(
    ctx: Context, scope: CoroutineScope, client: ButlerClient,
    courses: List<Course>, periods: List<Period>, today: String,
    // 這兩個是給課程頁用的：課表格子點下去要能跳進那門課的工作區。
    // 課程功能是選配的，日常頁的課表照樣要能用，所以給預設值——
    // resolve 回 null 就是「這一格對不到任何課程資料夾」，格子照畫、點不進去。
    resolve: (Course) -> CourseInfo? = { null },
    onOpenCourse: (CourseInfo) -> Unit = {},
    lead: LazyListScope.() -> Unit = {},
    extra: LazyListScope.() -> Unit = {},
    /** 整頁最底下（加課表單與節次表之後）。課程頁放換學期那一列。 */
    tail: LazyListScope.() -> Unit = {},
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
    // 週一覽／單日。**預設週**：使用者要的就是「總要有個一覽圖」，
    // 而單日那些資訊在週表上點一下也看得到
    var weekly by rememberSaveable { mutableStateOf(true) }
    var picked by remember { mutableStateOf<Course?>(null) }

    val byDay = remember(courses) { courses.groupBy { it.day } }
    val dayCourses = byDay[day].orEmpty().sortedBy { it.fromPeriod }
    val span = remember(periods) { periods.associateBy { it.no } }
    val maxNo = periods.maxOfOrNull { it.no } ?: 12

    picked?.let { c ->
        val info = resolve(c)
        CourseDetail(
            c, span,
            onClose = { picked = null },
            onDelete = {
                scope.launch { AgendaRepo.remove(ctx, client, "courses", c.id) }
                picked = null
            },
            onOpen = info?.let { { picked = null; onOpenCourse(it) } },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.Screen),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        lead()

        item {
            Card(pad = 10.dp) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "課表", color = Palette.Text, fontSize = Type.Body,
                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                    )
                    ViewToggle("週", weekly) { weekly = true }
                    ViewToggle("日", !weekly) { weekly = false }
                }
                if (weekly) {
                    CourseGrid(courses, periods, todayDay()) { picked = it }
                } else {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
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
        }

        if (!weekly) item {
            SectionHead(
                if (day == todayDay()) "今天（週${WEEK[day]}）" else "週${WEEK[day]}",
                hint = if (dayCourses.isEmpty()) null else "${dayCourses.size} 堂",
                tint = Accents.Course,
            )
        }

        if (!weekly && day == todayDay() && dayCourses.isNotEmpty()) {
            item {
                // 「現在第幾節、正在上什麼、下一堂幾點」：課表最常被問的那一句
                val cal = Calendar.getInstance()
                val now = nowSlot(
                    periods, dayCourses,
                    cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE),
                )
                NowLine(now)
            }
        }
        if (!weekly && dayCourses.isEmpty()) {
            item {
                Text(
                    "這天沒課。", color = Palette.TextFaint, fontSize = Type.Body,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
        items(if (weekly) emptyList() else dayCourses, key = { it.id }) { c ->
            // 單日列點下去跟週表格子一樣開詳情——那裡才有「進工作區」。
            // StripeRow 沒有 modifier 參數（它自己管圓角與裁切），點擊包在外面；
            // 圓角要跟它一樣，不然按下去的漣漪是方的
            Box(Modifier.clip(Radii.Card).clickable(role = Role.Button) { picked = c }) {
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
        }

        extra()

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
                // 週表上沒有星期列可以點，而且沒課的那幾天連欄位都沒有——
                // 不給選的話「加到週六」會是一句沒有依據的話
                if (weekly) {
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
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { editing = !editing },
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
        tail()
    }
}

@Composable
private fun WeekCell(
    label: String, on: Boolean, today: Boolean, count: Int, tint: Color,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Column(
        modifier.clickable(role = Role.Button, onClick = onClick),
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
                .background(if (on) tint else Palette.SurfaceHi, Radii.Chip)
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
internal fun minsOrNull(t: String): Int? {
    val p = t.trim().split(":")
    if (p.size != 2) return null
    val h = p[0].toIntOrNull() ?: return null
    val m = p[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

/** 同 [minsOrNull]，解不出來當 00:00。用在「算出來還是要給個數字」的地方。 */
internal fun minsOf(hhmm: String): Int = minsOrNull(hhmm) ?: 0

/** 時刻加減分鐘。跨過午夜就停在 23:59——課表不會排到隔天，那種輸入是手滑。 */
private fun addMin(hhmm: String, min: Int): String {
    val total = minsOf(hhmm) + min
    if (total >= 24 * 60) return "23:59"
    return "${two(total / 60)}:${two(total % 60)}"
}

/** 單日檢視頂上那一行：現在第幾節・正在上什麼　下一堂幾點・什麼課。 */
@Composable
internal fun NowLine(now: NowInfo) {
    val head = when {
        now.current != null -> "現在第${now.period}節　${now.current.name}"
        now.period != null -> "現在第${now.period}節　空堂"
        else -> "現在下課"
    }
    val tail = now.next?.let { n ->
        "下一堂 ${now.nextStart.ifBlank { "第${n.fromPeriod}節" }}　${n.name}" +
            (if (n.room.isNotBlank()) "・${n.room}" else "")
    } ?: "今天沒有下一堂了"
    Column(
        Modifier.fillMaxWidth().clip(Radii.Tiny)
            .background(Palette.Text.soft())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(head, color = Palette.Text, fontSize = Type.Body, fontWeight = FontWeight.Medium)
        Text(tail, color = Palette.TextDim, fontSize = Type.Meta)
    }
}


/** 今天是星期幾，0=週一。Calendar 是 1=週日，換算過。 */
internal fun todayDay(): Int =
    (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7
