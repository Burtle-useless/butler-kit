package dev.butlerkit.app.net

import dev.butlerkit.app.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 日常：行事曆／鬧鐘／記帳／課表。 */
interface AgendaApi {
    suspend fun getAgendaRaw(): Result<String>
    suspend fun getSummary(month: String = ""): Result<MonthSummary>
    suspend fun addAgenda(kind: String, body: JSONObject): Result<JSONObject>
    suspend fun patchAgenda(kind: String, id: String, body: JSONObject): Result<Unit>
    suspend fun putPeriods(periods: JSONArray): Result<Unit>
    suspend fun deleteAgenda(kind: String, id: String): Result<Unit>
}

internal class AgendaApiImpl(core: ClientCore) : AgendaApi, ClientCore by core {

    // ── 行事曆／鬧鐘／記帳 ────────────────────────────────────────────────
    /**
     * 三類一次拉完，回**原始 JSON 文字**。助理改過資料時伺服器會推 agenda.changed，
     * 收到就重呼叫這個。
     *
     * 刻意回字串而不是解析好的物件：呼叫端要原封不動存進 Prefs 當開機快取，
     * 回物件的話還得再序列化一次，那份序列化跟伺服器的格式遲早會走鐘。
     */
    override suspend fun getAgendaRaw(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("${prefs.baseUrl}/v1/agenda").auth().build()
            apiClient.newCall(req).execute().use { r ->
                r.failIfBad()
                r.body?.string().orEmpty()
            }
        }
    }

    /** month 傳空字串代表這個月。 */
    override suspend fun getSummary(month: String): Result<MonthSummary> =
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
    override suspend fun addAgenda(kind: String, body: JSONObject): Result<JSONObject> =
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

    override suspend fun patchAgenda(kind: String, id: String, body: JSONObject): Result<Unit> =
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
    override suspend fun putPeriods(periods: JSONArray): Result<Unit> =
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

    override suspend fun deleteAgenda(kind: String, id: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("${prefs.baseUrl}/v1/agenda/$kind/$id").auth().delete().build()
                apiClient.newCall(req).execute().use { r ->
                    r.failIfBad()
                }
            }
        }
}
