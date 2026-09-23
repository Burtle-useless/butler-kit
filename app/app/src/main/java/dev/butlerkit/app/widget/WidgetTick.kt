package dev.butlerkit.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.net.AgendaData
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.parseAgenda
import dev.butlerkit.app.notify.AppForeground
import dev.butlerkit.app.notify.ButlerService
import dev.butlerkit.app.ui.gridPeriods
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 時間往前走時把 widget 重畫一次。
 *
 * 資料沒變，但畫面會變——一堂課上到一半要變成上完了、下一個行程要變成快到了、
 * 過了午夜整張課表要換成新的一天。這些都不是資料事件，沒有人會通知我們。
 *
 * **不用固定間隔輪詢**：每 15 分鐘醒一次，一天 96 次，其中絕大多數都是「什麼都沒變」。
 * 改成算出下一個真正會讓畫面不一樣的時刻（下一節課的起訖、下一個行程、午夜），
 * 只排那一個 alarm，響完再算下一個。一天大概十來次，而且每次都真的有事發生。
 *
 * **用精準且會喚醒的鬧鐘**，雖然「晚幾分鐘重畫」本身無所謂。原本用 inexact 的
 * [AlarmManager.set] 配 `RTC`，兩件事都壞了：
 *
 * 一、`RTC` 不喚醒裝置。手機整夜沒人動的話午夜那一發根本不會觸發，
 * 早上拿起來看到的還是昨天的課表——那正是這支存在的理由。
 *
 * 二、更要緊的是 [WidgetTickReceiver] 兼職的看門狗。Android 12+ 從背景啟動前景服務
 * 是禁止的，豁免清單裡有「鬧鐘觸發的廣播」，但**只認精準鬧鐘**
 * （`setExact*` / `setAlarmClock`），inexact 的 `set()` 不在裡面。用 inexact 排的話
 * `startForegroundService` 會拋 `ForegroundServiceStartNotAllowedException`，
 * 被吞掉之後看門狗等於不存在，而且沒有任何 log 說它沒生效。
 *
 * 代價是吃精準鬧鐘的配額，但這支一天只醒十來次，而 App 本來就宣告了
 * `USE_EXACT_ALARM`（核心功能就是鬧鐘），配額不是問題。
 */
object WidgetTick {

    private const val REQ = 0x7115
    private const val REQ_PROGRESS = 0x7116

    /**
     * 上課中與上課前一小時，課表 widget 每幾分鐘重畫一次。
     *
     * 課表 widget 有「上課中那堂用色塊按比例填到上到哪」與「下一堂幾分後開始」，
     * 兩個都跟著分鐘走；只在節次交界重畫的話，色塊會停在開始那一刻、倒數停在一小時前。
     * 五分鐘一格：一堂 100 分鐘的課每格走 5%，眼睛看得出在動，一天也才幾十次。
     */
    internal const val PROGRESS_STEP_MIN = 5

    /** 這一發是跟著分鐘走的那種（不是節次交界）。 */
    const val EXTRA_PROGRESS = "progress"

    /** 算出下一個邊界並排上去。資料變動或前一個 tick 響完都要重新呼叫。 */
    fun reschedule(ctx: Context) {
        val data = parseAgenda(Prefs(ctx).agendaCache) ?: AgendaData()
        val at = nextBoundary(data)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val op = pending(ctx)
        // Android 12 走的是要使用者手動開的 SCHEDULE_EXACT_ALARM，可能被拒。
        // 退回 setAndAllowWhileIdle 至少保住 RTC_WAKEUP——跨日重畫會正常，
        // 只有看門狗那一段拿不到豁免（見上面的說明）。
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
        }.onFailure { Log.w(ButlerClient.TAG, "widget 排重畫失敗：${it.message}") }
        Log.i(ButlerClient.TAG, "widget 下次重畫 at=$at exact=$exact")
        scheduleProgress(ctx, data, am, exact)
    }

    /**
     * 跟著分鐘走的那一發，跟上面的節次交界**分開排**（各自一個 PendingIntent）。
     *
     * **不喚醒手機**（`RTC` 不是 `RTC_WAKEUP`）：螢幕關著沒人在看 widget，為了畫一條
     * 沒人看的色塊把手機叫醒是浪費。錯過的 RTC 鬧鐘會在下次亮螢幕時立刻補發，
     * 所以一拿起手機看到的就是當下的進度。節次交界那一發照舊會喚醒——跨日、看門狗
     * 都靠它，這裡不能跟它共用同一個鬧鐘。
     */
    private fun scheduleProgress(ctx: Context, data: AgendaData, am: AlarmManager, exact: Boolean) {
        val op = pendingProgress(ctx)
        val date = LocalDate.now()
        val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
        val next = progressMarks(data, date.dayOfWeek.value - 1).firstOrNull { it > nowMin }
        if (next == null) {
            am.cancel(op)
            return
        }
        val at = date.atStartOfDay(ZoneId.systemDefault()).plusMinutes(next.toLong())
            .toInstant().toEpochMilli()
        runCatching {
            if (exact) am.setExact(AlarmManager.RTC, at, op) else am.set(AlarmManager.RTC, at, op)
        }.onFailure { Log.w(ButlerClient.TAG, "widget 排進度重畫失敗：${it.message}") }
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ, Intent(ctx, WidgetTickReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun pendingProgress(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_PROGRESS,
        Intent(ctx, WidgetTickReceiver::class.java).putExtra(EXTRA_PROGRESS, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * 今天哪些時刻（當天第幾分鐘）要為了色塊與倒數重畫：每堂課上課中每 [PROGRESS_STEP_MIN]
     * 分鐘一次，外加上課前一小時內每 [PROGRESS_STEP_MIN] 分鐘一次（「25 分後開始」）。
     * 節次的起訖本身不在這裡——那是 [nextBoundary] 的事。
     */
    internal fun progressMarks(data: AgendaData, todayIdx: Int): java.util.SortedSet<Int> {
        val marks = sortedSetOf<Int>()
        val pStart = data.periods.associate { it.no to hhmm(it.start) }
        val pEnd = data.periods.associate { it.no to hhmm(it.end) }
        data.courses.filter { it.day == todayIdx }.forEach { c ->
            val s = pStart[c.fromPeriod] ?: return@forEach
            val e = pEnd[c.toPeriod] ?: return@forEach
            var m = s + PROGRESS_STEP_MIN
            while (m < e) { marks += m; m += PROGRESS_STEP_MIN }
            var k = s - 60
            while (k < s) { if (k > 0) marks += k; k += PROGRESS_STEP_MIN }
        }
        return marks
    }

    /**
     * 下一個會讓畫面長得不一樣的時刻（epoch 毫秒）。
     *
     * 一定包含明天 00:00：就算今天完全沒課沒行程，跨日時「今天」的定義本身就變了。
     */
    private fun nextBoundary(data: AgendaData): Long {
        val zone = ZoneId.systemDefault()
        val date = LocalDate.now()
        val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
        val today = date.dayOfWeek.value - 1
        val todayStr = date.toString()

        val marks = sortedSetOf<Int>()

        val pStart = data.periods.associate { it.no to hhmm(it.start) }
        val pEnd = data.periods.associate { it.no to hhmm(it.end) }
        data.courses.filter { it.day == today }.forEach { c ->
            pStart[c.fromPeriod]?.let { marks += it }
            pEnd[c.toPeriod]?.let { marks += it }
        }
        // 課表 widget 拉大成整週時，節次軸標的是「現在第幾節」——每一節的起訖都是
        // 畫面會變的時刻，不只今天有課的那幾節。只算網格實際畫出來的節次範圍
        // （跟 CourseWidget 用同一支 gridPeriods），一天十來個點，仍是事件驅動不是輪詢
        if (data.courses.isNotEmpty()) {
            val range = gridPeriods(data.courses, data.periods.maxOfOrNull { it.no } ?: 0)
            data.periods.filter { it.no in range }.forEach { p ->
                pStart[p.no]?.let { marks += it }
                pEnd[p.no]?.let { marks += it }
            }
        }
        data.events.filter { it.start.startsWith(todayStr) }.forEach { e ->
            hhmm(e.start.substringAfter('T', ""))?.let {
                marks += it
                // 「快到了」的高亮門檻是提前一小時，那一刻畫面也會變
                if (it >= 60) marks += it - 60
            }
        }

        val nextToday = marks.firstOrNull { it > nowMin }
        return if (nextToday != null) {
            date.atStartOfDay(zone).plusMinutes(nextToday.toLong()).toInstant().toEpochMilli()
        } else {
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }
}

/**
 * 邊界到了：重畫、然後排下一個。
 *
 * 一定要在這裡重排下一次——AlarmManager 的 set 是一次性的，忘了排的話
 * widget 就永遠停在這一刻的樣子，直到下次有資料變動才動。
 *
 * 順便當背景連線的看門狗。服務被系統殺掉後 START_STICKY 不一定救得回來
 * （背景重啟前景服務會被 Android 12+ 擋下），而**精準**鬧鐘觸發的廣播有豁免
 * （見 [WidgetTick] 的說明，這個「精準」是必要條件不是細節），
 * 醒著的時候順手戳一下幾乎不花成本——服務還活著的話 start 只是再跑一次
 * onStartCommand，連線不會重開。
 */
class WidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        val app = ctx.applicationContext
        // 跟著分鐘走的那一發只重畫課表：五分鐘一次，不值得每次都戳服務、拉一趟額度
        val progressOnly = intent.getBooleanExtra(WidgetTick.EXTRA_PROGRESS, false)
        if (!progressOnly && !AppForeground.visible && Prefs(app).isConfigured()) ButlerService.start(app)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Widgets.refreshAgenda(app)
                WidgetTick.reschedule(app)
                // 額度沒有人會通知我們，只能自己問。邊界醒來時順手拉一次，
                // 讓桌面上的百分比至少跟上「今天上完課」這種尺度的時間感
                if (!progressOnly) UsageWorker.runOnce(app)
            } finally {
                pending.finish()
            }
        }
    }
}
