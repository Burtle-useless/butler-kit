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
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ, Intent(ctx, WidgetTickReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

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
        if (!AppForeground.visible && Prefs(app).isConfigured()) ButlerService.start(app)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Widgets.refreshAgenda(app)
                WidgetTick.reschedule(app)
                // 額度沒有人會通知我們，只能自己問。邊界醒來時順手拉一次，
                // 讓桌面上的百分比至少跟上「今天上完課」這種尺度的時間感
                UsageWorker.runOnce(app)
            } finally {
                pending.finish()
            }
        }
    }
}
