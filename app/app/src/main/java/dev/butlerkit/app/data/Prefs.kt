package dev.butlerkit.app.data

import android.content.Context
import android.content.SharedPreferences

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

    var host: String
        get() = sp.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(v) = sp.edit().putString(KEY_HOST, v).apply()

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_TOKEN, v).apply()

    /**
     * 最後收到的事件序號，重連時當 Last-Event-ID 用。
     * 沒有它的話 App 一切到背景就會漏掉整段回覆——這是續傳的錨點。
     */
    var lastSeq: Long
        get() = sp.getLong(KEY_LAST_SEQ, -1L)
        set(v) = sp.edit().putLong(KEY_LAST_SEQ, v).apply()

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

    val baseUrl: String get() = "http://$host"

    fun isConfigured(): Boolean = host.isNotBlank() && token.isNotBlank()

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_TOKEN = "token"
        private const val KEY_LAST_SEQ = "last_seq"
        private const val KEY_AGENDA = "agenda_cache"
        private const val KEY_SCHEDULED = "scheduled_ids"
        private const val KEY_BUSY_SINCE = "busy_since"
        private const val KEY_TALK_MINE = "talk_mine"
        private const val KEY_TALK_THEIRS = "talk_theirs"

        /**
         * 伺服器位址，格式 `主機:埠`。
         *
         * 刻意留空——填一個猜的位址進去，連不上的時候症狀是「一直轉圈」，
         * 而那跟防火牆擋掉長得一模一樣，新手會往錯的方向查。空的至少會直接
         * 把人導到設定頁。
         *
         * 用 Tailscale 的話建議填 MagicDNS 名稱（`your-pc.tailXXXX.ts.net:47362`）
         * 而不是 IP：位址會變，名字不會，而 network_security_config 的白名單
         * 是編譯期資源，改一次就要重編一次 APK。
         */
        const val DEFAULT_HOST = ""
    }
}
