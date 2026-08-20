package dev.butlerkit.app.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.data.InboxRepo
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.LocalSession
import dev.butlerkit.app.net.OfferedFile
import dev.butlerkit.app.net.SearchHit
import dev.butlerkit.app.voice.DictateKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 捲到某一項的**尾端**用的偏移量（px）。
 *
 * LazyColumn 沒有「對齊這一項的底部」這種 API，只能給一個大到穩穩超過任何
 * 單則訊息高度的偏移，讓它自己 clamp 在列表最底。一則訊息不可能高過十萬 px。
 */
private const val TO_ITEM_END = 100_000

/** 判定「已經在最底下」的容差（px）。差幾像素還算貼底，否則跟隨會莫名斷掉。 */
private const val BOTTOM_SLACK = 24

/**
 * 畫面上真正看得到的串流文字。
 *
 * **判斷「有沒有串流那一項」一律用這個，不要直接看 `streaming` 是否為空。**
 * 清掉標記之後可能變成空字串——某一段串流只夾帶 `[[DONE]]` 這類東西時就會發生——
 * 而這件事決定了 LazyColumn 到底有沒有多出那一項。捲動邏輯若拿 `streaming` 判斷、
 * 清單那邊拿清乾淨的結果判斷，兩邊就會差一格，捲動目標指向不存在的索引，
 * 再配上刻意很大的 [TO_ITEM_END]，落點完全不可預期。
 */
private val ChatState.previewText: String get() = cleanMarkers(streaming)

/**
 * 聊天畫面。兩個分頁共用同一份實作，差別只在 [multiConv]：
 *   助理頁（false）＝單一聊天視窗，沒有對話清單，標題就是「助理」；
 *   cc-bot 頁（true）＝完整的多對話管理（左側抽屜、新增、刪除）。
 * 資料層不分家——都是 ChatViewModel 以 conv_id 分流的同一條事件流。
 */
@Composable
fun ChatScreen(
    state: ChatState,
    title: String,
    multiConv: Boolean,
    client: ButlerClient,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAnswer: (String, String, String?) -> Unit,
    onSwitchConv: (String) -> Unit,
    onNewConv: () -> Unit,
    onDeleteConv: (String) -> Unit,
    onAttach: (Uri) -> Unit,
    onRemoveAttach: (String) -> Unit,
    onConvSettings: (String?, String?) -> Unit,
) {
    // 每條對話各自一份捲動狀態。共用一份的話，從一條長對話切到另一條短的，
    // 新對話會直接停在上一條的捲動位置——短的那條根本沒那麼多內容，看到的是空白。
    // 下面的 landed 也綁同一個 key，兩個要一起換：位置重置了但 landed 還留著 true，
    // 就不會走「進畫面瞬移到底」那條路，切過去看到的是最上面的舊訊息。
    val listState = remember(state.currentConv) { LazyListState() }
    var input by remember { mutableStateOf("") }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 系統檔案挑選器。用 OpenMultipleDocuments 而不是 GetContent：
    // 前者能一次挑多張照片，且走 SAF 拿得到穩定的檔名。
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> uris.forEach(onAttach) }

    // 用 previewText 而不是 streaming：理由見它的說明，兩邊判斷不一致會讓
    // 捲動目標指向不存在的索引。
    val preview = remember(state.streaming) { state.previewText }
    val lastIndex = state.items.size + (if (preview.isNotEmpty()) 1 else 0) - 1

    // 「人是不是正停在最底下」。跟隨與否全看這個，不看有沒有新訊息。
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + BOTTOM_SLACK
        }
    }

    // 只有「跟隨中」才會自動捲。
    //
    // 原本是每有新內容就無條件捲到底，於是往上翻歷史會被硬拉回來，這在它一邊
    // 生成一邊更新時等於完全沒辦法讀舊訊息。跟隨只由**使用者自己動手**決定：
    // 一碰就停下（follow=false），滑回底部放手才恢復。程式自己捲的不算，
    // 否則自動捲到一半內容變長就會把自己關掉。
    // 一樣綁對話：在舊對話裡往上翻歷史會把 follow 關掉，那個「關掉」不該跟著人
    // 帶到下一條對話——切過去之後新訊息不會自動跟，看起來像卡住了。
    var follow by remember(state.currentConv) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { i ->
            if (i is DragInteraction.Start) follow = false
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }              // 手放開、慣性也停了才判斷
            .collect { if (atBottom) follow = true }
    }

    // **第一次是瞬移不是動畫**：進畫面時整串歷史已經在手上，播動畫等於當著人的面
    // 從最舊一路刷到最新，切頁面就閃一次，很吵。
    //
    // 捲的目標是最後一項的**尾巴**不是開頭——一則長回覆用 scrollToItem(lastIndex)
    // 只會把它的第一行對齊畫面頂端，內容全在下面看不到，人得再自己往下滑。
    // 生成中（streaming 每幾個字就更新一次）用瞬移：動畫會被下一次更新打斷，
    // 看起來就是畫面在抖。
    var landed by remember(state.currentConv) { mutableStateOf(false) }
    LaunchedEffect(state.items.size, state.streaming, state.status) {
        if (lastIndex < 0) return@LaunchedEffect
        when {
            !landed -> {
                listState.scrollToItem(lastIndex, TO_ITEM_END)
                landed = true
            }
            !follow -> Unit
            state.streaming.isNotEmpty() -> listState.scrollToItem(lastIndex, TO_ITEM_END)
            else -> listState.animateScrollToItem(lastIndex, TO_ITEM_END)
        }
    }

    // 鍵盤展開時列表被壓矮，底下的訊息會被鍵盤蓋掉。imePadding 只讓輸入列讓位，
    // 不會把已經捲好的內容往上帶。這裡跟著鍵盤動畫的每一幀重新貼底——用瞬移而非
    // 動畫，讓內容跟鍵盤是同一個動作，慢半拍會看成畫面在抖。
    // 一樣看 follow：正在讀舊訊息時點開鍵盤不該把人踢到最底下。
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (lastIndex >= 0 && follow) listState.scrollToItem(lastIndex, TO_ITEM_END)
    }

    val body = @Composable {
        ChatBody(
            state, listState, input,
            title = title,
            multiConv = multiConv,
            client = client,
            onAnswer = onAnswer,
            onInput = { input = it },
            onSend = { onSend(input); input = "" },
            onStop = onStop,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onResend = onSend,
            onNewConv = onNewConv,
            onPickFile = { picker.launch(arrayOf("*/*")) },
            onRemoveAttach = onRemoveAttach,
            onConvSettings = onConvSettings,
        )
    }

    if (multiConv) {
        // 對話清單＝左側抽屜（照 cc-bot 在 Discord 的頻道列表）：
        // 左滑或點頂欄開，＋新對話置頂，目前對話高亮
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = Palette.Surface,
                    modifier = Modifier.widthIn(max = 300.dp),
                ) {
                    ConvDrawer(
                        state = state,
                        client = client,
                        onNew = {
                            scope.launch { drawerState.close() }
                            onNewConv()
                        },
                        onPick = {
                            scope.launch { drawerState.close() }
                            onSwitchConv(it)
                        },
                        onDelete = onDeleteConv,
                    )
                }
            },
            content = body,
        )
    } else {
        body()
    }
}

/** cc-bot 頁的對話清單：排掉助理的專屬對話，那條歸助理頁管。 */
private fun ChatState.ccConversations() =
    conversations.filter { it.id != ChatViewModel.DEFAULT_CONV }

/**
 * 抽屜內容：搜尋＋新對話＋對話清單。
 *
 * 搜尋放這裡而不是另開一頁——找舊訊息的目的九成是「跳回那條對話」，
 * 而這裡本來就是切對話的地方，找到直接點過去。
 */
@Composable
private fun ConvDrawer(
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
    BasicTextField(
        value = query,
        onValueChange = { q ->
            query = q
            if (q.isBlank()) hits = emptyList()
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
            // 輸入框一律 Radii.Field，跟 Components.kt 的 Field() 同一個形狀
            .background(Palette.SurfaceHi, Radii.Field)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = Type.Body, color = Palette.Text,
        ),
        singleLine = true,
        cursorBrush = SolidColor(Palette.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            if (query.isNotBlank()) {
                searching = true
                scope.launch {
                    client.search(query).onSuccess { r ->
                        // 助理的專屬對話歸助理頁管，這裡列出來也切不過去
                        hits = r.filter { it.convId != ChatViewModel.DEFAULT_CONV }
                    }
                    searching = false
                }
            }
        }),
        decorationBox = { inner ->
            Box {
                if (query.isEmpty()) {
                    Text("搜尋訊息", color = Palette.TextFaint, fontSize = Type.Body)
                }
                inner()
            }
        },
    )
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
                    // 原本是一個「×」字元，可點範圍只有 20dp 高——而且這是刪除，
                    // 誤觸的代價比別的鈕都大
                    IconBtn(Icons.Filled.Close, "刪掉這條對話") { onDelete(c.id) }
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
        .clickable(onClick = onClick)
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

@Composable
private fun ChatBody(
    state: ChatState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    input: String,
    title: String,
    multiConv: Boolean,
    client: ButlerClient,
    onAnswer: (String, String, String?) -> Unit,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenDrawer: () -> Unit,
    onResend: (String) -> Unit,
    onNewConv: () -> Unit,
    onPickFile: () -> Unit,
    onRemoveAttach: (String) -> Unit,
    onConvSettings: (String?, String?) -> Unit,
) {
    // cc-bot 頁一條對話都還沒有時，currentConv 會停在助理那條——
    // 直接顯示會把助理的私人對話端到 cc-bot 頁上，所以擋掉改顯示空狀態
    val noCcConv = multiConv && !state.pendingNew &&
        state.currentConv == ChatViewModel.DEFAULT_CONV
    if (noCcConv) {
        NoConvPlaceholder(title, onNewConv)
        return
    }

    // 助理在等你回答的時候，整個回合就停在那一題上——這時候從底下送出的訊息只會
    // 排進佇列乾等到它逾時，人看到的是「我打了字卻什麼都沒發生」。所以有題目
    // 待答時，輸入列直接改成回答那一題，不必先滑上去找那張卡。
    //
    // 只有「伺服器真的停著在等」的那種才鎖輸入列（破壞性指令確認）。附在訊息
    // 上的選項不鎖：那種沒有人在等，直接打字就是正常送出，而且它會一直留在
    // 軌跡上——鎖了的話輸入列會永遠停在回答模式。
    val waitingAsk = state.items.lastOrNull {
        it is TraceItem.AskItem && it.pending && !it.req.isInline
    } as TraceItem.AskItem?

    // 串流預覽一律看「清掉控制標記之後」還剩什麼：助理有時整輪只吐一個 [[DONE]]，
    // 拿原始字串判斷會畫出一顆沒有內容的空氣泡。
    val preview = remember(state.streaming) { state.previewText }

    var showConvSettings by remember { mutableStateOf(false) }
    if (showConvSettings) {
        ConvSettingsDialog(
            state = state,
            onApply = onConvSettings,
            onDismiss = { showConvSettings = false },
        )
    }

    Column(Modifier.fillMaxSize().background(Palette.Bg).imePadding()) {
        ChatTopBar(state, title, multiConv, onOpenDrawer) { showConvSettings = true }
        HorizontalDivider(color = Palette.Line, thickness = 0.6.dp)

        if (state.items.isEmpty() && preview.isEmpty()) {
            // 空對話：大臉問候。這是桌寵存在感最強的時刻。
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                PetFace(state.pet, 120.dp)
                // 連得上就不寫字，空畫面上一顆吉祥物就夠了。
                // 連不上才印，而且印真正的原因：頂欄那行紅字是 maxLines=1，長訊息的
                // 尾巴會被截掉（明文被擋時最關鍵的那半句正好在後面），這裡有整片空間。
                // 別寫死「Tailscale 開了沒？」——連不上的原因不只一種，照著那句去查
                // Tailscale 會走一段冤枉路。
                if (!state.connected) {
                    Text(
                        "……" + (state.connError ?: "連不上電腦。Tailscale 開了沒？"),
                        color = Palette.TextDim, fontSize = Type.Body,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 16.dp, start = Space.Screen, end = Space.Screen),
                    )
                }
            }
        } else {
            // 最新一則回覆旁的桌寵是「活的」（跟著實際狀態變表情），
            // 歷史訊息旁的是安靜的 Idle——像通訊軟體的頭像，但最新那顆會演戲
            val lastReply = state.items.indexOfLast { it is TraceItem.Reply }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    horizontal = Space.Screen, vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.items.size) { i ->
                    val live = i == lastReply && preview.isEmpty()
                    TraceRow(
                        state.items[i],
                        if (live) state.pet else PetMood.Idle,
                        client = client,
                        onResend = onResend,
                        onAnswer = onAnswer,
                    )
                }
                if (preview.isNotEmpty()) {
                    item { BotRow(state.pet) { MarkdownText(preview) } }
                }
            }
        }

        if (state.busy) StatusLine(state)

        InputDock(
            value = input,
            busy = state.busy,
            answering = waitingAsk != null,
            attachments = state.attachments,
            uploading = state.uploading,
            uploadError = state.uploadError,
            onChange = onInput,
            onSend = {
                if (waitingAsk != null) {
                    onAnswer(waitingAsk.req.askId, "", input)
                    onInput("")
                } else {
                    onSend()
                }
            },
            onStop = onStop,
            onPickFile = onPickFile,
            onRemoveAttach = onRemoveAttach,
        )
    }
}

/** cc-bot 頁還沒有任何對話時的空畫面。 */
@Composable
private fun NoConvPlaceholder(title: String, onNewConv: () -> Unit) = Column(
    Modifier.fillMaxSize().background(Palette.Bg),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
) {
    PetFace(PetMood.Idle, 96.dp, Modifier.padding(bottom = 14.dp))
    Text(title, color = Palette.Text, fontSize = Type.Title,
        fontWeight = FontWeight.Medium)
    Text(
        "這裡放多對話。助理那頁是單一視窗，兩邊互不干擾。",
        color = Palette.TextDim, fontSize = Type.Meta,
        lineHeight = Type.MetaLine,
        modifier = Modifier.padding(top = 8.dp, start = 32.dp, end = 32.dp),
    )
    Button(
        onClick = onNewConv,
        modifier = Modifier.padding(top = 20.dp),
        shape = Radii.Field,
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.Accent, contentColor = Palette.Bg,
        ),
    ) {
        Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
        Text("新對話", fontSize = Type.Body, modifier = Modifier.padding(start = 6.dp))
    }
}

/** 緊湊頂欄：☰ 開抽屜（僅多對話頁）＋標題＋⚙ 對話設定＋連線點。 */
@Composable
private fun ChatTopBar(
    state: ChatState,
    title: String,
    multiConv: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // 助理頁固定顯示「助理」；cc-bot 頁顯示當前對話的標題
    val currentTitle = when {
        !multiConv -> title
        state.pendingNew -> "新對話"    // 還沒落地，清單裡也還沒有它
        else -> state.conversations
            .firstOrNull { it.id == state.currentConv }?.title ?: state.currentConv
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = Space.Screen)) {
        Row(
            // heightIn 不是 height：系統字級調大時標題會需要更高的一列，
            // 寫死高度的話字會被上下切掉
            Modifier.fillMaxWidth().heightIn(min = 50.dp)
                .let { if (multiConv) it.clickable(onClick = onOpenDrawer) else it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (multiConv) {
                Icon(
                    Icons.Filled.Menu, "對話清單",
                    tint = Palette.TextDim, modifier = Modifier.size(22.dp),
                )
            }
            Text(
                currentTitle, fontSize = Type.Head,
                fontWeight = FontWeight.Bold, color = Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = if (multiConv) 12.dp else 0.dp)
                    .weight(1f),
            )
            state.connError?.let {
                Text(it, fontSize = Type.Tiny, color = Palette.Danger,
                    fontFamily = FontFamily.SansSerif,
                    modifier = Modifier.padding(end = 8.dp), maxLines = 1)
            }
            // 用文字而不是一顆綠點：點只有顏色，色弱看不出差別
            Text(
                if (state.connected) "已連線" else "斷線",
                fontSize = Type.Tiny,
                fontFamily = FontFamily.SansSerif,
                color = if (state.connected) Palette.TextFaint else Palette.Danger,
                modifier = Modifier.padding(end = 6.dp),
            )
            // 對話設定（模型／思考強度／context）。整條頂欄本身是開抽屜的觸控區，
            // 這顆要自己的 clickable 才不會被外層吃掉。
            IconBtn(Icons.Filled.Settings, "對話設定", onClick = onOpenSettings)
        }
        // 雙規線：粗上細下，報頭的落款
        Box(Modifier.fillMaxWidth().height(2.dp).background(Palette.Text))
        Spacer(Modifier.height(2.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
    }
}

/**
 * 對話設定面板：cc-bot 的 `/status`＋`/model_session`＋`/effort_session` 合成一頁。
 *
 * 這些在 Discord 是三個要打字的指令，在這裡是頂欄一顆⚙。**覆寫是 per-conv 的**——
 * 「這條對話用 Opus 想深一點、其他維持 Sonnet」在 cc-bot 上是常用操作，
 * App 端先前完全沒有入口，只能改全域預設把所有對話一起換掉。
 */
@Composable
private fun ConvSettingsDialog(
    state: ChatState,
    onApply: (String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = state.convStatus
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("關閉", color = Palette.Accent) }
        },
        containerColor = Palette.Surface,
        titleContentColor = Palette.Text,
        textContentColor = Palette.TextDim,
        title = {
            Text("這條對話", fontSize = Type.Title, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (cs == null) {
                    Text("還沒拿到狀態。連上電腦之後再開一次。", fontSize = Type.Meta)
                    return@Column
                }
                CtxBar(cs.ctxTokens, cs.ctxLimit)
                Column {
                    Text("工作目錄", color = Palette.TextDim, fontSize = Type.Tiny)
                    Text(
                        cs.cwd.ifBlank { "（預設）" },
                        color = Palette.Text, fontSize = Type.Tiny,
                        fontFamily = FontFamily.Monospace, maxLines = 2,
                    )
                }
                ChipPicker(
                    label = "模型",
                    options = state.settings?.models.orEmpty(),
                    override = cs.modelOverride,
                    effective = cs.model,
                ) { onApply(it, null) }
                ChipPicker(
                    label = "思考強度",
                    options = state.settings?.efforts.orEmpty(),
                    override = cs.effortOverride,
                    effective = cs.effort,
                ) { onApply(null, it) }
            }
        },
    )
}

/** context 用量長條。分母由伺服器給（隨模型變），不在前端寫死。 */
@Composable
private fun CtxBar(tokens: Int, limit: Int) = Column {
    val frac = if (limit > 0) (tokens.toFloat() / limit).coerceIn(0f, 1f) else 0f
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("context", color = Palette.TextDim, fontSize = Type.Tiny)
        Text(
            "%,d / %,d".format(tokens, limit),
            color = Palette.TextDim, fontSize = Type.Tiny,
            fontFamily = FontFamily.Monospace,
        )
    }
    Box(
        Modifier.fillMaxWidth().height(4.dp).padding(top = 1.dp)
            .background(Palette.SurfaceHi, Radii.Chip),
    ) {
        Box(
            Modifier.fillMaxWidth(frac).fillMaxHeight()
                // 逼近 0.85 就會觸發自動壓縮，先變色當預告
                .background(if (frac > 0.85f) Palette.Danger else Palette.Accent, Radii.Chip),
        )
    }
}

/**
 * 一列可橫捲的選項晶片。第一顆固定是「跟隨預設」（送空字串＝清除覆寫），
 * 它高亮時把目前生效的值寫在括號裡——不然使用者只知道「沒覆寫」，
 * 不知道實際跑的是什麼。
 */
@Composable
private fun ChipPicker(
    label: String,
    options: List<String>,
    override: String,
    effective: String,
    onPick: (String) -> Unit,
) = Column {
    Text(label, color = Palette.TextDim, fontSize = Type.Tiny)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val all = listOf("") + options
        all.forEach { opt ->
            val on = opt == override
            Text(
                if (opt.isEmpty()) "跟隨預設${if (override.isEmpty()) "（$effective）" else ""}"
                else shortModel(opt),
                color = if (on) Palette.Bg else Palette.Text,
                fontSize = Type.Tiny,
                maxLines = 1,
                modifier = Modifier
                    // clip 要在 clickable 之前，否則水波紋是方形、蓋過膠囊的圓角
                    .clip(Radii.Chip)
                    .background(if (on) Palette.Accent else Palette.SurfaceHi)
                    .clickable { onPick(opt) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** `claude-sonnet-4-6` → `sonnet-4-6`。晶片放不下完整型號，而前綴每個都一樣。 */
private fun shortModel(m: String) = m.removePrefix("claude-")

/** 頭像欄寬：過程資訊照這個縮排，跟正文對齊在同一條縱線上。 */
private val AvatarW = 42.dp

/**
 * 桌寵的一列：平鋪式（照 cc-bot 在 Discord 的樣子）——
 * 左側頭像、右側內容**用滿寬度**，不裝氣泡。長 Markdown 內容氣泡會浪費寬度。
 */
@Composable
private fun BotRow(mood: PetMood, content: @Composable () -> Unit) = Row(
    Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top,
) {
    PetFace(mood, 32.dp, Modifier.padding(top = 1.dp))
    Column(Modifier.weight(1f).padding(start = 10.dp)) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TraceRow(
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
                    Surface(
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
                    if (item.queued || item.dropped) {
                        Text(
                            if (item.dropped) "已取消，這則沒有送進去"
                            else "排隊中，它還在忙上一輪",
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

    // 出錯：桌寵本人擺出 >< 臉站在訊息旁邊
    is TraceItem.ErrorItem -> BotRow(PetMood.Error) {
        Text(
            item.detail,
            color = Palette.Danger, fontSize = Type.Meta,
            lineHeight = Type.MetaLine,
        )
    }
}

/**
 * 思考塊：**要跟「助理對你說的話」一眼分得開**。
 *
 * 原本只靠顏色淡一點來區分，使用者實測回報分不清哪些是思考、哪些是要給他看的
 * 輸出——顏色是最弱的訊號，一整段佔滿寬度的文字看起來就是正文。改成左側一條
 * 直線加「想」標籤：**有直線的是助理在自言自語，有頭像的才是它對你說的話**。
 *
 * 預設收摺三行。思考現在是全文送過來的（伺服器不再截在 220 字），不收摺的話
 * 一輪下來畫面會被自言自語灌爆；但「點開看全部」得真的看得到，所以用
 * onTextLayout 問排版結果有沒有溢出，只在真的有被藏起來時才顯示提示——
 * 寫死提示會讓沒東西可展開的短思考也騙人。
 */
@Composable
private fun ThinkingRow(item: TraceItem.Thinking) {
    var open by remember { mutableStateOf(false) }
    var clipped by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .padding(start = AvatarW, top = 3.dp, bottom = 3.dp)
            .height(IntrinsicSize.Min)
            .clickable { open = !open },
    ) {
        // 這條線就是「這段不是對你說的」的記號
        Box(Modifier.width(2.dp).fillMaxHeight().background(Palette.Line, Radii.Chip))
        Column(Modifier.padding(start = 10.dp)) {
            Text(
                "想",
                color = Palette.TextFaint,
                fontSize = Type.Tiny,
                fontWeight = FontWeight.Medium,
            )
            Text(
                item.text,
                color = Palette.TextFaint,
                fontSize = Type.Tiny,
                lineHeight = Type.MetaLine,
                maxLines = if (open) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!open) clipped = it.hasVisualOverflow },
                modifier = Modifier.padding(top = 1.dp),
            )
            if (clipped) {
                Text(
                    if (open) "收起來" else "點開看全部",
                    color = Palette.TextDim,
                    fontSize = Type.Tiny,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/**
 * 一個階段（cc-bot 摺疊風格）：
 *   說明文字當主行
 *   -# 讀 2 個檔案・執行 1 個指令・新增 x.py +28 −0   ← 小灰字統計，點了展開明細
 */
@Composable
private fun StageRow(s: TraceItem.Stage) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = AvatarW)) {
        if (s.text.isNotBlank()) {
            Text(
                s.text,
                color = Palette.TextDim,
                fontSize = Type.Meta,
                lineHeight = Type.MetaLine,
            )
        }
        val summary = s.summary
        if (summary.isNotBlank()) {
            Text(
                summary,
                color = Palette.TextFaint,
                fontSize = Type.Tiny,
                modifier = Modifier.padding(top = 2.dp)
                    .clickable { open = !open },
            )
        }
        if (open) {
            s.tools.forEach { c ->
                Text(
                    "${c.icon} ${c.tool}  ${c.summary}",
                    color = Palette.TextFaint,
                    fontSize = Type.Tiny,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 10.dp, top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusLine(state: ChatState) = Column(
    Modifier.fillMaxWidth()
        .padding(start = Space.Screen + AvatarW, end = Space.Screen)
        .padding(vertical = 6.dp),
) {
    StatusHead(state)
    // 背景子代理：伺服器每兩秒把進行中的清單帶在 status.bg 裡。不畫出來的話，
    // 主回合在等子代理時畫面只剩「想一下」，看起來像卡住了。
    state.status?.bg.orEmpty().forEach { desc ->
        // 原本前面掛一個「⚙」字元。「背景：」三個字已經把話說完了，
        // 再擺一個可以換成任何符號的圖示只是裝飾
        Text(
            "背景：$desc",
            color = Palette.TextFaint,
            fontSize = Type.Tiny,
            lineHeight = Type.TinyLine,
            maxLines = 2,
            modifier = Modifier.padding(start = 15.dp, top = 2.dp),
        )
    }
}

@Composable
private fun StatusHead(state: ChatState) = Row(
    verticalAlignment = Alignment.CenterVertically,
) {
    CircularProgressIndicator(
        Modifier.size(11.dp), color = Palette.Accent, strokeWidth = 1.5.dp,
    )
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
    Text(
        buildString {
            append(" ")
            append(note ?: phase ?: tail ?: "想一下")
            if (secs > 3) append("  ${secs}s")
        },
        color = Palette.TextFaint,
        fontSize = Type.Tiny,
        maxLines = 1,
    )
}

/**
 * 一體式輸入列：貼在畫面底部，細線與內容區隔開。
 * 送出鈕是 44dp 圓鈕（WCAG 最小觸控目標），忙碌時變成停止鈕。
 * 不做 navigationBarsPadding——外層 Root Scaffold 的 padding 已含系統列，
 * 再墊一次就是輸入列懸空的三層 insets 事故之一。
 */
@Composable
private fun InputDock(
    value: String, busy: Boolean, answering: Boolean,
    attachments: List<Attachment>, uploading: List<String>, uploadError: String?,
    onChange: (String) -> Unit,
    onSend: () -> Unit, onStop: () -> Unit,
    onPickFile: () -> Unit, onRemoveAttach: (String) -> Unit,
) = Column(Modifier.fillMaxWidth()) {
    HorizontalDivider(color = Palette.Line, thickness = 0.6.dp)
    AttachmentStrip(attachments, uploading, uploadError, onRemoveAttach)
    // 只挑了檔案沒打字也能送——「看一下這張圖」很多時候不需要多說什麼。
    // 但回答提問時只認文字：附件的路徑當作答案送過去沒有意義。
    val canSend = if (answering) value.isNotBlank() else {
        value.isNotBlank() || attachments.isNotEmpty()
    }
    Row(
        Modifier.fillMaxWidth().background(Palette.Bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 附加檔案。放輸入框左邊（照通訊軟體的慣例），48dp 觸控目標。
        // 方框墨線——印刷品的按鈕是框，不是浮著的圓
        Box(
            Modifier.size(48.dp)
                .border(1.dp, Palette.Line)
                .clickable(onClick = onPickFile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add, "附加檔案",
                tint = Palette.TextDim, modifier = Modifier.size(20.dp),
            )
        }
        // 講話填字。接在現有文字後面，不覆蓋——講一段補打幾個字再講是常見用法
        DictateKey(onText = { onChange(value + it) })
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f)
                .background(Palette.SurfaceHi, Radii.Bubble)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = Type.Body, lineHeight = Type.BodyLine, color = Palette.Text,
            ),
            maxLines = 5,
            cursorBrush = SolidColor(Palette.Accent),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            if (answering) "回答上面那題" else "有事就說",
                            color = if (answering) Palette.Accent.copy(alpha = 0.7f)
                            else Palette.TextFaint,
                            fontSize = Type.Body,
                        )
                    }
                    inner()
                }
            },
        )
        // 忙碌時停止鍵**額外出現**，不取代送出鍵——
        // 排隊機制的前提就是「忙的時候還能繼續講」，兩顆共用一個位置等於把它關掉了
        if (busy) {
            Box(
                Modifier.size(48.dp)
                    .border(1.dp, Palette.Danger)
                    .clickable(onClick = onStop),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Stop, "停下來",
                    tint = Palette.Danger, modifier = Modifier.size(20.dp),
                )
            }
        }
        // 能送＝墨底反白（報紙的實色鈕）；不能送＝虛框。朱紅留給警示不給主動作
        Box(
            Modifier.size(48.dp)
                .background(if (canSend) Palette.Text else Palette.Bg)
                .border(1.dp, if (canSend) Palette.Text else Palette.Line)
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send, "送出",
                tint = if (canSend) Palette.Bg else Palette.TextFaint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 輸入列上方的附件列：已傳好的檔案、正在傳的、以及失敗原因。
 * 檔案在**按送出之前**就已經傳到電腦上了，所以這裡顯示的每一個都是既成事實，
 * 點 × 只是不要附在這則訊息上（電腦上那份留著，不特地去刪）。
 */
@Composable
private fun AttachmentStrip(
    attachments: List<Attachment>,
    uploading: List<String>,
    error: String?,
    onRemove: (String) -> Unit,
) {
    if (attachments.isEmpty() && uploading.isEmpty() && error == null) return
    Column(
        Modifier.fillMaxWidth().background(Palette.Bg)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        error?.let {
            Text(it, color = Palette.Danger, fontSize = Type.Meta, maxLines = 2)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            uploading.forEach { name ->
                Row(
                    // 固定高度的單行膠囊：Radii.Chip 的適用場景就是這個
                    Modifier.background(Palette.Surface, Radii.Chip)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = Palette.Accent,
                    )
                    Text(name, color = Palette.TextDim, fontSize = Type.Meta, maxLines = 1)
                }
            }
            attachments.forEach { a ->
                Row(
                    Modifier.background(Palette.SurfaceHi, Radii.Chip)
                        .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Filled.AttachFile, null,
                        tint = Palette.TextDim, modifier = Modifier.size(14.dp),
                    )
                    Text(a.name, color = Palette.Text, fontSize = Type.Meta, maxLines = 1)
                    Text(
                        fmtBytes(a.bytes),
                        color = Palette.TextFaint, fontSize = Type.Meta,
                    )
                    // 膠囊才 30dp 高，這裡不能用 minimumInteractiveComponentSize——
                    // 它會把整列的版面撐到 48dp，膠囊跟著變成一條粗方塊。
                    // 改成靠內縮把可點範圍撐大；旁邊沒有別的可點元素，
                    // Compose 的觸控擴展會補完剩下的差距
                    Icon(
                        Icons.Filled.Close, "不要附這個檔",
                        tint = Palette.TextDim,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onRemove(a.path) }
                            .padding(6.dp)
                            .size(14.dp),
                    )
                }
            }
        }
    }
}

private fun fmtBytes(n: Long): String = when {
    n >= 1024 * 1024 -> "${n / (1024 * 1024)}MB"
    n >= 1024 -> "${n / 1024}KB"
    else -> "${n}B"
}

/**
 * 助理傳來的一個檔案。
 *
 * 圖片直接畫在對話裡——要先按下載才看得到的圖，跟沒傳給你差不多。
 * 預覽的 bytes 走的是跟存檔同一個端點（伺服器沒有縮圖端點），所以只對
 * [PREVIEW_MAX_BYTES] 以內的圖這麼做，大圖一律先給取件單。
 */
@Composable
private fun FileOfferCard(item: TraceItem.FileOffer, client: ButlerClient) {
    val ctx = LocalContext.current
    // key 用 fileId：LazyColumn 回收重用時，狀態不可以跟著位置留給下一個檔案
    var preview by remember(item.fileId) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    var previewFailed by remember(item.fileId) { mutableStateOf(false) }
    // 存檔狀態放 InboxRepo 不放這裡：這張卡在 LazyColumn 裡會被回收，
    // 用 remember 存的話滑出去再滑回來就忘了自己存過，還會看到「存檔中…」歸零
    val downloading by InboxRepo.downloading.collectAsState()
    val savedMap by InboxRepo.saved.collectAsState()
    val saving = item.fileId in downloading
    val savedAt = savedMap[item.fileId]

    if (item.previewable) {
        LaunchedEffect(item.fileId) {
            val buf = java.io.ByteArrayOutputStream()
            client.downloadOfferedFile(item.fileId, buf)
                .onSuccess {
                    val raw = buf.toByteArray()
                    // 解碼一定要離開主執行緒。LaunchedEffect 的 coroutine 跑在
                    // Compose 的 UI dispatcher 上，decodeByteArray 是純 CPU 工作，
                    // 一張相機拍的圖就要幾百毫秒——那段時間畫面完全不動，
                    // 連捲動都停住，圖夠大就直接吃 ANR。
                    // 用 Default 不是 IO：這裡不等外部裝置，是在燒 CPU。
                    val bmp = withContext(Dispatchers.Default) {
                        BitmapFactory.decodeByteArray(raw, 0, raw.size)
                    }
                    preview = bmp
                    // 副檔名說是圖、實際解不出來（壞檔或不支援的格式）也算失敗，
                    // 否則轉圈會一直轉下去
                    if (bmp == null) previewFailed = true
                }
                .onFailure { previewFailed = true }
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(start = AvatarW)
            // 提問卡有底色這張沒有，兩張同類的卡並排就差一階。補上才是同一層
            .background(Palette.Surface, Radii.Card)
            .border(1.dp, Palette.Line, Radii.Card)
            .padding(Space.Inner),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (item.isImage) Icons.Filled.Image else Icons.Filled.Description,
                null, tint = Palette.Accent, modifier = Modifier.size(18.dp),
            )
            // 檔名是這張卡的主體，不是附註
            Text(
                item.name, color = Palette.Text, fontSize = Type.Body,
                fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Text(fmtBytes(item.bytes), color = Palette.TextFaint, fontSize = Type.Tiny)
        }

        if (item.note.isNotBlank()) {
            Text(item.note, color = Palette.TextDim, fontSize = Type.Meta,
                lineHeight = Type.MetaLine)
        }

        preview?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = item.name,
                // Radii.Chip 是 999dp 的膠囊：拿去裁一張整寬的圖，左右兩端會被切成
                // 半圓，圖的內容跟著缺一角。圖片要的是小圓角
                modifier = Modifier.fillMaxWidth().clip(Radii.Tiny),
                contentScale = ContentScale.FillWidth,
            )
        }
        if (item.previewable && preview == null && !previewFailed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    Modifier.size(12.dp), color = Palette.Accent, strokeWidth = 1.5.dp,
                )
                Text("圖片載入中…", color = Palette.TextFaint, fontSize = Type.Tiny)
            }
        }
        if (previewFailed) {
            Text("圖片載不回來，原檔可能已經不在了。",
                color = Palette.TextFaint, fontSize = Type.Tiny)
        }

        Text(
            if (savedAt != null) "已存到 $savedAt" else if (saving) "存檔中…" else "存到手機",
            color = if (savedAt != null) Palette.Ok else Palette.Accent,
            fontSize = Type.Body,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                // 一行 13sp 的文字連結高度只有 18dp，遠低於 48dp 的可觸控下限。
                // 這是卡片上唯一的操作，撐成一顆看得出可以按的方塊；
                // clip 在 clickable 之前，水波紋才會跟著圓角走
                .clip(Radii.Field)
                .background(Palette.SurfaceHi)
                .clickable(enabled = !saving && savedAt == null) {
                    // gone 這裡填 false：清單端點才算得出這個旗標，而下載
                    // 失敗本來就會走 InboxRepo 的錯誤流程，不必先問一次
                    InboxRepo.startDownload(
                        ctx, client,
                        OfferedFile(
                            id = item.fileId, name = item.name, bytes = item.bytes,
                            note = item.note, at = "", gone = false,
                        ),
                    )
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/**
 * 助理問你一件事——**長在對話裡，不是彈出視窗**。
 *
 * 原本是 Dialog，但助理需要你決定的時候，人通常不在 App 裡；Dialog 是暫時性 UI，
 * 離開再回來就沒了，那則提問等於石沉大海。放進軌跡它才會一直等在那，
 * 事後也看得到自己當初選了什麼。
 *
 * 指令原文一律完整顯示不截斷——攻擊面正是「說明講 A、指令做 B」。
 */
@Composable
private fun AskCard(
    item: TraceItem.AskItem,
    onAnswer: (String, String, String?) -> Unit,
) {
    val ask = item.req
    // 破壞性指令按下「執行」之前先要一次本人確認（伺服器用 require_biometric 指定）
    val auth = rememberDeviceAuth()
    Column(
        Modifier.fillMaxWidth().padding(start = AvatarW)
            .background(Palette.Surface, Radii.Card)
            .border(
                1.dp,
                // 還在等你的時候邊框亮起來：軌跡往下捲很快，這一列不能長得像
                // 其他過程資訊那樣可以略過
                if (item.pending) Palette.Accent.copy(alpha = 0.5f) else Palette.Line,
                Radii.Card,
            )
            .padding(Space.Inner),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            ask.title, color = Palette.Text, fontSize = Type.Body,
            fontWeight = FontWeight.Medium, lineHeight = Type.BodyLine,
        )
        if (ask.body.isNotBlank()) {
            Text(ask.body, color = Palette.TextDim, fontSize = Type.Meta,
                lineHeight = Type.MetaLine)
        }
        if (ask.raw.isNotBlank()) {
            Box(
                // 程式碼區塊用 Radii.Tiny。原本是 Chip（999dp 膠囊），指令一長成
                // 兩行就會變成一顆兩端渾圓的藥丸，等寬字排在裡面對不上左邊界
                Modifier.fillMaxWidth()
                    .background(Palette.Bg, Radii.Tiny)
                    .border(1.dp, Palette.Danger.copy(alpha = 0.4f), Radii.Tiny)
                    .padding(12.dp),
            ) {
                Text(
                    ask.raw, color = Palette.Text, fontSize = Type.Mono,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }

        if (item.pending) {
            ask.choices.sortedBy { it.id == "yes" }.forEach { c ->
                val danger = c.id == "yes" && ask.kind == "confirm_destructive"
                if (danger) {
                    TextButton(
                        onClick = {
                            auth(ask.requireBiometric, true) {
                                onAnswer(ask.askId, c.id, null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(c.label, color = Palette.Danger, fontSize = Type.Body) }
                } else {
                    Button(
                        onClick = { onAnswer(ask.askId, c.id, null) },
                        modifier = Modifier.fillMaxWidth(),
                        // 全 App 的按鈕都是 Radii.Field，這裡跟著走
                        shape = Radii.Field,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Palette.Accent, contentColor = Palette.Bg,
                        ),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(c.label, fontSize = Type.Body,
                                fontWeight = FontWeight.Medium)
                            if (c.detail.isNotBlank()) {
                                Text(c.detail, fontSize = Type.Tiny,
                                    color = Palette.Bg.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            }
            OwnAnswerField { text -> onAnswer(ask.askId, "", text) }
        } else {
            // 空字串＝逾時或被停止，沒有人回答。這跟「選了取消」不一樣，
            // 得說清楚，否則使用者會以為是自己按的。
            val picked = ask.choices.firstOrNull { it.id == item.answeredChoiceId }
            Text(
                when {
                    item.answeredText != null -> "回了：${item.answeredText}"
                    picked != null -> "選了：${picked.label}"
                    item.answeredChoiceId.isNullOrBlank() -> "沒有回答（逾時或已停止）"
                    else -> "已處理"
                },
                color = Palette.TextFaint, fontSize = Type.Meta,
            )
        }
    }
}

/**
 * 提問卡裡的「自己回一句」。
 *
 * 選項是它猜的，未必包含你真正想講的話。伺服器端本來就收自由文字
 * （`answer.text or choice_id`），缺的一直只是入口——沒有入口，人只能改用
 * 底下的輸入列，而那時整個回合正停在這一題上，訊息只會排進佇列乾等，
 * 看起來就像 App 當掉。
 */
@Composable
private fun OwnAnswerField(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f)
                .background(Palette.SurfaceHi, Radii.Bubble)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = Type.Meta, lineHeight = Type.MetaLine, color = Palette.Text,
            ),
            maxLines = 4,
            cursorBrush = SolidColor(Palette.Accent),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text("或者自己說", color = Palette.TextFaint, fontSize = Type.Meta)
                    }
                    inner()
                }
            },
        )
        val can = text.isNotBlank()
        Box(
            // 這顆在提問卡片裡，38dp 的視覺尺寸不能再放大。
            // minimumInteractiveComponentSize 一定要放在 size 前面（外層），
            // 擺後面的話它收到的是固定 38dp 的限制，等於沒寫
            Modifier.minimumInteractiveComponentSize()
                .size(38.dp)
                .clip(CircleShape)
                .background(if (can) Palette.Accent else Palette.Surface)
                .clickable(enabled = can) { onSubmit(text.trim()); text = "" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send, "送出回答",
                tint = if (can) Palette.Bg else Palette.TextFaint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
