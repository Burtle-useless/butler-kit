package dev.butlerkit.app.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自己的挑檔面板：最近的相片直接排成格狀，點一下就附上。
 *
 * 2026-09-04 使用者：「目前手機上選擇檔案是直接跳過去安卓原生的檔案程式選的樣子，
 * 那個還蠻難用的，通常程式都會自己做一個檔案選擇工具吧」。
 *
 * **刻意只做相片格，不做完整的檔案瀏覽器。** 絕大多數情況要附的就是剛截的圖或剛拍的
 * 照片，那條路要快；真的要翻資料夾時系統挑選器該做的事它做得比自己刻的好（權限、
 * 雲端硬碟、最近使用），所以底下永遠留一顆「其他檔案…」通到它。
 *
 * 沒有相片權限時不強迫要——面板直接顯示一顆「其他檔案…」，跟以前一樣可用。
 */

/** 一次讀幾張。手機相簿動輒上萬張，全撈進來只是白等。 */
private const val RECENT_LIMIT = 60

private data class Shot(val uri: Uri, val id: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerSheet(
    onPicked: (List<Uri>) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var shots by remember { mutableStateOf<List<Shot>>(emptyList()) }
    var granted by remember { mutableStateOf(hasPhotoPermission(ctx)) }

    val system = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
        onDismiss()
    }
    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok }

    LaunchedEffect(granted) {
        if (granted) shots = withContext(Dispatchers.IO) { recentPhotos(ctx) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Palette.Surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "最近的相片", color = Palette.Text, fontSize = Type.Title,
                fontWeight = FontWeight.Bold,
            )
            when {
                !granted -> {
                    Text(
                        "要看最近的相片得先給讀取權限；不給也沒關係，可以直接用下面那顆挑檔案。",
                        color = Palette.TextDim, fontSize = Type.Meta,
                    )
                    SheetButton("允許讀取相片") { ask.launch(photoPermission()) }
                }
                shots.isEmpty() -> Text(
                    "相簿裡沒有找到相片。", color = Palette.TextFaint, fontSize = Type.Meta,
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(shots, key = { it.id }) { s ->
                        ShotCell(s) { onPicked(listOf(s.uri)); onDismiss() }
                    }
                }
            }
            SheetButton("其他檔案…") { system.launch(arrayOf("*/*")) }
        }
    }
}

@Composable
private fun SheetButton(label: String, onClick: () -> Unit) = Box(
    Modifier.fillMaxWidth()
        .clip(Radii.Field)
        .background(Palette.SurfaceHi)
        .clickable(role = Role.Button, onClick = onClick)
        .padding(vertical = 14.dp),
    contentAlignment = Alignment.Center,
) {
    Text(label, color = Palette.Text, fontSize = Type.Body)
}

@Composable
private fun ShotCell(s: Shot, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(s.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(s.id) {
        bmp = withContext(Dispatchers.IO) { loadThumb(ctx, s) }
    }
    Box(
        Modifier.aspectRatio(1f).clip(Radii.Tiny)
            .background(Palette.SurfaceHi)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        bmp?.let {
            Image(
                bitmap = it, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
            )
        }
    }
}

private fun photoPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private fun hasPhotoPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, photoPermission()) == PackageManager.PERMISSION_GRANTED

/** 相簿裡最近的幾張，新到舊。任何失敗都回空清單——挑檔面板不能因此打不開。 */
private fun recentPhotos(ctx: Context): List<Shot> = runCatching {
    val cols = arrayOf(MediaStore.Images.Media._ID)
    val out = mutableListOf<Shot>()
    ctx.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC",
    )?.use { c ->
        val idx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        while (c.moveToNext() && out.size < RECENT_LIMIT) {
            val id = c.getLong(idx)
            out += Shot(
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                id,
            )
        }
    }
    out
}.getOrDefault(emptyList())

/**
 * 縮圖。Android 10 起有 loadThumbnail，舊版退回自己 decode 並降取樣。
 *
 * 跟系統要 512px 而不是 256：三欄格子在 1440 寬的螢幕上一格就是 480px，
 * 256 的縮圖等於放大快兩倍畫，整片格子都是糊的（2026-09-05 使用者截圖回報
 * 「解析度也太低」——說的就是這個面板）。512 對高密度螢幕剛好貼齊，
 * MediaStore 的縮圖有系統快取，成本差不了多少。
 */
private fun loadThumb(ctx: Context, s: Shot): ImageBitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ctx.contentResolver.loadThumbnail(s.uri, android.util.Size(512, 512), null)
            .asImageBitmap()
    } else {
        ctx.contentResolver.openInputStream(s.uri)?.use { input ->
            // 舊路寫死 inSampleSize=8 對手機截圖（1440 寬）等於縮到 180px，更糊。
            // 先探尺寸再算，讓短邊落在 512～1024 之間
            val probe = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeStream(input, null, probe)
            var sample = 1
            val shorter = minOf(probe.outWidth, probe.outHeight)
            while (shorter / (sample * 2) >= 512) sample *= 2
            ctx.contentResolver.openInputStream(s.uri)?.use { again ->
                val o = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                }
                android.graphics.BitmapFactory.decodeStream(again, null, o)?.asImageBitmap()
            }
        }
    }
}.getOrNull()
