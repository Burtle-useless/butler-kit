package dev.butlerkit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.KanbanCard
import dev.butlerkit.app.net.KanbanColumn
import kotlinx.coroutines.launch

/**
 * 看板：報紙分版——一屏一欄，欄與欄橫著翻。
 *
 * 2026-08-19 重寫。前一版把三欄攤平在同一條清單、每件工作壓成 44dp 單行、
 * 靠長按拖放（位移÷行高）換算落點，使用者的評價是「非常不滿」。查了行動版
 * 看板的通行做法之後照三條原則重做：
 *
 * 1. **一次只看一欄。** 手機一屏擺不下三欄，攤平混在一起等於哪欄都看不清。
 *    改成 HorizontalPager 一欄一頁，頂上是帶數字的版面索引（像報紙頭版的
 *    目錄），點索引或橫滑都能換欄。
 * 2. **條目要不用點開就讀得懂。** 標題一到兩行襯線字、備註跟在下面、
 *    急件是紅框「急」章。不再為了拖放把每列鎖成等高單行。
 * 3. **換欄用明確的按鈕，不用拖放。** 每一條右邊就是它的下一步
 *    （待辦→「開工」、進行中→「完成」），一下就按到。拖放在手機上是
 *    出了名的難用——會誤觸、會跟捲動打架，而舊版靠行高換算落點的做法
 *    連個落點預覽都給不了。要精準指定欄位的話點進條目用選擇器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanScreen(client: ButlerClient) {
    val scope = rememberCoroutineScope()

    var columns by remember { mutableStateOf<List<KanbanColumn>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<KanbanCard?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            client.getKanban()
                .onSuccess { columns = it; error = null }
                .onFailure { error = it.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    // 頁數固定為 3（待辦／進行中／完成）：資料還沒回來時 pager 也要能建
    val pager = rememberPagerState(pageCount = { if (columns.isEmpty()) 3 else columns.size })

    Column(Modifier.fillMaxSize()) {
        // ── 版面索引：欄名＋張數，選中的黑底反白（報紙目錄）─────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.Screen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            columns.forEachIndexed { i, col ->
                val sel = pager.currentPage == i
                Row(
                    Modifier
                        .background(if (sel) Palette.Text else Palette.Bg)
                        .clickable { scope.launch { pager.animateScrollToPage(i) } }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        col.label,
                        color = if (sel) Palette.Bg else Palette.TextDim,
                        fontSize = Type.Meta,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "${col.total}",
                        color = if (sel) Palette.Accent else Palette.TextFaint,
                        fontSize = Type.Tiny,
                        fontFamily = FontFamily.SansSerif,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // 新增走報紙的語彙：「刊登」。FAB 那顆浮著的圓鈕是 Material 的東西，
            // 而且會遮住清單最後一條
            Text(
                "＋刊登",
                color = Palette.Accent,
                fontSize = Type.Meta,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier
                    .clickable { showAdd = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        // 粗規線把索引跟內文分開（報頭下那條）
        Box(
            Modifier.fillMaxWidth().padding(horizontal = Space.Screen)
                .height(2.dp).background(Palette.Text),
        )

        when {
            error != null -> Center { Text(error!!, color = Palette.TextDim, fontSize = Type.Body) }
            loading -> Center { Text("載入中…", color = Palette.TextFaint, fontSize = Type.Body) }
            columns.all { it.total == 0 } -> Center {
                Text(
                    "版面還是空的。右上角刊登一件工作，\n或直接跟助理說你要做什麼。",
                    color = Palette.TextFaint, fontSize = Type.Body,
                    lineHeight = Type.BodyLine, textAlign = TextAlign.Center,
                )
            }
            else -> HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val col = columns.getOrNull(page) ?: return@HorizontalPager
                ColumnPage(
                    col = col,
                    onOpen = { editing = it },
                    onAdvance = { card ->
                        val next = when (card.status) {
                            "todo" -> "doing"
                            "doing" -> "done"
                            else -> "todo"
                        }
                        scope.launch {
                            client.patchKanbanCard(card.id, status = next)
                            reload()
                        }
                    },
                )
            }
        }
    }

    if (showAdd) {
        AddSheet(
            onDismiss = { showAdd = false },
            onAdd = { title, status, urgent ->
                showAdd = false
                scope.launch {
                    client.addKanbanCard(title = title, status = status, urgent = urgent)
                    reload()
                }
            },
        )
    }

    editing?.let { card ->
        EditSheet(
            card = card,
            onDismiss = { editing = null },
            onPatch = { status, urgent, title, note ->
                editing = null
                scope.launch {
                    client.patchKanbanCard(
                        card.id, status = status, urgent = urgent,
                        title = title, note = note,
                    )
                    reload()
                }
            },
            onArchive = {
                editing = null
                scope.launch { client.archiveKanbanCard(card.id); reload() }
            },
        )
    }
}

/** 一欄＝一頁：條目直排，細線分隔。 */
@Composable
private fun ColumnPage(
    col: KanbanColumn,
    onOpen: (KanbanCard) -> Unit,
    onAdvance: (KanbanCard) -> Unit,
) {
    if (col.cards.isEmpty()) {
        Center {
            Text(
                "「${col.label}」這一版沒有東西。",
                color = Palette.TextFaint, fontSize = Type.Body,
            )
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = Space.Screen),
    ) {
        items(col.cards, key = { it.id }) { card ->
            Entry(card, onOpen = { onOpen(card) }, onAdvance = { onAdvance(card) })
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
        }
        if (col.hidden > 0) {
            item {
                Text(
                    "另有 ${col.hidden} 件沒列出",
                    color = Palette.TextFaint, fontSize = Type.Tiny,
                    fontFamily = FontFamily.SansSerif,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * 一件工作＝一則簡訊欄條目：標題（襯線）、備註（縮灰）、右側下一步按鈕。
 * 高度隨內容走，不再鎖 44dp——可讀比可拖重要。
 */
@Composable
private fun Entry(card: KanbanCard, onOpen: () -> Unit, onAdvance: () -> Unit) {
    val done = card.status == "done"
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (card.urgent && !done) {
                    // 紅框「急」章。報紙的標籤是方框字，不是圓 chip
                    Text(
                        "急",
                        color = Palette.Accent, fontSize = Type.Tiny,
                        fontFamily = FontFamily.SansSerif,
                        modifier = Modifier
                            .border(1.dp, Palette.Accent)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    card.title,
                    color = if (done) Palette.TextFaint else Palette.Text,
                    fontSize = Type.Body,
                    lineHeight = Type.BodyLine,
                    maxLines = 2,
                    // 完成的條目劃掉——印刷品的「做完了」就是一條線
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
            }
            if (card.note.isNotBlank() && !done) {
                Spacer(Modifier.height(2.dp))
                Text(
                    card.note,
                    color = Palette.TextDim, fontSize = Type.Meta,
                    lineHeight = Type.MetaLine, maxLines = 2,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        // 下一步：這一欄裡最常做的那個動作，一下就按到（不用長按、不用拖）
        val (label, tint) = when (card.status) {
            "todo" -> "開工" to Palette.Text
            "doing" -> "完成" to Palette.Ok
            else -> "重開" to Palette.TextFaint
        }
        Text(
            label,
            color = tint, fontSize = Type.Meta,
            fontFamily = FontFamily.SansSerif,
            modifier = Modifier
                .border(1.dp, if (card.status == "doing") Palette.Ok else Palette.Line)
                .clickable(onClick = onAdvance)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

// ── 新增 ────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(
    onDismiss: () -> Unit,
    onAdd: (title: String, status: String, urgent: Boolean) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("todo") }
    var urgent by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.Screen, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("刊登一件工作", color = Palette.Text, fontSize = Type.Head,
                fontWeight = FontWeight.Bold)

            Field(title, { title = it }, "像「助理－看板重寫」這樣寫")

            Text("放進哪一版", color = Palette.TextDim, fontSize = Type.Meta,
                fontFamily = FontFamily.SansSerif)
            StatusPicker(status) { status = it }

            UrgentToggle(urgent) { urgent = !urgent }

            ActionButton("刊出", title.isNotBlank()) { onAdd(title.trim(), status, urgent) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── 編輯 ────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSheet(
    card: KanbanCard,
    onDismiss: () -> Unit,
    onPatch: (status: String?, urgent: Boolean?, title: String?, note: String?) -> Unit,
    onArchive: () -> Unit,
) {
    var title by remember { mutableStateOf(card.title) }
    var note by remember { mutableStateOf(card.note) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.Screen, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Field(title, { title = it }, "工作名稱")

            Text("放進哪一版", color = Palette.TextDim, fontSize = Type.Meta,
                fontFamily = FontFamily.SansSerif)
            StatusPicker(card.status) { onPatch(it, null, null, null) }

            UrgentToggle(card.urgent) { onPatch(null, !card.urgent, null, null) }

            Text("備註（卡在哪、下一步）", color = Palette.TextDim, fontSize = Type.Meta,
                fontFamily = FontFamily.SansSerif)
            Field(note, { note = it }, "選填", maxLines = 3)

            ActionButton("存起來", title != card.title || note != card.note) {
                onPatch(null, null, title.trim(), note)
            }

            Row(
                Modifier.fillMaxWidth()
                    .border(1.dp, Palette.Danger)
                    .clickable(onClick = onArchive)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) { Text("下架這件工作", color = Palette.Danger, fontSize = Type.Body) }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 三個版面的橫排選擇器，新增與編輯共用。選中＝黑底反白。 */
@Composable
private fun StatusPicker(current: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("todo" to "待辦", "doing" to "進行中", "done" to "完成").forEach { (k, label) ->
            val sel = current == k
            Box(
                Modifier.weight(1f)
                    .background(if (sel) Palette.Text else Palette.SurfaceHi)
                    .border(1.dp, if (sel) Palette.Text else Palette.Line)
                    .clickable { onPick(k) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (sel) Palette.Bg else Palette.TextDim,
                    fontSize = Type.Meta, fontFamily = FontFamily.SansSerif,
                )
            }
        }
    }
}

@Composable
private fun UrgentToggle(urgent: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (urgent) Palette.DangerSoft else Palette.SurfaceHi)
            .border(1.dp, if (urgent) Palette.Danger else Palette.Line)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (urgent) "取消急件" else "標成急件",
            color = if (urgent) Palette.Danger else Palette.TextDim,
            fontSize = Type.Body, modifier = Modifier.weight(1f),
        )
        if (urgent) {
            Text(
                "急", color = Palette.Danger, fontSize = Type.Tiny,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier.border(1.dp, Palette.Danger)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/** 這一頁的輸入框長相統一在這裡，免得兩個 sheet 各寫一份會走鐘。 */
@Composable
private fun Field(
    value: String, onChange: (String) -> Unit,
    hint: String, maxLines: Int = 1,
) {
    Box(
        Modifier.fillMaxWidth().background(Palette.SurfaceHi)
            .border(1.dp, Palette.Line).padding(14.dp),
    ) {
        if (value.isEmpty()) {
            Text(hint, color = Palette.TextFaint, fontSize = Type.Body)
        }
        BasicTextField(
            value = value, onValueChange = onChange,
            textStyle = TextStyle(
                color = Palette.Text, fontSize = Type.Body,
                lineHeight = Type.BodyLine, fontFamily = Fonts.Base,
            ),
            cursorBrush = SolidColor(Palette.Accent),
            maxLines = maxLines,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
