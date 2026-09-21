package dev.butlerkit.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** 檔案往返：截圖、手機傳檔上去、助理傳下來的檔案。 */
interface FilesApi {
    suspend fun screenshot(): Result<ByteArray>

    /**
     * 傳一個檔案到電腦，回它在電腦上的絕對路徑。
     *
     * 收的是「怎麼打開這個檔」而不是 ByteArray：上限 100MB，整份讀進記憶體等於
     * 傳一支影片就多佔 100MB 的 heap，低階手機會直接 OOM——而 OOM 的樣子是
     * App 整個消失，不是一句「上傳失敗」。[size] 小於等於零＝查不到大小，
     * 交給 OkHttp 走 chunked。
     */
    suspend fun uploadFile(
        name: String, mime: String, size: Long, openStream: () -> InputStream,
    ): Result<String>
    suspend fun listOfferedFiles(): Result<List<OfferedFile>>
    suspend fun downloadOfferedFile(fileId: String, out: java.io.OutputStream): Result<Long>

    /**
     * 取回自己傳上去的檔案（`/v1/uploads/{name}`）。畫使用者訊息裡的縮圖用。
     *
     * 不能靠手機本地的 Uri：那份只有「剛選檔的這台裝置、這一次」有，
     * 換裝置或重建畫面之後圖就沒了。
     */
    suspend fun downloadUpload(name: String, out: java.io.OutputStream): Result<Long>
}

/**
 * 從 InputStream 直接串出去的 request body，中間不落一份完整的副本。
 *
 * [size] 大於零就當 Content-Length 送（伺服器與 Cloudflare 都看得到大小、能提早擋）；
 * 查不到大小時回 -1，OkHttp 會改用 chunked。
 */
private class StreamBody(
    private val mime: MediaType,
    private val size: Long,
    private val open: () -> InputStream,
) : RequestBody() {
    override fun contentType(): MediaType = mime
    override fun contentLength(): Long = if (size > 0) size else -1L
    override fun writeTo(sink: BufferedSink) {
        open().use { input -> input.source().use { src -> sink.writeAll(src) } }
    }
}

internal class FilesApiImpl(core: ClientCore) : FilesApi, ClientCore by core {

    /** 截電腦畫面。回 PNG bytes，由呼叫端 decode。 */
    override suspend fun screenshot(): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${prefs.baseUrl}/v1/tools/screenshot")
                .auth().build()
            // 雙螢幕截圖 + PNG 壓縮可能要幾秒，放寬這一個呼叫的逾時
            apiClient.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
                .newCall(req).execute().use { r ->
                    r.failIfBad()
                    r.body?.bytes() ?: error("empty body")
                }
        }
    }

    /**
     * 傳檔到電腦，回傳它在電腦上的絕對路徑。
     *
     * body 是原始 bytes、檔名走 query 參數（伺服器端刻意不收 multipart）。
     * 逾時放寬到 120 秒：照片走行動網路可能不快，20 秒的預設會在訊號差時失敗。
     * **這兩個逾時是「單次 socket 讀寫」的上限，不是整個請求的**——所以一支
     * 七十幾 MB 的影片傳上五分鐘也不會被它砍掉，只有真的卡住才會。
     */
    override suspend fun uploadFile(
        name: String, mime: String, size: Long, openStream: () -> InputStream,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val q = java.net.URLEncoder.encode(name, "UTF-8")
            val req = Request.Builder()
                .url("${prefs.baseUrl}/v1/uploads?name=$q").auth()
                .post(StreamBody(mime.toMediaType(), size, openStream))
                .build()
            apiClient.newBuilder()
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
                .newCall(req).execute().use { r ->
                    JSONObject(r.textOrThrow()).getString("path")
                }
        }
    }

    // ── 助理傳來的檔案 ──────────────────────────────────────────────────────
    override suspend fun listOfferedFiles(): Result<List<OfferedFile>> = getJson("/v1/files")
        .mapCatching { o ->
            val arr = o.getJSONArray("files")
            (0 until arr.length()).map { arr.getJSONObject(it).toOfferedFile() }
        }

    /**
     * 下載一個檔案，直接寫進呼叫端給的 [out]，回傳寫了幾個 byte。
     *
     * 刻意不回 ByteArray：上限是 256MB，整份讀進記憶體會讓手機直接 OOM。
     * 串流複製的話多大都只佔一個 buffer。
     */
    override suspend fun downloadOfferedFile(fileId: String, out: java.io.OutputStream):
        Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${prefs.baseUrl}/v1/files/$fileId").auth().build()
            apiClient.newBuilder()
                .readTimeout(300, TimeUnit.SECONDS)
                .build()
                .newCall(req).execute().use { r ->
                    r.failIfBad()
                    val body = r.body ?: error("空回應")
                    body.byteStream().use { it.copyTo(out) }
                }
        }
    }

    override suspend fun downloadUpload(name: String, out: java.io.OutputStream):
        Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${prefs.baseUrl}/v1/uploads/${java.net.URLEncoder.encode(name, "UTF-8")}")
                .auth().build()
            apiClient.newCall(req).execute().use { r ->
                r.failIfBad()
                val body = r.body ?: error("空回應")
                body.byteStream().use { it.copyTo(out) }
            }
        }
    }
}
