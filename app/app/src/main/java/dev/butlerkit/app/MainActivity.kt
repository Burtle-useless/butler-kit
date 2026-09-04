package dev.butlerkit.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.notify.Notifier
import dev.butlerkit.app.ui.ActionButton
import dev.butlerkit.app.ui.AgendaScreen
import dev.butlerkit.app.ui.ButlerTheme
import dev.butlerkit.app.ui.ChatScreen
import dev.butlerkit.app.ui.ChatViewModel
import dev.butlerkit.app.ui.PetFace
import dev.butlerkit.app.ui.Palette
import dev.butlerkit.app.ui.PetMood
import dev.butlerkit.app.ui.SettingsScreen
import dev.butlerkit.app.ui.ToolsScreen
import dev.butlerkit.app.ui.Type

/**
 * 助理與工作區分成兩頁（2026-08-11）：
 * 助理＝單一聊天視窗、日常應用，不該被一堆工作對話淹掉；
 * 工作＝原本那套多對話管理原封不動搬過來。
 * 底下是同一個 ChatViewModel、同一條事件流，只差在看哪個 conv_id。
 *
 * 標籤原本寫 `cc-bot`（2026-08-14 改）：那是這套東西在電腦端的專案名，
 * 印在使用者的底欄上等於要他背實作細節。
 *
 * **設定刻意不在這裡**：底欄的位置該留給每天都會按的東西，設定改完就不會再進去。
 * 入口移到工具頁右上角的齒輪（見 [ToolsScreen]），底欄因此從五格降到四格——
 * 五格會讓每個點擊目標窄到容易誤觸。
 */
private enum class Tab(val label: String, val icon: ImageVector?) {
    Qi("助理", null),                                  // 助理的圖示是牠本人，不是向量圖
    CcBot("工作", Icons.AutoMirrored.Filled.List),
    Daily("日常", Icons.Filled.DateRange),
    Tools("工具", Icons.Filled.Build),
}

/**
 * 從通知或桌面 widget 帶進來的導航目標。
 *
 * [stamp] 存在的理由：同一張 widget 連點兩次，conv/tab/sub 三個欄位一模一樣，
 * 沒有它的話 `LaunchedEffect(nav)` 認為狀態沒變而不重跑——使用者自己切走之後
 * 再點同一張 widget 就沒反應。它只是「這是新的一次點擊」的識別。
 */
private data class Nav(
    val conv: String? = null,
    val tab: String? = null,
    val sub: String? = null,
    val stamp: Long = 0L,
)

private fun Intent.toNav(): Nav = Nav(
    conv = getStringExtra(Notifier.EXTRA_CONV),
    tab = getStringExtra(MainActivity.EXTRA_TAB),
    sub = getStringExtra(MainActivity.EXTRA_SUB),
    stamp = SystemClock.elapsedRealtime(),
)

class MainActivity : ComponentActivity() {

    /**
     * 目前要跳去哪。
     *
     * 必須是 state 而不是每次讀 `intent`：Activity 已經開著時，從 widget 或通知
     * 進來走的是 [onNewIntent]，setContent 那段**不會**重跑，直接讀 intent 的話
     * 畫面永遠停在第一次啟動時的那個目標。
     */
    private val nav = mutableStateOf(Nav())

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        nav.value = intent.toNav()
    }

    /** 通知權限（Android 13+ 要執行期同意）。拒絕了也不擋 App，只是背景通知會失效。 */
    private val askNotify = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.i(ButlerClient.TAG, "通知權限 granted=$granted")
    }

    private fun ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val ok = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!ok) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // edge-to-edge 是鍵盤佈局正確的前提：沒開的話系統 adjustResize 會先縮一次
        // 視窗，Compose 的 imePadding 再墊一次鍵盤高度——雙重讓位，輸入列懸在半空
        enableEdgeToEdge()
        ensureNotifyPermission()
        nav.value = intent?.toNav() ?: Nav()
        val prefs = Prefs(applicationContext)
        Log.i(
            ButlerClient.TAG,
            "onCreate configured=${prefs.isConfigured()} host='${prefs.host}' " +
                "tokenLen=${prefs.token.length} lastSeq=${prefs.lastSeq}",
        )
        setContent {
            // 色票（跟隨系統日夜）、M3 配色與全 App 的預設字型都在 ButlerTheme 裡。
            // 要換字型改 Theme.kt 的 Fonts.Base 一處就好，不要改這裡。
            ButlerTheme {
                var configured by remember { mutableStateOf(prefs.isConfigured()) }
                if (!configured) {
                    SetupScreen(prefs) { configured = true }
                } else {
                    Root(prefs, nav.value)
                }
            }
        }
    }

    companion object {
        /** widget 點擊帶的目的地。值見 `widget/WidgetUi.kt` 的 TAB_/SUB_ 常數。 */
        const val EXTRA_TAB = "open_tab"
        const val EXTRA_SUB = "open_sub"
    }
}

@Composable
private fun Root(prefs: Prefs, nav: Nav = Nav()) {
    val vm: ChatViewModel = viewModel()
    val state by vm.state.collectAsState()
    var tab by remember { mutableStateOf(Tab.Qi) }
    // 設定不是分頁而是蓋上來的一層：它離開底欄之後，如果還混在 tab 狀態裡，
    // 底欄會出現「四格全都沒選中」的空窗畫面。
    var settingsOpen by remember { mutableStateOf(false) }
    // widget 指定的子分頁。日常頁切過去之後就清掉，見 AgendaScreen 的 onSubConsumed
    var pendingSub by remember { mutableStateOf<String?>(null) }
    // 每個分頁各自的狀態保管處，見下面 SaveableStateProvider 的說明
    val tabStates = rememberSaveableStateHolder()
    // 操作失敗的一句話。放在設定那層之前收：改設定失敗也要看得到
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.toasts.collect { snackbar.showSnackbar(it) } }

    // 分頁決定看哪個對話：助理頁固定那條專屬對話，cc-bot 頁回到上次選的
    LaunchedEffect(tab) {
        when (tab) {
            Tab.Qi -> vm.enterQiTab()
            Tab.CcBot -> vm.enterCcTab()
            else -> Unit
        }
    }

    // 從通知或 widget 點進來：落在該去的分頁。
    // 通知帶對話 id、widget 帶分頁名，兩者不會同時出現
    LaunchedEffect(nav) {
        nav.conv?.takeIf { it.isNotBlank() }?.let {
            if (it == ChatViewModel.DEFAULT_CONV) {
                tab = Tab.Qi
                // 顯式再叫一次。上面那個 LaunchedEffect(tab) 只在 tab **變動**時跑，
                // 而從通知點進來時 tab 常常本來就停在助理頁——那條路上
                // enterQiTab 不執行，markRead 也就不執行，通知點了不會消失。
                vm.enterQiTab()
            } else {
                tab = Tab.CcBot
                vm.switchCcConversation(it)
            }
        }
        when (nav.tab) {
            "daily" -> {
                tab = Tab.Daily
                pendingSub = nav.sub
            }
            "tools" -> tab = Tab.Tools
        }
    }

    // 設定蓋成獨立一層，不留底欄：進來就是專心改設定，改完按返回鍵回原本那頁
    if (settingsOpen) {
        val closeSettings = {
            settingsOpen = false
        }
        BackHandler { closeSettings() }
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            IconButton(onClick = closeSettings) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "返回",
                    tint = Palette.Text, modifier = Modifier.size(22.dp),
                )
            }
            SettingsScreen(
                state = state, prefs = prefs, client = vm.client,
                onLoad = vm::loadSettings,
                onApply = vm::applySettings,
                onSetCwd = vm::setCwd,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
        return
    }

    Scaffold(
        containerColor = Palette.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // M3 預設 80dp，對只有圖示加一行小字的底欄來說太厚，壓到 64dp。
            // 要另外加手勢導覽條的高度：Modifier.height 設的是含 inset 的總高，
            // 只寫 64dp 的話最下面那排字會被系統的手勢條壓到。
            val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            NavigationBar(
                modifier = Modifier.height(64.dp + navInset),
                containerColor = Palette.Surface,
                tonalElevation = 0.dp,
            ) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            val c = if (tab == t) Palette.Accent else Palette.TextFaint
                            // 助理分頁的圖示就是助理本人，其他分頁用向量圖示。
                            // 原本全用文字符號（# ◷ ⌘ ≡）：那些字元在不同機器上由不同
                            // 字型湊出來，粗細與基線各自為政，排在一起就是廉價感的來源。
                            val icon = t.icon
                            if (icon == null) {
                                // 只有選中的那格顯示真心情，其他格一律 Offline
                                PetFace(if (tab == t) state.pet else PetMood.Offline, 24.dp)
                            } else {
                                Icon(icon, t.label, tint = c, modifier = Modifier.size(22.dp))
                            }
                        },
                        label = {
                            Text(t.label, fontSize = Type.Tiny,
                                color = if (tab == t) Palette.Accent else Palette.TextFaint)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            // 透明：M3 預設那顆膠囊指示是 Material 的招牌形狀，
                            // 在報紙版面上像貼了一塊藥丸貼紙。選中狀態交給
                            // 朱紅字色表達，報紙的「現在在這裡」本來就是紅筆圈的
                            indicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        // consumeWindowInsets：把 Scaffold 已扣掉的 padding 從 insets 裡「消耗」掉，
        // 下游的 imePadding 才知道底部已經讓過多少——否則鍵盤高度會跟底欄高度相加，
        // 輸入列懸在鍵盤上方一截
        Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)) {
            // 每個分頁各自保存自己的 rememberSaveable。`when (tab)` 會把離開的那頁
            // 整個移出組合樹，裡面的狀態預設一律歸零——日常頁切走再回來會跳回
            // 行事曆、月份重置到本月，工具頁的搜尋結果整個不見。
            // 只把狀態改成 rememberSaveable 是不夠的：沒有這個 holder 承接，
            // composable 一離開組合樹它照樣消失。兩件事要一起做。
            // 聊天頁不靠這個——它的狀態在 ViewModel 裡，本來就活得比畫面久。
            tabStates.SaveableStateProvider(tab) {
                when (tab) {
                    Tab.Qi -> ChatScreen(
                        state = state,
                        title = "助理",
                        multiConv = false,
                        client = vm.client,
                        onSend = vm::send,
                        onDraft = vm::setDraft,
                        onStop = vm::stop,
                        onStopBg = vm::stopBgTask,
                        onAnswer = vm::answerAsk,
                        onSwitchConv = vm::switchCcConversation,
                        onNewConv = vm::newConversation,
                        onDeleteConv = vm::deleteConversation,
                        onAttach = vm::attach,
                        onRemoveAttach = vm::removeAttachment,
                        onConvSettings = vm::applyConvSettings,
                        onLoadOlder = { vm.loadOlder(state.currentConv) },
                    )
                    Tab.CcBot -> ChatScreen(
                        state = state,
                        title = "工作",
                        multiConv = true,
                        client = vm.client,
                        onSend = vm::send,
                        onDraft = vm::setDraft,
                        onStop = vm::stop,
                        onStopBg = vm::stopBgTask,
                        onAnswer = vm::answerAsk,
                        onSwitchConv = vm::switchCcConversation,
                        onNewConv = vm::newConversation,
                        onDeleteConv = vm::deleteConversation,
                        onAttach = vm::attach,
                        onRemoveAttach = vm::removeAttachment,
                        onConvSettings = vm::applyConvSettings,
                        onLoadOlder = { vm.loadOlder(state.currentConv) },
                    )
                    Tab.Daily -> AgendaScreen(vm.client, pendingSub) { pendingSub = null }
                    Tab.Tools -> ToolsScreen(vm.client) { settingsOpen = true }
                }
            }
        }
    }
}

/**
 * 首次啟動的連線設定。Phase 5 會用 QR 配對取代整頁。
 */
@Composable
private fun SetupScreen(prefs: Prefs, onDone: () -> Unit) {
    var host by remember { mutableStateOf(prefs.host) }
    var token by remember { mutableStateOf(prefs.token) }
    Surface(color = Palette.Bg, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "連線設定", color = Palette.Text, fontSize = Type.Display,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "host 填連得到電腦的位址加 port（Tailscale 主機名、通道網址都行）；" +
                    "token 在電腦端啟動日誌那行 \"device token:\" 後面。",
                color = Palette.TextDim, fontSize = Type.Meta, lineHeight = Type.MetaLine,
            )
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text("host") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("device token") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // 這是使用者看到的第一個畫面，用 App 自己的按鈕而不是 M3 預設的
            // ——預設 Button 的圓角與字重跟後面每一頁都不一樣
            ActionButton("連線", host.isNotBlank() && token.isNotBlank()) {
                prefs.host = host.trim()
                prefs.token = token.trim()
                onDone()
            }
        }
    }
}
