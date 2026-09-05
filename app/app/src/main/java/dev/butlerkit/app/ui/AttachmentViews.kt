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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.net.ButlerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 附件的呈現：圖片畫縮圖，其他畫檔案卡。
 *
 * 2026-09-04 之前這裡只有一串路徑文字——選好檔案是檔名、送出去之後是路徑，
 * 使用者：「應該是預覽圖片，如果是其他檔案的話應該要是那種檔案的圖標，
 * 而不是現在的一串文字」。
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
    fitWidth: androidx.compose.ui.unit.Dp? = null,
    onRemove: (() -> Unit)? = null,
) {
    var full by remember(a.path) { mutableStateOf(false) }
    if (full) FullImage(a, client) { full = false }
    Box(modifier) {
        if (a.isImage && a.bytes in 1..THUMB_MAX_BYTES) {
            // 點縮圖看大圖：縮圖上字或細節本來就看不清
            Box(Modifier.clickable(role = Role.Button) { full = true }) {
                Thumb(a, client, size, fitWidth)
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
private fun Thumb(
    a: Attachment,
    client: ButlerClient,
    size: androidx.compose.ui.unit.Dp,
    fitWidth: androidx.compose.ui.unit.Dp? = null,
) {
    var bmp by remember(a.path) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(a.path) { mutableStateOf(false) }
    // decode 的目標像素。原本整張原圖 decode 再交給 Image 一次縮 8 倍，
    // 預設的 bilinear 過濾在這麼大的縮階下就是糊（2026-09-05 使用者回報
    // 「解析度也太低」）。抓顯示尺寸的 1.5 倍：inSampleSize 只能是 2 的冪，
    // 留這個餘裕讓最後一段縮放永遠落在 2 倍以內。
    val density = LocalDensity.current
    val targetPx = with(density) { ((fitWidth ?: size) * 1.5f).roundToPx() }
    LaunchedEffect(a.path) {
        if (bmp != null || failed) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val out = java.io.ByteArrayOutputStream()
                client.downloadUpload(a.stored, out).getOrThrow()
                decodeScaled(out.toByteArray(), targetPx)
            }.getOrNull()
        }
        if (loaded == null) failed = true else bmp = loaded.asImageBitmap()
    }
    val b = bmp
    if (b != null) {
        // 訊息裡的圖照原比例畫，不再裁成正方形——直向截圖被切到只剩中間一塊，
        // 預覽的意義都沒了。比例夾在 [0.62, 1.8]：超長截圖照 Telegram 的做法
        // 顯示上半部，點開看全圖。輸入列那顆小的（fitWidth=null）維持方形。
        val mod = if (fitWidth != null) {
            val ratio = (b.width.toFloat() / b.height).coerceIn(0.62f, 1.8f)
            Modifier.width(fitWidth).aspectRatio(ratio).clip(Radii.Tiny)
        } else {
            Modifier.size(size).clip(Radii.Tiny)
        }
        Image(
            bitmap = b,
            contentDescription = a.name,
            modifier = mod,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
        )
        return
    }
    // 還沒載到、或載不回來：先給一個佔位，不要讓版面跳
    val holder = if (fitWidth != null) {
        Modifier.width(fitWidth).aspectRatio(1.33f)
    } else {
        Modifier.size(size)
    }
    Box(
        holder.clip(Radii.Tiny)
            .background(Palette.SurfaceHi)
            .border(1.dp, Palette.Line, Radii.Tiny),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (failed) "🖼" else "…", color = Palette.TextFaint, fontSize = Type.Meta)
    }
}


/**
 * 兩段式 decode：先讀尺寸、算出 2 的冪的 inSampleSize，短邊貼著 [targetPx] 再真的解。
 * JPEG 解碼器的整數縮階是逐塊平均，畫質遠好於解全圖之後一次 bilinear 縮到底，
 * 也順便讓 4000px 的原圖不會以整張 bitmap 佔著記憶體。
 */
private fun decodeScaled(bytes: ByteArray, targetPx: Int): android.graphics.Bitmap? {
    val probe = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
    if (probe.outWidth <= 0 || probe.outHeight <= 0) return null
    var sample = 1
    // 用短邊算：Crop 貼齊的是短邊，短邊夠清楚整張就夠清楚
    val shorter = minOf(probe.outWidth, probe.outHeight)
    while (shorter / (sample * 2) >= targetPx) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
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
        ext in setOf("apk",) -> "📦"
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
            // 訊息裡的圖照原比例、給到 240dp 寬——120dp 的正方形裁圖既小又只露
            // 中間一塊，看不出內容（2026-09-05 使用者回報「解析度也太低」）
            AttachmentPreview(a, client, fitWidth = 240.dp)
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
                client.downloadUpload(a.stored, out).getOrThrow()
                // 4096 是護欄不是縮圖：一般照片與截圖原尺寸直接過，
                // 只有全景圖那種等級才會被減一階，免得整張 bitmap 撐爆記憶體
                decodeScaled(out.toByteArray(), 4096)
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
                    filterQuality = FilterQuality.High,
                )
                failed -> Text("圖片載不回來，原檔可能已經不在了。",
                    color = Palette.TextFaint, fontSize = Type.Meta)
                else -> Text("載入中…", color = Palette.TextFaint, fontSize = Type.Meta)
            }
        }
    }
}
