package dev.butlerkit.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 連線設定與續傳游標。
 *
 * Phase 1 刻意用 SharedPreferences 而不是 Room——這裡只有四個純量，
 * 引入 Room + KSP 只為了存一個 Long 不划算。等到要快取事件歷史（讓 App 冷啟動
 * 就能顯示上次的對話）才值得換。
 *
 * token 目前也存這裡；Phase 5 做 QR 配對時要改存 Android Keystore
 * （EncryptedSharedPreferences），那時這個檔案要一併搬。
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("butler", Context.MODE_PRIVATE)

    /** 伺服器位址。可以是 `主機:埠`，也可以是完整網址（`https://…`）。 */
    var host: String
        get() = sp.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(v) = sp.edit().putString(KEY_HOST, v).apply()

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_TOKEN, v).apply()

    /**
     * **畫面**最後收到的事件序號，前景重連時當 Last-Event-ID 用。
     * 沒有它的話 App 一切到背景就會漏掉整段回覆——這是續傳的錨點。
     *
     * 只有 ChatViewModel 能動它。背景服務用 [bgSeq]，兩者不可共用，理由見下。
     */
    var lastSeq: Long
        get() = sp.getLong(KEY_LAST_SEQ, -1L)
        set(v) = sp.edit().putLong(KEY_LAST_SEQ, v).apply()

    /**
     * **背景服務**的續傳游標，只用來決定「哪些事件還沒推播過」。
     *
     * 為什麼要跟 [lastSeq] 分開：兩條連線同時只會有一條在跑（前景 ViewModel／
     * 背景 ButlerService），但它們的職責完全不同——ViewModel 維護聊天軌跡，
     * ButlerService 只發通知，**不存任何內容**。共用一個游標時，背景收到的事件
     * 會把游標推過頭，使用者點通知回到前景，ViewModel 帶著這個游標重連，伺服器
     * 判定「那些你都收過了」而不重播；ViewModel 手上卻一則都沒有。
     * 而 `loadSnapshot` 的兜底條件是「本地為空才填歷史」，App 進程還活著、
     * 軌跡不為空，於是歷史也不補——那段對話就永久卡在兩條連線的交接縫裡。
     *
     * 症狀是使用者 2026-08-17 回報的「通知看得到內容，跳進 App 什麼都沒有」。
     * 分家之後前景會把背景期間的事件整段補回來，畫面才對得上通知。
     */
    var bgSeq: Long
        get() = sp.getLong(KEY_BG_SEQ, -1L)
        set(v) = sp.edit().putLong(KEY_BG_SEQ, v).apply()

    /**
     * 上次拉到的行事曆／鬧鐘／記帳原始 JSON。
     *
     * 存這份的唯一理由是**重開機**：AlarmManager 的排程開機後全部消失，
     * 而開機那一刻 App 沒開、可能也還沒有網路，跟電腦要不到資料。
     * 沒有這份快取，重開機等於所有鬧鐘靜悄悄失效。
     */
    var agendaCache: String
        get() = sp.getString(KEY_AGENDA, "") ?: ""
        set(v) = sp.edit().putString(KEY_AGENDA, v).apply()

    /**
     * 上次拉到的用量原始 JSON，給桌面 widget 用。
     *
     * 行事曆那份快取是為了重開機，這份是為了**widget 沒有網路可等**：
     * widget 每次重畫只有幾秒的時間可以出畫面，來不及發一趟 HTTP。
     * 所以由 UsageWorker 在背景拉好放這裡，widget 只負責畫。
     * 拉的時間一併存 [usageAt]，畫面上要標「幾分鐘前」——一個沒有時間戳的
     * 用量數字看起來永遠是即時的，那會騙人。
     */
    var usageCache: String
        get() = sp.getString(KEY_USAGE, "") ?: ""
        set(v) = sp.edit().putString(KEY_USAGE, v).apply()

    var usageAt: Long
        get() = sp.getLong(KEY_USAGE_AT, 0L)
        set(v) = sp.edit().putLong(KEY_USAGE_AT, v).apply()

    /**
     * 快取變動時吐出新內容的流。**桌面 widget 專用**。
     *
     * 為什麼需要：Glance 的 `provideGlance` 只在 session 建立時跑一次，在那裡讀到的
     * 值會被寫死進 composition。session 是常駐的，所以之後不管收到幾次更新請求，
     * 畫面重組用的都還是最初那份快取——實測就是「加課看得到、刪課刪不掉」。
     * 讓 composition 直接訂閱這個流，資料換了它自己會重組。
     *
     * 一開始先發一次現值，訂閱者不必自己補第一筆。
     */
    fun watch(key: String): Flow<String> = callbackFlow {
        trySend(sp.getString(key, "") ?: "")
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, k ->
            if (k == key) trySend(p.getString(k, "") ?: "")
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /**
     * 這段「助理正在忙」是什麼時候開始的（epoch 毫秒，0＝現在不忙）。
     *
     * 計時器不能用伺服器 status 事件裡的 elapsed 來顯示，那個值有三種歸零方式：
     * ①續跑與重試每輪都是新的 run_turn，start 重新計時 ②斷線續傳會把背景期間
     * 累積的 status 整批重播，elapsed 從舊值一路往上跑，畫面看起來就是倒退重數
     * ③Activity 被系統回收後 ViewModel 全新，什麼都不記得。
     * 使用者要看的是「我送出到現在等了多久」，所以起點必須撐過這三件事——
     * 存進 SharedPreferences 是唯一能跨 ViewModel 重建與跨進程的做法。
     */
    var busySince: Long
        get() = sp.getLong(KEY_BUSY_SINCE, 0L)
        set(v) = sp.edit().putLong(KEY_BUSY_SINCE, v).apply()

    /** 目前排進 AlarmManager 的 id。下次同步時照這份取消，才不會留下孤兒鬧鐘。 */
    var scheduledIds: Set<String>
        get() = sp.getStringSet(KEY_SCHEDULED, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_SCHEDULED, v).apply()

    /**
     * 已經下載過的檔案：file_id → 存到哪裡（給使用者看的描述）。
     *
     * 這份原本只活在記憶體裡，理由是「重存一次只是多一個檔案」。實際用起來不是：
     * App 一被系統回收，整份清單就全部退回「沒下載過」的樣子，
     * 使用者只能靠記憶分辨哪幾個抓過了——檔案多的時候等於沒有這個標記。
     *
     * 存 StringSet 不行（要的是成對的 id 與位置），所以序列化成 JSON 塞一個 String。
     * 只有幾十個字元乘上筆數，SharedPreferences 撐得住。
     */
    var savedFiles: Map<String, String>
        get() = runCatching {
            Json.decodeFromString(SAVED_SERIALIZER, sp.getString(KEY_SAVED_FILES, "") ?: "")
        }.getOrDefault(emptyMap())
        // serializer 明寫出來：單參數的 encodeToString 是擴充函式，會輸給
        // Json 自己那個吃 (SerializationStrategy, value) 的成員函式，編不過
        set(v) = sp.edit()
            .putString(KEY_SAVED_FILES, Json.encodeToString(SAVED_SERIALIZER, v))
            .apply()

    /**
     * 剛剛送去給系統安裝、還不知道結果的那個 APK（file_id），沒有就是空字串。
     *
     * 安裝這件事沒有回呼：Intent 送出去之後畫面就交給系統的套件安裝器了，
     * 使用者可能裝、也可能按取消，App 這邊完全收不到消息。唯一的確認來自
     * 更新成功後系統發的 MY_PACKAGE_REPLACED——但那則廣播只說「我被換掉了」，
     * 不說是被哪個檔案換掉的。所以要先把 id 記在這裡，等廣播來了才對得起來。
     */
    var pendingInstall: String
        get() = sp.getString(KEY_PENDING_INSTALL, "") ?: ""
        set(v) = sp.edit().putString(KEY_PENDING_INSTALL, v).apply()

    /**
     * 已經裝起來的 APK（file_id），舊到新。
     *
     * 卡片上那顆按鈕裝完之後要變成「已更新」而不是退回「存到手機」。做不到的話
     * 畫面看起來就像什麼都沒發生過，而 APK 又不像一般檔案存在「下載」資料夾裡
     * 可以自己去確認——安裝檔裝完就被清掉了（見 [ApkUpdate]），沒有任何痕跡。
     *
     * 不比對 versionCode 而是記 id：這支 App 的 versionCode 是寫死的，
     * 每一版都一樣，拿來比等於沒比。
     *
     * 存 JSON List 不存 StringSet：滿了要丟最舊的那筆，而 StringSet 無序。
     */
    var installedApks: List<String>
        get() = runCatching {
            Json.decodeFromString(ID_LIST_SERIALIZER, sp.getString(KEY_INSTALLED_APKS, "") ?: "")
        }.getOrDefault(emptyList())
        set(v) = sp.edit()
            .putString(KEY_INSTALLED_APKS, Json.encodeToString(ID_LIST_SERIALIZER, v))
            .apply()

    /**
     * 打到一半還沒送出的訊息：conv_id → 內容。
     *
     * 輸入框的內容原本只是畫面上的一個 `remember`，離開分頁就沒了，App 被系統
     * 回收更是連影子都不剩——打了一段長訊息、中途切出去查個東西，回來是空的。
     * 每條對話各存一份：草稿是講給那條對話聽的，切過去又切回來要看到原本那句。
     *
     * 跟 [savedFiles] 同樣的理由用 JSON 塞一個 String：要的是成對的鍵值。
     */
    var drafts: Map<String, String>
        get() = runCatching {
            Json.decodeFromString(SAVED_SERIALIZER, sp.getString(KEY_DRAFTS, "") ?: "")
        }.getOrDefault(emptyMap())
        set(v) = sp.edit()
            .putString(KEY_DRAFTS, Json.encodeToString(SAVED_SERIALIZER, v))
            .apply()

    /**
     * 面對面翻譯上次選的兩個語言，存 `TalkLang` 的 enum name。
     *
     * 存這個是因為換語言要重新下載／載入語言包（各約 30MB），
     * 每次開畫面都退回預設值等於逼他每次重選一次。
     */
    var talkMine: String
        get() = sp.getString(KEY_TALK_MINE, "") ?: ""
        set(v) = sp.edit().putString(KEY_TALK_MINE, v).apply()

    var talkTheirs: String
        get() = sp.getString(KEY_TALK_THEIRS, "") ?: ""
        set(v) = sp.edit().putString(KEY_TALK_THEIRS, v).apply()

    /**
     * 請求的網址前綴。
     *
     * [host] 自己帶了 scheme 就照用，沒帶的才補 `http://`——`主機:埠` 這種寫法
     * 是區網或 tailnet 直連，沒有憑證也沒有 https；走 Cloudflare Tunnel 之類的
     * 通道則是完整的 `https://…`。兩種都要能填，設定頁那一欄是同一個。
     */
    val baseUrl: String
        get() = if (host.startsWith("http://") || host.startsWith("https://")) {
            host.trimEnd('/')
        } else {
            "http://$host"
        }

    fun isConfigured(): Boolean = host.isNotBlank() && token.isNotBlank()

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_TOKEN = "token"
        private const val KEY_LAST_SEQ = "last_seq"
        private const val KEY_BG_SEQ = "bg_seq"
        // 這兩個不是 private：widget 要拿它們去 [watch] 訂閱對應的那份快取
        const val KEY_AGENDA = "agenda_cache"
        const val KEY_USAGE = "usage_cache"
        private const val KEY_USAGE_AT = "usage_at"
        private const val KEY_SCHEDULED = "scheduled_ids"
        private const val KEY_SAVED_FILES = "saved_files"
        private const val KEY_DRAFTS = "drafts"
        private const val KEY_PENDING_INSTALL = "pending_install"
        private const val KEY_INSTALLED_APKS = "installed_apks"

        /** [savedFiles] 與 [drafts] 共用的 JSON serializer。 */
        private val SAVED_SERIALIZER = MapSerializer(String.serializer(), String.serializer())

        /** [installedApks] 的 JSON serializer。 */
        private val ID_LIST_SERIALIZER = ListSerializer(String.serializer())
        private const val KEY_BUSY_SINCE = "busy_since"
        private const val KEY_TALK_MINE = "talk_mine"
        private const val KEY_TALK_THEIRS = "talk_theirs"

        /**
         * 伺服器位址，格式 `主機:埠`，或完整網址（`https://…`）。
         *
         * 刻意留空——填一個猜的位址進去，連不上的時候症狀是「一直轉圈」，
         * 而那跟防火牆擋掉長得一模一樣，新手會往錯的方向查。空的至少會直接
         * 把人導到設定頁。
         *
         * 用 Tailscale 的話建議填 MagicDNS 名稱（`your-pc.tailXXXX.ts.net:47362`）
         * 而不是 IP：位址會變，名字不會，而 network_security_config 的白名單
         * 是編譯期資源，改一次就要重編一次 APK。
         *
         * 走 Cloudflare Tunnel 之類的通道就填那個 https 網址
         * （`https://butler.example.com`，443 可省略）。那條路不碰明文白名單，
         * 什麼都不必改。
         */
        const val DEFAULT_HOST = ""
    }
}
