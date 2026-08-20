package dev.butlerkit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.data.ApkUpdate
import dev.butlerkit.app.data.InboxRepo
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.OfferedFile
import kotlinx.coroutines.launch

/**
 * 助理傳來的檔案。
 *
 * 刻意不自動下載：上限 256MB，替使用者決定用掉行動網路不是幫忙。
 * 收到通知、清單上多一列，什麼時候拿他自己決定。
 */
@Composable
fun InboxCard(client: ButlerClient) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by InboxRepo.files.collectAsState()
    val downloading by InboxRepo.downloading.collectAsState()
    val saved by InboxRepo.saved.collectAsState()
    val staged by InboxRepo.staged.collectAsState()
    val error by InboxRepo.error.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().background(Palette.Surface, Radii.Card)
            // 這張卡原本沒有邊框，跟它下面三張工具卡並排時會像少了一層
            .border(1.dp, Palette.Line, Radii.Card)
            .padding(Space.Inner),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Type.Head 而不是 Title：這頁下面三個工具的標題才是 Title。
            // 它是唯一會自己冒出新東西的區塊，字級要比使用者主動來找的東西大一階
            Text(
                "助理傳來的檔案", color = Palette.Text, fontSize = Type.Head,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            )
            Text(
                "重新整理", color = Palette.Accent, fontSize = Type.Tiny,
                modifier = Modifier.minimumInteractiveComponentSize()
                    .clip(Radii.Chip)
                    .clickable { scope.launch { InboxRepo.refresh(client) } }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        error?.let {
            Text(
                it, color = Palette.Danger, fontSize = Type.Meta,
                modifier = Modifier.clickable { InboxRepo.clearError() },
            )
        }

        if (files.isEmpty()) {
            Text(
                "還沒有。它做好圖或報告會直接丟過來。",
                color = Palette.TextFaint, fontSize = Type.Meta,
            )
        }

        // 只露最近幾個。這張卡跟其他工具擠在同一頁，檔案一累積就把下面的東西
        // 全推出畫面，而舊檔案十之八九是已經存過或原檔早就不在的。
        val shown = if (expanded) files else files.take(COLLAPSED)
        shown.forEach { f ->
            // APK 是「一次更新」不是「一個檔案」：下載好之後還有一步要做，
            // 按鈕得跟著換字，不然按完「下載」就沒有下文了
            val ready = ApkUpdate.isApk(f.name) && f.id in staged
            FileRow(
                file = f,
                busy = f.id in downloading,
                savedAt = saved[f.id],
                ready = ready,
                onAct = {
                    if (ready) InboxRepo.install(ctx, f.id)
                    else InboxRepo.startDownload(ctx, client, f)
                },
            )
        }
        if (files.size > COLLAPSED) {
            Row(
                Modifier.fillMaxWidth()
                    .clip(Radii.Chip)
                    .clickable { expanded = !expanded }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (expanded) "收起" else "還有 ${files.size - COLLAPSED} 個",
                    color = Palette.Accent, fontSize = Type.Tiny,
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null, tint = Palette.Accent, modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 折疊起來時露幾個。三個大約是一天內傳過來的量。 */
private const val COLLAPSED = 3

@Composable
private fun FileRow(
    file: OfferedFile,
    busy: Boolean,
    savedAt: String?,
    /** APK 而且已經下載好，按鈕是「安裝」不是「下載」。 */
    ready: Boolean,
    onAct: () -> Unit,
) = Row(
    // Radii.Field 不是 Chip：備註一長、或系統字體放大到 1.3 倍，第二行就會折成兩行，
    // 999dp 的全膠囊跟著漲成一顆巨大藥丸，右邊的「下載」還會變成正圓。
    // 這一列的高度是內容決定的，不能用只在單行成立的形狀
    Modifier.fillMaxWidth().background(Palette.SurfaceHi, Radii.Field)
        .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    // 文字欄吃掉所有剩餘寬度，不留這道縫的話備註會直接貼上「下載」
    horizontalArrangement = Arrangement.spacedBy(10.dp),
) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            file.name,
            color = if (file.gone) Palette.TextFaint else Palette.Text,
            fontSize = Type.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
        )
        // 第二行輪流講最重要的那件事：可以裝了 > 存好了 > 原檔不在了 > 備註 > 大小與時間
        Text(
            when {
                ready -> "已經下載好，按一下就裝"
                savedAt != null -> "已存到 $savedAt"
                file.gone -> "電腦上那個檔案已經不在了"
                file.note.isNotBlank() -> file.note
                else -> "${fmtSize(file.bytes)} · ${file.at.replace('T', ' ')}"
            },
            color = when {
                ready || savedAt != null -> Palette.Ok
                file.gone -> Palette.Danger
                else -> Palette.TextFaint
            },
            // 截斷一定要有刪節號。少了它讀起來像資料只存到一半
            fontSize = Type.Tiny, lineHeight = Type.TinyLine, maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    when {
        busy -> CircularProgressIndicator(
            Modifier.size(18.dp), color = Palette.Accent, strokeWidth = 2.dp,
        )
        file.gone -> Unit
        // 同樣不用 Chip：「下載」只有兩個字，全膠囊會把它捏成一顆正圓
        else -> Box(
            Modifier.clip(Radii.Field)
                // 「再存一次」是收尾動作，不該跟主要動作搶同一個顏色
                .background(if (!ready && savedAt != null) Palette.Surface else Palette.Accent)
                .clickable(onClick = onAct)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                when {
                    ready -> "安裝"
                    savedAt != null -> "再存一次"
                    else -> "下載"
                },
                color = if (!ready && savedAt != null) Palette.TextDim else Palette.Bg,
                fontSize = Type.Tiny, fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun fmtSize(n: Long): String = when {
    n >= 1024 * 1024 -> "%.1fMB".format(n / 1024.0 / 1024.0)
    n >= 1024 -> "${n / 1024}KB"
    else -> "${n}B"
}
