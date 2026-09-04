package dev.butlerkit.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** 對話：送訊息、停、回答提問、對話清單與 snapshot、接管電腦上的 session、搜尋。 */
interface ConversationApi {
    suspend fun sendMessage(
        convId: String, text: String,
        attachments: List<dev.butlerkit.app.ui.Attachment> = emptyList(),
    ): Result<Unit>
    suspend fun answerAsk(askId: String, choiceId: String, text: String? = null): Result<Unit>
    suspend fun stopTurn(convId: String): Result<Unit>
    suspend fun stopBgTask(convId: String, taskId: String): Result<Unit>
    suspend fun listConversations(): Result<List<ConvInfo>>
    suspend fun createConversation(): Result<String>
    suspend fun listLocalSessions(q: String = "", offset: Int = 0, limit: Int = 40): Result<LocalSessionPage>
    suspend fun adoptSession(sessionId: String): Result<String>
    suspend fun deleteConversation(convId: String): Result<Unit>
    suspend fun setCwd(convId: String, path: String): Result<Unit>
    suspend fun snapshot(convId: String): Result<Snapshot>
    /** 比 [beforeMs] 更早的最後 [limit] 則歷史，舊到新。捲到頂時往前翻頁用。 */
    suspend fun historyBefore(convId: String, beforeMs: Long, limit: Int = 60): Result<HistoryPage>
    suspend fun setConvSettings(convId: String, model: String?, effort: String?): Result<Unit>
    suspend fun search(query: String): Result<List<SearchHit>>
}

internal class ConversationApiImpl(core: ClientCore) : ConversationApi, ClientCore by core {

    override suspend fun sendMessage(
        convId: String, text: String,
        attachments: List<dev.butlerkit.app.ui.Attachment>,
    ): Result<Unit> {
        val body = JSONObject().put("text", text)
        // 附件是結構化欄位，不是拼進本文的路徑——路徑由伺服器在送給模型的那一份
        // 接上去（engine.turn.stamp），使用者氣泡裡不會出現它
        if (attachments.isNotEmpty()) {
            val arr = JSONArray()
            attachments.forEach {
                arr.put(
                    JSONObject()
                        .put("name", it.name).put("path", it.path)
                        .put("bytes", it.bytes).put("mime", it.mime),
                )
            }
            body.put("attachments", arr)
        }
        return post("/v1/conversations/$convId/message", body)
    }

    /**
     * 回答提問。[text] 是自己打的字，伺服器端優先採用它（`answer.text or choice_id`），
     * 所以想講的話不在選項裡時不必勉強挑一個。
     */
    override suspend fun answerAsk(
        askId: String, choiceId: String, text: String?,
    ): Result<Unit> = post(
        "/v1/asks/$askId/answer",
        JSONObject().put("choice_id", choiceId).apply {
            if (!text.isNullOrBlank()) put("text", text)
        },
    )

    override suspend fun stopTurn(convId: String): Result<Unit> =
        post("/v1/conversations/$convId/stop", JSONObject())

    /**
     * 停掉一件還在跑的背景工作。跟 [stopTurn] 是兩回事：那個停的是助理現在這一輪，
     * 這個停的是它稍早丟出去、現在還在自己跑的那件事。
     *
     * 不在這裡改本地狀態——伺服器停掉之後會送一則 `bg.state`，卡片由那條路更新。
     * 兩處都寫就會有兩份真相，而按下去到真的停掉之間本來就有網路延遲。
     */
    override suspend fun stopBgTask(convId: String, taskId: String): Result<Unit> =
        post("/v1/conversations/$convId/tasks/$taskId/stop", JSONObject())

    override suspend fun listConversations(): Result<List<ConvInfo>> = getJson("/v1/conversations")
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

    override suspend fun createConversation(): Result<String> =
        postJson("/v1/conversations", JSONObject()).mapCatching { it.getString("conv_id") }

    /**
     * 電腦上還沒接管的 session，一次一頁。
     *
     * [q] 有值時比對開場白與工作目錄；[offset] 跳過前幾筆。伺服器要為每筆開檔
     * 讀開場白，所以不一次全撈，改成滑到底再續拉。
     */
    override suspend fun listLocalSessions(
        q: String, offset: Int, limit: Int,
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
    override suspend fun adoptSession(sessionId: String): Result<String> =
        postJson("/v1/sessions/$sessionId/adopt", JSONObject())
            .mapCatching { it.getString("conv_id") }

    override suspend fun deleteConversation(convId: String): Result<Unit> =
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

    override suspend fun setCwd(convId: String, path: String): Result<Unit> =
        post("/v1/conversations/$convId/cwd", JSONObject().put("path", path))

    /** 拉對話全貌：歷史訊息＋伺服器端 busy 真值。切對話與冷啟動時用。 */
    override suspend fun snapshot(convId: String): Result<Snapshot> =
        getJson("/v1/conversations/$convId/snapshot").mapCatching { o ->
            val arr = o.getJSONArray("messages")
            // 舊版伺服器沒有這個欄位，用 optJSONArray 退回空陣列——升級 App 卻還沒
            // 重啟服務的那段時間不能整個 snapshot 解析失敗（那會讓歷史全空）
            val fa = o.optJSONArray("files")
            val pa = o.optJSONArray("pending")
            val aa = o.optJSONArray("asks")
            val ba = o.optJSONArray("bg")
            Snapshot(
                // 手刻而不是共用 BgTask.from：那支吃 kotlinx 的 JsonObject（事件流），
                // 這裡是 HTTP 回應的 org.json。欄位名一致，伺服器那邊是同一份來源。
                bg = (0 until (ba?.length() ?: 0)).mapNotNull { i ->
                    ba!!.optJSONObject(i)?.let { b ->
                        BgTask(
                            id = b.optString("id"),
                            desc = b.optString("desc").ifBlank { "背景工作" },
                            status = b.optString("status").ifBlank { "running" },
                            startedAtMs = (b.optDouble("started_at", 0.0) * 1000).toLong(),
                            finishedAtMs = (b.optDouble("finished_at", 0.0) * 1000).toLong(),
                            lastTool = b.optString("last_tool"),
                            tokens = b.optInt("tokens"),
                            toolUses = b.optInt("tool_uses"),
                            summary = b.optString("summary"),
                        )
                    }
                },
                busy = o.optBoolean("busy"),
                queued = o.optInt("queued"),
                messages = parseHistoryMessages(arr),
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
                hasMore = o.optBoolean("has_more"),
            )
        }

    override suspend fun historyBefore(
        convId: String, beforeMs: Long, limit: Int,
    ): Result<HistoryPage> =
        getJson("/v1/conversations/$convId/history?before_ms=$beforeMs&limit=$limit")
            .mapCatching { o ->
                HistoryPage(
                    messages = parseHistoryMessages(o.getJSONArray("messages")),
                    hasMore = o.optBoolean("has_more"),
                )
            }

    /** 只覆寫這一個對話的 model/effort。空字串＝清除覆寫、回到跟隨帳號預設。 */
    override suspend fun setConvSettings(convId: String, model: String?, effort: String?): Result<Unit> =
        post("/v1/conversations/$convId/settings", JSONObject().apply {
            if (model != null) put("model", model)
            if (effort != null) put("effort", effort)
        })

    override suspend fun search(query: String): Result<List<SearchHit>> =
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

    /**
     * 歷史訊息陣列。snapshot 與往前翻頁的 history 端點格式相同，共用這一支。
     *
     * 附在訊息上的選項 id 用陣列位置造（"h$i"）：那個 id 只在本地用來辨認
     * 「按的是哪一張卡」，翻頁時同一頁裡不重複就夠。
     */
    private fun parseHistoryMessages(arr: JSONArray): List<HistoryMsg> =
        (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            HistoryMsg(
                role = m.getString("role"),
                text = m.getString("text"),
                think = m.optString("think"),
                atMs = m.optLong("at_ms"),
                ask = parseHistoryAsk(m.optJSONObject("ask"), "h$i"),
                tools = parseHistoryTools(m.optJSONArray("tools")),
            )
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

    /**
     * 歷史訊息裡的工具軌跡。
     *
     * 欄位名跟即時的 `tool.call` 事件一致——伺服器兩邊都是 `toolinfo.tool_info`
     * 出來的同一份 dict，這裡只是換 org.json 再解一次（理由同 [parseHistoryAsk]）。
     */
    private fun parseHistoryTools(arr: JSONArray?): List<ToolCall> =
        (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val t = arr!!.optJSONObject(i) ?: return@mapNotNull null
            ToolCall(
                tool = t.optString("tool"),
                icon = t.optString("icon"),
                summary = t.optString("summary"),
                raw = t.optString("raw"),
                dangerous = t.optBoolean("dangerous"),
                kind = t.optString("kind", "other"),
                added = t.optInt("added"),
                removed = t.optInt("removed"),
                file = t.optString("file"),
            )
        }
}
