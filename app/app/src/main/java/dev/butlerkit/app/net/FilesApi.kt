package dev.butlerkit.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 檔案往返：截圖、手機傳檔上去、助理傳下來的檔案。 */
interface FilesApi {
    suspend fun screenshot(): Result<ByteArray>
    suspend fun uploadFile(name: String, bytes: ByteArray, mime: String): Result<String>
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
     * 逾時放寬到 120 秒：照片走 tailnet 可能不快，20 秒的預設會在手機訊號差時失敗。
     */
    override suspend fun uploadFile(name: String, bytes: ByteArray, mime: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val q = java.net.URLEncoder.encode(name, "UTF-8")
                val req = Request.Builder()
                    .url("${prefs.baseUrl}/v1/uploads?name=$q").auth()
                    .post(bytes.toRequestBody(mime.toMediaType()))
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
