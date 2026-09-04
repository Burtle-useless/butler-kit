package dev.butlerkit.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.net.ButlerClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * 捲到某一項的**尾端**用的偏移量（px）。
 *
 * LazyColumn 沒有「對齊這一項的底部」這種 API，只能給一個大到穩穩超過任何
 * 單則訊息高度的偏移，讓它自己 clamp 在列表最底。一則訊息不可能高過十萬 px。
 */
private const val TO_ITEM_END = 100_000

/** 判定「已經在最底下」的容差（px）。差幾像素還算貼底，否則跟隨會莫名斷掉。 */
private const val BOTTOM_SLACK = 24

/** 「載入更早」露出來之後等這麼久還停在最上面才真的去拉，甩過頭彈回去的不算。 */
private const val OLDER_DEBOUNCE_MS = 250L

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
    onDraft: (String) -> Unit,
    onStop: () -> Unit,
    /** 停掉一件還在跑的背景工作。跟 [onStop] 是兩回事，見 ChatViewModel.stopBgTask。 */
    onStopBg: (String) -> Unit,
    onAnswer: (String, String, String?) -> Unit,
    onSwitchConv: (String) -> Unit,
    onNewConv: () -> Unit,
    onDeleteConv: (String) -> Unit,
    onAttach: (Uri) -> Unit,
    onRemoveAttach: (String) -> Unit,
    onConvSettings: (String?, String?) -> Unit,
    /** 捲到最上面那列「載入更早」時往前翻一頁（見 ChatViewModel.loadOlder）。 */
    onLoadOlder: () -> Unit,
) {
    // 每條對話各自一份捲動狀態。共用一份的話，從一條長對話切到另一條短的，
    // 新對話會直接停在上一條的捲動位置——短的那條根本沒那麼多內容，看到的是空白。
    // 下面的 landed 也綁同一個 key，兩個要一起換：位置重置了但 landed 還留著 true，
    // 就不會走「進畫面瞬移到底」那條路，切過去看到的是最上面的舊訊息。
    //
    // **一定要 rememberSaveable**（由 MainActivity 的 SaveableStateProvider 承接）。
    // 底欄的 `when (tab)` 會把離開的分頁整個移出組合樹，普通的 remember 全部歸零——
    // 往上翻歷史、切去工具頁看一眼、切回來，位置沒了、landed 也沒了，於是又被
    // 瞬移到最底。這正是「一直想把我拉到畫面的最下面」的主要來路（2026-08-22 回報）。
    val listState = rememberSaveable(state.currentConv, saver = LazyListState.Saver) {
        LazyListState()
    }
    // 輸入框的內容住在 ViewModel（見 ChatState.draft），不是這裡的 remember：
    // 打一半切去別的分頁、或 App 被系統回收，remember 一律歸零
    val input = state.draft
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 系統檔案挑選器。用 OpenMultipleDocuments 而不是 GetContent：
    // 前者能一次挑多張照片，且走 SAF 拿得到穩定的檔名。
    // 挑檔改走自己的面板（最近相片格＋「其他檔案…」通到系統挑選器）。
    // 系統挑選器本身還在，只是變成面板底下那顆按鈕，不再是唯一的路。
    var showPicker by remember { mutableStateOf(false) }
    if (showPicker) {
        PickerSheet(
            onPicked = { uris -> uris.forEach(onAttach) },
            onDismiss = { showPicker = false },
        )
    }

    // 用 previewText 而不是 streaming：理由見它的說明，兩邊判斷不一致會讓
    // 捲動目標指向不存在的索引。
    val preview = remember(state.streaming) { state.previewText }
    // 尾巴那幾項也是列表裡的項目（見 ChatBody 的 LazyColumn），漏算任何一格都會讓
    // 「捲到底」少捲一格，而少的正好是正在長出來的那一行。三格各自獨立：
    //   串流預覽或打字指示（忙碌中一定有其中一個）、狀態列（忙碌中）、背景工作列
    //   （有背景工作時，忙不忙都在）。
    // 先前把狀態列與背景列當成「只會出現一個」而只加一格，但 ChatBody 是兩個獨立的
    // if，忙碌中又有背景工作時兩格都在——這時 lastIndex 少一，跟隨就永遠差一格。
    // 最上面的「載入更早」也是一項（hasMore 時才有），漏算會讓「捲到底」差一格。
    val headCount = if (state.hasMore) 1 else 0
    val lastIndex = headCount + state.items.size +
        (if (preview.isNotEmpty() || state.busy) 1 else 0) +
        (if (state.busy) 1 else 0) +
        (if (state.bgTasks.isNotEmpty()) 1 else 0) - 1

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
    // 跟 listState 同理要能存活過分頁切換，否則翻到一半切走再回來就恢復跟隨。
    var follow by rememberSaveable(state.currentConv) { mutableStateOf(true) }
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
    var landed by rememberSaveable(state.currentConv) { mutableStateOf(false) }
    LaunchedEffect(
        state.items.size, state.streaming, state.status, state.busy, state.bgTasks,
    ) {
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

    // 捲到最上面那列「載入更早」露出來就往前翻一頁。
    //
    // 只在 landed 之後才算數：進畫面那一瞬間列表還停在索引 0（瞬移到底是下一步
    // 才做的），不擋的話每開一條對話就白拉一頁。露出來之後再等一小段確認還停在
    // 上面才觸發，甩過頭又彈回去的不算。載入中或沒有更早的 ViewModel 自己會擋，
    // 這裡不重複判斷。
    LaunchedEffect(listState, state.hasMore) {
        if (!state.hasMore) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex == 0 }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                delay(OLDER_DEBOUNCE_MS)
                if (landed && listState.firstVisibleItemIndex == 0) onLoadOlder()
            }
    }

    // 更早的一頁接在最前面之後，畫面不能跳。LazyColumn 的位置是「第幾項＋偏移」，
    // 前面多了 N 項它就會顯示成往上跳了 N 項——正在看的那則被推出畫面外。所以
    // 記住上一次的第一項，這次它出現在第 N 個位置就把索引往後推 N，偏移不變。
    // 用物件同一性找而不是比內容：兩則一模一樣的「好」在不同時間是兩個物件。
    // 「載入更早」那一列本身也要算：這一頁是最後一頁的話它會跟著消失，少一格。
    var prevFirst by remember(state.currentConv) { mutableStateOf<TraceItem?>(null) }
    var prevHead by remember(state.currentConv) { mutableStateOf(0) }
    LaunchedEffect(state.items, headCount) {
        val first = prevFirst
        val idx = if (first == null) -1 else state.items.indexOfFirst { it === first }
        // 找不到＝整串被換掉了（snapshot 重建、切對話），那不是前置，交給落地那段
        val shift = if (idx < 0) 0 else idx + headCount - prevHead
        prevFirst = state.items.firstOrNull()
        prevHead = headCount
        if (shift > 0) {
            listState.scrollToItem(
                listState.firstVisibleItemIndex + shift,
                listState.firstVisibleItemScrollOffset,
            )
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

    // 回到底部：脫離跟隨之後的回程。先捲完再恢復跟隨——順序反過來的話，動畫
    // 還在跑就已經在跟了，新內容進來會各捲各的，看起來像畫面在打架。
    val backToBottom: () -> Unit = {
        scope.launch {
            if (lastIndex >= 0) listState.animateScrollToItem(lastIndex, TO_ITEM_END)
            follow = true
        }
        Unit
    }

    val body = @Composable {
        ChatBody(
            state, listState, input,
            title = title,
            multiConv = multiConv,
            client = client,
            onAnswer = onAnswer,
            onInput = onDraft,
            // 自己送出的訊息一定要看得到。人在上面翻歷史時按送出，畫面停在原地
            // 等於這則訊息石沉大海——所以送出本身就是一次「我要回到最新」
            onSend = { onSend(input); onDraft(""); follow = true },
            // 貼在底部時這顆只會擋住內容，所以只在人離開底部時出現
            showToBottom = !atBottom,
            onToBottom = backToBottom,
            onStop = onStop,
            onStopBg = onStopBg,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onResend = onSend,
            onNewConv = onNewConv,
            onPickFile = { showPicker = true },
            onRemoveAttach = onRemoveAttach,
            onConvSettings = onConvSettings,
            onLoadOlder = onLoadOlder,
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
    showToBottom: Boolean,
    onToBottom: () -> Unit,
    onStop: () -> Unit,
    onStopBg: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    onResend: (String) -> Unit,
    onNewConv: () -> Unit,
    onPickFile: () -> Unit,
    onRemoveAttach: (String) -> Unit,
    onConvSettings: (String?, String?) -> Unit,
    onLoadOlder: () -> Unit,
) {
    // cc-bot 頁一條對話都還沒有時，currentConv 會停在別人那條（剛從助理切過來）——
    // 直接顯示會把別人的對話端到 cc-bot 頁上，所以擋掉改顯示空狀態
    val noCcConv = multiConv && !state.pendingNew && !isCcConv(state.currentConv)
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

        // 忙的時候也要走下面那條路：狀態列現在住在對話串的尾巴，
        // 對話還空著就送出第一則訊息時，這裡若走大臉分支會連狀態列一起沒有
        if (state.items.isEmpty() && preview.isEmpty() && !state.busy && state.historyLoading) {
            // 歷史還在路上：先畫骨架，不要閃一下大臉／「還沒有對話」再換成內容。
            // 切對話與冷啟動都會經過這裡，snapshot 一趟通常幾百毫秒
            HistorySkeleton(Modifier.weight(1f).fillMaxWidth())
        } else if (state.items.isEmpty() && preview.isEmpty() && !state.busy) {
            // 空對話：大臉問候。這是桌寵存在感最強的時刻。
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                PetFace(state.pet, 120.dp)
                // 連得上就不寫字，一顆吉祥物就夠了。連不上才印原因。
                if (!state.connected) {
                    Text(
                        "……連不上電腦。它開著嗎？",
                        color = Palette.TextDim, fontSize = Type.Body,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        } else {
            // 最新一則回覆旁的那顆吉祥物是「活的」（跟著實際狀態變表情），
            // 歷史訊息旁的是安靜的 Idle——像通訊軟體的頭像，但最新那顆會演戲
            val lastReply = state.items.indexOfLast { it is TraceItem.Reply }
            // Box 只是為了讓回到底部那顆浮在訊息流上。不用 weight 給 LazyColumn
            // 而是給 Box：兩層都吃 weight 的話，第二層拿到的是「剩下的剩下」
            Box(Modifier.weight(1f).fillMaxWidth()) {
              LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = Space.Screen, vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                // 最上面一列：還有更早的才出現。給固定 key 的理由同尾巴那幾項
                if (state.hasMore) {
                    item(key = "older") { OlderRow(state.olderLoading, onLoadOlder) }
                }
                items(state.items.size) { i ->
                    val live = i == lastReply && preview.isEmpty()
                    // 跨日才插一條日期，不是每則都印時間（那會吵）；
                    // 單則的時刻放在長按選單裡
                    dayLabelBefore(state.items, i)?.let { DaySeparator(it) }
                    TraceRow(
                        state.items[i],
                        if (live) state.pet else PetMood.Idle,
                        client = client,
                        onResend = onResend,
                        onAnswer = onAnswer,
                    )
                }
                // 尾巴這幾項一定要給固定的 key。`items(count)` 那個多載是拿
                // **絕對位置**當 key 的，所以對話每多一行（工具、思考、回覆），
                // 下面這些就整批往後移一格，Compose 會判定「舊的沒了、這是新的」
                // 而把它們銷毀重建——卡片裡的 remember 與計時的 LaunchedEffect
                // 跟著重跑，畫面上就是閃一下、秒數歸零（使用者 2026-08-25 回報）
                if (preview.isNotEmpty()) {
                    item(key = "preview") { BotRow(state.pet) { MarkdownText(preview) } }
                } else if (state.busy) {
                    // 還沒吐出任何字：頭像旁三顆點，跟成熟聊天 App 的「對方正在輸入」
                    // 同一個語彙。有字之後這一格就換成上面的預覽，key 不同會重建一次
                    // 頭像，但兩者本來就是不同的東西，不硬共用一個 key
                    item(key = "typing") { BotRow(state.pet) { TypingDots() } }
                }
                // 狀態列是對話的一部分，不是輸入框的一部分。原本擺在 InputDock
                // 上面、跟著 imePadding 走，鍵盤一升起它就黏在鍵盤頭上飄著，
                // 跟正在長出來的內容分了家（使用者 2026-08-22 回報）
                if (state.busy) {
                    item(key = "status") { StatusLine(state) }
                }
                // 背景卡片自己佔一項，助理忙不忙都住在這一格。原本忙的時候畫在
                // StatusLine 裡、收工後才換成獨立的一列，那是兩個不同的格子，
                // 「助理收工」的那一刻卡片必定被銷毀重建一次——耗時歸零，而它
                // 說的正是「其他的結束了，這件還在跑」，最不該在此時跳動。
                if (state.bgTasks.isNotEmpty()) {
                    item(key = "bg") { BackgroundLine(state.bgTasks, onStopBg) }
                }
              }
              if (showToBottom) {
                  ToBottomButton(onToBottom, Modifier.align(Alignment.BottomEnd))
              }
            }
        }

        // 未答的提問釘一條在輸入框上方。
        //
        // 卡片本身在軌跡裡，後面繼續跑的工具會一行行把它往上推，推到看不見為止。
        // 這一條不隨捲動走，點它就跳回卡片。
        val pendingAsk = state.items.indexOfLast {
            it is TraceItem.AskItem && it.pending
        }
        if (pendingAsk >= 0) {
            val askTitle = (state.items[pendingAsk] as TraceItem.AskItem).req.title
            // 頂端「載入更早」那一格也算一項，跳的時候要補進去
            val head = if (state.hasMore) 1 else 0
            val jumpScope = rememberCoroutineScope()
            PendingAskBar(askTitle) {
                jumpScope.launch { listState.animateScrollToItem(pendingAsk + head) }
            }
        }

        InputDock(
            value = input,
            busy = state.busy,
            answering = waitingAsk != null,
            attachments = state.attachments,
            uploading = state.uploading,
            uploadError = state.uploadError,
            client = client,
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

/**
 * 列表最上面那一列：「載入更早」一行淡字，載入中換成小型進度。
 * 捲到它露出來就會自動翻頁（見 ChatScreen 的觀察），點它也行——自動觸發
 * 被防抖擋掉、或上一趟失敗時，人有地方可以再按一次。
 */
@Composable
private fun OlderRow(loading: Boolean, onClick: () -> Unit) = Box(
    Modifier
        .fillMaxWidth()
        .clickable(enabled = !loading, role = Role.Button, onClick = onClick)
        .padding(vertical = 6.dp),
    contentAlignment = Alignment.Center,
) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.TextFaint,
        )
    } else {
        Text(
            "載入更早", color = Palette.TextFaint,
            fontSize = Type.Tiny, lineHeight = Type.TinyLine,
        )
    }
}

/**
 * 回到最新的訊息。
 *
 * 往上翻歷史就脫離跟隨，之後畫面不會自己動——沒有這顆的話，一條長對話要一路
 * 滑回去才追得上助理正在講的話。形狀跟輸入列那兩顆同一套（Radii.Field）。
 * 底色給不透明的 Palette.Bg：它蓋在訊息上，半透明會讓底下的字透出來變成一團。
 */
@Composable
private fun ToBottomButton(onClick: () -> Unit, modifier: Modifier = Modifier) = Box(
    modifier
        .padding(end = Space.Screen, bottom = 12.dp)
        .size(40.dp)
        .clip(Radii.Field)
        .background(Palette.Bg)
        .border(1.dp, Palette.Line, Radii.Field)
        .clickable(role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
) {
    Icon(
        Icons.Filled.KeyboardArrowDown, "回到最新的訊息",
        tint = Palette.Text, modifier = Modifier.size(22.dp),
    )
}

/**
 * 歷史載入中的骨架：一則右側的短條（使用者）、一則左側帶頭像位的長條（助理），
 * 重複幾組。灰面淡淡呼吸，不轉圈——轉圈說的是「等」，骨架說的是「內容馬上到、
 * 版面就是這樣」。全用 SurfaceHi，不加第五種顏色。
 */
@Composable
private fun HistorySkeleton(modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "skeleton")
    val alpha by inf.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(700), RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    val tone = Palette.SurfaceHi.copy(alpha = Palette.SurfaceHi.alpha * alpha)
    Column(
        modifier.padding(horizontal = Space.Screen, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(Modifier.width(150.dp).height(38.dp).background(tone, Radii.Bubble))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(32.dp).background(tone, Radii.Chip))
                Column(
                    Modifier.weight(1f).padding(start = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.fillMaxWidth().height(14.dp).background(tone, Radii.Chip))
                    Box(Modifier.fillMaxWidth(0.8f).height(14.dp).background(tone, Radii.Chip))
                    Box(Modifier.fillMaxWidth(0.55f).height(14.dp).background(tone, Radii.Chip))
                }
            }
        }
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
    ActionButton(
        "新對話",
        modifier = Modifier.padding(top = 20.dp),
        pad = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
        onClick = onNewConv,
    )
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

    // 報頭：標題行，底下壓一粗一細的雙規線。
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
            // 連得上就不寫字——正常是常態，常態不需要標示。斷線才印，
            // 而且用文字不用一顆點：點只有顏色，色弱看不出差別
            if (!state.connected) {
                Text(
                    "斷線",
                    fontSize = Type.Tiny,
                    fontFamily = FontFamily.SansSerif,
                    color = Palette.Danger,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
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
