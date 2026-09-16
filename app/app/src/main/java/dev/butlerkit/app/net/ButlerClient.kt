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
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject

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
        httpCode == 401 -> "配對失效了，要重新配對一次。"
        // 403 不會是 butler 發的——它的認證失敗一律回 401（transport/auth.py 的
        // require_token）。所以 403 只可能是 Cloudflare Access 擋下的，意思是這個
        // build 沒帶到服務憑證。重新配對救不了，要換一份編好憑證的 App。
        httpCode == 403 -> "這支 App 沒有通行憑證，要重新安裝一份。"
        t is java.net.UnknownHostException -> "找不到電腦的位址，去設定確認主機那一欄。"
        t is java.net.SocketTimeoutException || "ETIMEDOUT" in m || "timeout" in m ->
            "電腦沒回應，它可能睡著了。"
        "Failed to connect" in m || "ECONNREFUSED" in m || "Connection refused" in m ->
            "連不上電腦。它可能沒開機，或是那邊網路斷了。"
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
internal fun errorFrom(code: Int, text: String): Nothing {
    val detail = runCatching { JSONObject(text).getString("detail") }
        .getOrNull()?.takeIf { it.isNotBlank() }
    error(detail ?: humanError(null, code))
}

/** 讀出回應內容；非 2xx 就拋 [errorFrom] 的人話例外。 */
internal fun Response.textOrThrow(): String {
    // body 只能讀一次，所以成功失敗都先讀出來再分流
    val text = body?.string().orEmpty()
    if (!isSuccessful) errorFrom(code, text)
    return text
}

/** 非 2xx 就拋人話例外。用在成功時不能碰 body 的地方（下載、截圖那些二進位回應）。 */
internal fun Response.failIfBad() {
    if (!isSuccessful) errorFrom(code, body?.string().orEmpty())
}
/**
 * 跟電腦上 butler 服務講話的唯一入口。
 *
 * 2026-09-03 按領域拆檔：對話／設定／裝置／檔案／日常／看板各一個介面與實作
 * （`*Api.kt`），這裡用委派把它們組回同一個物件——呼叫端照舊 `client.snapshot(...)`，
 * 對外 API 名稱一個都沒變。只有事件流 [stream] 與 [health] 留在本體：它們是連線
 * 本身，不屬於任何一個領域。
 */
class ButlerClient private constructor(private val core: ClientCore) :
    ConversationApi by ConversationApiImpl(core),
    SettingsApi by SettingsApiImpl(core),
    DeviceApi by DeviceApiImpl(core),
    FilesApi by FilesApiImpl(core),
    AgendaApi by AgendaApiImpl(core),
    KanbanApi by KanbanApiImpl(core) {

    constructor(prefs: Prefs) : this(HttpCore(prefs))

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
        val prefs = core.prefs
        val url = "${prefs.baseUrl}/v1/stream"
        Log.i(TAG, "SSE 連線中 url=$url lastSeq=$lastSeq tokenLen=${prefs.token.length}")
        val req = Request.Builder()
            .url(url)
            .run { with(core) { auth() } }
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

        val source = EventSources.createFactory(core.sseClient).newEventSource(req, listener)
        awaitClose { source.cancel() }
    }.buffer(Channel.UNLIMITED)

    suspend fun health(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("${core.prefs.baseUrl}/v1/health").build()
            core.apiClient.newCall(req).execute().use { r ->
                r.failIfBad()
                r.body?.string().orEmpty()
            }
        }
    }

    companion object {
        /** 統一 log tag，診斷時 `adb logcat -s butler` 就能只看這隻 App 的訊息。 */
        const val TAG = "butler"
    }
}
