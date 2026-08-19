package dev.butlerkit.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.DeviceInfo
import dev.butlerkit.app.net.humanError
import dev.butlerkit.app.notify.ButlerService
import dev.butlerkit.app.notify.NotifyKind
import kotlinx.coroutines.launch

/**
 * 設定頁：模型與思考強度是「帳號預設」，改了之後所有沒單獨覆寫的對話下回合生效。
 */
@Composable
fun SettingsScreen(state: ChatState, prefs: Prefs, client: ButlerClient,
                   onLoad: () -> Unit, onApply: (String?, String?) -> Unit,
                   onSetCwd: suspend (String) -> Result<Unit>) {
    LaunchedEffect(Unit) { onLoad() }
    val s = state.settings

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(Space.Screen),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "設定", color = Palette.Text, fontSize = Type.Head,
            fontWeight = FontWeight.Bold,
        )

        // 用量放最上面：改模型之前該先看到「上一個模型花了多少」，
        // 擺在下面的話兩件事永遠對不起來。
        UsageCard(client)

        SettingCard("模型") {
            if (s == null) {
                Text("讀取中…", color = Palette.TextFaint, fontSize = Type.Meta)
            } else {
                PickerRow(
                    label = "預設模型",
                    value = s.model ?: "（內建預設）",
                    options = listOf("（內建預設）") + s.models,
                ) { picked ->
                    onApply(picked.takeIf { it != "（內建預設）" }, s.effort)
                }
                PickerRow(
                    label = "思考強度",
                    value = s.effort ?: "（內建預設）",
                    options = listOf("（內建預設）") + s.efforts,
                ) { picked ->
                    onApply(s.model, picked.takeIf { it != "（內建預設）" })
                }
            }
        }

        // ── 工作目錄（對照 cc-bot 的 /cd）────────────────────────────
        var cwd by remember { mutableStateOf("") }
        var cwdMsg by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        SettingCard("目前對話的工作目錄") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicTextField(
                    value = cwd,
                    onValueChange = { cwd = it; cwdMsg = null },
                    modifier = Modifier.weight(1f)
                        .background(Palette.SurfaceHi, Radii.Chip)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = Type.Meta, color = Palette.Text,
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(Palette.Accent),
                    decorationBox = { inner ->
                        Box {
                            if (cwd.isEmpty()) {
                                Text("C:\\Users\\you\\...", color = Palette.TextFaint,
                                    fontSize = Type.Meta)
                            }
                            inner()
                        }
                    },
                )
                Button(
                    onClick = {
                        scope.launch {
                            onSetCwd(cwd)
                                .onSuccess { cwdMsg = "換好了" }
                                .onFailure { cwdMsg = it.message ?: "失敗" }
                        }
                    },
                    enabled = cwd.isNotBlank(),
                    shape = Radii.Chip,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Palette.Accent, contentColor = Palette.Bg,
                    ),
                ) { Text("套用", fontSize = Type.Meta) }
            }
            cwdMsg?.let {
                Text(it, fontSize = Type.Tiny,
                    color = if (it == "換好了") Palette.Ok else Palette.Danger)
            }
        }

        // ── 通知 ────────────────────────────────────────────────────
        // 「助理在待命」那條常駐訊息是 Android 8 起對前景服務的硬性要求，App 端
        // 沒有任何方法隱藏自己的前景服務通知（startForeground 一定要帶一則）。
        // 唯一能讓它消失的是使用者關掉那個通知類別——關掉之後服務照跑、
        // 該來的通知照來，只是通知列乾淨了。所以這裡給的是一鍵到那頁的入口，
        // 而不是假裝 App 能關掉它。
        val ctx = LocalContext.current
        SettingCard("通知") {
            Text(
                "助理做完事情或需要你回答時會蓋一則橫幅在畫面上。你正開著 App 的時候" +
                    "不會跳——內容已經在你眼前了。",
                color = Palette.TextDim, fontSize = Type.Meta,
            )
            Text(
                "通知列常駐的「助理在待命」是 Android 對背景連線的硬性規定，" +
                    "App 不能自己隱藏。在系統設定裡關掉它就看不到了，" +
                    "助理該通知你的時候照樣會通知。",
                color = Palette.TextFaint, fontSize = Type.Tiny,
            )
            Button(
                onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                .putExtra(
                                    Settings.EXTRA_CHANNEL_ID,
                                    NotifyKind.Service.channelId,
                                )
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                shape = Radii.Chip,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.SurfaceHi, contentColor = Palette.Text,
                ),
            ) { Text("關掉「助理在待命」那條", fontSize = Type.Meta) }

            // Android 14 起「全螢幕通知」是可以被使用者關掉的許可。關掉之後鬧鐘
            // 照響，但螢幕不會亮起來、也沒有蓋滿畫面的關閉鈕——睡著的人就這樣錯過。
            // 這個狀態完全無聲，所以只在真的被關掉時把它講出來並給一鍵入口。
            if (!canUseFullScreen(ctx)) {
                Text(
                    "全螢幕鬧鐘被關掉了。現在鬧鐘只會出現一則橫幅，" +
                        "螢幕不會亮起來蓋滿畫面——睡覺時很可能錯過。",
                    color = Palette.Warn, fontSize = Type.Tiny,
                )
                Button(
                    onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                    Uri.parse("package:${ctx.packageName}"),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    shape = Radii.Chip,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Palette.Warn, contentColor = Palette.Bg,
                    ),
                ) { Text("去開啟全螢幕鬧鐘", fontSize = Type.Meta) }
            }
        }

        SettingCard("定位") { LocationRows() }

        SettingCard("連線") {
            // 位址要能改。原本這裡是唯讀的 InfoRow，而唯一寫得了 prefs.host 的地方是
            // 首次設定頁——那頁只在還沒配對時出現。電腦換了 tailnet 位址之後，
            // humanError 會叫使用者「去設定確認主機那一欄」，他到了這裡卻只能看不能改，
            // 唯一的出路是清除 App 資料重來（連 token 一起賠掉）。
            HostRow(prefs)
            InfoRow("狀態", if (state.connected) "已連線" else "未連線")
            state.status?.let {
                if (it.ctxTokens > 0) {
                    InfoRow("目前對話 context", "%,d tokens".format(it.ctxTokens))
                }
            }
        }

        // 放在最後：這是「出事才會用到」的東西，不該擠在每天要調的設定前面。
        DevicesCard(client)
    }
}

/**
 * 已配對的裝置，以及撤銷入口。
 *
 * 後端從 Phase 1 就有 `revoke()`，但一直沒有畫面——手機遺失時得另外找一台
 * 能發 HTTP 請求的機器，而那個情境下人在外面、手上只有另一支手機。
 *
 * 三道防線疊起來，因為這是**不可逆**而且會把人鎖在門外的動作：
 *  1. 自己這一台不給撤銷鈕（伺服器也會擋，但讓人按下去再被拒絕是很差的解釋）
 *  2. 兩段式確認，第二段是紅色的，而且明講撤掉之後會怎樣
 *  3. 真正送出前走一次鎖屏憑證（[rememberDeviceAuth]），撿到手機的人按不下去
 */
@Composable
private fun DevicesCard(client: ButlerClient) {
    val scope = rememberCoroutineScope()
    val auth = rememberDeviceAuth()
    var devices by remember { mutableStateOf<List<DeviceInfo>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    /** 正在確認要撤銷哪一台（存 hash）。null＝沒有任何一列展開確認。 */
    var arming by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        err = null
        client.listDevices()
            .onSuccess { devices = it }
            .onFailure { err = humanError(it) }
    }

    SettingCard("已授權的裝置") {
        Text(
            "手機掉了就在另一台上把它撤掉，撤掉的那台會立刻連不上。",
            color = Palette.TextDim, fontSize = Type.Meta, lineHeight = Type.MetaLine,
        )
        err?.let {
            Text(
                "$it（點一下重試）", color = Palette.Danger, fontSize = Type.Meta,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    .clickable { reload++ },
            )
        }
        val list = devices
        if (list != null && list.isEmpty()) {
            Text(
                "一台都沒有。", color = Palette.TextFaint, fontSize = Type.Meta,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        list?.forEach { d ->
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (d.isThis) "這一台" else d.name.ifBlank { "裝置 ${d.short}" },
                            color = if (d.isThis) Palette.Accent else Palette.Text,
                            fontSize = Type.Body,
                            fontWeight = if (d.isThis) FontWeight.Medium
                                         else FontWeight.Normal,
                        )
                        Text(
                            "${d.short} · 上次活動 ${agoText(d.lastSeen)}",
                            color = Palette.TextFaint, fontSize = Type.Tiny,
                        )
                    }
                    if (!d.isThis && arming != d.hash) {
                        Text(
                            "撤銷", color = Palette.Danger, fontSize = Type.Meta,
                            modifier = Modifier.clip(Radii.Chip)
                                .clickable { arming = d.hash }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
                if (arming == d.hash) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 8.dp)
                            .background(Palette.DangerSoft, Radii.Chip)
                            .border(1.dp, Palette.Danger, Radii.Chip)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "撤掉之後那台要重新配對才連得回來，而配對碼只有在電腦旁邊" +
                                "才拿得到。確定嗎？",
                            color = Palette.Danger, fontSize = Type.Meta,
                            lineHeight = Type.MetaLine,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { arming = null },
                                shape = Radii.Chip,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Palette.SurfaceHi,
                                    contentColor = Palette.Text,
                                ),
                            ) { Text("算了", fontSize = Type.Meta) }
                            Button(
                                onClick = {
                                    // 送出前驗一次本人。沒設鎖屏的手機會直接放行
                                    // （見 needsDeviceAuth）——擋下去只會讓那台
                                    // 手機永遠撤不了東西。
                                    auth(true, true) {
                                        arming = null
                                        busy = true
                                        scope.launch {
                                            client.revokeDevice(d.hash)
                                                .onFailure { err = humanError(it) }
                                            busy = false
                                            reload++   // 不管成敗都重拉，以清單為準
                                        }
                                    }
                                },
                                enabled = !busy,
                                shape = Radii.Chip,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Palette.Danger,
                                    contentColor = Palette.Bg,
                                    disabledContainerColor = Palette.SurfaceHi,
                                    disabledContentColor = Palette.TextFaint,
                                ),
                            ) { Text("確定撤銷", fontSize = Type.Meta) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「3 分鐘前」。
 *
 * last_seen 只活在伺服器記憶體裡（刻意不落磁碟，見 auth.py 的 _LAST_SEEN），
 * 服務一重啟就全部回到 null——那時顯示「沒有紀錄」而不是「很久以前」，
 * 免得看起來像那台裝置已經失聯很久。
 */
private fun agoText(epochSec: Double?): String {
    if (epochSec == null || epochSec <= 0.0) return "沒有紀錄"
    val secs = (System.currentTimeMillis() / 1000.0 - epochSec).toLong()
    return when {
        secs < 60 -> "剛剛"
        secs < 3600 -> "${secs / 60} 分鐘前"
        secs < 86400 -> "${secs / 3600} 小時前"
        else -> "${secs / 86400} 天前"
    }
}

/**
 * 定位權限的狀態與授權入口。
 *
 * 為什麼要有這一塊：權限請求只能從畫面發起，助理在背景是要不到的。沒有這個入口，
 * `where_am_i` 永遠只會回「定位權限沒開」，而使用者不會知道要去哪裡開。
 */
@Composable
private fun LocationRows() {
    val ctx = LocalContext.current

    fun granted(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    var fine by remember { mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION)) }
    var coarse by remember {
        mutableStateOf(granted(Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    // 問過一輪還是沒拿到＝可能被永久拒絕（系統之後不再彈窗），這時只剩系統設定那條路
    var asked by remember { mutableStateOf(false) }

    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { res ->
        fine = res[Manifest.permission.ACCESS_FINE_LOCATION] ?: fine
        coarse = res[Manifest.permission.ACCESS_COARSE_LOCATION] ?: coarse
        asked = true
        // 背景服務要重啟才會帶上 location 這個前景服務型別——它是在 startForeground
        // 當下決定的（見 ButlerService）。不重啟的話「授權了但 App 一進背景就抓不到」，
        // 而這正是助理最常需要問位置的時候。服務已經在跑，再 start 一次只會重跑
        // startForeground 更新型別，不會重連 SSE。
        if (fine || coarse) ButlerService.start(ctx)
    }

    InfoRow(
        "權限",
        when {
            fine -> "精確位置"
            coarse -> "大概位置"
            else -> "沒開"
        },
    )
    Text(
        if (fine || coarse) {
            "每 15 分鐘更新一次位置，助理問的時候就不用等你的手機重抓。" +
                "電腦上只留最新那一筆，不記你去過哪裡。" +
                if (coarse && !fine) "現在只給大概位置，回答得了在哪一區，說不出在哪條路。" else ""
        } else {
            "助理現在不知道你在哪。開了之後每 15 分鐘更新一次目前位置——" +
                "電腦上只留最新那一筆，不會記錄你的行蹤。"
        },
        color = Palette.TextDim, fontSize = Type.Tiny,
    )
    if (!fine && !coarse) {
        Button(
            onClick = {
                if (asked) {
                    // 已經問過一輪還是沒有：系統不會再彈窗了，只能帶去設定頁
                    runCatching {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${ctx.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                } else {
                    ask.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ))
                }
            },
            shape = Radii.Chip,
            colors = ButtonDefaults.buttonColors(
                containerColor = Palette.SurfaceHi, contentColor = Palette.Text,
            ),
        ) { Text(if (asked) "去系統設定開啟" else "允許助理知道我在哪", fontSize = Type.Meta) }
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) = Column(
    Modifier.fillMaxWidth().background(Palette.Surface, Radii.Card)
        // 邊框與標題字級都跟工具頁的卡片對齊
        .border(1.dp, Palette.Line, Radii.Card)
        .padding(Space.Inner),
    verticalArrangement = Arrangement.spacedBy(10.dp),
) {
    Text(title, color = Palette.Text, fontSize = Type.Title, fontWeight = FontWeight.Bold)
    content()
}

/** Android 14 以下一律可用；14 起是可被關掉的許可，要問系統。 */
private fun canUseFullScreen(ctx: android.content.Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return true
    }
    val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return true
    return nm.canUseFullScreenIntent()
}

/**
 * 可編輯的電腦位址。
 *
 * 存好之後**不當場重連**，而是要使用者自己重開 App：連線散在 ViewModel 與
 * ButlerService 兩處，中途換掉 baseUrl 會讓正在跑的那條用舊位址、下一條用新的，
 * 而這個畫面出現的時機本來就是「已經連不上了」。講清楚下一步比偷偷重試有用。
 */
@Composable
private fun HostRow(prefs: Prefs) {
    var host by remember { mutableStateOf(prefs.host) }
    var saved by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("電腦位址", color = Palette.TextDim, fontSize = Type.Meta)
        BasicTextField(
            value = host,
            onValueChange = { host = it; saved = false },
            modifier = Modifier.weight(1f)
                .background(Palette.SurfaceHi, Radii.Chip)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = Type.Meta, color = Palette.Text,
            ),
            singleLine = true,
            cursorBrush = SolidColor(Palette.Accent),
            decorationBox = { inner ->
                Box {
                    if (host.isEmpty()) {
                        Text("100.x.x.x:47362", color = Palette.TextFaint, fontSize = Type.Meta)
                    }
                    inner()
                }
            },
        )
        Button(
            onClick = { prefs.host = host.trim(); saved = true },
            enabled = host.isNotBlank() && host.trim() != prefs.host,
            shape = Radii.Chip,
            colors = ButtonDefaults.buttonColors(
                containerColor = Palette.Accent, contentColor = Palette.Bg,
            ),
        ) { Text("存", fontSize = Type.Meta) }
    }
    if (saved) {
        Text("存好了，重開 App 生效。", color = Palette.Ok, fontSize = Type.Tiny)
    }
}

@Composable
private fun InfoRow(label: String, value: String) = Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(label, color = Palette.TextDim, fontSize = Type.Meta)
    Text(value, color = Palette.Text, fontSize = Type.Meta)
}

@Composable
private fun PickerRow(
    label: String, value: String, options: List<String>,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Palette.TextDim, fontSize = Type.Meta)
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, color = Palette.Accent, fontSize = Type.Meta)
                Icon(
                    Icons.Filled.ArrowDropDown, null,
                    tint = Palette.Accent, modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt, fontSize = Type.Meta,
                            color = if (opt == value) Palette.Accent else Palette.Text) },
                        onClick = { open = false; onPick(opt) },
                    )
                }
            }
        }
    }
}
