package dev.butlerkit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.LocalSession
import dev.butlerkit.app.net.SearchHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * 這條對話歸不歸「工作」那頁管。
 *
 * 助理有自己的分頁，牠那條對話在工作頁的清單裡列出來也切不過去
 * ——切過去只會看到別人的對話被端到工作頁上，而且送出的話會套到錯的人格。
 * 每加一個有自己分頁的對話，這裡就要跟著加一條。
 */
internal fun isCcConv(id: String) = id != ChatViewModel.DEFAULT_CONV

/** cc-bot 頁的對話清單。 */
private fun ChatState.ccConversations() = conversations.filter { isCcConv(it.id) }

/**
 * 抽屜內容：搜尋＋新對話＋對話清單。
 *
 * 搜尋放這裡而不是另開一頁——找舊訊息的目的九成是「跳回那條對話」，
 * 而這裡本來就是切對話的地方，找到直接點過去。
 */
@Composable
internal fun ConvDrawer(
    state: ChatState,
    client: ButlerClient,
    onNew: () -> Unit,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
) = Column(Modifier.fillMaxSize().padding(vertical = 12.dp)) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    Field(
        query, "搜尋訊息",
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        pad = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            if (query.isNotBlank()) {
                searching = true
                scope.launch {
                    client.search(query).onSuccess { r ->
                        hits = r.filter { isCcConv(it.convId) }
                    }
                    searching = false
                }
            }
        }),
    ) { q ->
        query = q
        if (q.isBlank()) hits = emptyList()
    }
    if (query.isNotBlank()) {
        SearchResults(hits, searching, onPick)
        return@Column
    }
    var picking by remember { mutableStateOf(false) }
    // 這兩列原本是「＋ 新對話」「⇱ 接電腦上的 session」「← 回對話清單」，
    // 前面掛的都是全形字元充當圖示：粗細與大小跟著系統字型跑，讀螢幕軟體會
    // 把它們唸成標點。抽屜是第二批清字元圖示時漏掉的一整塊
    DrawerAction(Icons.Filled.Add, "新對話", Palette.Accent, onNew)
    // 電腦上用 CLI 或官方 App 跑過的 session，助理本來看不到。點進去挑一個接回來，
    // 之後就能在手機上續談那條——不必回電腦前面。
    if (picking) {
        DrawerAction(
            Icons.AutoMirrored.Filled.ArrowBack, "回對話清單", Palette.TextDim,
        ) { picking = false }
    } else {
        DrawerAction(
            Icons.Filled.Computer, "接電腦上的 session", Palette.Accent,
        ) { picking = true }
    }
    HorizontalDivider(color = Palette.Line, thickness = 0.6.dp)
    if (picking) {
        LocalSessionPicker(client, onPick)
        return@Column
    }
    val convs = state.ccConversations()
    LazyColumn(Modifier.weight(1f)) {
        items(convs.size) { i ->
            val c = convs[i]
            // 待建立的新對話還不在清單上，這時任何一條都不該被標成「目前」
            val current = c.id == state.currentConv && !state.pendingNew
            Row(
                // 刪除鈕是 48dp，列要有 56dp 才容得下它又不會被上下的 padding
                // 疊成一條胖列。沒有刪除鈕的那列靠 heightIn 維持一樣高
                Modifier.fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .background(if (current) Palette.SurfaceHi else Palette.Surface)
                    .clickable { onPick(c.id) }
                    .padding(start = 18.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 這個 # 留著不換成圖示：它是 Discord 頻道的寫法，抽屜整個就是照
                // cc-bot 在 Discord 的頻道列表做的，換掉會失去那層對應
                Text(
                    "#", color = if (current) Palette.Accent else Palette.TextFaint,
                    fontSize = Type.Body,
                )
                Text(
                    c.title,
                    color = if (current) Palette.Text else Palette.TextDim,
                    fontSize = Type.Body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                if (!current) {
                    // 刪除不可逆，一鍵就刪太危險（誤觸一次整條對話對應就沒了）。
                    // 兩段式：第一下把鈕換成「確定刪除」，三秒內再按才真的刪，
                    // 不按就自己收回去——跟電腦版設定頁撤銷裝置的做法同一套，
                    // 不用會鎖畫面的對話框
                    var arming by remember(c.id) { mutableStateOf(false) }
                    LaunchedEffect(arming) {
                        if (arming) { delay(3000); arming = false }
                    }
                    if (arming) {
                        Text(
                            "確定刪除",
                            color = Palette.Danger, fontSize = Type.Tiny,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(Radii.Chip)
                                .border(1.dp, Palette.Danger, Radii.Chip)
                                .clickable(role = Role.Button) { arming = false; onDelete(c.id) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    } else {
                        IconBtn(Icons.Filled.Close, "刪掉這條對話") { arming = true }
                    }
                }
            }
        }
    }
}

/** 抽屜裡的一列操作：圖示＋文字，整列可點，56dp 高（跟下面的對話列對齊）。 */
@Composable
private fun DrawerAction(
    icon: ImageVector,
    text: String,
    tint: Color,
    onClick: () -> Unit,
) = Row(
    Modifier.fillMaxWidth()
        .heightIn(min = 56.dp)
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 18.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    // 文字已經把事情講完了，圖示不重複描述，所以 contentDescription 給 null
    Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
    Text(
        text, color = tint, fontSize = Type.Body, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 12.dp),
    )
}

/**
 * 搜尋結果列表。點一則就切到那條對話。
 *
 * 只切對話、不捲到那一則——訊息沒有穩定的行號可跳，硬做會跳錯位置比不跳更煩。
 */
@Composable
private fun ColumnScope.SearchResults(
    hits: List<SearchHit>,
    searching: Boolean,
    onPick: (String) -> Unit,
) {
    if (searching) {
        Text(
            "找找看…", color = Palette.TextFaint, fontSize = Type.Meta,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
        return
    }
    if (hits.isEmpty()) {
        Text(
            "按鍵盤上的搜尋鍵開始找。", color = Palette.TextFaint, fontSize = Type.Tiny,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
        return
    }
    LazyColumn(Modifier.weight(1f)) {
        items(hits.size) { i ->
            val h = hits[i]
            Column(
                Modifier.fillMaxWidth().clickable { onPick(h.convId) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(h.title, color = Palette.Accent, fontSize = Type.Tiny, maxLines = 1)
                Text(
                    h.snippet, color = Palette.TextDim, fontSize = Type.Meta,
                    lineHeight = Type.MetaLine, maxLines = 3,
                )
            }
        }
    }
}

/** 一頁抓幾筆。伺服器要為每筆開檔讀前幾行，一次抓太多會讓清單開得很慢。 */
private const val SESSION_PAGE = 40

/**
 * 電腦上既有 session 的挑選清單。
 *
 * 點一則就接成新對話並切過去。接管只是把 session id 記到那條對話上，
 * 真正的 resume 留到下一則訊息送出時才發生——點錯不會白開一個 CC 行程。
 *
 * 分頁載入：之前寫死只拿前 60 筆，電腦上現有 130 幾個 session，再舊的根本
 * 點不到。改成滑到底自動續拉，直到伺服器說沒有了為止。
 */
@Composable
private fun ColumnScope.LocalSessionPicker(
    client: ButlerClient,
    onPicked: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<LocalSession>() }
    var loaded by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }

    // 以 page 當 key：底部的 loader 一進畫面就把 page 加一，這裡跟著抓下一頁
    LaunchedEffect(page) {
        loading = true
        client.listLocalSessions(offset = page * SESSION_PAGE, limit = SESSION_PAGE)
            .onSuccess { rows.addAll(it.rows); hasMore = it.hasMore }
            .onFailure { hasMore = false }
        loading = false
        loaded = true
    }

    if (!loaded) {
        Text(
            "翻電腦上的紀錄…", color = Palette.TextFaint, fontSize = Type.Meta,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
        return
    }
    if (rows.isEmpty()) {
        Text(
            "沒有還沒接管的 session。", color = Palette.TextFaint, fontSize = Type.Tiny,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
        return
    }
    LazyColumn(Modifier.weight(1f)) {
        items(rows.size) { i ->
            val s = rows[i]
            val loading = busy == s.sessionId
            Column(
                Modifier.fillMaxWidth()
                    .clickable(enabled = busy == null) {
                        busy = s.sessionId
                        scope.launch {
                            client.adoptSession(s.sessionId).onSuccess(onPicked)
                            busy = null
                        }
                    }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    s.title, color = if (loading) Palette.TextFaint else Palette.Text,
                    fontSize = Type.Meta, lineHeight = Type.MetaLine, maxLines = 2,
                )
                Text(
                    listOfNotNull(
                        if (loading) "接手中…" else null,
                        shortPath(s.cwd),
                        relTime(s.mtime),
                        s.branch.takeIf { it.isNotBlank() },
                        if (s.sidechain) "子代理" else null,
                    ).joinToString(" · "),
                    color = Palette.TextFaint, fontSize = Type.Tiny, maxLines = 1,
                )
            }
        }
        // 這一列滑進畫面就代表看到底了，順勢抓下一頁。
        // 沒有另外算捲動位置：loader 被組合出來本身就是「到底了」最準的訊號。
        if (hasMore) {
            item {
                LaunchedEffect(rows.size) { if (!loading) page += 1 }
                Text(
                    "翻更舊的…（已 ${rows.size} 筆）",
                    color = Palette.TextFaint, fontSize = Type.Tiny,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
        } else {
            item {
                Text(
                    "到底了，共 ${rows.size} 筆",
                    color = Palette.TextFaint, fontSize = Type.Tiny,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/** 工作目錄只留最後兩層：手機一行放不下 `C:\Users\you\Desktop\...` 那種長度。 */
private fun shortPath(p: String): String? {
    if (p.isBlank()) return null
    val parts = p.trimEnd('\\', '/').split('\\', '/').filter { it.isNotBlank() }
    return if (parts.size <= 2) p else parts.takeLast(2).joinToString("\\")
}

/** 相對時間。跨過一週就直接給日期，「37 天前」沒人算得出是哪天。 */
private fun relTime(epochSec: Double): String? {
    if (epochSec <= 0) return null
    val ms = (epochSec * 1000).toLong()
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000 -> "剛剛"
        diff < 3_600_000 -> "${diff / 60_000} 分鐘前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小時前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000} 天前"
        else -> android.text.format.DateFormat.format("M/d", ms).toString()
    }
}
