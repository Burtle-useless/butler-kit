package dev.butlerkit.app.net

/** 這一檔只放 [ButlerClient] 各領域 API 往返的資料型別；請求本身在各 *Api.kt。 */

/** 連線狀態或一則事件。UI 兩者都要，所以走同一條 Flow。 */
sealed interface Wire {
    data class Ev(val event: ServerEvent) : Wire
    data class Conn(val connected: Boolean, val error: String? = null) : Wire
}

data class ConvInfo(val id: String, val title: String, val mtime: Double)

/** 電腦上既有的 Claude Code session（還沒被助理接管的那些）。 */
data class LocalSession(
    val sessionId: String,
    val cwd: String,
    val title: String,
    val mtime: Double,
    val branch: String,
    val sidechain: Boolean,
)

/** 一頁 session。[hasMore] 為 true 代表後面還有，滑到底可以再拉一頁。 */
data class LocalSessionPage(val rows: List<LocalSession>, val hasMore: Boolean)

data class SearchHit(
    val convId: String, val title: String, val role: String, val snippet: String,
)

/**
 * [think] 是這一回合的思考全文，只有 assistant 那側會有，沒有就是空字串。
 * [atMs] 是這則的時間（epoch 毫秒），用來跟檔案卡片排在同一條軸上。
 */
data class HistoryMsg(
    val role: String,
    val text: String,
    val think: String = "",
    val atMs: Long = 0L,
    /**
     * 這一回合動過的工具，舊到新，只有 assistant 那側會有。
     *
     * 跟 [think] 同一個道理：不帶的話，回看歷史就只剩問與答，助理到底改了哪些
     * 檔案、跑了什麼指令全部消失，而那是唯一的紀錄（2026-08-25 使用者回報）。
     */
    val tools: List<ToolCall> = emptyList(),
    /**
     * 這則訊息底下的選項按鈕，沒有就是 null。
     *
     * 伺服器重建歷史時從回覆原文的 [[ASK:]] 標記重新抽出來（見 fold.ask_payload），
     * 而且**只有還沒被回答的那一則會帶**——對話往下走了就代表已經答過。
     * 這樣重裝 App、被系統回收、換手機，該回答的那題都還在原地等。
     */
    val ask: AskRequest? = null,
)

/**
 * 歷史裡的檔案卡片。
 *
 * 為什麼要單獨從伺服器補：卡片是 butler 自己造的東西，CC 的逐字稿裡沒有它，
 * 所以重建畫面時變不出來——App 一重啟對話裡的下載框就整排消失。
 * [atMs] 由伺服器算好（那邊才知道登記時間是本地時區），App 只負責照著排。
 */
/**
 * 排著還沒輪到的訊息。
 *
 * 跟 [HistoryFile] 同一個道理：它還沒送進 CC，所以逐字稿裡沒有它，重建畫面時
 * 補不回來——使用者看不到自己剛剛送出了什麼，但伺服器照樣會處理它。
 */
data class PendingMsg(val msgId: String, val text: String)

data class HistoryFile(
    val fileId: String,
    val name: String,
    val bytes: Long,
    val note: String = "",
    val mime: String = "",
    val atMs: Long = 0L,
)

/**
 * 看板上的一件工作。屬於哪個企劃直接寫在 [title] 裡（「助理－看板拖放」），
 * 不是獨立欄位。[status] 是 todo／doing／done；[lastTouch] 是 ISO 8601 字串。
 */
data class KanbanCard(
    val id: String,
    val title: String,
    val status: String = "todo",
    val urgent: Boolean = false,
    val note: String = "",
    val lastTouch: String = "",
)

/**
 * 一個階段欄，含它底下的工作。伺服器已排好順序，照著畫就好。
 * [total] 是這一欄總共幾張、[hidden] 是被收起來沒回傳的張數（只有完成欄會有）。
 */
data class KanbanColumn(
    val status: String,
    val label: String,
    val total: Int,
    val hidden: Int,
    val cards: List<KanbanCard>,
)

/**
 * 電腦上那個服務的現況。
 *
 * [commit]／[subject] 是**行程啟動當下**的版本，也就是現在真正在跑的那份；
 * [latestCommit]／[latestSubject] 是磁碟上最新的一筆。兩者不同就代表助理改完了
 * 但還沒重啟——這正是「我到底按過重啟了沒」的答案。
 * 拿不到 git 資訊時全是空字串。
 */
data class SystemStatus(
    val pid: Int,
    val uptime: String,
    val commit: String,
    val subject: String,
    val latestCommit: String = "",
    val latestSubject: String = "",
)

/**
 * 一台配對過的裝置。
 *
 * [hash] 是 token 的 SHA-256，**它本身不是憑證**——伺服器從來沒存過明文，
 * 拿到這串既登入不了也解不回 token，所以可以安全地帶到畫面上。
 * 撤銷 API 收的就是它。
 */
data class DeviceInfo(
    val hash: String,
    /** 雜湊前 8 碼，給人辨認用。全長 64 個字在手機上排不下。 */
    val short: String,
    val name: String,
    /** 配對時間，epoch 秒。 */
    val created: Double,
    /** 最後一次通過認證的時間；只存在伺服器記憶體，服務重啟後為 null。 */
    val lastSeen: Double?,
    /** 是不是正在看這個畫面的這一台。這一列不給撤銷。 */
    val isThis: Boolean,
)

data class Snapshot(
    val busy: Boolean,
    val queued: Int,
    val messages: List<HistoryMsg>,
    /** 這段對話裡傳過的檔案，舊到新。重建軌跡時依 atMs 插回訊息之間。 */
    val files: List<HistoryFile> = emptyList(),
    /** 排著還沒輪到的訊息，舊到新。重建軌跡時接在歷史最後面。 */
    val pending: List<PendingMsg> = emptyList(),
    /**
     * 還在等你回答的提問。
     *
     * 這個不補回來的後果比前兩個重：伺服器等不到答案會 fail-closed 當成拒絕，
     * 那件事直接被擋掉，而使用者根本不知道它問過。
     */
    val asks: List<AskRequest> = emptyList(),
    /**
     * 背景工作：還在跑的，加上還沒被收起來的完成紀錄。
     *
     * 同一個病的第五種：`bg.state` 事件只在有變化時發，App 重開之後在下一件
     * 工作跑完之前一則都收不到，那幾張卡片就消失了——而它們正是「等一下
     * 還會有東西自己冒出來」的唯一預告。
     */
    val bg: List<BgTask> = emptyList(),
    // 對話層狀態（等同 cc-bot 的 /status）。model/effort 是生效值，
    // override 是「這個對話有沒有單獨覆寫」（空字串＝跟隨帳號預設）
    val model: String = "",
    val effort: String = "",
    val modelOverride: String = "",
    val effortOverride: String = "",
    val cwd: String = "",
    val ctxTokens: Int = 0,
    /** context 上限，隨模型變（Opus 在 max 方案是 1M）。用量長條的分母。 */
    val ctxLimit: Int = 200_000,
    /**
     * [messages] 只是最後一頁（60 則），前面還有沒有更早的。
     * 舊伺服器沒這個欄位，當 false——畫面上就不會出現「載入更早」。
     */
    val hasMore: Boolean = false,
)

/**
 * 更早的一頁歷史（`GET /v1/conversations/{id}/history?before_ms=`）。
 * [messages] 舊到新、格式跟 snapshot 的一樣；[hasMore] 為 true 代表再往前還有。
 */
data class HistoryPage(val messages: List<HistoryMsg>, val hasMore: Boolean)

/**
 * 一個可選的模型。對應伺服器 `engine/models.ModelInfo.to_wire()`，內容來自 CLI 的
 * initialize 回應（官方 app 的選單就是這份），CLI 升版清單自己會長。
 */
data class ModelInfo(
    val value: String,          // 給設定用的值（default／opus[1m]／sonnet…）
    val resolved: String,       // 實際會跑的模型 id
    val name: String,           // 顯示名
    val description: String,
    val efforts: List<String>,  // 這個模型能選的思考等級；空＝不支援
    val supportsEffort: Boolean,
)

data class SettingsInfo(
    val model: String?,
    val effort: String?,
    val models: List<String>,
    val efforts: List<String>,
    val infos: List<ModelInfo> = emptyList(),
    /**
     * 伺服器現在有沒有在跑破壞性指令之前先問一次（由啟動腳本的 CONFIRM_DANGEROUS 決定，
     * App 只能看不能改）。舊伺服器沒這個欄位時視為開著——那是安全的那一邊。
     */
    val confirmDangerous: Boolean = true,
)
