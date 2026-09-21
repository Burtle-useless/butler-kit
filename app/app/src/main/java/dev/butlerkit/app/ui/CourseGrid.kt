package dev.butlerkit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.net.Course
import dev.butlerkit.app.net.Period

/**
 * 課表的週一覽：一眼看完整週。
 *
 * 單日檢視回答的是「今天接下來上什麼」，但**排課、跟人對時間、看哪天最累**這些事
 * 只有攤開整週才做得到——課表這種東西總要有一張一覽圖。
 *
 * 手機寬度是這個版面唯一的難題（一格分七欄只剩四十幾 dp）。市面上的課表 App
 * 都靠同一組手段解決，這裡照做：
 *
 *  1. **只畫有課的天**。週末沒課就不佔欄位，七欄變五欄，每欄從 43dp 回到 60dp。
 *  2. **只畫有課的節次**。沒人從第一節排到第十二節，掐頭去尾之後高度砍半。
 *  3. **連續節次合併成一格**。三節連上的課是一個色塊不是三格，這也是紙本課表的畫法。
 *  4. **格子只放課名**，教室老師點開才看——格子裡塞不下，塞了也讀不到。
 *
 * **刻意不用彩色**：整個 App 是單色印刷風（見 `Accents`，四個都指向文字色），
 * 課表突然變成彩虹色塊會像另一個 App。改用格線與淡底來分：有課的格子上淡灰底，
 * 空堂留白，正在上的那一堂加深。這正是紙本課表的樣子。
 */

/** 網格裡的一格。[course] 是 null 代表空堂。 */
internal data class GridSlot(
    val from: Int,
    val span: Int,
    val course: Course?,
    /** 同一個時段還有幾堂課（衝堂）。0＝沒衝突。 */
    val clash: Int = 0,
)

/**
 * 排出某一天從第 [first] 到第 [last] 節的格子。
 *
 * 連續節次會合併成一格（`span` > 1），空堂一節一格——空堂合併的話，
 * 隔壁有課的那幾欄就對不齊了。
 *
 * 衝堂不丟掉：同一節有兩堂課時顯示第一堂並把數量記在 [GridSlot.clash]，
 * 讓畫面標得出來。**吃掉衝突的那堂比顯示錯誤更糟**——排錯課的人永遠不會發現。
 */
internal fun layoutDay(courses: List<Course>, first: Int, last: Int): List<GridSlot> {
    if (last < first) return emptyList()
    val sorted = courses.sortedWith(compareBy({ it.fromPeriod }, { it.name }))
    val out = mutableListOf<GridSlot>()
    var p = first
    while (p <= last) {
        // 這一節開始的課。用 <= p <= 而不是 fromPeriod == p：某堂課的起點可能
        // 在 first 之前（節次表被改小了），那時它仍該從 first 這一格畫起
        val here = sorted.filter { it.fromPeriod <= p && p <= it.toPeriod }
        if (here.isEmpty()) {
            out += GridSlot(p, 1, null)
            p += 1
            continue
        }
        val c = here.first()
        val end = minOf(c.toPeriod, last)
        out += GridSlot(p, maxOf(1, end - p + 1), c, clash = here.size - 1)
        p = end + 1
    }
    return out
}

/** 要畫哪幾天：有課的天，一天都沒有就給週一到週五。 */
internal fun gridDays(courses: List<Course>): List<Int> {
    val has = courses.map { it.day }.filter { it in 0..6 }.distinct().sorted()
    return has.ifEmpty { listOf(0, 1, 2, 3, 4) }
}

/** 要畫哪幾節：有課的範圍，沒課就給 1..8。上下各不留白——空白格沒有資訊。 */
internal fun gridPeriods(courses: List<Course>, maxNo: Int): IntRange {
    if (courses.isEmpty()) return 1..minOf(8, maxOf(maxNo, 1))
    val lo = courses.minOf { it.fromPeriod }.coerceAtLeast(1)
    val hi = courses.maxOf { it.toPeriod }.coerceAtMost(maxOf(maxNo, 1))
    return if (hi < lo) lo..lo else lo..hi
}

/** 此刻的狀態：第幾節、正在上什麼、下一堂是什麼。 */
internal data class NowInfo(
    val period: Int?,
    val current: Course?,
    val next: Course?,
    val nextStart: String,
)

/**
 * 算「現在」。[nowMins] 是今天幾點幾分換成分鐘數，[todays] 是今天的課。
 *
 * 下課時間（不在任何節次裡）period 是 null，但 next 照樣算——那正是最常看
 * 這一行的時候：剛下課，想知道下一堂幾點、在哪。
 */
internal fun nowSlot(periods: List<Period>, todays: List<Course>, nowMins: Int): NowInfo {
    val cur = periods.firstOrNull { p ->
        val s = minsOrNull(p.start) ?: return@firstOrNull false
        val e = minsOrNull(p.end) ?: return@firstOrNull false
        nowMins in s until e
    }
    val sorted = todays.sortedBy { it.fromPeriod }
    val current = cur?.let { p -> sorted.firstOrNull { p.no in it.fromPeriod..it.toPeriod } }
    // 下一堂：開始節次的 start 在現在之後的第一堂（不管現在有沒有在上課）
    val byNo = periods.associateBy { it.no }
    val next = sorted.firstOrNull { c ->
        val s = byNo[c.fromPeriod]?.start?.let(::minsOrNull) ?: return@firstOrNull false
        s > nowMins && c !== current
    }
    return NowInfo(
        period = cur?.no,
        current = current,
        next = next,
        nextStart = next?.let { byNo[it.fromPeriod]?.start }.orEmpty(),
    )
}

// 課表課名對課程資料夾的對照 2026-09-07 搬到伺服器（courses_api.names_match）：
// 那邊同時看得到兩份名單，App 只拿它算好的 agenda_ids（見 net/Courses.kt）。

/** 一節的高度。三個中文字一行、兩行放得下，再矮就讀不到教室名。 */
private val ROW_H = 46.dp
private val TIME_W = 30.dp
/** 欄寬下限：低於這個就改成橫向捲動，硬擠只會讓每格都變成「⋯」。 */
private val MIN_COL_W = 52.dp
/** 欄寬上限：再寬就變成留白，一眼掃過去反而要移動視線。 */
private val MAX_COL_W = 76.dp

/**
 * 課名在格子裡的短版：冒號之後砍掉。
 *
 * 「社會關懷：非營利組織的服務學習」在 60dp 寬的格子裡只會顯示「社會關懷：非…」，
 * 而冒號前那四個字本來就足以認出是哪一門。**括號不動**——「微積分（一）」的
 * （一）是用來分班的，砍了會認錯課。
 */
internal fun shortName(name: String): String {
    val cut = name.indexOfFirst { it == '：' || it == ':' }
    return if (cut in 2 until name.length - 1) name.take(cut) else name
}

@Composable
internal fun CourseGrid(
    courses: List<Course>,
    periods: List<Period>,
    todayDay: Int,
    onPick: (Course) -> Unit,
) {
    val days = gridDays(courses)
    val maxNo = periods.maxOfOrNull { it.no } ?: 12
    val range = gridPeriods(courses, maxNo)
    val span = periods.associateBy { it.no }
    val byDay = courses.groupBy { it.day }

    // 欄寬自己算：天數少就讓每欄寬一點（多一個字就多認得出一門課），
    // 天數多到擠不下就退回下限並讓它橫向捲——硬擠成 40dp 每格都只剩省略號
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val colW = ((maxWidth - TIME_W) / days.size.coerceAtLeast(1))
        .coerceIn(MIN_COL_W, MAX_COL_W)
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(TIME_W))
            days.forEach { d ->
                Box(
                    Modifier.width(colW).padding(bottom = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        WEEK[d],
                        color = if (d == todayDay) Palette.Text else Palette.TextDim,
                        fontSize = Type.Meta,
                        fontWeight = if (d == todayDay) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            // 左邊時間欄：節次 ＋ 開始時間。只有節次的話還是得心算
            Column(Modifier.width(TIME_W)) {
                range.forEach { no ->
                    Column(
                        Modifier.height(ROW_H).padding(end = 4.dp, top = 2.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text("$no", color = Palette.TextDim, fontSize = Type.Tiny)
                        span[no]?.start?.let {
                            Text(it.take(5), color = Palette.TextFaint, fontSize = 8.sp())
                        }
                    }
                }
            }
            days.forEach { d ->
                Column(Modifier.width(colW)) {
                    layoutDay(byDay[d].orEmpty(), range.first, range.last).forEach { s ->
                        SlotCell(s, colW, d == todayDay, onPick)
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun SlotCell(
    slot: GridSlot,
    colW: androidx.compose.ui.unit.Dp,
    isToday: Boolean,
    onPick: (Course) -> Unit,
) {
    val h = ROW_H * slot.span
    val c = slot.course
    if (c == null) {
        // 空堂：只留一層極淡的底，不畫框。每格都框起來的話整張表看起來
        // 像一堆空卡片，眼睛會去讀那些框而不是課
        Box(
            Modifier.width(colW).height(h).padding(1.dp)
                .clip(Radii.Tiny)
                .background(Palette.Line.copy(alpha = 0.18f)),
        )
        return
    }
    Box(
        Modifier.width(colW).height(h).padding(1.dp)
            .clip(Radii.Tiny)
            .background(if (isToday) Palette.Text.copy(alpha = 0.16f) else Palette.Text.soft())
            .clickable { onPick(c) }
            .padding(horizontal = 3.dp, vertical = 2.dp),
    ) {
        Column {
            Text(
                shortName(c.name),
                color = Palette.Text, fontSize = Type.Tiny, lineHeight = Type.TinyLine,
                fontWeight = FontWeight.Medium,
                maxLines = if (slot.span > 1) 3 else 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
            )
            if (c.room.isNotBlank() && slot.span > 1) {
                Text(
                    c.room, color = Palette.TextDim, fontSize = 9.sp(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 衝堂：不吃掉也不假裝沒事，角落標一個數字讓人自己去看
        if (slot.clash > 0) {
            Text(
                "+${slot.clash}",
                color = Palette.TextDim, fontSize = 9.sp(),
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/** 小字級的簡寫。格子裡的字比 Type.Tiny 還要再小一階，不值得進設計系統。 */
private fun Int.sp() = androidx.compose.ui.unit.TextUnit(
    this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp,
)

/** 週／日切換的小按鈕。選中的填底，沒選的只有字。 */
@Composable
internal fun ViewToggle(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(Radii.Chip)
            .background(if (on) Palette.Text.soft() else Palette.Bg)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            color = if (on) Palette.Text else Palette.TextDim,
            fontSize = Type.Meta,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * 點週表格子跳出來的課程詳情。
 *
 * 格子裡只放得下課名，其他資訊都在這裡：完整名稱、第幾節到第幾節換算成幾點、
 * 老師、教室、備註，以及刪掉這堂課。
 */
@Composable
internal fun CourseDetail(
    c: Course,
    span: Map<Int, Period>,
    onClose: () -> Unit,
    onDelete: (() -> Unit)?,
    /** 這堂課對得到課程資料夾時給：多一顆「進工作區」。對不到（體育）就只有課表資訊。 */
    onOpen: (() -> Unit)? = null,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Box(
            Modifier.fillMaxWidth().padding(24.dp)
                .clip(Radii.Card)
                .background(Palette.Surface)
                .border(0.6.dp, Palette.Line, Radii.Card)
                .padding(16.dp),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    c.name, color = Palette.Text, fontSize = Type.Title,
                    fontWeight = FontWeight.Bold,
                )
                val from = span[c.fromPeriod]?.start
                val to = span[c.toPeriod]?.end
                val range = if (c.fromPeriod == c.toPeriod) "第${c.fromPeriod}節"
                else "第${c.fromPeriod}–${c.toPeriod}節"
                val clock = if (from != null && to != null) "　$from–$to" else ""
                Text(
                    "週${WEEK.getOrElse(c.day) { "?" }}　$range$clock",
                    color = Palette.TextDim, fontSize = Type.Meta,
                )
                listOf(c.teacher, c.room).filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }?.let {
                        Text(it.joinToString("・"), color = Palette.Text, fontSize = Type.Body)
                    }
                if (c.note.isNotBlank()) {
                    Text(c.note, color = Palette.TextFaint, fontSize = Type.Meta)
                }
                if (onOpen == null) {
                    Text(
                        "這門課沒有課程資料夾，只有課表資訊。",
                        color = Palette.TextFaint, fontSize = Type.Meta,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // 三顆等寬時「進工作區」四個字會被折成兩行（AVD 上看到的）：
                // 主要動作給寬一點，兩顆兩個字的窄一點
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.weight(0.8f)) {
                        PickerChip("關掉", Modifier.fillMaxWidth()) { onClose() }
                    }
                    if (onOpen != null) {
                        Box(Modifier.weight(1.4f)) {
                            PickerChip("進工作區", Modifier.fillMaxWidth()) { onOpen() }
                        }
                    }
                    if (onDelete != null) {
                        Box(Modifier.weight(0.8f)) {
                            PickerChip("刪掉", Modifier.fillMaxWidth()) { onDelete() }
                        }
                    }
                }
            }
        }
    }
}
