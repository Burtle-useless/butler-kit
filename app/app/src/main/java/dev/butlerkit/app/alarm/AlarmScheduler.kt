package dev.butlerkit.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.net.Alarm
import dev.butlerkit.app.net.AgendaData
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.CalEvent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 把伺服器上的鬧鐘與行程提醒，排進手機本地的 AlarmManager。
 *
 * **為什麼觸發在手機而不是電腦**：資料放電腦（助理才改得動），但真正要響的那一刻
 * 不能依賴電腦開著、網路通著、SSE 沒斷。鬧鐘沒響的代價是睡過頭，
 * 所以觸發這件事一定要在離線也能運作的地方。
 *
 * 排程在重開機後會全部消失，所以每次同步都把資料快取到 Prefs，
 * 開機廣播再照著重排一次（見 BootReceiver）。
 */
object AlarmScheduler {

    /** 鬧鐘：會吵人、會蓋畫面。 */
    const val TYPE_ALARM = "alarm"

    /** 行程提醒：只發通知，不吵。 */
    const val TYPE_REMIND = "remind"

    const val EXTRA_TYPE = "type"
    const val EXTRA_ID = "id"
    const val EXTRA_LABEL = "label"
    const val EXTRA_TIME = "time"

    /**
     * 全量重排。
     *
     * 每次都「先取消上次排的、再排這次的」而不是做差異比對——
     * 差異比對要多存一份狀態且容易漏（改時間但 id 沒變的那種），
     * 而重排幾十個 alarm 的成本是毫秒級。
     */
    fun sync(ctx: Context, data: AgendaData) {
        val prefs = Prefs(ctx)
        // 貪睡是本地補排的一次性觸發，伺服器資料裡沒有它，所以全量重排會把它掃掉。
        // 但也不能一律留著——來源鬧鐘被關掉或刪掉時，使用者的意思顯然是「別再吵我」，
        // 那時貪睡也該一起消失。原本 "$id#snooze" 從沒進過 scheduledIds，
        // 於是**永遠取消不掉**：鬧鐘關了五分鐘後照樣響，畫面上還找不到它是打哪來的。
        val liveAlarms = data.alarms.filter { it.enabled }.map { it.id }.toSet()
        val keptSnoozes = prefs.scheduledIds.filter {
            it.endsWith(SNOOZE_SUFFIX) && it.removeSuffix(SNOOZE_SUFFIX) in liveAlarms
        }.toSet()
        (prefs.scheduledIds - keptSnoozes).forEach { cancel(ctx, it) }

        val live = keptSnoozes.toMutableSet()
        data.alarms.filter { it.enabled }.forEach { a ->
            nextTrigger(a)?.let { at ->
                schedule(ctx, TYPE_ALARM, a.id, a.label.ifBlank { "鬧鐘" }, a.time, at)
                live += a.id
            }
        }
        data.events.filter { !it.done && it.remindMin > 0 }.forEach { e ->
            remindAt(e)?.let { at ->
                schedule(ctx, TYPE_REMIND, e.id, e.title, e.start.substringAfter('T'), at)
                live += e.id
            }
        }
        prefs.scheduledIds = live
        Log.i(ButlerClient.TAG, "鬧鐘已重排 ${live.size} 個")
    }

    /** 貪睡排程的 id 後綴。[sync] 靠它認出「這個不是伺服器來的」。 */
    const val SNOOZE_SUFFIX = "#snooze"

    /**
     * 貪睡：不動伺服器資料，只在本地補排一次性觸發。
     *
     * 一定要登記進 `scheduledIds`——沒登記的排程 [sync] 看不見也取消不掉，
     * 那正是「鬧鐘都關了它還是響」的來源。
     */
    fun snooze(ctx: Context, id: String, label: String, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        val sid = "$id$SNOOZE_SUFFIX"
        schedule(ctx, TYPE_ALARM, sid, label, "", at)
        val prefs = Prefs(ctx)
        prefs.scheduledIds = prefs.scheduledIds + sid
    }

    /** 貪睡響過了就把登記拿掉，免得清單裡一直留著一個早就不存在的排程。 */
    fun forgetSnooze(ctx: Context, sid: String) {
        val prefs = Prefs(ctx)
        prefs.scheduledIds = prefs.scheduledIds - sid
    }

    // ── 下一次該響的時刻 ────────────────────────────────────────────────
    /** 回傳 epoch millis；已經過去且不會再響的回 null。 */
    fun nextTrigger(a: Alarm, now: LocalDateTime = LocalDateTime.now()): Long? {
        val t = runCatching { LocalTime.parse(a.time) }.getOrNull() ?: return null
        val day: LocalDate = when {
            // 指定日期的一次性鬧鐘：過了就不再響
            a.date != null -> runCatching { LocalDate.parse(a.date) }.getOrNull() ?: return null
            // 每週重複：從今天起找最近一個符合的星期（今天但時間已過就跳過）
            a.days.isNotEmpty() -> (0..7).asSequence()
                .map { now.toLocalDate().plusDays(it.toLong()) }
                .firstOrNull { d ->
                    // store 用 0=週一，DayOfWeek.value 是 1=週一，差一
                    (d.dayOfWeek.value - 1) in a.days &&
                        LocalDateTime.of(d, t).isAfter(now)
                } ?: return null
            // 沒指定日期也不重複：下一個到這個時間
            else -> if (t.isAfter(now.toLocalTime())) now.toLocalDate()
                    else now.toLocalDate().plusDays(1)
        }
        val at = LocalDateTime.of(day, t)
        if (!at.isAfter(now)) return null
        return at.toEpoch()
    }

    /** 行程的提醒時刻：開始前 remindMin 分鐘。已經過了就不排。 */
    fun remindAt(e: CalEvent, now: LocalDateTime = LocalDateTime.now()): Long? {
        val start = runCatching { LocalDateTime.parse(e.start) }.getOrNull() ?: return null
        val at = start.minusMinutes(e.remindMin.toLong())
        return if (at.isAfter(now)) at.toEpoch() else null
    }

    private fun LocalDateTime.toEpoch(): Long =
        atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ── AlarmManager ───────────────────────────────────────────────────
    private fun intentFor(
        ctx: Context, type: String, id: String, label: String, time: String,
    ): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).apply {
            // action 帶上 id：PendingIntent 的相等性不看 extras，只看
            // (requestCode, action, data, type, class, categories)。
            // 少了這個，兩個不同鬧鐘會被當成同一個 PendingIntent 互相覆蓋。
            action = "dev.butlerkit.app.FIRE.$id"
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_TIME, time)
        }
        return PendingIntent.getBroadcast(
            ctx, id.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun schedule(
        ctx: Context, type: String, id: String, label: String, time: String, at: Long,
    ) {
        val mgr = ctx.getSystemService(AlarmManager::class.java) ?: return
        val op = intentFor(ctx, type, id, label, time)
        runCatching {
            if (type == TYPE_ALARM) {
                // setAlarmClock 是唯一「Doze 也一定準時」的排程方式，而且會在狀態列
                // 顯示鬧鐘圖示——那個圖示本身就是給人的保證：它真的排上了。
                // showIntent 是點狀態列圖示時開的畫面。
                val show = PendingIntent.getActivity(
                    ctx, id.hashCode(),
                    Intent(ctx, dev.butlerkit.app.MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                mgr.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), op)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // 提醒不必是鬧鐘等級，但要能穿透 Doze，否則手機放著沒動就不會跳。
                // 沒有精準鬧鐘權限時退回 set()：晚幾分鐘的提醒仍然有用，
                // 直接不排才是最糟的結果。
                if (canExact(ctx, mgr)) {
                    mgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
                } else {
                    mgr.set(AlarmManager.RTC_WAKEUP, at, op)
                }
            } else {
                mgr.setExact(AlarmManager.RTC_WAKEUP, at, op)
            }
        }.onFailure { Log.w(ButlerClient.TAG, "排 $type $id 失敗：${it.message}") }
    }

    private fun canExact(ctx: Context, mgr: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || mgr.canScheduleExactAlarms()

    private fun cancel(ctx: Context, id: String) {
        val mgr = ctx.getSystemService(AlarmManager::class.java) ?: return
        mgr.cancel(intentFor(ctx, TYPE_ALARM, id, "", ""))
    }
}
