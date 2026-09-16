package dev.butlerkit.app.net

import android.util.Log
import dev.butlerkit.app.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 各領域 API 實作共用的底盤：兩個 OkHttp client、帶 token 的 [auth]、三支 JSON 往返。
 *
 * 做成介面而不是基底類別，是為了讓每個 *ApiImpl 用 `ClientCore by core` 把這些
 * 成員直接攤進自己的作用域——搬過來的方法本文一個字都不用改、而六個實作共用
 * 同一組連線池（各自 new 一個 OkHttpClient 等於六個連線池與執行緒池）。
 */
internal interface ClientCore {
    val prefs: Prefs
    val sseClient: OkHttpClient
    val apiClient: OkHttpClient
    fun Request.Builder.auth(): Request.Builder
    suspend fun getJson(path: String): Result<JSONObject>
    suspend fun postJson(path: String, body: JSONObject): Result<JSONObject>
    suspend fun post(path: String, body: JSONObject): Result<Unit>
}

internal val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

internal class HttpCore(override val prefs: Prefs) : ClientCore {

    /**
     * SSE 專用的 client。
     *
     * readTimeout 要設成「**大於心跳間隔**」，不是 0，也不是預設值：
     *
     * - 預設 10 秒太短：CC 思考期間可能 30 秒以上沒有任何輸出，client 會自己掐斷
     *   連線再無限重連，看起來像伺服器壞掉，其實是自己造成的。
     * - 0（永不逾時）看似安全，實際更糟：連線死掉時若對端沒送出 RST（進程被殺、
     *   網路悄悄斷掉都會這樣），OkHttp 會無限等下去，App 永遠不知道要重連。
     *   實測過——伺服器停掉 30 秒，App 在前景、裝置也沒進 Doze，卻毫無反應。
     *
     * 伺服器每 SSE_KEEPALIVE_SEC(15) 秒送一個 SSE comment。comment 不會觸發
     * onEvent，但它是 TCP 上的實際資料，會重置這個計時器。所以取 3 倍心跳當上限：
     * 正常時永遠不會逾時，連線一死最多 45 秒就被發現並觸發重連。
     */
    override val sseClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(45, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(AccessAuth.interceptor)
        .build()

    /** 一般請求用的 client，這個要有逾時，卡住比失敗更糟。 */
    override val apiClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // 攔截器而不是逐支呼叫加標頭：漏掉任何一支就是那一支被 Cloudflare 擋在門外，
        // 而 /v1/health 那支刻意沒有 .auth()，正是最容易被漏掉的一支。
        .addInterceptor(AccessAuth.interceptor)
        .build()

    override fun Request.Builder.auth() = header("Authorization", "Bearer ${prefs.token}")

    override suspend fun getJson(path: String): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("${prefs.baseUrl}$path").auth().build()
                apiClient.newCall(req).execute().use { r ->
                    r.failIfBad()
                    JSONObject(r.body?.string().orEmpty())
                }
            }
        }

    override suspend fun postJson(path: String, body: JSONObject): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("${prefs.baseUrl}$path").auth()
                    .post(body.toString().toRequestBody(JSON_MEDIA)).build()
                apiClient.newCall(req).execute().use { r ->
                    r.failIfBad()
                    JSONObject(r.body?.string().orEmpty())
                }
            }
        }

    override suspend fun post(path: String, body: JSONObject): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("${prefs.baseUrl}$path")
                    .auth()
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .build()
                apiClient.newCall(req).execute().use { r ->
                    r.failIfBad()
                }
            }
        }
}
