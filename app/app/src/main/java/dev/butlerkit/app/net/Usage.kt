package dev.butlerkit.app.net

import org.json.JSONObject

/**
 * 用量表的資料模型。
 *
 * 成本用 Double 存美元原值，不在這裡換算台幣——匯率會變，而畫面上寫死一個
 * 過期的匯率比只寫美元更誤導。
 */
data class UsageBucket(
    val turns: Int,
    val input: Long,
    val output: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val cost: Double,
) {
    /** 送進模型的總量：新輸入＋快取讀取＋快取寫入。用來畫「吃了多少 context」。 */
    val totalIn: Long get() = input + cacheRead + cacheWrite
}

/** 逐日一格。伺服器保證天數補滿（沒用的日子給零），這裡不用再補。 */
data class DayUsage(
    val date: String,
    val input: Long,
    val output: Long,
    val cost: Double,
    val turns: Int,
)

data class ModelUsage(val model: String, val turns: Int, val cost: Double, val output: Long)

/**
 * 訂閱方案的一條額度（5 小時、7 天…）。
 *
 * 跟上面那些是**不同來源**：上面是我們自己記的帳，這條是 Anthropic 那邊的餘額。
 * 百分比與夾限都在伺服器算完了，這裡只負責畫。
 * resetsAt 解析失敗給 null——倒數只是輔助資訊，不值得為它讓整張表掛掉。
 */
data class PlanLimit(
    val key: String,
    val label: String,
    val pct: Float,
    val resetsAt: java.time.Instant?,
)

/**
 * 這台電腦上**所有** CC 的用量，來源是掃 ~/.claude/projects 的逐字稿。
 *
 * 跟上面那些是第三種東西：上面是走助理的帳，limits 是全帳號的餘額，這個是
 * 「本機這些量被誰吃掉的」——cc-bot、終端機直接開的、subagent 全算進來。
 * 沒有 cost 欄位：金額只在 SDK 的 ResultMessage 裡，那則不會寫進逐字稿。
 */
data class KindUsage(val kind: String, val turns: Int, val input: Long, val output: Long)

data class LocalUsage(
    val today: UsageBucket,
    val month: UsageBucket,
    val days: List<DayUsage>,
    val models: List<ModelUsage>,
    val kinds: List<KindUsage>,
    val scannedAt: String,
) {
    val main: KindUsage? get() = kinds.firstOrNull { it.kind == "main" }
    val subagent: KindUsage? get() = kinds.firstOrNull { it.kind == "subagent" }
}

data class UsageReport(
    val today: UsageBucket,
    val month: UsageBucket,
    val monthKey: String,
    val days: List<DayUsage>,
    val models: List<ModelUsage>,
    val limits: List<PlanLimit> = emptyList(),
    val local: LocalUsage? = null,
)

private fun JSONObject.toBucket() = UsageBucket(
    turns = optInt("turns"),
    input = optLong("in"),
    output = optLong("out"),
    cacheRead = optLong("cache_read"),
    cacheWrite = optLong("cache_write"),
    cost = optDouble("cost", 0.0),
)

/** 逐日陣列。助理的帳與本機帳是同一個格式，兩邊都用這支解。 */
private fun JSONObject.toDays(): List<DayUsage> {
    val arr = optJSONArray("days") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val d = arr.getJSONObject(i)
        DayUsage(
            date = d.optString("date"),
            input = d.optLong("in"),
            output = d.optLong("out"),
            cost = d.optDouble("cost", 0.0),
            turns = d.optInt("turns"),
        )
    }
}

private fun JSONObject.toModels(): List<ModelUsage> {
    val arr = optJSONArray("models") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val m = arr.getJSONObject(i)
        ModelUsage(
            model = m.optString("model"),
            turns = m.optInt("turns"),
            cost = m.optDouble("cost", 0.0),
            output = m.optLong("out"),
        )
    }
}

fun parseUsage(o: JSONObject): UsageReport {
    val limitsArr = o.optJSONArray("limits")
    return UsageReport(
        today = o.optJSONObject("today")?.toBucket() ?: JSONObject().toBucket(),
        month = o.optJSONObject("month")?.toBucket() ?: JSONObject().toBucket(),
        monthKey = o.optString("month_key"),
        days = o.toDays(),
        models = o.toModels(),
        limits = (0 until (limitsArr?.length() ?: 0)).map { i ->
            val l = limitsArr!!.getJSONObject(i)
            PlanLimit(
                key = l.optString("key"),
                label = l.optString("label"),
                pct = l.optDouble("pct", 0.0).toFloat(),
                // 用 OffsetDateTime 不用 Instant.parse：官方給的是
                // "2026-08-11T06:20:00.744010+00:00"，而 Instant.parse 只認 Z 結尾，
                // 帶位移的寫法會直接拋例外，倒數就永遠是空的。
                resetsAt = runCatching {
                    java.time.OffsetDateTime.parse(l.optString("resets_at")).toInstant()
                }.getOrNull(),
            )
        },
        local = o.optJSONObject("local")?.let { lo ->
            val kindsArr = lo.optJSONArray("kinds")
            LocalUsage(
                today = lo.optJSONObject("today")?.toBucket() ?: JSONObject().toBucket(),
                month = lo.optJSONObject("month")?.toBucket() ?: JSONObject().toBucket(),
                days = lo.toDays(),
                models = lo.toModels(),
                kinds = (0 until (kindsArr?.length() ?: 0)).map { i ->
                    val k = kindsArr!!.getJSONObject(i)
                    KindUsage(
                        kind = k.optString("kind"),
                        turns = k.optInt("turns"),
                        input = k.optLong("in"),
                        output = k.optLong("out"),
                    )
                },
                scannedAt = lo.optString("scanned_at"),
            )
        },
    )
}
