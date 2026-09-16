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
    /** 事件發生時刻（epoch 秒，浮點）。舊版伺服器不帶，退回 0。 */
    val ts: Double = 0.0,
) {
    /** [ts] 換成 epoch 毫秒，軌跡項目存的是這個。 */
    val atMs: Long get() = (ts * 1000).toLong()

    fun str(key: String): String = data[key]?.jsonPrimitive?.contentOrNull ?: ""
    fun bool(key: String): Boolean = data[key]?.jsonPrimitive?.booleanOrNull ?: false
    fun int(key: String): Int = data[key]?.jsonPrimitive?.intOrNull ?: 0
    fun dbl(key: String): Double = data[key]?.jsonPrimitive?.doubleOrNull ?: 0.0

    /** 字串陣列欄位。 */
    fun strList(key: String): List<String> = runCatching {
        data[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    }.getOrDefault(emptyList())

    /** 使用者訊息帶的附件。路徑只給模型讀，畫面靠 name/mime 決定畫縮圖還是檔案卡。 */
    fun attachments(key: String = "attachments"): List<dev.butlerkit.app.ui.Attachment> =
        runCatching {
            data[key]?.jsonArray?.mapNotNull { el ->
                runCatching {
                    val o = el.jsonObject
                    dev.butlerkit.app.ui.Attachment(
                        name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        path = o["path"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        bytes = o["bytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                        mime = o["mime"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }.getOrNull()
            }.orEmpty()
        }.getOrDefault(emptyList())

    /** 背景工作清單（`bg.state` 的 tasks、`status` 的 bg）。 */
    fun bgTasks(key: String): List<BgTask> = runCatching {
        data[key]?.jsonArray?.mapNotNull { el ->
            runCatching { BgTask.from(el.jsonObject) }.getOrNull()
        }.orEmpty()
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
                ts = obj["ts"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            )
        }.getOrNull()
    }
}

/**
 * 一件背景工作的當下全貌。對應 `server/engine/bg_notify.py` 的 `BgTask.to_wire()`。
 *
 * [startedAtMs] 存的是起始時刻而不是已經跑了幾秒：秒數由畫面自己推算，跟狀態列
 * 的計時同一套做法。存秒數的話沒有新事件進來時畫面就停在最後一個數字上，
 * 看起來像卡住了——而背景工作最常見的情況正是「很久沒有任何動靜」。
 */
data class BgTask(
    val id: String,
    val desc: String,
    /** running / completed / failed / stopped */
    val status: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    /** 最近呼叫的工具名。空的代表還沒動作，或伺服器還沒收到進度。 */
    val lastTool: String,
    val tokens: Int,
    val toolUses: Int,
    /** 完成後的結果摘要。進行中一律是空的。 */
    val summary: String,
) {
    val running: Boolean get() = status == "running"
    val failed: Boolean get() = status == "failed"

    companion object {
        fun from(o: JsonObject): BgTask {
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
            // 伺服器給的是 epoch 秒（浮點），畫面上算耗時要毫秒
            fun ms(k: String) =
                ((o[k]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong()
            return BgTask(
                id = s("id"),
                desc = s("desc").ifBlank { "背景工作" },
                status = s("status").ifBlank { "running" },
                startedAtMs = ms("started_at"),
                finishedAtMs = ms("finished_at"),
                lastTool = s("last_tool"),
                tokens = o["tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                toolUses = o["tool_uses"]?.jsonPrimitive?.intOrNull ?: 0,
                summary = s("summary"),
            )
        }
    }
}

/**
 * 一次工具呼叫。
 *
 * 兩個來源送的是同一種東西：即時的 `tool.call` 事件，與重建歷史時逐字稿裡的
 * `tool_use` 區塊（伺服器兩邊都走 `toolinfo.tool_info` 萃取）。所以它跟
 * [AskRequest]、[BgTask] 一樣屬於線上型別——放在 ui 那側的話，net 要嘛反過來
 * 依賴 ui，要嘛自己再定義一份一模一樣的。
 *
 * [kind]／[added]／[removed]／[file] 是給一個已經拆掉的統計列用的，現在只寫不讀。
 */
data class ToolCall(
    val tool: String,
    val icon: String,
    val summary: String,
    /** 指令原文。即時事件裡不截斷；歷史裡有上限（見 history._TOOL_RAW_MAX）。 */
    val raw: String,
    val dangerous: Boolean,
    val kind: String = "other",
    val added: Int = 0,
    val removed: Int = 0,
    val file: String = "",
)

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
