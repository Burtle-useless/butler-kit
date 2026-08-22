package dev.butlerkit.app.net

import android.util.Log
import dev.butlerkit.app.data.Prefs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
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

/**
 * 把連線例外翻成看得懂的話。
 *
 * 直接顯示 `Throwable.message` 的話，畫面上會出現「Failed to connect to
 * /100.x.y.z:47362」——那是 Java 講給開發者聽的，說的是「哪個位址失敗了」，
 * 而不是「你現在該做什麼」。錯誤訊息要能指出下一步，否則只是在報故障。
 *
 * 放在這裡而不是各自的 Repo：SSE 斷線與 REST 失敗是同一批例外，訊息各寫一套
 * 的話同一個狀況在聊天頁與日常頁會顯示成兩種說法。
 */
fun humanError(t: Throwable?, httpCode: Int? = null): String {
    val m = t?.message.orEmpty()
    return when {
        // 只認 httpCode，不再用 `"401" in m` 這種子字串比對——訊息裡碰巧出現 401
        // （例如伺服器回的中文 detail 帶了數字）就會誤報「配對失效」，
        // 而那句話會讓人跑去重新配對，把好好的裝置解掉。
        httpCode == 401 || httpCode == 403 -> "配對失效了，要重新配對一次。"
        // host 填了明文 http 位址（IP、區網主機名）就會走到這裡：明文白名單是
        // 編譯期資源、預設只放行 ts.net，Android 直接把連線擋掉。原本沒有這條，
        // 訊息落到最後的 else 顯示英文原文 `CLEARTEXT communication to … not
        // permitted by network security policy`，而頂欄那行 maxLines=1，尾巴看不到。
        // 這句**不要指名 Tailscale**：走通道的人填的是 https 網址，根本碰不到
        // 這條路；會撞上的是自己接區網 http 的人，要的是「怎麼放行」而不是「裝 VPN」。
        m.contains("cleartext", ignoreCase = true) ->
            "這個位址不能走明文連線。改用 https 網址，或把它加進 network_security_config.xml 的白名單。"
        t is java.net.UnknownHostException -> "找不到電腦的位址，去設定確認主機那一欄。"
        t is java.net.SocketTimeoutException || "ETIMEDOUT" in m || "timeout" in m ->
            "電腦沒回應，它可能睡著了。"
        "Failed to connect" in m || "ECONNREFUSED" in m || "Connection refused" in m ->
            "連不上電腦。確認它開著、服務在跑、連線通道也還在。"
        "Software caused connection abort" in m || "Connection reset" in m ->
            "連線被中斷了。"
        httpCode != null && httpCode >= 500 -> "電腦那邊出錯了（$httpCode）。"
        httpCode == 404 -> "找不到這一筆，可能已經被刪掉了。"
        // 4xx 的通用退路。正常情況走不到——伺服器的 4xx 都帶中文 detail，
        // 由 textOrThrow 撈出來直接用。這條是 detail 也讀不到時的最後一層。
        httpCode != null && httpCode >= 400 -> "這個操作沒有被接受（$httpCode）。"
        m.isBlank() -> "出了點狀況（${t?.javaClass?.simpleName ?: "連線中斷"}）。"
        else -> m
    }
}

/**
 * 把一個失敗的回應變成「訊息已經是人話」的例外。
 *
 * 伺服器的 4xx 一律帶 `detail`，而且那句是中文、是寫給使用者看的
 * （store 層的 AgendaError 就是為此存在）。撈得到就直接用它。
 *
 * **絕不讓 `HTTP 404: {"detail":"…"}` 這種東西跑到畫面上。** 原本 patchAgenda、
 * deleteAgenda、getJson 那幾支各自拼字串，紅色橫幅顯示的就是那個——
 * 正好是 [humanError] 的說明裡講明要避免的。抽成一支的另一個理由是它們
 * 各拼各的：同一個 404 在日常頁跟工具頁會長得不一樣。
 */
private fun errorFrom(code: Int, text: String): Nothing {
    val detail = runCatching { JSONObject(text).getString("detail") }
        .getOrNull()?.takeIf { it.isNotBlank() }
    error(detail ?: humanError(null, code))
}

/** 讀出回應內容；非 2xx 就拋 [errorFrom] 的人話例外。 */
private fun Response.textOrThrow(): String {
    // body 只能讀一次，所以成功失敗都先讀出來再分流
    val text = body?.string().orEmpty()
    if (!isSuccessful) errorFrom(code, text)
    return text
}

/** 非 2xx 就拋人話例外。用在成功時不能碰 body 的地方（下載、截圖那些二進位回應）。 */
private fun Response.failIfBad() {
    if (!isSuccessful) errorFrom(code, body?.string().orEmpty())
}

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

/** 一頁 session。[hasMore] 為 true 代表後面還有，滑到底可以再拉一頁。 */
data class LocalSessionPage(val rows: List<LocalSession>, val hasMore: Boolean)

data class SearchHit(
    val convId: String, val title: String, val role: String, val snippet: String,
)

/**
 * [think] 是這一回合的思考全文，只有 assistant 那側會有，沒有就是空字串。
 * [atMs] 是這則的時間（epoch 毫秒），用來跟檔案卡片排在同一條軸上。
 */
data class HistoryMsg(
    val role: String,
    val text: String,
    val think: String = "",
    val atMs: Long = 0L,
    /**
     * 這則訊息底下的選項按鈕，沒有就是 null。
     *
     * 伺服器重建歷史時從回覆原文的 [[ASK:]] 標記重新抽出來（見 fold.ask_payload），
     * 而且**只有還沒被回答的那一則會帶**——對話往下走了就代表已經答過。
     * 這樣重裝 App、被系統回收、換手機，該回答的那題都還在原地等。
     */
    val ask: AskRequest? = null,
)

/**
 * 歷史裡的檔案卡片。
 *
 * 為什麼要單獨從伺服器補：卡片是 butler 自己造的東西，CC 的逐字稿裡沒有它，
 * 所以重建畫面時變不出來——App 一重啟對話裡的下載框就整排消失。
 * [atMs] 由伺服器算好（那邊才知道登記時間是本地時區），App 只負責照著排。
 */
/**
 * 排著還沒輪到的訊息。
 *
 * 跟 [HistoryFile] 同一個道理：它還沒送進 CC，所以逐字稿裡沒有它，重建畫面時
 * 補不回來——使用者看不到自己剛剛送出了什麼，但伺服器照樣會處理它。
 */
data class PendingMsg(val msgId: String, val text: String)

data class HistoryFile(
    val fileId: String,
    val name: String,
    val bytes: Long,
    val note: String = "",
    val mime: String = "",
    val atMs: Long = 0L,
)

/**
 * 看板上的一件工作。屬於哪個企劃直接寫在 [title] 裡（「助理－看板拖放」），
 * 不是獨立欄位。[status] 是 todo／doing／done；[lastTouch] 是 ISO 8601 字串。
 */
data class KanbanCard(
    val id: String,
    val title: String,
    val status: String = "todo",
    val urgent: Boolean = false,
    val note: String = "",
    val lastTouch: String = "",
)

/**
 * 一個階段欄，含它底下的工作。伺服器已排好順序，照著畫就好。
 * [total] 是這一欄總共幾張、[hidden] 是被收起來沒回傳的張數（只有完成欄會有）。
 */
data class KanbanColumn(
    val status: String,
    val label: String,
    val total: Int,
    val hidden: Int,
    val cards: List<KanbanCard>,
)

/**
 * 電腦上那個服務的現況。[commit] 與 [subject] 是它正在跑的版本，
 * 按下重啟之後靠這兩個確認新程式碼真的生效了。拿不到 git 資訊時會是空字串。
 *
 * [latestCommit] 是**磁碟上**最新的一筆。跟 [commit] 不同就代表程式改過了
 * 但還沒重啟——少了這組對照，改完程式碼一 commit，版本那行立刻顯示新雜湊，
 * 可是行程裡跑的還是舊的。
 */
data class SystemStatus(
    val pid: Int,
    val uptime: String,
    val commit: String,
    val subject: String,
    val latestCommit: String = "",
    val latestSubject: String = "",
)

/**
 * 一台配對過的裝置。
 *
 * [hash] 是 token 的 SHA-256，**它本身不是憑證**——伺服器從來沒存過明文，
 * 拿到這串既登入不了也解不回 token，所以可以安全地帶到畫面上。
 * 撤銷 API 收的就是它。
 */
data class DeviceInfo(
    val hash: String,
    /** 雜湊前 8 碼，給人辨認用。全長 64 個字在手機上排不下。 */
    val short: String,
    val name: String,
    /** 配對時間，epoch 秒。 */
    val created: Double,
    /** 最後一次通過認證的時間；只存在伺服器記憶體，服務重啟後為 null。 */
    val lastSeen: Double?,
    /** 是不是正在看這個畫面的這一台。這一列不給撤銷。 */
    val isThis: Boolean,
)

data class Snapshot(
    val busy: Boolean,
    val queued: Int,
    val messages: List<HistoryMsg>,
    /** 這段對話裡傳過的檔案，舊到新。重建軌跡時依 atMs 插回訊息之間。 */
    val files: List<HistoryFile> = emptyList(),
    /** 排著還沒輪到的訊息，舊到新。重建軌跡時接在歷史最後面。 */
    val pending: List<PendingMsg> = emptyList(),
    /**
     * 還在等你回答的提問。
     *
     * 這個不補回來的後果比前兩個重：伺服器等不到答案會 fail-closed 當成拒絕，
     * 那件事直接被擋掉，而使用者根本不知道它問過。
     */
    val asks: List<AskRequest> = emptyList(),
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
     *
     * **緩衝開到無上限**（見最後那個 `buffer`）。callbackFlow 預設容量 64，滿了之後
     * `trySend` 直接回失敗，而它的回傳值本來沒有人看——長回覆逐字灌得比 UI 消費快時
     * 事件就這樣一則則不見，畫面上是文字缺一段，`lastSeq` 還會跳號讓下次續傳
     * 從錯的地方開始。零 log，看起來像模型自己吃字。
     *
     * 無上限的代價是理論上的記憶體堆積，但事件都是短字串、而且一個回合總會結束；
     * 拿「可能多佔幾百 KB」換「絕不丟字」是划算的。
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
                    // 緩衝已經開到無上限，所以這裡只剩「flow 已關閉」會失敗。
                    // 還是要看回傳值——真的丟掉一則事件時要留下痕跡，
                    // 不然下游看到的是缺字，卻沒有任何線索指向這裡。
                    if (trySend(Wire.Ev(ev)).isFailure) {
                        Log.w(TAG, "事件送不進 flow，丟棄 seq=${ev.seq} type=${ev.type}")
                    }
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
                val reason = humanError(t, response?.code)
                Log.e(TAG, "SSE 失敗 code=${response?.code} reason=$reason", t)
                trySend(Wire.Conn(connected = false, error = reason))
                close()
            }
        }

        val source = EventSources.createFactory(sseClient).newEventSource(req, listener)
        awaitClose { source.cancel() }
    }.buffer(Channel.UNLIMITED)

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

    /**
     * 電腦上還沒接管的 session，一次一頁。
     *
     * [q] 有值時比對開場白與工作目錄；[offset] 跳過前幾筆。伺服器要為每筆開檔
     * 讀開場白，所以不一次全撈，改成滑到底再續拉。
     */
    suspend fun listLocalSessions(
        q: String = "", offset: Int = 0, limit: Int = 40,
    ): Result<LocalSessionPage> =
        getJson(
            "/v1/sessions?limit=$limit&offset=$offset&q=" +
                java.net.URLEncoder.encode(q, "UTF-8"),
        ).mapCatching { obj ->
                val arr = obj.getJSONArray("sessions")
                LocalSessionPage(
                    rows = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        LocalSession(
                            sessionId = o.getString("session_id"),
                            cwd = o.optString("cwd"),
                            title = o.optString("title"),
                            mtime = o.optDouble("mtime", 0.0),
                            branch = o.optString("branch"),
                            sidechain = o.optBoolean("sidechain"),
                        )
                    },
                    hasMore = obj.optBoolean("has_more"),
                )
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
                    r.failIfBad()
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
            // 舊版伺服器沒有這個欄位，用 optJSONArray 退回空陣列——升級 App 卻還沒
            // 重啟服務的那段時間不能整個 snapshot 解析失敗（那會讓歷史全空）
            val fa = o.optJSONArray("files")
            val pa = o.optJSONArray("pending")
            val aa = o.optJSONArray("asks")
            Snapshot(
                busy = o.optBoolean("busy"),
                queued = o.optInt("queued"),
                messages = (0 until arr.length()).map { i ->
                    val m = arr.getJSONObject(i)
                    HistoryMsg(
                        role = m.getString("role"),
                        text = m.getString("text"),
                        think = m.optString("think"),
                        atMs = m.optLong("at_ms"),
                        ask = parseHistoryAsk(m.optJSONObject("ask"), "h$i"),
                    )
                },
                files = (0 until (fa?.length() ?: 0)).map { i ->
                    val f = fa!!.getJSONObject(i)
                    HistoryFile(
                        fileId = f.getString("file_id"),
                        name = f.getString("name"),
                        bytes = f.optLong("bytes"),
                        note = f.optString("note"),
                        mime = f.optString("mime"),
                        atMs = f.optLong("at_ms"),
                    )
                },
                pending = (0 until (pa?.length() ?: 0)).map { i ->
                    val p = pa!!.getJSONObject(i)
                    PendingMsg(msgId = p.optString("msg_id"), text = p.optString("text"))
                },
                // 手刻而不是共用 AskRequest.from：那支吃的是事件流的 ServerEvent
                // （kotlinx.serialization），這裡是 HTTP 回應的 org.json，兩套不同的
                // JSON 型別。欄位名跟 ask.request 事件一致，伺服器那邊是同一份來源。
                asks = (0 until (aa?.length() ?: 0)).map { i ->
                    val a = aa!!.getJSONObject(i)
                    val ca = a.optJSONArray("choices")
                    AskRequest(
                        askId = a.optString("ask_id"),
                        kind = a.optString("kind"),
                        title = a.optString("title"),
                        body = a.optString("body"),
                        // 指令原文不可截斷，理由見 AskRequest.raw
                        raw = a.optString("raw"),
                        choices = (0 until (ca?.length() ?: 0)).map { j ->
                            val c = ca!!.getJSONObject(j)
                            AskChoice(
                                id = c.optString("id"),
                                label = c.optString("label"),
                                detail = c.optString("detail"),
                            )
                        },
                        requireBiometric = a.optBoolean("require_biometric"),
                    )
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
                        JSONObject(r.textOrThrow()).getString("path")
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
                    r.failIfBad()
                    val body = r.body ?: error("空回應")
                    body.byteStream().use { it.copyTo(out) }
                }
        }
    }

    suspend fun getUsage(span: Int = 14): Result<UsageReport> =
        getJson("/v1/usage?span=$span").mapCatching { parseUsage(it) }

    /**
     * 同一支端點，回**原始 JSON 文字**給 widget 的快取用。理由跟 [getAgendaRaw]
     * 一樣：存進 Prefs 的東西要能用同一套 parseUsage 解回來，中間不多一層自己
     * 定義的格式。span 跟 [getUsage] 一樣給 14——widget 上也要畫逐日長條，
     * 給 1 的話那張圖只會有今天一根（2026-08-14 由 1 改回 14）。
     */
    suspend fun getUsageRaw(span: Int = 14): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${prefs.baseUrl}/v1/usage?span=$span").auth().build()
            apiClient.newCall(req).execute().use { r ->
                r.failIfBad()
                r.body?.string().orEmpty()
            }
        }
    }

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
                r.failIfBad()
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
                    JSONObject(r.textOrThrow())
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
                    r.failIfBad()
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
                    r.failIfBad()
                }
            }
        }

    suspend fun deleteAgenda(kind: String, id: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("${prefs.baseUrl}/v1/agenda/$kind/$id").auth().delete().build()
                apiClient.newCall(req).execute().use { r ->
                    r.failIfBad()
                }
            }
        }

    /**
     * 回報目前位置（含抓不到時的原因）。
     *
     * 失敗只記一行不重試：伺服器那端有自己的逾時，而位置過幾秒就不是同一回事了，
     * 重送一筆舊座標比不送更糟。
     */
    suspend fun reportLocation(body: JSONObject): Result<Unit> =
        post("/v1/device/location", body).onFailure {
            Log.w(TAG, "回報位置失敗：${it.message}")
        }

    /** 電腦上那個服務的現況：跑多久了、正在跑哪一版。 */
    suspend fun systemStatus(): Result<SystemStatus> = getJson("/v1/system")
        .mapCatching {
            SystemStatus(
                pid = it.optInt("pid"),
                uptime = it.optString("uptime"),
                commit = it.optString("commit"),
                subject = it.optString("subject"),
                latestCommit = it.optString("latest_commit"),
                latestSubject = it.optString("latest_subject"),
            )
        }

    /**
     * 請電腦重啟服務。
     *
     * 回應會趕在服務停掉之前送出來（腳本會先等到沒人在串流才動手），
     * 所以拿到成功不代表已經重啟完，只代表「排進去了」。
     */
    suspend fun restartSystem(): Result<Unit> =
        post("/v1/system/restart", JSONObject())

    // ── 已授權的裝置 ──────────────────────────────────────────────────────────

    /**
     * 列出所有配對過的裝置。
     *
     * 伺服器已經照 created 排好，這裡不再排。`this` 那一台由伺服器判定
     * （它比對的是這次請求用的 token），App 自己算不出來也不該算。
     */
    suspend fun listDevices(): Result<List<DeviceInfo>> = getJson("/v1/devices")
        .mapCatching { o ->
            val arr = o.optJSONArray("devices") ?: return@mapCatching emptyList()
            (0 until arr.length()).map { i ->
                val d = arr.getJSONObject(i)
                DeviceInfo(
                    hash = d.optString("hash"),
                    short = d.optString("short"),
                    name = d.optString("name"),
                    created = d.optDouble("created", 0.0),
                    // last_seen 只活在伺服器記憶體裡，服務重啟就回到 null
                    lastSeen = if (d.isNull("last_seen")) null
                               else d.optDouble("last_seen"),
                    isThis = d.optBoolean("this"),
                )
            }
        }

    /**
     * 撤銷一台裝置，立即生效。
     *
     * 伺服器擋掉「撤銷自己這台」（400），所以這裡不必自己防——但 UI 仍然不給
     * 那一列撤銷鈕，因為讓人按下去再被拒絕是很差的解釋方式。
     */
    suspend fun revokeDevice(hash: String): Result<Unit> =
        post("/v1/devices/$hash/revoke", JSONObject())

    // ── 看板 ──────────────────────────────────────────────────────────────────

    /**
     * 拉整張看板。伺服器已經照顯示順序排好（待辦與進行中照 order，完成照時間新到舊），
     * 這裡**不要再排一次**——排序規則寫兩份遲早會走鐘。
     * 尤其別把急件挑到最上面：拖放要成立，看到的順序就得跟資料裡的順序一致。
     */
    suspend fun getKanban(): Result<List<KanbanColumn>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("${prefs.baseUrl}/v1/kanban").auth().build()
                apiClient.newCall(req).execute().use { r ->
                    r.failIfBad()
                    val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("columns")
                    if (arr == null) emptyList() else (0 until arr.length()).map { i ->
                        val p = arr.getJSONObject(i)
                        val cs = p.optJSONArray("cards")
                        KanbanColumn(
                            status = p.optString("status"),
                            label = p.optString("label"),
                            total = p.optInt("total"),
                            hidden = p.optInt("hidden"),
                            cards = if (cs == null) emptyList()
                                    else (0 until cs.length()).map { parseKanbanCard(cs.getJSONObject(it)) },
                        )
                    }
                }
            }
        }

    /** 新增一件工作，落在指定的階段欄。 */
    suspend fun addKanbanCard(title: String, status: String = "todo",
                              urgent: Boolean = false, note: String = ""): Result<KanbanCard> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("title", title)
                    .put("status", status).put("urgent", urgent).put("note", note)
                val req = Request.Builder()
                    .url("${prefs.baseUrl}/v1/kanban/cards").auth()
                    .post(body.toString().toRequestBody(JSON_MEDIA)).build()
                apiClient.newCall(req).execute().use { r ->
                    r.failIfBad()
                    parseKanbanCard(JSONObject(r.body?.string().orEmpty()))
                }
            }
        }

    /** 改欄位。拖放要用 [moveKanbanCard]——那條才會處理其他卡片讓位。 */
    suspend fun patchKanbanCard(id: String, status: String? = null, urgent: Boolean? = null,
                                title: String? = null, note: String? = null): Result<KanbanCard> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                if (status != null) body.put("status", status)
                if (urgent != null) body.put("urgent", urgent)
                if (title != null) body.put("title", title)
                if (note != null) body.put("note", note)
                val req = Request.Builder()
                    .url("${prefs.baseUrl}/v1/kanban/cards/$id").auth()
                    .patch(body.toString().toRequestBody(JSON_MEDIA)).build()
                apiClient.newCall(req).execute().use { r ->
                    r.failIfBad()
                    parseKanbanCard(JSONObject(r.body?.string().orEmpty()))
                }
            }
        }

    /** 拖放落點：落在哪一欄的第幾個位置。 */
    suspend fun moveKanbanCard(id: String, status: String, order: Int): Result<KanbanCard> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("status", status).put("order", order)
                val req = Request.Builder()
                    .url("${prefs.baseUrl}/v1/kanban/cards/$id/move").auth()
                    .post(body.toString().toRequestBody(JSON_MEDIA)).build()
                apiClient.newCall(req).execute().use { r ->
                    r.failIfBad()
                    parseKanbanCard(JSONObject(r.body?.string().orEmpty()))
                }
            }
        }

    /** 封存工作（邏輯刪除）。 */
    suspend fun archiveKanbanCard(id: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("${prefs.baseUrl}/v1/kanban/cards/$id").auth()
                    .delete().build()
                apiClient.newCall(req).execute().use { r -> r.failIfBad() }
            }
        }

    /**
     * 歷史訊息底下的選項。
     *
     * 事件走 kotlinx（[AskRequest.inline]）、HTTP 走 org.json，所以同一份格式
     * 要解析兩次。欄位名對不上的話症狀是「重開 App 按鈕就不見了」，很難查，
     * 兩邊都只認 title 與 choices[].{id,label,detail}。
     */
    private fun parseHistoryAsk(o: JSONObject?, localId: String): AskRequest? {
        if (o == null) return null
        val arr = o.optJSONArray("choices") ?: return null
        val choices = (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            AskChoice(
                id = c.optString("id"),
                label = c.optString("label"),
                detail = c.optString("detail"),
            )
        }
        if (choices.isEmpty()) return null
        return AskRequest(
            askId = AskRequest.INLINE_PREFIX + localId,
            kind = "choose", title = o.optString("title"), body = "", raw = "",
            choices = choices, requireBiometric = false,
        )
    }

    private fun parseKanbanCard(o: JSONObject): KanbanCard = KanbanCard(
        id = o.optString("id"),
        title = o.optString("title"),
        status = o.optString("status", "todo"),
        urgent = o.optBoolean("urgent"),
        note = o.optString("note"),
        lastTouch = o.optString("last_touch"),
    )

    private suspend fun getJson(path: String): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("${prefs.baseUrl}$path").auth().build()
                apiClient.newCall(req).execute().use { r ->
                    r.failIfBad()
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
                    r.failIfBad()
                    JSONObject(r.body?.string().orEmpty())
                }
            }
        }

    suspend fun health(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("${prefs.baseUrl}/v1/health").build()
            apiClient.newCall(req).execute().use { r ->
                r.failIfBad()
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
                    r.failIfBad()
                }
            }
        }

    companion object {
        /** 統一 log tag，診斷時 `adb logcat -s butler` 就能只看這隻 App 的訊息。 */
        const val TAG = "butler"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
