package dev.butlerkit.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.SearchHit
import dev.butlerkit.app.net.SystemStatus
import dev.butlerkit.app.net.humanError
import dev.butlerkit.app.voice.TalkActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 工具頁：cc-bot 搬過來的「電腦端能力」，跟聊天完全分開。
 * Phase 3 先有截圖；之後的工具照同樣的區塊模式往下加。
 */
@Composable
fun ToolsScreen(client: ButlerClient, onOpenSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    var shot by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 工具 / 看板 切換。rememberSaveable 讓切分頁再回來時停在上次的選擇。
    var showKanban by rememberSaveable { mutableStateOf(false) }

    // 標題列：「工具 | 看板」切換器 ＋ 設定齒輪
    // 切到看板時整個捲動列表換成 KanbanScreen，標題列仍然固定在頂部。
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.Screen, vertical = 0.dp)
                .padding(top = 20.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 工具 / 看板 切換。選中＝黑底反白（報紙的版名章），
            // 不用 Material 那種淡色膠囊——那是這一版要甩掉的東西
            Row(Modifier.weight(1f).border(1.dp, Palette.Text)) {
                listOf(false to "工具", true to "看板").forEach { (isKanban, label) ->
                    val selected = showKanban == isKanban
                    Box(
                        Modifier
                            .weight(1f)
                            .background(if (selected) Palette.Text else Palette.Bg)
                            .clickable { showKanban = isKanban }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = if (selected) Palette.Bg else Palette.TextDim,
                            fontSize = Type.Meta,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
            IconBtn(Icons.Filled.Settings, "設定", onClick = onOpenSettings)
        }

        if (showKanban) {
            // 看板直接接管整個剩餘空間
            KanbanScreen(client)
            return@Column
        }

    // 這一頁不是四張並排的卡片，是「一張主卡 ＋ 三段工具」。
    // 收件匣是唯一會自己冒出新東西的區塊，它獨佔卡片的形狀；下面三個工具是
    // 使用者主動來找的，降成用分隔線隔開的段落。份量差距全靠形狀、字級與留白，
    // 不靠圖示也不靠顏色——舊版每張卡左上角那個彩色圓角方塊裡塞一個 Material
    // 圖示，四張排下來就是一模一樣的模子，只有顏色不同。
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = Space.Screen),
    ) {

        // 收件匣放最上面：這是唯一「助理主動塞東西進來」的區塊，
        // 有新檔案時它得是打開這頁第一眼看到的東西。
        InboxCard(client)
        Spacer(Modifier.height(24.dp))

        // ── 面對面翻譯 ──────────────────────────────────────────────
        // 這一區跟這頁其他工具性質不同：它完全在手機上跑，電腦關機也能用。
        // 放在這裡是因為沒有更好的地方——底部分頁五個已經滿了。
        val context = LocalContext.current
        ToolSection(
            title = "面對面翻譯",
            // 卡片上只留一句「這是什麼」。怎麼拿手機、收音孔在哪、離線包要不要管，
            // 那些是**用的時候**才需要知道的事，收進「怎麼用」裡，不佔入口的版面
            subtitle = "兩個人看同一面螢幕，手機不用轉來轉去",
            howTo = "底下兩顆按鈕，誰要講就按自己那顆，譯文出現在同一面兩個人一起看。" +
                "收音孔在底部，講的人把底端朝自己會清楚一點。翻譯走 Google，" +
                "離線語言包只有斷網時才會用到，平常不必管它。",
        ) {
            PillButton("開始對話") {
                context.startActivity(Intent(context, TalkActivity::class.java))
            }
        }

        // ── 看電腦畫面 ──────────────────────────────────────────────
        ToolSection(
            title = "電腦畫面",
            subtitle = "抓當下的完整桌面，含雙螢幕",
        ) {
            PillButton(
                text = if (shot == null) "截一張" else "再截一張",
                loading = loading,
            ) {
                loading = true; error = null
                scope.launch {
                    client.screenshot()
                        .onSuccess { bytes ->
                            // 解碼一定要離開主執行緒。client.screenshot() 內部雖然
                            // 走 IO，onSuccess 這段卻是回到主執行緒跑的——雙螢幕
                            // 3840×1080 的 PNG 解起來要數百毫秒到數秒，畫面在那段
                            // 期間完全不動，長一點就是 ANR。CPU-bound 用 Default。
                            val bmp = withContext(Dispatchers.Default) {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                            // decodeByteArray 解不開時回 null 而不是拋錯。原本直接
                            // 指派過去，畫面就是按了按鈕、轉圈結束、什麼都沒出現，
                            // 沒有任何線索。
                            if (bmp == null) error = "圖片解不開，再截一張看看。"
                            else shot = bmp
                        }
                        // 這裡曾經直接顯示 Throwable.message，畫面上就會出現
                        // 「Failed to connect to /100.x.x.x:47362」
                        .onFailure { error = humanError(it) }
                    loading = false
                }
            }
            error?.let { Text(it, color = Palette.Danger, fontSize = Type.Meta) }
            shot?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "電腦畫面",
                    modifier = Modifier.fillMaxWidth().clip(Radii.Field),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }

        // ── 對話搜尋 ────────────────────────────────────────────────
        // 關鍵字用 rememberSaveable：切去別的分頁再回來不必重打（見 MainActivity
        // 的 SaveableStateProvider）。結果本身刻意**不**保存——SearchHit 存不進
        // Bundle，而且只留 searched 不留 hits 的話，切回來會顯示一句
        // 「什麼都沒找到」，那是假的。回來時就是一個填好關鍵字、還沒按過的搜尋框。
        var query by rememberSaveable { mutableStateOf("") }
        var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
        var searched by remember { mutableStateOf(false) }
        var searchError by remember { mutableStateOf<String?>(null) }
        ToolSection(
            title = "對話搜尋",
            subtitle = "翻以前跟助理講過的話",
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f)
                        .background(Palette.SurfaceHi, Radii.Field)
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = Type.Body, color = Palette.Text,
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(Palette.Accent),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text("關鍵字", color = Palette.TextFaint,
                                    fontSize = Type.Body)
                            }
                            inner()
                        }
                    },
                )
                PillButton("找", enabled = query.isNotBlank()) {
                    scope.launch {
                        searchError = null
                        client.search(query)
                            .onSuccess { hits = it; searched = true }
                            // 失敗一定要講。原本只有 onSuccess，斷線時按「找」
                            // 完全沒有反應——沒有結果、沒有錯誤、按鈕也沒變化，
                            // 看起來就像這顆按鈕壞了。
                            .onFailure {
                                searchError = humanError(it)
                                searched = false
                                hits = emptyList()
                            }
                    }
                }
            }
            searchError?.let {
                Text(it, color = Palette.Danger, fontSize = Type.Meta)
            }
            if (searched && hits.isEmpty()) {
                Text("什麼都沒找到。", color = Palette.TextFaint, fontSize = Type.Body)
            }
            hits.forEach { h ->
                Column(
                    Modifier.fillMaxWidth().background(Palette.SurfaceHi, Radii.Field)
                        .padding(12.dp),
                ) {
                    Text(
                        h.title, color = Palette.Accent, fontSize = Type.Meta,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        h.snippet, color = Palette.TextDim, fontSize = Type.Meta,
                        lineHeight = Type.MetaLine,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        // ── 服務控制台 ──────────────────────────────────────────────
        ServiceSection(client)

        // 各段落都以分隔線開頭，這條是收尾——少了它最後一段會看起來沒結束
        HorizontalDivider(color = Palette.Line, thickness = 0.6.dp)
        Text(
            "之後的工具會出現在這裡。想到什麼跟助理說。",
            color = Palette.TextFaint, fontSize = Type.Meta,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 28.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }   // 工具捲動 Column
    }   // fillMaxSize Column
}

/**
 * 工具區段：一條分隔線 ＋ 標題 ＋ 一行說明，底下是這個工具自己的內容。
 *
 * **沒有卡片、沒有圖示、沒有專屬顏色**，這三樣都是刻意拿掉的。
 *
 * 舊版每個工具都是一張卡，左上角一個彩色圓角方塊裡放一個 Material 圖示。問題有二：
 * 一是那些圖示（Translate／DesktopWindows／Search）隨便哪個 App 都能拿去用，
 * 換掉也沒人發現，佔了 44dp 卻沒說出任何這個 App 才有的事；二是那些顏色是跟
 * [Accents] 借的——「面對面翻譯」配的是課表紫、「電腦畫面」配的是鬧鐘橘。
 * 分頁色在日常頁擔的是「這是哪一類」的導覽資訊，借到這裡只剩裝飾，
 * 還會讓在日常頁學會「紫＝課表」的人到這頁看到一張紫色的翻譯卡。
 *
 * 層次改由形狀擔：上面的收件匣是卡片，這裡是段落，一眼就分得出主從。
 *
 * [howTo] 有給的話會多一行「怎麼用」，點了才展開。長說明不該擺在入口上——
 * 那是使用時才需要的資訊，放在這裡只會把段落撐長。
 */
@Composable
private fun ToolSection(
    title: String,
    subtitle: String,
    howTo: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    HorizontalDivider(color = Palette.Line, thickness = 0.6.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 「怎麼用」要跟**標題**同一列，不能跟整個標題塊置中對齊——標題塊有兩行，
        // 置中的話它會落在標題與說明的縫隙上，看起來像在標註說明那一行
        Column {
            // 標題列固定 48dp：只有「面對面翻譯」那段有「怎麼用」，而那顆的觸控目標
            // 會把所在列撐到 48dp。不給其他兩段同樣的高度的話，三段的標題與說明
            // 之間會一段鬆兩段緊，掃下來就是對不齊
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title, color = Palette.Text, fontSize = Type.Title,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                )
                if (howTo != null) {
                    Text(
                        if (open) "收起" else "怎麼用",
                        color = Palette.Accent, fontSize = Type.Meta,
                        // 這行字本身只有 18dp 高，撐開可點範圍但外觀不變
                        modifier = Modifier.minimumInteractiveComponentSize()
                            .clip(Radii.Chip)
                            .clickable { open = !open }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
            Text(
                subtitle, color = Palette.TextDim, fontSize = Type.Meta,
                lineHeight = Type.MetaLine,
            )
        }
        if (howTo != null && open) {
            Text(
                howTo, color = Palette.TextDim, fontSize = Type.Meta,
                lineHeight = Type.MetaLine,
                modifier = Modifier.background(Palette.SurfaceHi, Radii.Field).padding(12.dp),
            )
        }
        content()
    }
}

/**
 * 電腦上那個服務的控制台。
 *
 * **為什麼把重啟交給使用者按。** 助理改完伺服器程式要重啟才生效，而它以前是自己
 * 去跑重啟腳本：它的行程是伺服器的子孫，重啟必定連它一起收掉，畫面上就是講到
 * 一半突然斷線。按鈕放到這裡之後，斷線變成使用者自己按下去、預期會發生的事。
 */
@Composable
private fun ServiceSection(client: ButlerClient) {
    val scope = rememberCoroutineScope()
    var sys by remember { mutableStateOf<SystemStatus?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 兩段式確認。這個動作會中斷正在跑的工作，不該一下就按到。
    var arming by remember { mutableStateOf(false) }
    // 按下之後狀態要重拉才看得出版本有沒有換，用它觸發
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        client.systemStatus()
            .onSuccess { sys = it; err = null }
            .onFailure { err = humanError(it) }
    }

    ToolSection(
        title = "服務",
        subtitle = "電腦上那個程式的狀態",
        howTo = "助理改完自己的程式之後要重啟才會生效。它不會自己重啟——那會把它自己" +
            "一起關掉，你只會看到它講到一半斷線。所以它改好之後會跟你說一聲，由你在這裡按。" +
            "按下去不會立刻斷：它會等到目前這輪話講完才動手，之後 App 會自己接回來。",
    ) {
        sys?.let { s ->
            Text(
                "已經跑了 ${s.uptime}",
                color = Palette.Text, fontSize = Type.Body,
            )
            if (s.commit.isNotBlank()) {
                Text(
                    "版本 ${s.commit}　${s.subject}",
                    color = Palette.TextDim, fontSize = Type.Meta,
                    lineHeight = Type.MetaLine,
                )
            }
        }
        err?.let { Text(it, color = Palette.Danger, fontSize = Type.Meta) }

        if (!arming) {
            PillButton("重新啟動", loading = busy) { arming = true }
        } else {
            Text(
                "會中斷現在進行中的工作，助理手上沒做完的事不會自己接回去。",
                color = Palette.Danger, fontSize = Type.Meta, lineHeight = Type.MetaLine,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("算了") { arming = false }
                PillButton("確定重啟", danger = true, loading = busy) {
                    arming = false
                    busy = true
                    scope.launch {
                        client.restartSystem()
                            .onSuccess { err = null }
                            .onFailure { err = humanError(it) }
                        // 服務要幾秒才回得來。等一下再拉狀態，太早問只會拿到連線失敗，
                        // 畫面上就成了「按了重啟結果跳紅字」。
                        delay(12_000)
                        busy = false
                        reload++
                    }
                }
            }
        }
    }
}

/**
 * 動作按鈕。[loading] 時把文字換成轉圈，寬度不變才不會讓整列跳一下。
 *
 * 形狀是 [Radii.Field] 不是 [Radii.Chip]：卡片拆成段落之後這顆按鈕沒有邊框框著，
 * 全膠囊加大內縮會漲成一顆亮青色的胖藥丸，三段各一顆就變成整頁最搶眼的東西，
 * 而它們並不是這頁的重點。方角一點、矮一點，份量才回到它該有的位置。
 */
@Composable
private fun PillButton(
    text: String,
    enabled: Boolean = true,
    loading: Boolean = false,
    /** 會造成中斷或損失的動作用這個，顏色跟其他按鈕明顯不同才不會誤按。 */
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = Radii.Field,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) Palette.Danger else Palette.Accent,
            contentColor = Palette.Bg,
            disabledContainerColor = Palette.SurfaceHi,
            disabledContentColor = Palette.TextFaint,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 20.dp, vertical = 12.dp,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                Modifier.size(16.dp), color = Palette.Bg, strokeWidth = 2.dp,
            )
        } else {
            Text(text, fontSize = Type.Body, fontWeight = FontWeight.Bold)
        }
    }
}
