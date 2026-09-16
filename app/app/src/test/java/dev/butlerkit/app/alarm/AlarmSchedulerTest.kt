package dev.butlerkit.app.alarm

import dev.butlerkit.app.net.Alarm
import dev.butlerkit.app.net.CalEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「下一次什麼時候響」的計算。
 *
 * 這是整個鬧鐘功能唯一算得出對錯的地方，也是最容易出錯的地方——
 * 星期的起算（store 用 0=週一，java.time 用 1=週一）差一格，鬧鐘就整整晚一天。
 * 其餘部分（AlarmManager、前景服務、鎖屏畫面）只能實機驗。
 */
class AlarmSchedulerTest {

    private fun at(y: Int, m: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, m, d, h, mi)

    private fun back(epoch: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZoneId.systemDefault())

    private fun alarm(
        time: String, days: List<Int> = emptyList(), date: String? = null,
    ) = Alarm(id = "a1", time = time, label = "", days = days, date = date, enabled = true)

    // 2026-08-10 是週一，store 的編碼是 0
    @Test
    fun `每週重複_今天還沒到就是今天`() {
        val next = AlarmScheduler.nextTrigger(
            alarm("07:00", days = listOf(0)), at(2026, 8, 10, 6, 0),
        )
        assertEquals(at(2026, 8, 10, 7, 0), back(next!!))
    }

    @Test
    fun `每週重複_今天已經過了就順延到下週同一天`() {
        val next = AlarmScheduler.nextTrigger(
            alarm("07:00", days = listOf(0)), at(2026, 8, 10, 8, 0),
        )
        assertEquals(at(2026, 8, 17, 7, 0), back(next!!))
    }

    @Test
    fun `平日鬧鐘_週六設的會落在下週一`() {
        // 2026-08-15 是週六
        val next = AlarmScheduler.nextTrigger(
            alarm("07:30", days = listOf(0, 1, 2, 3, 4)), at(2026, 8, 15, 10, 0),
        )
        assertEquals(at(2026, 8, 17, 7, 30), back(next!!))
    }

    @Test
    fun `週日的編碼是6_不是0`() {
        // 2026-08-16 是週日。若星期起算搞錯，這裡會算成隔天或前一天
        val next = AlarmScheduler.nextTrigger(
            alarm("09:00", days = listOf(6)), at(2026, 8, 16, 8, 0),
        )
        assertEquals(at(2026, 8, 16, 9, 0), back(next!!))
    }

    @Test
    fun `一次性鬧鐘_過期就不再響`() {
        assertNull(
            AlarmScheduler.nextTrigger(
                alarm("07:00", date = "2026-08-09"), at(2026, 8, 10, 8, 0),
            ),
        )
    }

    @Test
    fun `一次性鬧鐘_未來的日期照排`() {
        val next = AlarmScheduler.nextTrigger(
            alarm("07:00", date = "2026-08-12"), at(2026, 8, 10, 8, 0),
        )
        assertEquals(at(2026, 8, 12, 7, 0), back(next!!))
    }

    @Test
    fun `時間格式壞掉不要讓整批排程炸掉`() {
        assertNull(AlarmScheduler.nextTrigger(alarm("半夜三點"), at(2026, 8, 10, 8, 0)))
    }

    @Test
    fun `行程提醒_開始前十分鐘`() {
        val e = CalEvent("e1", "看牙醫", "2026-08-20T15:00", null, "", 10, false)
        val next = AlarmScheduler.remindAt(e, at(2026, 8, 20, 9, 0))
        assertEquals(at(2026, 8, 20, 14, 50), back(next!!))
    }

    @Test
    fun `行程提醒_提醒時刻已過就不排`() {
        val e = CalEvent("e1", "看牙醫", "2026-08-20T15:00", null, "", 10, false)
        assertNull(AlarmScheduler.remindAt(e, at(2026, 8, 20, 14, 55)))
    }
}
