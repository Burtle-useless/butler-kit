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
 * 重開 App 就忘記，也沒關係——再存一次只是多一個檔案。
 */
object InboxRepo {

    private val _files = MutableStateFlow<List<OfferedFile>>(emptyList())
    val files: StateFlow<List<OfferedFile>> = _files.asStateFlow()

    /** 正在下載的 file_id。清單上那一列要轉圈。 */
    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()

    /** 已經存好的 file_id → 存到哪裡（給使用者看的路徑描述）。 */
    private val _saved = MutableStateFlow<Map<String, String>>(emptyMap())
    val saved: StateFlow<Map<String, String>> = _saved.asStateFlow()

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
        if (file.id in _downloading.value) return
        val app = ctx.applicationContext
        scope.launch { download(app, client, file) }
    }

    suspend fun refresh(client: ButlerClient) = lock.withLock {
        client.listOfferedFiles()
            .onSuccess { _files.value = it }
            .onFailure { Log.w(ButlerClient.TAG, "拉檔案清單失敗：${it.message}") }
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
        _downloading.update { it + file.id }
        val result = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(ctx, client, file)
            } else {
                saveToAppDir(ctx, client, file)
            }
        }
        _downloading.update { it - file.id }
        return result.fold(
            onSuccess = { where ->
                _saved.update { it + (file.id to where) }
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
