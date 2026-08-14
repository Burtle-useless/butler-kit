package dev.butlerkit.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.net.ButlerClient
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
        Text("設定", color = Palette.Text, fontSize = Type.Title)

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
        }

        SettingCard("連線") {
            InfoRow("電腦位址", prefs.host)
            InfoRow("狀態", if (state.connected) "已連線" else "未連線")
            state.status?.let {
                if (it.ctxTokens > 0) {
                    InfoRow("目前對話 context", "%,d tokens".format(it.ctxTokens))
                }
            }
        }
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) = Column(
    Modifier.fillMaxWidth().background(Palette.Surface, Radii.Card).padding(Space.Inner),
    verticalArrangement = Arrangement.spacedBy(10.dp),
) {
    Text(title, color = Palette.Text, fontSize = Type.Body)
    content()
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
            Text("$value ▾", color = Palette.Accent, fontSize = Type.Meta)
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
