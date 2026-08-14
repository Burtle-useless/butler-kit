package dev.butlerkit.app.ui

import dev.butlerkit.app.net.AskRequest

/** 超過這個大小的圖不自動抓回來預覽，改給取件單讓人自己決定。 */
const val PREVIEW_MAX_BYTES = 8L * 1024 * 1024

private val MARKER_RE = Regex(
    """\[\[\s*(?:DONE|WAIT)\s*\]\]|\[\[MILESTONE:.*?\]\]""",
    RegexOption.IGNORE_CASE,
)

/**
 * 清掉 CC 用來跟系統溝通的控制標記。
 *
 * 伺服器端的 `clean_reply` 已經清過最終回覆，但**逐字串流的 text.delta 沒有清**——
 * 那些 delta 是原始文字，使用者會看到 [[DONE]] 在生成過程中閃過去。
 * 而且標記可能被切成兩半送達（先來 "[[DO" 再來 "NE]]"），所以除了整段比對，
 * 還要把尾端未閉合的片段先遮住，等下一段補齊再顯示。
 */
fun cleanMarkers(s: String): String {
    var t = MARKER_RE.replace(s, "")
    val open = t.lastIndexOf("[[")
    if (open >= 0 && !t.substring(open).contains("]]")) {
        t = t.substring(0, open)
    }
    return t.trimEnd()
}

/**
 * 定稿一則助理的回覆——清完控制標記後什麼都不剩就回 null，代表這則不該進軌跡。
 *
 * 助理偶爾整輪只吐一個 [[DONE]]（伺服器的空回覆重試會補問一次，下一則才是真話）。
 * 那則清完是空字串，照樣畫出來就是一顆沒有內容的孤兒頭像杵在真回覆上面——
 * 使用者看到的是「同一句話冒出兩個助理」。
 */
fun replyOrNull(turnId: String, text: String): TraceItem.Reply? =
    if (cleanMarkers(text).isBlank()) null else TraceItem.Reply(turnId, text)

/**
 * 軌跡上的一列。
 *
 * 在 Discord 上「一步步往下累積不覆蓋」需要一整套翻頁機制（2000 字上限），
 * 在 LazyColumn 上這是預設行為，所以這裡只描述語意、不管排版。
 */
sealed interface TraceItem {
    val turnId: String

    /**
     * 使用者說的話。
     *
     * [queued]＝助理還在忙上一輪，這則排在後面還沒被讀到。畫面上必須看得出來，
     * 否則排隊的跟已經在處理的長得一模一樣，人沒辦法確認它讀到哪了。
     * [dropped]＝按停止時被連帶取消，永遠不會被處理。
     * [msgId] 是伺服器給的號碼牌，`message.taken` / `message.dropped` 靠它指名。
     */
    data class UserMsg(
        override val turnId: String,
        val text: String,
        val msgId: String = "",
        val queued: Boolean = false,
        val dropped: Boolean = false,
    ) : TraceItem

    /** 模型這一步在想什麼（step.commit 的思考摘要，已定稿不會再變）。 */
    data class Thinking(override val turnId: String, val text: String) : TraceItem

    /**
     * 一個「階段」＝CC 的一句說明＋其下累積的工具統計（cc-bot 摺疊風格）。
     * 主行是說明文字，底下一行小灰字統計「讀 2 個檔案・執行 1 個指令 +28 −0」，
     * 點統計行展開每一則工具明細。
     */
    data class Stage(
        override val turnId: String,
        val text: String,
        val tools: List<ToolCall> = emptyList(),
    ) : TraceItem {
        /** cc-bot _fmt_seg_summary 的前端版：分類計數＋新增檔名＋增刪行數。 */
        val summary: String
            get() {
                if (tools.isEmpty()) return ""
                val parts = mutableListOf<String>()
                fun n(kind: String) = tools.count { it.kind == kind }
                if (n("read") > 0) parts += "讀 ${n("read")} 個檔案"
                if (n("search") > 0) parts += "搜尋 ${n("search")} 次"
                if (n("cmd") > 0) parts += "執行 ${n("cmd")} 個指令"
                if (n("web") > 0) parts += "上網 ${n("web")} 次"
                tools.mapNotNull { it.file.takeIf(String::isNotBlank) }
                    .distinct().take(2).forEach { parts += "新增 $it" }
                val edits = tools.count { it.kind == "edit" && it.file.isBlank() }
                if (edits > 0) parts += "改 $edits 個檔案"
                if (n("other") > 0) parts += "其他 ${n("other")}"
                val added = tools.sumOf { it.added }
                val removed = tools.sumOf { it.removed }
                val diff = buildString {
                    if (added > 0) append(" +$added")
                    if (removed > 0) append(" −$removed")
                }
                return parts.joinToString("・") + diff
            }
    }

    /**
     * 破壞性指令：**永遠單獨一列、永遠展開、不併入統計**。
     * 摺疊會把指令藏起來，「核對它到底在跑什麼」的防線就沒了。
     */
    data class DangerTool(override val turnId: String, val call: ToolCall) : TraceItem

    data class Reply(override val turnId: String, val markdown: String) : TraceItem

    /**
     * 助理傳了一個檔案過來。
     *
     * 檔案本體仍然留在電腦上，這一列拿的是**取件單**——按下去才真的下載。
     * 圖片例外：圖要是得先下載才看得到，那跟沒傳一樣，所以 [isImage] 的直接
     * 抓回來畫在對話裡（大小上限見 [PREVIEW_MAX_BYTES]）。
     *
     * [mime] 由伺服器登記時算好一併送來。沒有它就只能猜副檔名，而助理傳的
     * 截圖常常是 `x.png` 以外的名字。
     */
    data class FileOffer(
        override val turnId: String,
        val fileId: String,
        val name: String,
        val bytes: Long,
        val note: String,
        val mime: String,
    ) : TraceItem {
        val isImage: Boolean get() = mime.startsWith("image/")

        /** 值得直接畫出來的圖：太大的先給取件單，別為了預覽把行動網路吃光。 */
        val previewable: Boolean get() = isImage && bytes in 1..PREVIEW_MAX_BYTES
    }

    /**
     * 助理問你一件事。
     *
     * **刻意不做成彈出視窗**：Dialog 是暫時性 UI，人離開 App 再回來它就沒了，
     * 而助理問問題的時機正好就是使用者不在的時候。放進軌跡它才會留在歷史裡，
     * 回來還按得到、也看得到自己當初選了什麼。
     *
     * [answeredChoiceId] null＝還在等你；空字串＝逾時或被取消（沒人回答）；
     * 其餘＝當初選的那個選項 id。
     *
     * [answeredText] 是自己打的回答。有它就代表人有回，即使 choiceId 是空的——
     * 判斷「沒人回答」時得先看這個，否則自由作答會被顯示成逾時。
     */
    data class AskItem(
        override val turnId: String,
        val req: AskRequest,
        val answeredChoiceId: String? = null,
        val answeredText: String? = null,
    ) : TraceItem {
        val pending: Boolean get() = answeredChoiceId == null && answeredText == null
    }

    data class ErrorItem(
        override val turnId: String,
        val kind: String,
        val detail: String,
    ) : TraceItem
}

data class ToolCall(
    val tool: String,
    val icon: String,
    val summary: String,
    val raw: String,
    val dangerous: Boolean,
    val kind: String = "other",
    val added: Int = 0,
    val removed: Int = 0,
    val file: String = "",
)

/**
 * 底部狀態列的內容。轉圈動畫與計時由 UI 本地跑，不為此往返網路。
 *
 * [phase]＝這段時間在做的不是一般回話。目前只有 "compacting"（整理記憶）：
 * 那一輪的思考被伺服器擋著不送，狀態列沒東西可寫就會退回預設的「想一下」，
 * 看起來跟平常一模一樣，人不知道它其實在壓縮。空字串＝一般回合。
 *
 * [note]＝伺服器對這段等待的說明（「對話有點長了，我先整理一下記憶」、
 * 「剛才沒收到文字，重試第 1/2 次」）。這是**一次性**送來的，之後的 status
 * 不會再帶，所以要沿用到這一輪結束——不然提示閃一下就沒了，等於沒說。
 */
data class TurnStatus(
    val elapsed: Double,
    val model: String,
    val effort: String,
    val tools: Int,
    val ctxTokens: Int,
    val bg: List<String>,
    val phase: String = "",
    val note: String = "",
)

/**
 * 這條對話在伺服器上的狀態，等同 cc-bot 的 `/status`。
 *
 * [model]／[effort] 是**生效值**，[modelOverride]／[effortOverride] 是這條對話
 * 有沒有自己覆寫（空字串＝跟隨帳號預設）。兩者分開才能顯示出
 * 「跟隨預設（sonnet）」與「這條對話已改成 opus」的差別。
 */
data class ConvStatus(
    val model: String = "",
    val effort: String = "",
    val modelOverride: String = "",
    val effortOverride: String = "",
    val cwd: String = "",
    val ctxTokens: Int = 0,
    val ctxLimit: Int = 200_000,
)

/**
 * 已經傳到電腦上、等著跟下一則訊息一起送出的檔案。
 * [path] 是**電腦上的絕對路徑**——助理靠它用 Read 工具讀，
 * 所以附件的本體從來不進聊天內容，只有路徑會。
 */
data class Attachment(val name: String, val path: String, val bytes: Long)

data class ChatState(
    val items: List<TraceItem> = emptyList(),
    /** 生成中的回覆文字，逐字累積；reply.final 到達時定稿成 TraceItem.Reply。 */
    val streaming: String = "",
    /** 生成中的思考尾段，顯示在狀態列（流動的，會被下一步蓋掉）。 */
    val thinkingTail: String = "",
    val status: TurnStatus? = null,
    val busy: Boolean = false,
    /**
     * 這段忙碌從什麼時候開始（epoch 毫秒，0＝不忙）。狀態列的秒數由它本地推算，
     * 不用伺服器的 elapsed——理由見 [dev.butlerkit.app.data.Prefs.busySince]。
     */
    val busySince: Long = 0L,
    val connected: Boolean = false,
    val connError: String? = null,
    val lastSeq: Long = -1L,
    /** 桌寵心情：跟著真實事件流走，不是裝飾。 */
    val pet: PetMood = PetMood.Offline,
    /** 目前顯示的對話。 */
    val currentConv: String = "main",
    /**
     * 按了「新對話」但還沒講話：畫面清空、但伺服器上還沒真的建立。
     * 送出第一則訊息時才落地，避免清單堆一排從沒講過話的空對話。
     */
    val pendingNew: Boolean = false,
    /** 已上傳完成、掛在輸入列上方等著送出的附件。 */
    val attachments: List<Attachment> = emptyList(),
    /** 正在上傳中的檔名。上傳走網路可能要幾秒，沒有這個使用者不知道按了沒。 */
    val uploading: List<String> = emptyList(),
    /** 上傳失敗的原因，顯示在輸入列上方；下次挑檔時清掉。 */
    val uploadError: String? = null,
    val conversations: List<dev.butlerkit.app.net.ConvInfo> = emptyList(),
    val settings: dev.butlerkit.app.net.SettingsInfo? = null,
    /** 目前這條對話的伺服器狀態，切對話時重新拉。 */
    val convStatus: ConvStatus? = null,
)
