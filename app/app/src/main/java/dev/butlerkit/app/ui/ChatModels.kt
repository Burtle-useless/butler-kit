package dev.butlerkit.app.ui

import dev.butlerkit.app.net.AskRequest
import dev.butlerkit.app.net.BgTask
import dev.butlerkit.app.net.ToolCall

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
fun replyOrNull(turnId: String, text: String, atMs: Long = 0L): TraceItem.Reply? =
    if (cleanMarkers(text).isBlank()) null else TraceItem.Reply(turnId, text, atMs)

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
        /**
         * 這則是**插進正在跑的回合**的（不是排隊）。
         *
         * 伺服器一直有送這個旗標。不用的話插話與排隊的氣泡長得一模一樣，
         * 使用者會以為插話沒生效；而且插進去之後模型可能正在跑一個長工具，
         * 要過一會才反應得到，那段空窗期畫面上什麼都不說的話，感覺就是沒進去。
         */
        val steered: Boolean = false,
        /** 這則的時刻（epoch 毫秒）。0＝不知道（舊伺服器、本地造的項目）。 */
        val atMs: Long = 0L,
        /**
         * 跟這則一起送出的附件。**畫面畫的是它，不是路徑文字**——路徑只在伺服器
         * 組給模型的那一份出現（見 engine.turn.stamp）。
         */
        val attachments: List<Attachment> = emptyList(),
    ) : TraceItem

    /** 模型這一步在想什麼（step.commit 的思考摘要，已定稿不會再變）。 */
    data class Thinking(override val turnId: String, val text: String) : TraceItem

    /**
     * 一個「階段」＝CC 的一句說明＋其下累積的工具呼叫，畫面上一條工具一行。
     *
     * 原本這裡還有一個 `summary`，把工具摺成「讀 2 個檔案・執行 1 個指令 +28 −0」
     * 一行統計，明細要點開才看得到。2026-08-21 改成逐條直接列（理由見
     * ChatScreen.StageRow），統計就沒有讀者了，跟著刪掉。
     */
    data class Stage(
        override val turnId: String,
        val text: String,
        val tools: List<ToolCall> = emptyList(),
    ) : TraceItem

    /**
     * 破壞性指令：**永遠單獨一列、永遠展開、不併入統計**。
     * 摺疊會把指令藏起來，「核對它到底在跑什麼」的防線就沒了。
     */
    data class DangerTool(override val turnId: String, val call: ToolCall) : TraceItem

    data class Reply(
        override val turnId: String,
        val markdown: String,
        val atMs: Long = 0L,
    ) : TraceItem

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
        val atMs: Long = 0L,
    ) : TraceItem

    /**
     * 助理自己醒來的那一輪開頭的一行說明（`turn.start` 的 origin="wake"）。
     *
     * 背景工作跑完後 CLI 會自己另起一輪讓助理讀結果接著講——這一輪不是任何人問的，
     * 畫面上沒有使用者氣泡可以當脈絡，憑空冒出一段回覆會讓人找不到頭。跟官方
     * 終端機一樣補一行「為什麼醒來」。刻意不是氣泡：它不是誰說的話。
     */
    data class WakeNote(
        override val turnId: String,
        val text: String,
        val atMs: Long = 0L,
    ) : TraceItem
}

/** 這一列的時刻（epoch 毫秒）；沒有時間概念的列（工具、思考、卡片）一律 0。 */
val TraceItem.atMs: Long
    get() = when (this) {
        is TraceItem.UserMsg -> atMs
        is TraceItem.Reply -> atMs
        is TraceItem.WakeNote -> atMs
        is TraceItem.ErrorItem -> atMs
        else -> 0L
    }

/**
 * 第 [i] 列前面要不要插日期分隔，要的話回傳那行字（「9月3日 週三」），不要就 null。
 *
 * 規則跟成熟的聊天 App 一樣：只在**跨日**的地方插一條，不是每則都印時間。
 * 「跨日」比的是這一列與**前一個有時間的列**，中間夾著沒時間的工具列不算數；
 * 開頭第一個有時間的列前面也插一條，不然對話的起點沒有日期。
 */
fun dayLabelBefore(
    items: List<TraceItem>,
    i: Int,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): String? {
    val ms = items[i].atMs
    if (ms <= 0L) return null
    val day = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    for (j in i - 1 downTo 0) {
        val prev = items[j].atMs
        if (prev <= 0L) continue
        val prevDay = java.time.Instant.ofEpochMilli(prev).atZone(zone).toLocalDate()
        return if (prevDay == day) null else dayLabel(day)
    }
    return dayLabel(day)
}

private fun dayLabel(d: java.time.LocalDate): String {
    val week = "一二三四五六日"[d.dayOfWeek.value - 1]
    val year = if (d.year == java.time.LocalDate.now().year) "" else "${d.year}年"
    return "$year${d.monthValue}月${d.dayOfMonth}日 週$week"
}

/** 長按選單裡那行「15:32」。0 就不顯示，回 null。 */
fun clockLabel(ms: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? {
    if (ms <= 0L) return null
    val t = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
    return "%02d:%02d".format(t.hour, t.minute)
}

/** wake 回合開頭那行字。伺服器對不上是哪件工作時就只說「接著說」。 */
fun wakeNoteText(reason: BgTask?): String {
    if (reason == null || reason.desc.isBlank()) return "助理接著說"
    val how = when (reason.status) {
        "completed" -> "完成"
        "failed" -> "失敗"
        "stopped" -> "中止"
        else -> ""
    }
    return "背景工作「${reason.desc}」$how，助理接手"
}

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
    val bg: List<BgTask>,
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
 * 一個要跟訊息一起送出的附件（已經傳到電腦上，等著跟下一則訊息一起走）。
 *
 * [path] 是**電腦上的絕對路徑**（上傳端點回的），模型靠它用 Read 工具讀；
 * 使用者看到的是縮圖或檔案卡，不是這串路徑。[mime] 決定畫縮圖還是畫檔案圖示。
 */
data class Attachment(
    val name: String,
    val path: String,
    val bytes: Long,
    val mime: String = "",
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

data class ChatState(
    val items: List<TraceItem> = emptyList(),
    /** 生成中的回覆文字，逐字累積；reply.final 到達時定稿成 TraceItem.Reply。 */
    val streaming: String = "",
    /** 生成中的思考尾段，顯示在狀態列（流動的，會被下一步蓋掉）。 */
    val thinkingTail: String = "",
    val status: TurnStatus? = null,
    /**
     * 背景工作：還在跑的，加上還沒被收起來的完成紀錄。
     *
     * 跟 [TurnStatus.bg] 是同一份東西，但活得比一輪久：伺服器的狀態心跳隨回合
     * 結束而停，而背景工作不會停。助理說完「等它完成」收工之後，畫面上那幾張卡片
     * 得靠這個欄位留著——不然指示會跟著回合一起消失，看起來像什麼事都沒在發生。
     * 伺服器用 `bg.state` 事件維護它，使用者下次發言時把完成的收起來。
     */
    val bgTasks: List<BgTask> = emptyList(),
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
     * 輸入框裡打到一半的字。放在這裡而不是畫面的 `remember`：那個活不過
     * 切分頁，更活不過 App 被系統回收，打一半切出去回來就是空的。
     * 由 ChatViewModel 以 conv_id 分流並寫進 Prefs。
     */
    val draft: String = "",
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
    /**
     * 這條對話的歷史還在路上（snapshot 尚未回來、本地又一則都沒有）。
     * 畫面拿它畫骨架，而不是先閃一下「還沒有對話」的空狀態再換成內容。
     */
    val historyLoading: Boolean = false,
    /**
     * 本地最早那則前面還有更早的歷史（snapshot 只帶最後一頁）。
     * 畫面拿它決定要不要在最上面放「載入更早」那一列；舊伺服器沒這個概念，一律 false。
     */
    val hasMore: Boolean = false,
    /** 正在往前翻一頁。那一列換成小型進度，且同時只跑一趟。 */
    val olderLoading: Boolean = false,
)

// ── 工具軌跡的摺疊 ────────────────────────────────────────────────────────────
//
// 逐條列出每一次工具呼叫，好處是看得出「它到底在幹嘛」；壞處是量一大就把回覆
// 整個埋掉——一輪十幾次工具的話，使用者要往上滑好幾頁才找得到助理真正說的話。
//
// 所以不是二選一，是看量：少的照舊逐條（那個好處留著），多的才收成一行帶
// 「做了什麼」的摘要（不是只有數字），點一下展開。破壞性指令走 DangerTool，
// 從來就不進這裡，不受影響。
const val TOOL_FOLD_THRESHOLD = 5

/**
 * 一個階段的工具摘要，例如「讀 3 個檔・改 2 個檔・跑 7 個指令　+128 −40」。
 *
 * 刻意按 `kind` 分類講「做了什麼」而不是只報一個總數——只報數字的統計列
 * 看得出規模、看不出在幹嘛，那正是逐條列存在的理由，摺疊不能把它弄丟。
 */
fun toolSummary(tools: List<dev.butlerkit.app.net.ToolCall>): String {
    if (tools.isEmpty()) return ""
    val n = tools.groupingBy { it.kind }.eachCount()
    val parts = buildList {
        n["read"]?.let { add("讀 $it 個檔") }
        n["search"]?.let { add("搜尋 $it 次") }
        n["edit"]?.let { add("改 $it 個檔") }
        n["cmd"]?.let { add("跑 $it 個指令") }
        n["web"]?.let { add("上網 $it 次") }
        n["other"]?.let { add("其他 $it 次") }
    }
    val added = tools.sumOf { it.added }
    val removed = tools.sumOf { it.removed }
    val diff = when {
        added == 0 && removed == 0 -> ""
        else -> "　+$added −$removed"
    }
    return parts.joinToString("・") + diff
}
