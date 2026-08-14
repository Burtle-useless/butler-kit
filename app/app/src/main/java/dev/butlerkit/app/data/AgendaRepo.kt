package dev.butlerkit.app.data

import android.content.Context
import android.util.Log
import dev.butlerkit.app.alarm.AlarmScheduler
import dev.butlerkit.app.net.AgendaData
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.MonthSummary
import dev.butlerkit.app.net.parseAgenda
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 行事曆／鬧鐘／記帳／課表的唯一真相來源（App 這一側）。
 *
 * 為什麼是單例而不是 ViewModel：改動的來源有三個——你在日常頁手動改、助理透過工具改
 * （走 SSE 的 agenda.changed 通知）、以及 App 在背景時由前景服務收到的同一個通知。
 * 三個來源都得觸發「重拉 + 重排鬧鐘」，掛在任何一個畫面的 ViewModel 上都會漏掉
 * 另外兩條路徑。
 *
 * 每次成功拉取都做兩件事：快取原始 JSON（給重開機用）、重排 AlarmManager。
 * 這兩件不可以分開——只更新畫面不重排，助理設的鬧鐘就只是螢幕上的一行字。
 */
object AgendaRepo {

    private val _data = MutableStateFlow(AgendaData())
    val data: StateFlow<AgendaData> = _data.asStateFlow()

    private val _summary = MutableStateFlow<MonthSummary?>(null)
    val summary: StateFlow<MonthSummary?> = _summary.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 最近一次操作的錯誤，顯示完由畫面清掉。 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // 多條路徑可能同時觸發 refresh，同時寫 Prefs 跟排鬧鐘會互相打架
    private val lock = Mutex()

    fun clearError() {
        _error.value = null
    }

    /** 冷啟動用：先把上次的快取畫出來，不必等網路。 */
    fun loadCache(ctx: Context) {
        if (_data.value.events.isEmpty() && _data.value.alarms.isEmpty()) {
            parseAgenda(Prefs(ctx).agendaCache)?.let { _data.value = it }
        }
    }

    suspend fun refresh(ctx: Context, client: ButlerClient) = lock.withLock {
        _busy.value = true
        client.getAgendaRaw()
            .onSuccess { raw ->
                val parsed = parseAgenda(raw)
                if (parsed == null) {
                    _error.value = "資料格式看不懂"
                } else {
                    Prefs(ctx).agendaCache = raw
                    _data.value = parsed
                    AlarmScheduler.sync(ctx, parsed)
                }
            }
            .onFailure {
                Log.w(ButlerClient.TAG, "拉 agenda 失敗：${it.message}")
                _error.value = it.message
            }
        _busy.value = false
    }

    suspend fun refreshSummary(client: ButlerClient, month: String = "") {
        client.getSummary(month).onSuccess { _summary.value = it }
    }

    /**
     * 新增。成功就順手重拉——伺服器會補上 id、排序、以及一次性鬧鐘的日期，
     * 本地自己拼一份會跟伺服器版本不一致。
     */
    suspend fun add(
        ctx: Context, client: ButlerClient, kind: String, body: JSONObject,
    ): Boolean {
        val ok = client.addAgenda(kind, body)
            .onFailure { _error.value = it.message }
            .isSuccess
        if (ok) refresh(ctx, client)
        return ok
    }

    suspend fun patch(
        ctx: Context, client: ButlerClient, kind: String, id: String, body: JSONObject,
    ) {
        client.patchAgenda(kind, id, body).onFailure { _error.value = it.message }
        refresh(ctx, client)
    }

    suspend fun remove(ctx: Context, client: ButlerClient, kind: String, id: String) {
        client.deleteAgenda(kind, id).onFailure { _error.value = it.message }
        refresh(ctx, client)
    }

    /**
     * 存節次時間表。整份送，沒列到的節次會消失。
     *
     * 失敗時不重拉：整份改寫被伺服器擋下來就代表資料沒動，重拉只會把畫面上還沒送出去
     * 的編輯內容洗掉，使用者得從頭再填一次。
     */
    suspend fun savePeriods(
        ctx: Context, client: ButlerClient, periods: JSONArray,
    ): Boolean {
        val ok = client.putPeriods(periods)
            .onFailure { _error.value = it.message }
            .isSuccess
        if (ok) refresh(ctx, client)
        return ok
    }
}
