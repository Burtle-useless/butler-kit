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
    /**
     * 這組選項是「附在某則訊息底下的」，不是伺服器停著在等的提問。
     *
     * 兩者的差別在按下去之後：附在訊息上的選項等於幫你把那句話打進輸入框送出，
     * 走的是全新的一輪；伺服器在等的那種則要回到原本那一輪去（破壞性指令確認
     * 就是這種，逾時一律當成拒絕，所以不能改成非阻塞）。
     */
    val isInline: Boolean get() = askId.startsWith(INLINE_PREFIX)

    companion object {
        /** 附在訊息上的選項，其 askId 的前綴。伺服器發的 ask_id 是純 hex，不會撞。 */
        const val INLINE_PREFIX = "inline-"

        /**
         * 從 `reply.final` 事件裡的 `ask` 欄位建出來。
         *
         * [localId] 由呼叫端給一個對話內唯一的值（事件用 seq、歷史用索引），
         * 用途只是讓「按下去要標記哪一張卡」有得比對。
         */
        fun inline(obj: JsonObject?, localId: String): AskRequest? {
            val title = obj?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty()
            val choices = obj?.get("choices")?.jsonArray?.mapNotNull { el ->
                runCatching {
                    val o = el.jsonObject
                    AskChoice(
                        id = o["id"]?.jsonPrimitive?.content.orEmpty(),
                        label = o["label"]?.jsonPrimitive?.content.orEmpty(),
                        detail = o["detail"]?.jsonPrimitive?.content.orEmpty(),
                    )
                }.getOrNull()
            }.orEmpty()
            if (choices.isEmpty()) return null
            return AskRequest(
                askId = INLINE_PREFIX + localId,
                kind = "choose", title = title, body = "", raw = "",
                choices = choices, requireBiometric = false,
            )
        }

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
