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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import dev.butlerkit.app.net.CalEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

// ── 行事曆 ───────────────────────────────────────────────────────────────────
internal val WEEK_HEAD = listOf("日", "一", "二", "三", "四", "五", "六")

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
internal fun CalendarPane(
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
                .clickable(role = Role.Button, onClick = onToday)
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
        modifier.heightIn(min = 42.dp).clickable(role = Role.Button, onClick = onPick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            // 選中的日期是小圓角方塊（Radii.Chip），跟全 App 的按鈕同一套，不是正圓
            Modifier.size(25.dp)
                .background(if (on) Palette.Accent else Color.Transparent, Radii.Chip),
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
