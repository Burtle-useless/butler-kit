package dev.butlerkit.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 回覆裡用 `![說明](網址)` 引到的圖。
 *
 * **只認 http(s)。** 本機路徑一律不碰——那會變成「模型寫一行 markdown 就能叫
 * App 去讀手機上任何一個檔案」，而回覆內容不是可信輸入。助理自己產的圖走
 * `file.offer`（見 `ChatCards.FileOfferCard`），那條路有伺服器的授權擋著。
 *
 * 這裡**不帶 butler 的 token**，用的是另一個乾淨的 OkHttpClient：目標是外面的
 * 網站，把自家的憑證送出去沒有道理。
 *
 * 沒有引圖片載入函式庫：要的就是「下載、解碼、記住」三件事，而外部圖片在對話裡
 * 是偶爾出現一張的東西，為它多背一個相依不划算。
 */
internal object RemoteImages {
    /** 一張手機螢幕大小的圖大約 4–8 MB，留 6 張的位置。 */
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 抓下來超過這個大小就不解了。對話裡的插圖沒有理由比這個大。 */
    private const val MAX_BYTES = 12L * 1024 * 1024

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun peek(url: String): Bitmap? = cache.get(url)

    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).build()
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@withContext null
                    val body = res.body ?: return@withContext null
                    if (body.contentLength() > MAX_BYTES) return@withContext null
                    val raw = body.byteStream().readBytes()
                    if (raw.size > MAX_BYTES) return@withContext null
                    // 解碼是純 CPU 的活，已經在 IO 執行緒上了，不會卡住畫面
                    BitmapFactory.decodeByteArray(raw, 0, raw.size)
                        ?.also { cache.put(url, it) }
                }
            } catch (e: Exception) {
                // 網路不通、網址壞掉、不是圖片——在對話裡都只是「這張看不到」，
                // 不該讓整段回覆連帶炸掉
                null
            }
        }
    }
}

/**
 * 畫一張外部圖片。載入中與失敗都留一行字，不留空白——空白看起來像排版壞了。
 */
@Composable
internal fun MarkdownImage(url: String, alt: String, modifier: Modifier = Modifier) {
    var bmp by remember(url) { mutableStateOf(RemoteImages.peek(url)) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (bmp == null) {
            val got = RemoteImages.load(url)
            if (got == null) failed = true else bmp = got
        }
    }

    val caption = alt.ifBlank { "圖片" }
    when {
        bmp != null -> Box(modifier.fillMaxWidth()) {
            Image(
                bitmap = bmp!!.asImageBitmap(),
                contentDescription = alt,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(Radii.Tiny),
            )
        }
        failed -> Text(
            "［$caption 載不到］",
            color = Palette.TextFaint, fontSize = Type.Meta,
            modifier = modifier,
        )
        else -> Box(
            modifier.fillMaxWidth().clip(Radii.Tiny)
                .background(Palette.Surface).padding(12.dp),
        ) {
            Text("載入 $caption…", color = Palette.TextFaint, fontSize = Type.Meta)
        }
    }
}
