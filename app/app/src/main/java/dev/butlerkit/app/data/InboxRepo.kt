package dev.butlerkit.app.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.OfferedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 助理傳來的檔案（App 這一側）。
 *
 * 跟 [AgendaRepo] 同樣是單例、同樣的理由：清單會被三個地方觸發更新——
 * 你打開工具頁、助理傳了新檔案（SSE 的 file.offer）、App 在背景時由前景服務收到同一則。
 *
 * 下載完成的檔名記在 [saved] 裡而不是去掃磁碟：MediaStore 存進去之後
 * 要反查「這個 file_id 存過沒有」得跑一次 query，為了一個勾勾不值得。
 * 這份記錄會寫進 [Prefs.savedFiles]，撐得過 App 被系統回收與重開機。
 */
object InboxRepo {

    private val _files = MutableStateFlow<List<OfferedFile>>(emptyList())
    val files: StateFlow<List<OfferedFile>> = _files.asStateFlow()

    /** 正在下載的 file_id。清單上那一列要轉圈。 */
    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()

    /**
     * 已經存好的 file_id → 存到哪裡（給使用者看的路徑描述）。
     * 由 [restore] 從 Prefs 載回來，每次存檔成功再寫回去（見 [rememberSaved]）。
     */
    private val _saved = MutableStateFlow<Map<String, String>>(emptyMap())
    val saved: StateFlow<Map<String, String>> = _saved.asStateFlow()

    /**
     * 已經下載好、等著被安裝的 APK（file_id）。
     *
     * 跟 [saved] 分開而不是共用：一般檔案存進「下載」資料夾就結束了，
     * APK 存下來只是中途站——按鈕要從「下載」變成「安裝」，那是另一種狀態。
     * 兩者都撐得過 App 重開，但還原的來源不同：這個掃暫存目錄（[ApkUpdate.stagedIds]），
     * 檔案在不在是唯一的事實；[saved] 讀 Prefs 的記錄，因為存進「下載」資料夾之後
     * 那個檔案就不歸 App 管了。
     */
    private val _staged = MutableStateFlow<Set<String>>(emptySet())
    val staged: StateFlow<Set<String>> = _staged.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val lock = Mutex()

    /**
     * 下載專用的 scope，**刻意不綁任何畫面**。
     *
     * 原本下載跑在 composable 的 rememberCoroutineScope 上，於是：切走分頁、
     * 或聊天裡的檔案卡被 LazyColumn 滑出去回收，下載就被取消，畫面還紅字報
     * 「The coroutine scope left the composition」——看起來像伺服器壞了。
     * 檔案要不要存進手機跟使用者當下在看哪一頁無關，所以生命週期歸 App。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [saved] 最多記幾筆。一天傳不到幾個檔，300 筆夠久到不需要考慮。 */
    private const val SAVED_LIMIT = 300

    fun clearError() {
        _error.value = null
    }

    /**
     * 開始下載，不等它。同一個檔案重複按只會下載一次。
     *
     * [ctx] 一律轉成 applicationContext：這個 scope 活得比 Activity 久，
     * 抓著 Activity 不放就是洩漏。
     */
    fun startDownload(ctx: Context, client: ButlerClient, file: OfferedFile) {
        // 檢查與標記必須是同一個原子動作，而且要在**這裡**做完，不能等到
        // coroutine 跑起來才標記：連點兩下時，第二次的檢查會跑在第一次的
        // coroutine 排到之前，兩次都看到空集合、兩次都放行，同一個檔案下載兩遍。
        // compareAndSet 失敗代表中間有人改過，重讀再試一次。
        while (true) {
            val cur = _downloading.value
            if (file.id in cur) return
            if (_downloading.compareAndSet(cur, cur + file.id)) break
        }
        val app = ctx.applicationContext
        scope.launch { download(app, client, file) }
    }

    /**
     * App 啟動時把兩份狀態還原回來：已經備好的更新（掃暫存目錄），
     * 以及下載過哪些檔案（讀 Prefs）。
     */
    fun restore(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            _staged.value = ApkUpdate.stagedIds(app)
            _saved.value = Prefs(app).savedFiles
        }
    }

    /**
     * 記下「這個檔案存過了」，並寫進 Prefs。
     *
     * 上限 [SAVED_LIMIT] 筆、滿了丟最早記的：這份清單只是給眼睛看的標記，
     * 而 file_id 只增不減，不設限就是一個永遠不會被清理的欄位。
     * 重存一次要先 remove 再 put，讓它重新排到最後面——否則它會因為
     * 「最早被記過」而先被丟掉，明明才剛存完。
     */
    private fun rememberSaved(ctx: Context, fileId: String, where: String) {
        val next = LinkedHashMap(_saved.value).apply {
            remove(fileId)
            put(fileId, where)
        }
        while (next.size > SAVED_LIMIT) next.remove(next.keys.first())
        _saved.value = next
        Prefs(ctx).savedFiles = next
    }

    /**
     * 助理傳了東西過來。**只有 APK 會被自動抓下來**，而且只在 Wi-Fi 下。
     *
     * 一般檔案維持手動：256MB 上限，替使用者決定用掉行動網路不是幫忙。
     * APK 例外的理由是它不是「一個檔案」而是「一次更新」——使用者拿到它唯一
     * 會做的事就是安裝，先備好只是把等待時間挪到他還沒注意的時候。
     */
    fun onOffer(ctx: Context, client: ButlerClient, file: OfferedFile) {
        if (!ApkUpdate.isApk(file.name)) return
        val app = ctx.applicationContext
        // 已經備好就只是把狀態補上（例如 App 重開之後又收到同一則）
        if (ApkUpdate.staged(app, file.id) != null) {
            _staged.update { it + file.id }
            return
        }
        if (!ApkUpdate.onWifi(app)) return
        startDownload(app, client, file)
    }

    /**
     * 把系統的安裝畫面叫出來。前提是這支 APK 已經下載好（[staged] 裡有它）。
     *
     * 權限沒開就直接帶去設定頁：那個開關一個 App 只要開一次，但沒開的話送出
     * 安裝 Intent 只會得到一個看起來像壞掉的空白結果。
     */
    fun install(ctx: Context, fileId: String) {
        val app = ctx.applicationContext
        val apk = ApkUpdate.staged(app, fileId)
        if (apk == null) {
            // 檔案被系統清掉了（cacheDir 本來就會被清）。狀態退回去，讓他重抓
            _staged.update { it - fileId }
            _error.value = "更新檔被系統清掉了，再按一次下載"
            return
        }
        if (!ApkUpdate.allowed(app)) {
            _error.value = "系統要你先允許這個 App 安裝應用程式。開完回來再按一次安裝。"
            runCatching { ApkUpdate.openPermissionSettings(app) }
            return
        }
        runCatching { ApkUpdate.install(app, apk) }
            .onFailure { _error.value = "叫不出安裝畫面：${it.message ?: "未知原因"}" }
    }

    suspend fun refresh(client: ButlerClient) = lock.withLock {
        client.listOfferedFiles()
            .onSuccess {
                _files.value = it
                // 這次成功就把上次的錯誤收掉，否則連線恢復後那行紅字會一直掛著，
                // 看起來像還壞著。
                _error.value = null
            }
            .onFailure {
                Log.w(ButlerClient.TAG, "拉檔案清單失敗：${it.message}")
                // 也要說給使用者聽。只寫 log 的話，助理說「傳給你了」但清單是空的，
                // 而畫面上沒有任何東西顯示「是拉清單失敗，不是沒有檔案」。
                _error.value = "拿不到檔案清單：${it.message ?: "連線失敗"}"
            }
    }

    /**
     * 下載並存進「下載」資料夾。
     *
     * 存 Downloads 而不是 App 私有目錄：使用者要的是「這個檔案到我手機上了」，
     * 藏在 App 沙盒裡等於沒拿到——他得先知道有檔案管理器、再知道去哪一層找。
     */
    private suspend fun download(
        ctx: Context, client: ButlerClient, file: OfferedFile,
    ): Boolean {
        // 進來時 file.id 已經在 _downloading 裡了（由 startDownload 原子地放進去），
        // 這裡只負責收尾把它拿掉。
        val apk = ApkUpdate.isApk(file.name)
        val result = runCatching {
            when {
                apk -> stageApk(ctx, client, file)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    saveViaMediaStore(ctx, client, file)
                else -> saveToAppDir(ctx, client, file)
            }
        }
        _downloading.update { it - file.id }
        return result.fold(
            onSuccess = { where ->
                if (apk) _staged.update { it + file.id }
                else rememberSaved(ctx, file.id, where)
                true
            },
            onFailure = { e ->
                // 取消不是失敗。runCatching 連 CancellationException 都抓，
                // 直接報給使用者只會得到一句他看不懂又不該看到的協程訊息。
                if (e is CancellationException) throw e
                _error.value = "${file.name}：${e.message ?: "下載失敗"}"
                false
            },
        )
    }

    /**
     * APK 走自己的路：存進 App 的暫存區，不進公用「下載」目錄。理由見 [ApkUpdate]。
     *
     * 半截檔一定要刪掉。留著的話 [ApkUpdate.staged] 會以為它備好了，
     * 按下安裝只會得到系統一句「無法剖析套件」——那看起來像編壞了，不像沒下載完。
     */
    private suspend fun stageApk(
        ctx: Context, client: ButlerClient, file: OfferedFile,
    ): String {
        val target = ApkUpdate.target(ctx, file.id)
        try {
            target.outputStream().use { out ->
                client.downloadOfferedFile(file.id, out).getOrThrow()
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        return target.name
    }

    /**
     * Android 10 以上的正規做法：透過 MediaStore 寫進公用「下載」目錄，不需要任何權限。
     *
     * IS_PENDING 期間別的 App 看不到這個檔案——中途失敗留下的半截檔不會被誤開，
     * 而且我們自己會把它刪掉。
     */
    private suspend fun saveViaMediaStore(
        ctx: Context, client: ButlerClient, file: OfferedFile,
    ): String {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("系統不讓我寫入下載資料夾")
        try {
            cr.openOutputStream(uri)?.use { out ->
                client.downloadOfferedFile(file.id, out).getOrThrow()
            } ?: error("開不了寫入串流")
        } catch (e: Throwable) {
            runCatching { cr.delete(uri, null, null) }   // 半截檔不留在下載清單裡
            throw e
        }
        cr.update(uri, ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }, null, null)
        return "下載／${file.name}"
    }

    /** Android 9 以下：寫到 App 自己的外部目錄。那裡不需要權限，代價是不在公用下載夾。 */
    private suspend fun saveToAppDir(
        ctx: Context, client: ButlerClient, file: OfferedFile,
    ): String {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: error("找不到可寫入的位置")
        val target = File(dir, file.name)
        target.outputStream().use { out ->
            client.downloadOfferedFile(file.id, out).getOrThrow()
        }
        return target.absolutePath
    }
}
