package dev.butlerkit.app.net

import android.util.Log
import dev.butlerkit.app.data.Prefs
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 連線狀態或一則事件。UI 兩者都要，所以走同一條 Flow。 */
sealed interface Wire {
    data class Ev(val event: ServerEvent) : Wire
    data class Conn(val connected: Boolean, val error: String? = null) : Wire
}

data class ConvInfo(val id: String, val title: String, val mtime: Double)

/** 電腦上既有的 Claude Code session（還沒被助理接管的那些）。 */
data class LocalSession(
    val sessionId: String,
    val cwd: String,
    val title: String,
    val mtime: Double,
    val branch: String,
    val sidechain: Boolean,
)

data class SearchHit(
    val convId: String, val title: String, val role: String, val snippet: String,
)

data class HistoryMsg(val role: String, val text: String)

data class Snapshot(
    val busy: Boolean,
    val queued: Int,
    val messages: List<HistoryMsg>,
    // 對話層狀態（等同 cc-bot 的 /status）。model/effort 是生效值，
    // override 是「這個對話有沒有單獨覆寫」（空字串＝跟隨帳號預設）
    val model: String = "",
    val effort: String = "",
    val modelOverride: String = "",
    val effortOverride: String = "",
    val cwd: String = "",
    val ctxTokens: Int = 0,
    /** context 上限，隨模型變（Opus 在 max 方案是 1M）。用量長條的分母。 */
    val ctxLimit: Int = 200_000,
)

data class SettingsInfo(
    val model: String?,
    val effort: String?,
    val models: List<String>,
    val efforts: List<String>,
)

class ButlerClient(private val prefs: Prefs) {

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
    private val sseClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(45, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 一般請求用的 client，這個要有逾時，卡住比失敗更糟。 */
    private val apiClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun Request.Builder.auth() = header("Authorization", "Bearer ${prefs.token}")

    /**
     * 訂閱事件流。帶 lastSeq 即從該序號之後續傳。
     *
     * 斷線重連由呼叫端負責（ViewModel 用 retryWhen 加退避）——放在這裡的話，
     * 重連時的 lastSeq 會是舊的，續傳就會漏。
     */
    fun stream(lastSeq: Long): Flow<Wire> = callbackFlow {
        val url = "${prefs.baseUrl}/v1/stream"
        Log.i(TAG, "SSE 連線中 url=$url lastSeq=$lastSeq tokenLen=${prefs.token.length}")
        val req = Request.Builder()
            .url(url)
            .auth()
            .apply { if (lastSeq >= 0) header("Last-Event-ID", lastSeq.toString()) }
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.i(TAG, "SSE 已連上 code=${response.code}")
                trySend(Wire.Conn(connected = true))
            }

            override fun onEvent(
                eventSource: EventSource, id: String?, type: String?, data: String,
            ) {
                // 壞掉的單一事件跳過就好，不要讓它終止整條連線
                val ev = ServerEvent.parse(id, type, data)
                if (ev == null) {
                    Log.w(TAG, "事件解析失敗 id=$id type=$type raw=${data.take(120)}")
                } else {
                    Log.d(TAG, "事件 seq=${ev.seq} type=${ev.type}")
                    trySend(Wire.Ev(ev))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                Log.i(TAG, "SSE 已關閉")
                trySend(Wire.Conn(connected = false))
                close()
            }

            override fun onFailure(
                eventSource: EventSource, t: Throwable?, response: Response?,
            ) {
                val reason = when {
                    response?.code == 401 -> "裝置未授權（401）"
                    t != null -> t.message ?: t.javaClass.simpleName
                    else -> "連線中斷"
                }
                Log.e(TAG, "SSE 失敗 code=${response?.code} reason=$reason", t)
                trySend(Wire.Conn(connected = false, error = reason))
                close()
            }
        }

        val source = EventSources.createFactory(sseClient).newEventSource(req, listener)
        awaitClose { source.cancel() }
    }

    suspend fun sendMessage(convId: String, text: String): Result<Unit> =
        post("/v1/conversations/$convId/message", JSONObject().put("text", text))

    /**
     * 回答提問。[text] 是自己打的字，伺服器端優先採用它（`answer.text or choice_id`），
     * 所以想講的話不在選項裡時不必勉強挑一個。
     */
    suspend fun answerAsk(
        askId: String, choiceId: String, text: String? = null,
    ): Result<Unit> = post(
        "/v1/asks/$askId/answer",
        JSONObject().put("choice_id", choiceId).apply {
            if (!text.isNullOrBlank()) put("text", text)
        },
    )

    suspend fun stopTurn(convId: String): Result<Unit> =
        post("/v1/conversations/$convId/stop", JSONObject())

    suspend fun listConversations(): Result<List<ConvInfo>> = getJson("/v1/conversations")
        .mapCatching { obj ->
            val arr = obj.getJSONArray("conversations")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ConvInfo(
                    id = o.getString("conv_id"),
                    title = o.optString("title", o.getString("conv_id")),
                    mtime = o.optDouble("mtime", 0.0),
                )
            }
        }

    suspend fun createConversation(): Result<String> =
        postJson("/v1/conversations", JSONObject()).mapCatching { it.getString("conv_id") }

    /** 電腦上還沒接管的 session。[q] 有值時比對開場白與工作目錄。 */
    suspend fun listLocalSessions(q: String = ""): Result<List<LocalSession>> =
        getJson("/v1/sessions?limit=60&q=" + java.net.URLEncoder.encode(q, "UTF-8"))
            .mapCatching { obj ->
                val arr = obj.getJSONArray("sessions")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    LocalSession(
                        sessionId = o.getString("session_id"),
                        cwd = o.optString("cwd"),
                        title = o.optString("title"),
                        mtime = o.optDouble("mtime", 0.0),
                        branch = o.optString("branch"),
                        sidechain = o.optBoolean("sidechain"),
                    )
                }
            }

    /** 把一個既有 session 接成新對話，回新對話 id。 */
    suspend fun adoptSession(sessionId: String): Result<String> =
        postJson("/v1/sessions/$sessionId/adopt", JSONObject())
            .mapCatching { it.getString("conv_id") }

    suspend fun deleteConversation(convId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("${prefs.baseUrl}/v1/conversations/$convId")
                    .auth().delete().build()
                apiClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                }
            }
        }

    suspend fun getSettings(): Result<SettingsInfo> = getJson("/v1/settings")
        .mapCatching { o ->
            fun arr(k: String) = o.getJSONArray(k).let { a ->
                (0 until a.length()).map { a.getString(it) }
            }
            SettingsInfo(
                model = o.optString("model").takeIf { it.isNotBlank() && it != "null" },
                effort = o.optString("effort").takeIf { it.isNotBlank() && it != "null" },
                models = arr("models"),
                efforts = arr("efforts"),
            )
        }

    suspend fun setSettings(model: String?, effort: String?): Result<Unit> =
        post("/v1/settings", JSONObject().apply {
            put("model", model ?: "")
            put("effort", effort ?: "")
        })

    suspend fun setCwd(convId: String, path: String): Result<Unit> =
        post("/v1/conversations/$convId/cwd", JSONObject().put("path", path))

    /** 拉對話全貌：歷史訊息＋伺服器端 busy 真值。切對話與冷啟動時用。 */
    suspend fun snapshot(convId: String): Result<Snapshot> =
        getJson("/v1/conversations/$convId/snapshot").mapCatching { o ->
            val arr = o.getJSONArray("messages")
            Snapshot(
                busy = o.optBoolean("busy"),
                queued = o.optInt("queued"),
                messages = (0 until arr.length()).map { i ->
                    val m = arr.getJSONObject(i)
                    HistoryMsg(role = m.getString("role"), text = m.getString("text"))
                },
                model = o.optString("model"),
                effort = o.optString("effort"),
                modelOverride = o.optString("model_override"),
                effortOverride = o.optString("effort_override"),
                cwd = o.optString("cwd"),
                ctxTokens = o.optInt("ctx_tokens"),
                ctxLimit = o.optInt("ctx_limit", 200_000),
            )
        }

    /** 只覆寫這一個對話的 model/effort。空字串＝清除覆寫、回到跟隨帳號預設。 */
    suspend fun setConvSettings(convId: String, model: String?, effort: String?): Result<Unit> =
        post("/v1/conversations/$convId/settings", JSONObject().apply {
            if (model != null) put("model", model)
            if (effort != null) put("effort", effort)
        })

    suspend fun search(query: String): Result<List<SearchHit>> =
        getJson("/v1/search?q=" + java.net.URLEncoder.encode(query, "UTF-8"))
            .mapCatching { obj ->
                val arr = obj.getJSONArray("hits")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    SearchHit(
                        convId = o.getString("conv_id"),
                        title = o.optString("title"),
                        role = o.optString("role"),
                        snippet = o.optString("snippet"),
                    )
                }
            }

    /** 截電腦畫面。回 PNG bytes，由呼叫端 decode。 */
    suspend fun screenshot(): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${prefs.baseUrl}/v1/tools/screenshot")
                .auth().build()
            // 雙螢幕截圖 + PNG 壓縮可能要幾秒，放寬這一個呼叫的逾時
            apiClient.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
                .newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
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
    suspend fun uploadFile(name: String, bytes: ByteArray, mime: String): Result<String> =
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
                        val body = r.body?.string().orEmpty()
                        if (!r.isSuccessful) error("HTTP ${r.code}: $body")
                        JSONObject(body).getString("path")
                    }
            }
        }

    // ── 助理傳來的檔案 ──────────────────────────────────────────────────────
    suspend fun listOfferedFiles(): Result<List<OfferedFile>> = getJson("/v1/files")
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
    suspend fun downloadOfferedFile(fileId: String, out: java.io.OutputStream):
        Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${prefs.baseUrl}/v1/files/$fileId").auth().build()
            apiClient.newBuilder()
                .readTimeout(300, TimeUnit.SECONDS)
                .build()
                .newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        val text = r.body?.string().orEmpty()
                        error(runCatching { JSONObject(text).getString("detail") }
                            .getOrDefault("HTTP ${r.code}"))
                    }
                    val body = r.body ?: error("空回應")
                    body.byteStream().use { it.copyTo(out) }
                }
        }
    }

    suspend fun getUsage(span: Int = 14): Result<UsageReport> =
        getJson("/v1/usage?span=$span").mapCatching { parseUsage(it) }

    // ── 行事曆／鬧鐘／記帳 ────────────────────────────────────────────────
    /**
     * 三類一次拉完，回**原始 JSON 文字**。助理改過資料時伺服器會推 agenda.changed，
     * 收到就重呼叫這個。
     *
     * 刻意回字串而不是解析好的物件：呼叫端要原封不動存進 Prefs 當開機快取，
     * 回物件的話還得再序列化一次，那份序列化跟伺服器的格式遲早會走鐘。
     */
    suspend fun getAgendaRaw(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("${prefs.baseUrl}/v1/agenda").auth().build()
            apiClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        }
    }

    /** month 傳空字串代表這個月。 */
    suspend fun getSummary(month: String = ""): Result<MonthSummary> =
        getJson("/v1/agenda/summary?month=$month").mapCatching { o ->
            val cats = o.optJSONObject("by_category")
            MonthSummary(
                month = o.optString("month"),
                expense = o.optDouble("expense", 0.0),
                income = o.optDouble("income", 0.0),
                net = o.optDouble("net", 0.0),
                count = o.optInt("count"),
                // JSONObject 的 keys() 保留插入順序，伺服器已按金額排好，這裡不要再排
                byCategory = cats?.keys()?.asSequence()
                    ?.map { it to cats.optDouble(it, 0.0) }?.toList() ?: emptyList(),
            )
        }

    /**
     * kind 是 events / alarms / ledger / courses。回傳伺服器落庫後的那一筆（含 id）。
     *
     * 不共用 postJson：伺服器把「時間格式不對」這類訊息放在 400 的 detail 裡，
     * 那是寫給人看的中文，只回一個 HTTP 400 等於把最有用的部分丟掉。
     */
    suspend fun addAgenda(kind: String, body: JSONObject): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("${prefs.baseUrl}/v1/agenda/$kind").auth()
                    .post(body.toString().toRequestBody(JSON_MEDIA)).build()
                apiClient.newCall(req).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (!r.isSuccessful) {
                        error(runCatching { JSONObject(text).getString("detail") }
                            .getOrDefault("HTTP ${r.code}"))
                    }
                    JSONObject(text)
                }
            }
        }

    suspend fun patchAgenda(kind: String, id: String, body: JSONObject): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("${prefs.baseUrl}/v1/agenda/$kind/$id").auth()
                    .patch(body.toString().toRequestBody(JSON_MEDIA)).build()
                apiClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}: ${r.body?.string().orEmpty()}")
                }
            }
        }

    /**
     * 整份改寫節次時間表。
     *
     * 不走 patchAgenda 是因為節次表沒有 id——它是一張表，改一節的時間常常連帶要推後
     * 面幾節，一次一格地送會在中間留下前後矛盾的狀態。錯誤訊息比照 addAgenda 撈出
     * detail：使用者把第三節打成 25:00 時，要看到的是那句中文而不是 400。
     */
    suspend fun putPeriods(periods: JSONArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("periods", periods)
                val req = Request.Builder()
                    .url("${prefs.baseUrl}/v1/agenda/periods").auth()
                    .put(body.toString().toRequestBody(JSON_MEDIA)).build()
                apiClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        val text = r.body?.string().orEmpty()
                        error(runCatching { JSONObject(text).getString("detail") }
                            .getOrDefault("HTTP ${r.code}"))
                    }
                }
            }
        }

    suspend fun deleteAgenda(kind: String, id: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("${prefs.baseUrl}/v1/agenda/$kind/$id").auth().delete().build()
                apiClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                }
            }
        }

    private suspend fun getJson(path: String): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("${prefs.baseUrl}$path").auth().build()
                apiClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    JSONObject(r.body?.string().orEmpty())
                }
            }
        }

    private suspend fun postJson(path: String, body: JSONObject): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("${prefs.baseUrl}$path").auth()
                    .post(body.toString().toRequestBody(JSON_MEDIA)).build()
                apiClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    JSONObject(r.body?.string().orEmpty())
                }
            }
        }

    suspend fun health(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("${prefs.baseUrl}/v1/health").build()
            apiClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        }
    }

    private suspend fun post(path: String, body: JSONObject): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("${prefs.baseUrl}$path")
                    .auth()
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .build()
                apiClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}: ${r.body?.string().orEmpty()}")
                }
            }
        }

    companion object {
        /** 統一 log tag，診斷時 `adb logcat -s butler` 就能只看這隻 App 的訊息。 */
        const val TAG = "butler"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
