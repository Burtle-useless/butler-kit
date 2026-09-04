package dev.butlerkit.app.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.net.ButlerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 附件的呈現：圖片畫縮圖，其他畫檔案卡。
 *
 * 附件先前只是一串路徑文字——選好檔案是檔名、送出去之後是電腦上的絕對路徑。
 * 那串路徑對使用者沒有意義，他要的是「我挑對了嗎」。
 *
 * 圖從**伺服器**取回（`GET /v1/uploads/{name}`）而不是用手機本地的 Uri：本地那份
 * 只有「剛剛選檔的這台裝置、這一次」有，換裝置、重開 App、重建畫面之後就沒有了，
 * 那則訊息裡的圖會退回一行檔名。
 */

/** 縮圖上限：手機上一列縮圖不需要原圖，抓太大只是浪費行動網路。 */
private const val THUMB_MAX_BYTES = 8L * 1024 * 1024

@Composable
fun AttachmentPreview(
    a: Attachment,
    client: ButlerClient,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 72.dp,
    onRemove: (() -> Unit)? = null,
) {
    var full by remember(a.path) { mutableStateOf(false) }
    if (full) FullImage(a, client) { full = false }
    Box(modifier) {
        if (a.isImage && a.bytes in 1..THUMB_MAX_BYTES) {
            // 點縮圖看大圖：手機上縮圖只有 120dp，字或細節根本看不清
            Box(Modifier.clickable(role = Role.Button) { full = true }) {
                Thumb(a, client, size)
            }
        } else {
            FileChip(a)
        }
        if (onRemove != null) {
            // 移除鈕壓在右上角。縮圖本身就是觸控目標，這顆只要按得到就好
            Box(
                Modifier.align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(22.dp)
                    .background(Palette.Bg, Radii.Chip)
                    .clickable(role = Role.Button, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = Palette.TextDim, fontSize = Type.Meta)
            }
        }
    }
}

@Composable
private fun Thumb(a: Attachment, client: ButlerClient, size: androidx.compose.ui.unit.Dp) {
    var bmp by remember(a.path) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(a.path) { mutableStateOf(false) }
    LaunchedEffect(a.path) {
        if (bmp != null || failed) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val out = java.io.ByteArrayOutputStream()
                client.downloadUpload(a.name, out).getOrThrow()
                val bytes = out.toByteArray()
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        if (loaded == null) failed = true else bmp = loaded.asImageBitmap()
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b,
            contentDescription = a.name,
            modifier = Modifier.size(size).clip(Radii.Tiny),
            contentScale = ContentScale.Crop,
        )
        return
    }
    // 還沒載到、或載不回來：先給一個佔位，不要讓版面跳
    Box(
        Modifier.size(size).clip(Radii.Tiny)
            .background(Palette.SurfaceHi)
            .border(1.dp, Palette.Line, Radii.Tiny),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (failed) "🖼" else "…", color = Palette.TextFaint, fontSize = Type.Meta)
    }
}

/** 非圖片：一張帶副檔名圖示的卡。 */
@Composable
private fun FileChip(a: Attachment) {
    Row(
        Modifier.background(Palette.SurfaceHi, Radii.Chip)
            .border(1.dp, Palette.Line, Radii.Chip)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .widthIn(max = 200.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(fileGlyph(a.name, a.mime), fontSize = Type.Title)
        Column {
            Text(
                a.name, color = Palette.Text, fontSize = Type.Meta,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(fmtBytes(a.bytes), color = Palette.TextFaint, fontSize = Type.Tiny)
        }
    }
}

/**
 * 副檔名 → 一個字的圖示。
 *
 * 刻意用 emoji 而不是拉一套圖示資源：這裡要的是「一眼分得出是哪一類」，
 * 不是精確的檔案型別辨識，而多帶一包資源要維護深色淺色兩套。
 */
fun fileGlyph(name: String, mime: String = ""): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("image/") -> "🖼"
        mime.startsWith("video/") || ext in setOf("mp4", "mov", "mkv", "avi") -> "🎬"
        mime.startsWith("audio/") || ext in setOf("mp3", "wav", "ogg", "m4a", "flac") -> "🎵"
        ext == "pdf" -> "📕"
        ext in setOf("doc", "docx") -> "📘"
        ext in setOf("xls", "xlsx", "csv") -> "📗"
        ext in setOf("ppt", "pptx") -> "📙"
        ext in setOf("zip", "7z", "rar", "tar", "gz") -> "🗜"
        ext in setOf("apk") -> "📦"
        ext in setOf("py", "kt", "js", "ts", "java", "c", "cpp", "cs", "go", "rs",
                     "sh", "ps1", "html", "css", "json", "yml", "yaml", "toml") -> "📄"
        ext in setOf("txt", "md", "log") -> "📝"
        else -> "📎"
    }
}

/** 一則訊息底下的附件列（送出後畫在氣泡下方）。 */
@Composable
fun AttachmentRow(
    items: List<Attachment>,
    client: ButlerClient,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { a ->
            AttachmentPreview(a, client, size = 120.dp)
        }
    }
}

/**
 * 未答的提問：釘在輸入框上方的一條，點了跳回那張卡。
 *
 * 卡片本身留在軌跡裡（那是紀錄），這一條只負責「不論畫面被推多遠都知道有東西在等」。
 */
@Composable
fun PendingAskBar(title: String, onJump: () -> Unit) = Row(
    Modifier.fillMaxWidth()
        .background(Palette.SurfaceHi)
        .clickable(role = Role.Button, onClick = onJump)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    Text("❓", fontSize = Type.Meta)
    Text(
        title.ifBlank { "有一個問題等你回答" },
        color = Palette.Text, fontSize = Type.Meta,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    Text("看一下", color = Palette.Accent, fontSize = Type.Meta)
}


/**
 * 點縮圖之後的全螢幕檢視：整張圖依寬度縮放，點任何地方關掉。
 *
 * 用 Dialog 而不是另開一個畫面：這是「看一眼」的動作，不該進返回堆疊，
 * 也不該讓對話捲動位置跑掉。
 */
@Composable
private fun FullImage(a: Attachment, client: ButlerClient, onClose: () -> Unit) {
    var bmp by remember(a.path) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(a.path) { mutableStateOf(false) }
    LaunchedEffect(a.path) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val out = java.io.ByteArrayOutputStream()
                client.downloadUpload(a.name, out).getOrThrow()
                val bytes = out.toByteArray()
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        if (loaded == null) failed = true else bmp = loaded.asImageBitmap()
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .background(Palette.Bg)
                .clickable(role = Role.Button, onClick = onClose)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            when {
                b != null -> Image(
                    bitmap = b, contentDescription = a.name,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
                failed -> Text("圖片載不回來，原檔可能已經不在了。",
                    color = Palette.TextFaint, fontSize = Type.Meta)
                else -> Text("載入中…", color = Palette.TextFaint, fontSize = Type.Meta)
            }
        }
    }
}
