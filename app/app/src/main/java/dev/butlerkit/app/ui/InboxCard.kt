package dev.butlerkit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val error by InboxRepo.error.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().background(Palette.Surface, Radii.Card)
            .padding(Space.Inner),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("助理傳來的檔案", color = Palette.Text, fontSize = Type.Body,
                modifier = Modifier.weight(1f))
            Text(
                "重新整理", color = Palette.Accent, fontSize = Type.Tiny,
                modifier = Modifier.clickable {
                    scope.launch { InboxRepo.refresh(client) }
                }.padding(4.dp),
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
            FileRow(
                file = f,
                busy = f.id in downloading,
                savedAt = saved[f.id],
                onDownload = { InboxRepo.startDownload(ctx, client, f) },
            )
        }
        if (files.size > COLLAPSED) {
            Text(
                if (expanded) "收起 ▴" else "還有 ${files.size - COLLAPSED} 個 ▾",
                color = Palette.Accent, fontSize = Type.Tiny,
                modifier = Modifier.fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
            )
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
    onDownload: () -> Unit,
) = Row(
    Modifier.fillMaxWidth().background(Palette.SurfaceHi, Radii.Chip)
        .padding(horizontal = 10.dp, vertical = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f)) {
        Text(
            file.name,
            color = if (file.gone) Palette.TextFaint else Palette.Text,
            fontSize = Type.Meta, maxLines = 1, fontWeight = FontWeight.Medium,
        )
        // 第二行輪流講最重要的那件事：存好了 > 原檔不在了 > 備註 > 大小與時間
        Text(
            when {
                savedAt != null -> "已存到 $savedAt"
                file.gone -> "電腦上那個檔案已經不在了"
                file.note.isNotBlank() -> file.note
                else -> "${fmtSize(file.bytes)} · ${file.at.replace('T', ' ')}"
            },
            color = when {
                savedAt != null -> Palette.Ok
                file.gone -> Palette.Danger
                else -> Palette.TextFaint
            },
            fontSize = Type.Tiny, maxLines = 2,
        )
    }
    when {
        busy -> CircularProgressIndicator(
            Modifier.size(18.dp), color = Palette.Accent, strokeWidth = 2.dp,
        )
        file.gone -> Unit
        else -> Box(
            Modifier.background(
                if (savedAt != null) Palette.Surface else Palette.Accent, Radii.Chip,
            ).clickable(onClick = onDownload)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                if (savedAt != null) "再存一次" else "下載",
                color = if (savedAt != null) Palette.TextDim else Palette.Bg,
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
