package dev.butlerkit.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.voice.DictateKey

/**
 * 一體式輸入列：貼在畫面底部，細線與內容區隔開。
 * 右端那顆是 48dp 方框鈕（Android 最小觸控目標），忙碌且沒東西可送時變成停止鈕。
 * 不做 navigationBarsPadding——外層 Root Scaffold 的 padding 已含系統列，
 * 再墊一次就是輸入列懸空的三層 insets 事故之一。
 */
@Composable
internal fun InputDock(
    value: String, busy: Boolean, answering: Boolean,
    attachments: List<Attachment>, uploading: List<String>, uploadError: String?,
    client: dev.butlerkit.app.net.ButlerClient,
    onChange: (String) -> Unit,
    onSend: () -> Unit, onStop: () -> Unit,
    onPickFile: () -> Unit, onRemoveAttach: (String) -> Unit,
) = Column(Modifier.fillMaxWidth()) {
    HorizontalDivider(color = Palette.Line, thickness = 0.6.dp)
    AttachmentStrip(attachments, uploading, uploadError, client, onRemoveAttach)
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
        // 附加檔案。放輸入框左邊（照通訊軟體的慣例），48dp 觸控目標
        Box(
            Modifier.size(48.dp)
                .clip(Radii.Field)
                .border(1.dp, Palette.Line, Radii.Field)
                .clickable(role = Role.Button, onClick = onPickFile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add, "附加檔案",
                tint = Palette.TextDim, modifier = Modifier.size(20.dp),
            )
        }
        // 講話填字。接在現有文字後面，不覆蓋——講一段補打幾個字再講是常見用法
        DictateKey(onText = { onChange(value + it) })
        // 平常不放提示字，空的輸入框本來就看得懂。
        // 只有在等你回答選項題時才提醒一句，那句有功能意義
        Field(
            value, if (answering) "回答上面那題" else "",
            modifier = Modifier.weight(1f),
            lineHeight = Type.BodyLine, maxLines = 5,
            shape = Radii.Bubble,
            pad = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
            hintColor = Palette.Accent.copy(alpha = 0.7f),
            onChange = onChange,
        )
        // 送出與停止**同一顆鍵**（跟 Claude／ChatGPT 官方 App 同一套）：
        //   忙碌且輸入框空著 → 停止（Danger 色的框）；有東西可送 → 送出（實色鈕），
        //   忙碌中照樣能送——那就是排隊，排隊機制「忙的時候還能繼續講」的前提留著。
        // 代價是忙碌中打了字就得先清掉才按得到停止；2026-09-03 之前是兩顆並排，
        // 佔掉輸入框一格寬度而且平常那一格是空的。
        val mode = when {
            canSend -> DockKey.Send
            busy -> DockKey.Stop
            else -> DockKey.Idle
        }
        AnimatedContent(
            targetState = mode,
            // 150ms 淡入淡出＋輕微縮放：看得出換了一顆鍵，但不到會等它的程度
            transitionSpec = {
                (fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.85f))
                    .togetherWith(fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.85f))
            },
            label = "dock-key",
        ) { m ->
            when (m) {
                DockKey.Stop -> Box(
                    Modifier.size(48.dp)
                        .clip(Radii.Field)
                        .border(1.dp, Palette.Danger, Radii.Field)
                        .clickable(role = Role.Button, onClick = onStop),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Stop, "停下來",
                        tint = Palette.Danger, modifier = Modifier.size(20.dp),
                    )
                }
                // 能送＝實色反白鈕；不能送＝空框。Danger 色留給警示，不給主動作
                else -> {
                    val on = m == DockKey.Send
                    Box(
                        Modifier.size(48.dp)
                            .clip(Radii.Field)
                            .background(if (on) Palette.Text else Palette.Bg)
                            .border(1.dp, if (on) Palette.Text else Palette.Line, Radii.Field)
                            .clickable(enabled = on, role = Role.Button, onClick = onSend),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send, "送出",
                            tint = if (on) Palette.Bg else Palette.TextFaint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 輸入列右端那顆鍵此刻是什麼。 */
private enum class DockKey { Idle, Send, Stop }

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
    client: dev.butlerkit.app.net.ButlerClient,
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
                    // 固定高度的單行小標籤：Radii.Chip（8dp）的適用場景就是這個
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
                // 圖片畫縮圖、其他畫檔案卡：選完只看到檔名文字，人不知道自己挑對沒有
                AttachmentPreview(a, client, size = 64.dp) { onRemove(a.path) }
            }
        }
    }
}

internal fun fmtBytes(n: Long): String = when {
    n >= 1024 * 1024 -> "${n / (1024 * 1024)}MB"
    n >= 1024 -> "${n / 1024}KB"
    else -> "${n}B"
}
