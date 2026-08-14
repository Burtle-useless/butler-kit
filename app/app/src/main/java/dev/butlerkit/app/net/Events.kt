package dev.butlerkit.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 伺服器送來的一則事件。
 *
 * 對應 server/protocol/events.py 的 Event：SSE 的 `id` 是 seq、`event` 是型別、
 * `data` 是含 conv_id/turn_id 的 JSON。data 內容刻意保持自由演進，
 * 所以這裡用 JsonObject 而非固定欄位——伺服器加欄位不需要同步改 App。
 */
data class ServerEvent(
    val seq: Long,
    val type: String,
    val convId: String,
    val turnId: String,
    val data: JsonObject,
) {
    fun str(key: String): String = data[key]?.jsonPrimitive?.contentOrNull ?: ""
    fun bool(key: String): Boolean = data[key]?.jsonPrimitive?.booleanOrNull ?: false
    fun int(key: String): Int = data[key]?.jsonPrimitive?.intOrNull ?: 0
    fun dbl(key: String): Double = data[key]?.jsonPrimitive?.doubleOrNull ?: 0.0

    /** 字串陣列欄位（例如 status 的 bg：進行中的背景任務描述）。 */
    fun strList(key: String): List<String> = runCatching {
        data[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    }.getOrDefault(emptyList())

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 解析一則 SSE。格式不對就回 null，絕不讓一則壞事件炸掉整條連線。 */
        fun parse(id: String?, type: String?, raw: String): ServerEvent? = runCatching {
            val obj = json.parseToJsonElement(raw).jsonObject
            ServerEvent(
                seq = id?.toLongOrNull() ?: return null,
                type = type ?: return null,
                convId = obj["conv_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                turnId = obj["turn_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                data = obj,
            )
        }.getOrNull()
    }
}

/** ask.request 事件裡的一個選項。 */
data class AskChoice(val id: String, val label: String, val detail: String)

/** 需要使用者決定的提問。破壞性指令確認與選項題共用同一個模型。 */
data class AskRequest(
    val askId: String,
    val kind: String,
    val title: String,
    val body: String,
    /** 指令原文全文。**顯示時不可截斷**——攻擊面正是「說明講 A、指令做 B」。 */
    val raw: String,
    val choices: List<AskChoice>,
    val requireBiometric: Boolean,
) {
    companion object {
        fun from(ev: ServerEvent): AskRequest = AskRequest(
            askId = ev.str("ask_id"),
            kind = ev.str("kind"),
            title = ev.str("title"),
            body = ev.str("body"),
            raw = ev.str("raw"),
            choices = ev.data["choices"]?.jsonArray?.mapNotNull { el ->
                runCatching {
                    val o = el.jsonObject
                    AskChoice(
                        id = o["id"]?.jsonPrimitive?.content.orEmpty(),
                        label = o["label"]?.jsonPrimitive?.content.orEmpty(),
                        detail = o["detail"]?.jsonPrimitive?.content.orEmpty(),
                    )
                }.getOrNull()
            }.orEmpty(),
            requireBiometric = ev.bool("require_biometric"),
        )
    }
}
