package dev.butlerkit.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.notify.Notifier
import dev.butlerkit.app.ui.AgendaScreen
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

class MainActivity : ComponentActivity() {

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
        val prefs = Prefs(applicationContext)
        Log.i(
            ButlerClient.TAG,
            "onCreate configured=${prefs.isConfigured()} host='${prefs.host}' " +
                "tokenLen=${prefs.token.length} lastSeq=${prefs.lastSeq}",
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                surface = Palette.Surface,
                background = Palette.Bg,
            )) {
                var configured by remember { mutableStateOf(prefs.isConfigured()) }
                if (!configured) {
                    SetupScreen(prefs) { configured = true }
                } else {
                    Root(prefs, intent?.getStringExtra(Notifier.EXTRA_CONV))
                }
            }
        }
    }
}

@Composable
private fun Root(prefs: Prefs, openConv: String? = null) {
    val vm: ChatViewModel = viewModel()
    val state by vm.state.collectAsState()
    var tab by remember { mutableStateOf(Tab.Qi) }
    // 設定不是分頁而是蓋上來的一層：它離開底欄之後，如果還混在 tab 狀態裡，
    // 底欄會出現「四格全都沒選中」的空窗畫面。
    var settingsOpen by remember { mutableStateOf(false) }

    // 分頁決定看哪個對話：助理頁固定那條專屬對話，cc-bot 頁回到上次選的
    LaunchedEffect(tab) {
        when (tab) {
            Tab.Qi -> vm.enterQiTab()
            Tab.CcBot -> vm.enterCcTab()
            else -> Unit
        }
    }

    // 從通知點進來：切到那個對話，並落在它所屬的分頁
    LaunchedEffect(openConv) {
        openConv?.takeIf { it.isNotBlank() }?.let {
            if (it == ChatViewModel.DEFAULT_CONV) {
                tab = Tab.Qi
            } else {
                tab = Tab.CcBot
                vm.switchCcConversation(it)
            }
        }
    }

    // 設定蓋成獨立一層，不留底欄：進來就是專心改設定，改完按返回鍵回原本那頁
    if (settingsOpen) {
        BackHandler { settingsOpen = false }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            IconButton(onClick = { settingsOpen = false }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "返回",
                    tint = Palette.Text, modifier = Modifier.size(22.dp),
                )
            }
            SettingsScreen(
                state = state, prefs = prefs, client = vm.client,
                onLoad = vm::loadSettings,
                onApply = vm::applySettings,
                onSetCwd = { path -> vm.client.setCwd(state.currentConv, path) },
            )
        }
        return
    }

    Scaffold(
        containerColor = Palette.Bg,
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
                            indicatorColor = Palette.AccentSoft,
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
            when (tab) {
                Tab.Qi -> ChatScreen(
                    state = state,
                    title = "助理",
                    multiConv = false,
                    client = vm.client,
                    onSend = vm::send,
                    onStop = vm::stop,
                    onAnswer = vm::answerAsk,
                    onSwitchConv = vm::switchCcConversation,
                    onNewConv = vm::newConversation,
                    onDeleteConv = vm::deleteConversation,
                    onAttach = vm::attach,
                    onRemoveAttach = vm::removeAttachment,
                    onConvSettings = vm::applyConvSettings,
                )
                Tab.CcBot -> ChatScreen(
                    state = state,
                    title = "工作",
                    multiConv = true,
                    client = vm.client,
                    onSend = vm::send,
                    onStop = vm::stop,
                    onAnswer = vm::answerAsk,
                    onSwitchConv = vm::switchCcConversation,
                    onNewConv = vm::newConversation,
                    onDeleteConv = vm::deleteConversation,
                    onAttach = vm::attach,
                    onRemoveAttach = vm::removeAttachment,
                    onConvSettings = vm::applyConvSettings,
                )
                Tab.Daily -> AgendaScreen(vm.client)
                Tab.Tools -> ToolsScreen(vm.client) { settingsOpen = true }
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
            Text("連線設定", color = Palette.Text, fontSize = 22.sp)
            Text(
                "host 填電腦的 Tailscale 位址加 port；token 在電腦端啟動日誌那行 " +
                    "\"device token:\" 後面。",
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
            Button(
                onClick = {
                    prefs.host = host.trim()
                    prefs.token = token.trim()
                    onDone()
                },
                enabled = host.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("連線") }
        }
    }
}
