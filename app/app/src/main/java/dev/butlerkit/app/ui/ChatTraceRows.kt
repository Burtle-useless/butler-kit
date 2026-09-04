package dev.butlerkit.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.net.BgTask
import dev.butlerkit.app.net.ButlerClient
import kotlinx.coroutines.delay

/** 頭像欄寬：過程資訊照這個縮排，跟正文對齊在同一條縱線上。 */
internal val AvatarW = 42.dp

/**
 * 助理的一列：平鋪式——左側頭像、右側內容**用滿寬度**，不裝氣泡。
 * 長 Markdown 內容裝進氣泡會浪費寬度。
 */
@Composable
internal fun BotRow(mood: PetMood, content: @Composable () -> Unit) = Row(
    Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top,
) {
    PetFace(mood, 32.dp, Modifier.padding(top = 1.dp))
    Column(Modifier.weight(1f).padding(start = 10.dp)) { content() }
}

/**
 * 「助理正在打字」的三顆點：串流還沒吐出任何字時放在頭像旁邊。
 *
 * 三顆依序浮起再落下，一輪 900ms；用 TextFaint 不用強調色——它只是說「有東西要來」，
 * 不是要人注意什麼。有字之後就換成串流預覽（見 ChatBody）。
 */
@Composable
internal fun TypingDots() {
    val inf = rememberInfiniteTransition(label = "typing")
    val t by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "t",
    )
    val tone = Palette.TextFaint
    Row(
        // 對齊回覆第一行的高度：Markdown 內文行高 24sp，點放在那一行的中間
        Modifier.height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) { i ->
            // 每顆錯開三分之一輪；sin 半波＝浮起再落下
            val phase = ((t - i / 3f + 1f) % 1f)
            val lift = kotlin.math.sin(phase * Math.PI).toFloat().coerceAtLeast(0f)
            Box(
                Modifier.size(7.dp)
                    .offset(y = (-3).dp * lift)
                    .background(tone.copy(alpha = 0.45f + 0.55f * lift), CircleShape),
            )
        }
    }
}

/** 跨日的日期分隔：居中一行淡字，跟成熟聊天 App 同一套。 */
@Composable
internal fun DaySeparator(label: String) = Text(
    label,
    color = Palette.TextFaint, fontSize = Type.Tiny, lineHeight = Type.TinyLine,
    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
)

/** 長按選單裡的「15:32」那行：只是看的，按了就收起來。 */
@Composable
private fun ClockMenuItem(atMs: Long, onDismiss: () -> Unit) {
    val clock = clockLabel(atMs) ?: return
    DropdownMenuItem(
        text = { Text(clock, fontSize = Type.Meta, color = Palette.TextDim) },
        onClick = onDismiss,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TraceRow(
    item: TraceItem,
    pet: PetMood,
    client: ButlerClient,
    onResend: (String) -> Unit = {},
    onAnswer: (String, String, String?) -> Unit = { _, _, _ -> },
) = when (item) {
    is TraceItem.UserMsg -> {
        var menu by remember { mutableStateOf(false) }
        val clip = LocalClipboardManager.current
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Box {
                Column(horizontalAlignment = Alignment.End) {
                    // 附件畫成縮圖／檔案卡，不是一串路徑文字
                    if (item.attachments.isNotEmpty()) {
                        AttachmentRow(
                            item.attachments, client,
                            Modifier.widthIn(max = 300.dp).padding(bottom = 4.dp),
                        )
                    }
                    if (item.text.isNotBlank()) Surface(
                        // 還沒被讀到的訊息刻意畫成暗的：跟已經在處理的長成一樣，
                        // 人就沒辦法知道助理讀到哪一則了。
                        color = if (item.queued || item.dropped) Palette.SurfaceHi
                        else Palette.UserBubble,
                        shape = Radii.Bubble,
                        modifier = Modifier.widthIn(max = 300.dp)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { menu = true },
                            ),
                    ) {
                        Text(
                            item.text,
                            Modifier.padding(horizontal = Space.Inner, vertical = 10.dp),
                            color = when {
                                item.dropped -> Palette.TextFaint
                                item.queued -> Palette.TextDim
                                else -> Palette.Text
                            },
                            fontSize = Type.Body,
                            lineHeight = Type.BodyLine,
                        )
                    }
                    // 三種狀態要一眼分得出來：取消／排隊／插進正在跑的那一輪
                    val note = when {
                        item.dropped -> "已取消，這則沒有送進去"
                        item.queued -> "排隊中，它還在忙上一輪"
                        item.steered -> "⚡ 已插進正在跑的工作"
                        else -> null
                    }
                    if (note != null) {
                        Text(
                            note,
                            color = if (item.dropped) Palette.Danger else Palette.TextFaint,
                            fontSize = Type.Tiny,
                            modifier = Modifier.padding(top = 3.dp, end = 6.dp),
                        )
                    }
                }
                // 長按操作列：一般聊天 App 的慣例，之前完全沒有
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("複製", fontSize = Type.Meta, color = Palette.Text) },
                        onClick = {
                            clip.setText(AnnotatedString(item.text)); menu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("重送一次", fontSize = Type.Meta, color = Palette.Text) },
                        onClick = { menu = false; onResend(item.text) },
                    )
                    ClockMenuItem(item.atMs) { menu = false }
                }
            }
        }
    }

    is TraceItem.Reply -> {
        var menu by remember { mutableStateOf(false) }
        val clip = LocalClipboardManager.current
        Box {
            BotRow(pet) {
                Box(
                    Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { menu = true },
                    ),
                ) { MarkdownText(cleanMarkers(item.markdown)) }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("複製", fontSize = Type.Meta, color = Palette.Text) },
                    onClick = {
                        clip.setText(AnnotatedString(cleanMarkers(item.markdown)))
                        menu = false
                    },
                )
                ClockMenuItem(item.atMs) { menu = false }
            }
        }
    }

    is TraceItem.Thinking -> ThinkingRow(item)

    is TraceItem.Stage -> StageRow(item)

    is TraceItem.FileOffer -> FileOfferCard(item, client)

    is TraceItem.AskItem -> AskCard(item, onAnswer)

    // 破壞性指令是唯一不退為背景的過程資訊。摺疊會把指令藏起來，
    // 「核對它到底在跑什麼」的防線就沒了。
    is TraceItem.DangerTool -> Column(
        Modifier.fillMaxWidth().padding(start = AvatarW)
            .background(Palette.DangerSoft, Radii.Card)
            .border(1.dp, Palette.Danger.copy(alpha = 0.5f), Radii.Card)
            .padding(Space.Inner),
    ) {
        Text(
            "要動到系統的指令 · ${item.call.tool}",
            color = Palette.Danger, fontSize = Type.Meta, fontWeight = FontWeight.Medium,
        )
        Text(
            item.call.raw.ifBlank { item.call.summary },
            color = Palette.Text,
            fontSize = Type.Mono,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 6.dp)
                .horizontalScroll(rememberScrollState()),
        )
    }

    // 出錯：吉祥物本人擺出 >< 臉站在訊息旁邊。
    // 使用者自己按的停止（STOPPED）不是出錯——紅字加 >< 臉會讓人以為壞了，
    // 用跟「助理接手」同款的一行小字交代就好
    is TraceItem.ErrorItem -> if (item.kind == "STOPPED") {
        Text(
            item.detail,
            color = Palette.TextFaint, fontSize = Type.Tiny, lineHeight = Type.TinyLine,
            modifier = Modifier.fillMaxWidth().padding(start = AvatarW, top = 6.dp, bottom = 2.dp),
        )
    } else {
        BotRow(PetMood.Error) {
            Text(
                item.detail,
                color = Palette.Danger, fontSize = Type.Meta,
                lineHeight = Type.MetaLine,
            )
        }
    }

    // 助理自己醒來的那一輪：一行小字交代為什麼突然又開口，不裝盒子、不帶頭像——
    // 它不是誰說的話，只是回覆前面的脈絡
    is TraceItem.WakeNote -> Text(
        item.text,
        color = Palette.TextFaint, fontSize = Type.Tiny, lineHeight = Type.TinyLine,
        modifier = Modifier.fillMaxWidth().padding(start = AvatarW, top = 10.dp, bottom = 2.dp),
    )
}

/**
 * 思考塊：**要跟「助理對你說的話」一眼分得開**。
 *
 * 原本只靠顏色淡一點來區分，使用者實測回報分不清哪些是思考、哪些是要給他看的
 * 輸出——顏色是最弱的訊號，一整段佔滿寬度的文字看起來就是正文。改成左側一條
 * 直線加「想」標籤：**有直線的是助理在自言自語，有頭像的才是它對你說的話**。
 *
 * **預設整段收起，只留一行標籤。**先前收摺三行，露出來的那三行仍然佔掉半個
 * 畫面，一輪五六次思考下來正文就被自言自語擠到邊上——使用者比對電腦版之後
 * 明說要電腦版那個編排（2026-08-21）。電腦版的思考是 `<details>`，收起時只有
 * 「思考」兩個字。這裡照同一條規矩：想看再點。
 */
@Composable
private fun ThinkingRow(item: TraceItem.Thinking) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .padding(start = AvatarW, top = 3.dp, bottom = 3.dp)
            .height(IntrinsicSize.Min)
            .clickable(role = Role.Button) { open = !open },
    ) {
        // 這條線就是「這段不是對你說的」的記號
        Box(Modifier.width(2.dp).fillMaxHeight().background(Palette.Line, Radii.Chip))
        Column(Modifier.padding(start = 10.dp)) {
            // 三角是「這裡可以點開」的訊號。少了它，收起來的思考只剩孤零零一個
            // 「想」字貼在回覆上方，看起來像在說「以下是它想的」——正好相反。
            Text(
                if (open) "▾ 想" else "▸ 想",
                color = Palette.TextFaint,
                fontSize = Type.Tiny,
                fontWeight = FontWeight.Medium,
            )
            if (open) {
                Text(
                    item.text,
                    color = Palette.TextFaint,
                    fontSize = Type.Tiny,
                    lineHeight = Type.MetaLine,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/**
 * 一個階段：這一段做過的工具，**一條一行直接列出來**。
 *
 * 先前是「一行統計、點了才展開明細」的摺疊風格。統計看得出規模、看不出在幹嘛，
 * 而「它剛才到底動了什麼」正是使用者要看的；何況要點一下才知道，等於預設不給看。
 * 所以預設改成逐條列。
 *
 * 但量大的時候逐條列會把回覆整個埋掉，所以超過 [TOOL_FOLD_THRESHOLD] 才收起來，
 * 而且收起來那一行講的是「做了什麼」不是只有總數（見 [toolSummary]）——
 * 上一段那條批評不能因為摺疊又跑回來。
 */
@Composable
private fun StageRow(s: TraceItem.Stage) {
    // 工具多到會把回覆埋掉時預設收起來（見 ChatModels.TOOL_FOLD_THRESHOLD）。
    // 少的維持逐條列——上面那段的理由對小階段仍然成立。
    val foldable = s.tools.size > TOOL_FOLD_THRESHOLD
    var expanded by remember(s.turnId, s.tools.size) { mutableStateOf(!foldable) }
    Column(Modifier.fillMaxWidth().padding(start = AvatarW)) {
        if (s.text.isNotBlank()) {
            Text(
                s.text,
                color = Palette.TextDim,
                fontSize = Type.Meta,
                lineHeight = Type.MetaLine,
            )
        }
        if (foldable) {
            Text(
                (if (expanded) "▾ " else "▸ ") + toolSummary(s.tools),
                color = Palette.TextFaint,
                fontSize = Type.Tiny,
                modifier = Modifier.fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(top = 2.dp, bottom = 2.dp),
            )
        }
        if (!expanded) return@Column
        s.tools.forEachIndexed { i, c ->
            // 一行摘要，點一下展開指令原文（等寬、可選取複製）。原文本來就在
            // 事件裡（`raw`），先前只有破壞性指令那列會畫，其他一律截成一行省略——
            // 「它剛才到底跑了什麼」明明在手上卻看不到
            var open by remember(s.turnId, i) { mutableStateOf(false) }
            Column(
                Modifier.fillMaxWidth()
                    .clickable(enabled = c.raw.isNotBlank(), role = Role.Button) { open = !open }
                    .padding(top = 2.dp),
            ) {
                Text(
                    "${c.icon} ${c.tool}  ${c.summary}",
                    color = Palette.TextFaint,
                    fontSize = Type.Tiny,
                    fontFamily = FontFamily.Monospace,
                    maxLines = if (open) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (open && c.raw.isNotBlank() && c.raw != c.summary) {
                    SelectionContainer {
                        Text(
                            c.raw,
                            color = Palette.TextDim,
                            fontSize = Type.Tiny,
                            lineHeight = Type.TinyLine,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth()
                                .padding(top = 4.dp, bottom = 4.dp)
                                .background(Palette.SurfaceHi, Radii.Card)
                                .padding(8.dp)
                                .horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 背景工作的那幾張卡片，接在狀態列後面。
 *
 * 它跟 [StatusLine] 是各自獨立的一項，所以助理收工的那一刻，狀態列的其他部分
 * （游標、秒數、模型）收掉，而這幾張連同它們的計時原地留著不動。人看到的
 * 就是「其他的結束了，這件還在跑」。
 */
@Composable
internal fun BackgroundLine(tasks: List<BgTask>, onStopBg: (String) -> Unit) = Column(
    // 跟狀態列的距離交給 LazyColumn 的 spacedBy 給，這裡不再自己補 top——
    // 補了的話「助理還在忙」與「助理收工了」兩種狀態下的間距會差一截，而這一項
    // 的全部意義就是在那個交界上不要動
    Modifier.fillMaxWidth().padding(start = AvatarW),
) {
    // 每張卡片綁自己的 id：清單少掉一件時（帳本滿了丟最舊的、或使用者一發言
    // 就把完成的收起來），後面的卡片會整批往前移一格，沒有 id 的話 Compose
    // 按位置對應，每一張都會拿到別人的資料而重建
    tasks.forEach { key(it.id) { BgTaskCard(it, onStopBg) } }
}

/** 耗時寫成 `2:14` 或 `1:02:14`。起始時刻是 0（伺服器沒給）就不顯示。 */
private fun elapsedLabel(startMs: Long, endMs: Long): String? {
    if (startMs <= 0L) return null
    val s = ((endMs - startMs) / 1000).coerceAtLeast(0)
    val h = s / 3600
    val rest = "%d:%02d".format((s % 3600) / 60, s % 60)
    return if (h > 0) "$h:%02d:%02d".format((s % 3600) / 60, s % 60) else rest
}

/** token 數縮寫。長任務動輒幾十萬，完整數字在這個字級下只是一片糊。 */
private fun tokenLabel(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> "$n"
}

/**
 * 一件背景工作。
 *
 * 2026-08-25 之前這裡是一行 `Palette.TextFaint` 的小字「背景：某某某」——沒有
 * 進度、沒有耗時、不能點、不能停、跑完就消失。而 SDK 一直在送
 * `TaskProgressMessage`（token、工具次數、最近呼叫的工具）與 `stop_task()`，
 * 只是伺服器沒接（使用者回報：「太陽春了」）。
 *
 * 現在左邊那個方框標籤同時是狀態燈：進行中吃強調色、失敗吃 Danger、其餘灰。
 * 色票裡只有一個彩色（見 Theme.kt），所以「有東西正在跑」是整頁唯一會跳出來的東西。
 */
@Composable
private fun BgTaskCard(task: BgTask, onStopBg: (String) -> Unit) {
    // 耗時本地每秒推進，理由同 StatusHead：伺服器的進度訊息不保證多久來一次，
    // 拿它當時鐘的話畫面會停在最後一個數字上，看起來像卡住了。
    var now by remember(task.id) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(task.id, task.status) {
        while (task.running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val tone = when {
        task.running -> Palette.Accent
        task.failed -> Palette.Danger
        else -> Palette.TextFaint
    }
    val label = when {
        task.running -> "背景"
        task.failed -> "失敗"
        task.status == "stopped" -> "中止"
        else -> "完成"
    }
    // 進行中顯示「還在做什麼」，跑完顯示「結果是什麼」。兩者都空的時候整行不畫，
    // 而不是留一行空白或補一句「無」——那只是把版面撐開。
    val detail = if (task.running) {
        listOfNotNull(
            task.lastTool.takeIf { it.isNotBlank() },
            task.toolUses.takeIf { it > 0 }?.let { "$it 次工具" },
            task.tokens.takeIf { it > 0 }?.let { tokenLabel(it) },
        ).joinToString(" · ").takeIf { it.isNotBlank() }
    } else {
        task.summary.takeIf { it.isNotBlank() }
    }
    Column(Modifier.fillMaxWidth().padding(start = 15.dp, top = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = tone, fontSize = Type.Tiny, lineHeight = Type.TinyLine,
                modifier = Modifier.border(1.dp, tone).padding(horizontal = 4.dp),
            )
            Text(
                task.desc,
                color = Palette.TextDim, fontSize = Type.Tiny, lineHeight = Type.TinyLine,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            elapsedLabel(task.startedAtMs, if (task.running) now else task.finishedAtMs)
                ?.let {
                    Text(
                        it,
                        color = Palette.TextFaint,
                        fontSize = Type.Tiny, lineHeight = Type.TinyLine,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            if (task.running) {
                // 觸控區靠 padding 撐開：這個字級只有 11sp，照字面大小做按鈕
                // 在手機上按不到。左右各 10dp、上下 6dp 才勉強夠一根手指。
                Text(
                    "停",
                    color = Palette.Accent,
                    fontSize = Type.Tiny, lineHeight = Type.TinyLine,
                    modifier = Modifier
                        .clip(Radii.Chip)
                        .clickable(role = Role.Button) { onStopBg(task.id) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        if (detail != null) {
            Text(
                detail,
                color = Palette.TextFaint,
                fontSize = Type.Tiny, lineHeight = Type.TinyLine,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                // 對齊上一行的描述：方框標籤的寬度加它右邊的間距
                modifier = Modifier.padding(start = 34.dp, top = 1.dp),
            )
        }
    }
}

@Composable
internal fun StatusLine(state: ChatState) = Column(
    // 只留頭像寬度的縮排：它現在是 LazyColumn 的一項，左右的 Space.Screen
    // 由那邊的 contentPadding 給了，這裡再加一次會變兩倍
    Modifier.fillMaxWidth().padding(start = AvatarW),
) {
    StatusHead(state)
}

/**
 * 排字機那種閃動的方塊，跟電腦版串流時的游標是同一個東西（web/app.css 的 `.caret`）。
 *
 * 原本是一顆轉圈圈。轉圈圈說的是「載入中，請等待」——那是進度不明的等待，
 * 而助理在跑的時候畫面上正一行行長出思考與工具，等待從來不是不明的。游標說的是
 * 「這裡正在被打出來」，對得上實際發生的事，也跟電腦版接得起來。
 */
@Composable
private fun Caret() {
    val inf = rememberInfiniteTransition(label = "caret")
    // 兩個相鄰關鍵影格只差 1 毫秒＝硬切，不是淡入淡出。終端機的游標不會漸變
    val on by inf.animateFloat(
        initialValue = 1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1000
                1f at 0; 1f at 499
                0f at 500; 0f at 999
            },
        ),
        label = "on",
    )
    Box(
        Modifier.size(width = 6.dp, height = 12.dp)
            .background(Palette.Accent.copy(alpha = on)),
    )
}

@Composable
private fun StatusHead(state: ChatState) = Row(
    verticalAlignment = Alignment.CenterVertically,
) {
    Caret()
    val tail = state.thinkingTail.takeIf { it.isNotBlank() }?.takeLast(48)
    // 秒數本地每秒推進，不用伺服器 status 的 elapsed：那個值兩秒才更新一次，
    // 而且續跑會讓它重新從 0 算、斷線續傳會整批重播讓它倒退。起點撐得過
    // 切出去再切回來（見 ChatViewModel.syncBusyClock）。
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.busySince) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val secs = if (state.busySince == 0L) 0L else (now - state.busySince) / 1000
    // 伺服器對這段等待的說明（壓縮、空回覆重試）優先——它知道的比思考尾段多。
    // 壓縮那一輪的思考被伺服器擋著不送，落到預設文案就會謊稱它在想事情。
    val note = state.status?.note?.takeIf { it.isNotBlank() }
    val phase = if (state.status?.phase == "compacting") "整理記憶中，等我一下" else null
    // 秒數排在最前面。原本掛在思考尾段後面，而這一行只有一行、尾段又抓了 48 個字，
    // 手機寬度下秒數永遠被擠出畫面——計時器等於不存在（使用者 2026-08-21 回報）。
    // 超過一分鐘改用分秒：跑久的工作看「185s」得自己心算。
    val elapsed = when {
        secs <= 3 -> ""
        secs < 60 -> "${secs}s　"
        else -> "${secs / 60}分${secs % 60}秒　"
    }
    Text(
        buildString {
            append(" ")
            append(elapsed)
            append(note ?: phase ?: tail ?: "想一下")
        },
        color = Palette.TextFaint,
        fontSize = Type.Tiny,
        maxLines = 1,
    )
}
