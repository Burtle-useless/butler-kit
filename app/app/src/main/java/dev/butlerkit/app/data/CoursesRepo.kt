package dev.butlerkit.app.data

import android.content.Context
import android.util.Log
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.CourseDetail
import dev.butlerkit.app.net.CourseFile
import dev.butlerkit.app.net.CourseInfo
import dev.butlerkit.app.net.CoursesList
import dev.butlerkit.app.net.humanError
import dev.butlerkit.app.net.parseCourseDetail
import dev.butlerkit.app.net.parseCoursesList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 課程頁的資料。跟 [AgendaRepo] 同一套形狀：清單拉一次存原文、開機先讀快取頂著。
 *
 * 一門課的全貌（[details]）**不落快取**：它含整份 index.md、log.md 與檔案清單，
 * 每次進工作區拉一次就好，離線就顯示「拿不到」——那時他也送不出訊息。
 *
 * 刻意獨立一個 repo 而不塞進 AgendaRepo：這份資料的來源是另一個 session 維護的
 * md 檔（見 `net/Courses.kt`），更新節奏跟行事曆完全不同——上課時才會變。
 * 混在一起的話每次拉行事曆都順便拉一次課程，白打一趟。
 */
object CoursesRepo {
    private val _list = MutableStateFlow<CoursesList?>(null)
    val list: StateFlow<CoursesList?> = _list.asStateFlow()

    private val _details = MutableStateFlow<Map<String, CourseDetail>>(emptyMap())
    val details: StateFlow<Map<String, CourseDetail>> = _details.asStateFlow()

    /** 拉某一門課失敗的原因，key 是課名。成功就從這裡拿掉。 */
    private val _detailError = MutableStateFlow<Map<String, String>>(emptyMap())
    val detailError: StateFlow<Map<String, String>> = _detailError.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val lock = Mutex()

    fun loadCache(ctx: Context) {
        if (_list.value == null) {
            parseCoursesList(Prefs(ctx).coursesCache)?.let { _list.value = it }
        }
    }

    suspend fun refresh(ctx: Context, client: ButlerClient) = lock.withLock {
        client.getCoursesRaw()
            .onSuccess { raw ->
                val parsed = parseCoursesList(raw)
                if (parsed == null) {
                    _error.value = "資料格式看不懂"
                } else {
                    Prefs(ctx).coursesCache = raw
                    _list.value = parsed
                    _error.value = null
                }
            }
            .onFailure {
                Log.w(ButlerClient.TAG, "拉課程清單失敗：${it.message}")
                _error.value = humanError(it)
            }
    }

    suspend fun refreshDetail(client: ButlerClient, name: String) {
        client.getCourseRaw(name)
            .onSuccess { raw ->
                val parsed = parseCourseDetail(raw)
                if (parsed == null) {
                    _detailError.update { it + (name to "資料格式看不懂") }
                } else {
                    _details.update { it + (name to parsed) }
                    _detailError.update { it - name }
                }
            }
            .onFailure { e ->
                Log.w(ButlerClient.TAG, "拉課程 $name 失敗：${e.message}")
                _detailError.update { it + (name to humanError(e)) }
            }
    }

    /** 課表上的一格對到哪門課。對不到＝那門課還沒有資料夾（或名字對不上）。 */
    fun byAgendaId(id: String): CourseInfo? =
        _list.value?.courses?.firstOrNull { id in it.agendaIds }

    fun clearError() {
        _error.value = null
    }

    /**
     * 把一份教材抓進 App 的快取目錄，回本機檔案；由畫面用 FileProvider 交給系統開
     * （白名單在 res/xml/file_paths.xml 的 `courses/`）。
     * 同路徑同大小已經在就不重抓——講義一份幾 MB，每點一次都重拉沒道理。
     * 半截檔一定刪掉：留著會被當成抓好了，下次開是壞檔。
     */
    suspend fun fetchFile(
        ctx: Context, client: ButlerClient, course: String, file: CourseFile,
    ): Result<File> = withContext(Dispatchers.IO) {
        val target = File(ctx.cacheDir, "courses/$course/${file.path}")
        if (target.isFile && target.length() == file.size) {
            return@withContext Result.success(target)
        }
        target.parentFile?.mkdirs()
        runCatching {
            target.outputStream().use { out ->
                client.downloadCourseFile(course, file.path, out).getOrThrow()
            }
            target
        }.onFailure { e ->
            target.delete()
            if (e is CancellationException) throw e
        }
    }
}
