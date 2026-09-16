package dev.butlerkit.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** 看板。 */
interface KanbanApi {
    suspend fun getKanban(): Result<List<KanbanColumn>>
    suspend fun addKanbanCard(
        title: String, status: String = "todo", urgent: Boolean = false, note: String = "",
    ): Result<KanbanCard>
    suspend fun patchKanbanCard(
        id: String, status: String? = null, urgent: Boolean? = null,
        title: String? = null, note: String? = null,
    ): Result<KanbanCard>
    suspend fun moveKanbanCard(id: String, status: String, order: Int): Result<KanbanCard>
    suspend fun archiveKanbanCard(id: String): Result<Unit>
}

internal class KanbanApiImpl(core: ClientCore) : KanbanApi, ClientCore by core {

    // ── 看板 ──────────────────────────────────────────────────────────────────

    /**
     * 拉整張看板。伺服器已經照顯示順序排好（待辦與進行中照 order，完成照時間新到舊），
     * 這裡**不要再排一次**——排序規則寫兩份遲早會走鐘。
     * 尤其別把急件挑到最上面：拖放要成立，看到的順序就得跟資料裡的順序一致。
     */
    override suspend fun getKanban(): Result<List<KanbanColumn>> =
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
    override suspend fun addKanbanCard(title: String, status: String,
                              urgent: Boolean, note: String): Result<KanbanCard> =
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
    override suspend fun patchKanbanCard(id: String, status: String?, urgent: Boolean?,
                                title: String?, note: String?): Result<KanbanCard> =
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
    override suspend fun moveKanbanCard(id: String, status: String, order: Int): Result<KanbanCard> =
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
    override suspend fun archiveKanbanCard(id: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("${prefs.baseUrl}/v1/kanban/cards/$id").auth()
                    .delete().build()
                apiClient.newCall(req).execute().use { r -> r.failIfBad() }
            }
        }

    private fun parseKanbanCard(o: JSONObject): KanbanCard = KanbanCard(
        id = o.optString("id"),
        title = o.optString("title"),
        status = o.optString("status", "todo"),
        urgent = o.optBoolean("urgent"),
        note = o.optString("note"),
        lastTouch = o.optString("last_touch"),
    )
}
