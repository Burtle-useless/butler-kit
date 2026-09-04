package dev.butlerkit.app.net

import dev.butlerkit.app.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/** 帳號預設（模型／思考強度）與用量。 */
interface SettingsApi {
    suspend fun getSettings(): Result<SettingsInfo>
    suspend fun setSettings(model: String?, effort: String?): Result<Unit>
    suspend fun getUsage(span: Int = 14): Result<UsageReport>
    suspend fun getUsageRaw(span: Int = 14): Result<String>
}

internal class SettingsApiImpl(core: ClientCore) : SettingsApi, ClientCore by core {

    override suspend fun getSettings(): Result<SettingsInfo> = getJson("/v1/settings")
        .mapCatching { o ->
            fun arr(k: String) = o.getJSONArray(k).let { a ->
                (0 until a.length()).map { a.getString(it) }
            }
            val models = arr("models")
            // 新欄位帶顯示名與說明；舊伺服器沒有它時用值清單湊一份，面板照樣能用
            val infos = o.optJSONArray("model_infos")?.let { a ->
                (0 until a.length()).map { i ->
                    val m = a.getJSONObject(i)
                    val eff = m.optJSONArray("efforts")?.let { e ->
                        (0 until e.length()).map { e.getString(it) }
                    }.orEmpty()
                    ModelInfo(
                        value = m.optString("value"),
                        resolved = m.optString("resolved"),
                        name = m.optString("name").ifBlank { m.optString("value") },
                        description = m.optString("description"),
                        efforts = eff,
                        supportsEffort = m.optBoolean("supports_effort", eff.isNotEmpty()),
                    )
                }
            } ?: models.map { v ->
                ModelInfo(v, v, v.removePrefix("claude-"), "", arr("efforts"), true)
            }
            SettingsInfo(
                model = o.optString("model").takeIf { it.isNotBlank() && it != "null" },
                effort = o.optString("effort").takeIf { it.isNotBlank() && it != "null" },
                models = models,
                efforts = arr("efforts"),
                infos = infos,
                confirmDangerous = o.optBoolean("confirm_dangerous", true),
            )
        }

    override suspend fun setSettings(model: String?, effort: String?): Result<Unit> =
        post("/v1/settings", JSONObject().apply {
            put("model", model ?: "")
            put("effort", effort ?: "")
        })

    override suspend fun getUsage(span: Int): Result<UsageReport> =
        getJson("/v1/usage?span=$span").mapCatching { parseUsage(it) }

    /**
     * 同一支端點，回**原始 JSON 文字**給 widget 的快取用。理由跟 [getAgendaRaw]
     * 一樣：存進 Prefs 的東西要能用同一套 parseUsage 解回來，中間不多一層自己
     * 定義的格式。span 跟 [getUsage] 一樣給 14——widget 上也要畫逐日長條，
     * 給 1 的話那張圖只會有今天一根（2026-08-14 由 1 改回 14）。
     */
    override suspend fun getUsageRaw(span: Int): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${prefs.baseUrl}/v1/usage?span=$span").auth().build()
            apiClient.newCall(req).execute().use { r ->
                r.failIfBad()
                r.body?.string().orEmpty()
            }
        }
    }
}
