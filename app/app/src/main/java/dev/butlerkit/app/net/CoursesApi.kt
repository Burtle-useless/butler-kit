package dev.butlerkit.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.OutputStream
import java.net.URLEncoder

interface CoursesApi {
    /**
     * 課程清單的原始 JSON 文字。跟 agenda 一樣回字串不回物件：呼叫端要原封不動
     * 存進 Prefs 當離線快取，回物件還得再序列化一次。
     */
    suspend fun getCoursesRaw(): Result<String>

    /** 一門課的全貌（`/v1/courses/{name}`），原始 JSON。 */
    suspend fun getCourseRaw(name: String): Result<String>

    /** 把一份教材原檔抓下來寫進 [out]。[path] 是清單給的相對路徑。回寫入的位元組數。 */
    suspend fun downloadCourseFile(name: String, path: String, out: OutputStream): Result<Long>
}

internal class CoursesApiImpl(core: ClientCore) : CoursesApi, ClientCore by core {
    /**
     * 課名是中文資料夾名，放進 URL 路徑段一定要編碼。`URLEncoder` 是給 query 用的，
     * 空白會變 `+`，路徑段裡的 `+` 伺服器不會還原成空白，所以再換成 `%20`。
     */
    private fun seg(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun text(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).auth().build()
            apiClient.newCall(req).execute().use { r ->
                r.failIfBad()
                r.body?.string().orEmpty()
            }
        }
    }

    override suspend fun getCoursesRaw(): Result<String> = text("${prefs.baseUrl}/v1/courses")

    override suspend fun getCourseRaw(name: String): Result<String> =
        text("${prefs.baseUrl}/v1/courses/${seg(name)}")

    override suspend fun downloadCourseFile(
        name: String, path: String, out: OutputStream,
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${prefs.baseUrl}/v1/courses/${seg(name)}/file?path=" +
                URLEncoder.encode(path, "UTF-8")
            val req = Request.Builder().url(url).auth().build()
            apiClient.newCall(req).execute().use { r ->
                r.failIfBad()
                val body = r.body ?: error("空回應")
                body.byteStream().use { it.copyTo(out) }
            }
        }
    }
}
