package dev.butlerkit.app.widget

import dev.butlerkit.app.net.Alarm
import dev.butlerkit.app.net.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * widget 上兩段沒有畫面就看不出對錯的邏輯。
 *
 * 這裡不測 Compose，測的是「幾點顯示什麼字」——那些錯了不會編譯失敗、
 * 也不會拋例外，只會安靜地在桌面上顯示錯的時間。
 */
class WidgetLogicTest {

    // ── hhmm ────────────────────────────────────────────────────────────────

    @Test
    fun `零填充與非零填充都解得出來`() {
        assertEquals(490, hhmm("08:10"))
        // 節次時間表是使用者手打的，"8:10" 這種寫法很常見
        assertEquals(490, hhmm("8:10"))
        assertEquals(0, hhmm("00:00"))
        assertEquals(1439, hhmm("23:59"))
    }

    @Test
    fun `解不出來的一律回 null 而不是丟例外`() {
        assertNull(hhmm(""))
        assertNull(hhmm("八點"))
        assertNull(hhmm("25:00"))
        assertNull(hhmm("12:60"))
        assertNull(hhmm("1230"))
    }

    // ── nextAlarm ───────────────────────────────────────────────────────────

    private val monday = LocalDate.of(2026, 8, 10)   // 確認過是週一

    private fun alarm(
        time: String, days: List<Int> = emptyList(), date: String? = null,
        enabled: Boolean = true, label: String = "",
    ) = Alarm("a-$time", time, label, days, date, enabled)

    @Test
    fun `今天稍晚會響的不加前綴`() {
        val r = nextAlarm(listOf(alarm("18:00")), monday, LocalTime.of(9, 0))
        assertEquals("18:00", r?.second)
    }

    @Test
    fun `時間已過的單次鬧鐘算到明天`() {
        val r = nextAlarm(listOf(alarm("07:30")), monday, LocalTime.of(9, 0))
        assertEquals("明天 07:30", r?.second)
    }

    /**
     * 這條釘的是實際寫錯過的一版：當時拿「還有多久」除以 1440 去判斷是第幾天，
     * 而 23 小時後除出來是 0，於是「明天早上七點半」被寫成「07:30」。
     */
    @Test
    fun `不到二十四小時但已經跨日的要標明天`() {
        val r = nextAlarm(listOf(alarm("07:30")), monday, LocalTime.of(8, 0))
        assertEquals("明天 07:30", r?.second)
    }

    @Test
    fun `每週重複挑最近的那一天`() {
        // 週一 09:00，鬧鐘設在週三(2)與週五(4)
        val r = nextAlarm(listOf(alarm("07:00", days = listOf(2, 4))), monday, LocalTime.of(9, 0))
        assertEquals("2 天後 07:00", r?.second)
    }

    @Test
    fun `每週重複的今天已經響過就跳到下週`() {
        // 週一 09:00，鬧鐘只設週一 07:00
        val r = nextAlarm(listOf(alarm("07:00", days = listOf(0))), monday, LocalTime.of(9, 0))
        assertEquals("7 天後 07:00", r?.second)
    }

    @Test
    fun `關掉的鬧鐘不算`() {
        val r = nextAlarm(
            listOf(alarm("07:00", enabled = false), alarm("18:00")),
            monday, LocalTime.of(9, 0),
        )
        assertEquals("18:00", r?.second)
    }

    @Test
    fun `指定日期已經過去的不算`() {
        val r = nextAlarm(
            listOf(alarm("07:00", date = "2026-08-01")),
            monday, LocalTime.of(9, 0),
        )
        assertNull(r)
    }

    @Test
    fun `多個鬧鐘取最早的那個`() {
        val r = nextAlarm(
            listOf(
                alarm("23:00", label = "晚"),
                alarm("10:00", label = "早"),
                alarm("07:00", days = listOf(2)),
            ),
            monday, LocalTime.of(9, 0),
        )
        assertEquals("早", r?.first?.label)
    }

    @Test
    fun `一個都沒有時回 null`() {
        assertNull(nextAlarm(emptyList(), monday, LocalTime.of(9, 0)))
    }

    // ── nextCourse ──────────────────────────────────────────────────────────

    private fun course(name: String, day: Int, from: Int, to: Int = from) =
        Course("c-$name-$day", name, day, from, to, "", "", "")

    /** 第 1 節 08:10、第 5 節 13:10、第 8 節 16:10 */
    private val periodStart = mapOf(1 to 490, 5 to 790, 8 to 970)

    @Test
    fun `下一次上課取隔天最早的那堂`() {
        val r = nextCourse(
            listOf(
                course("下午的", day = 2, from = 5),
                course("早上的", day = 2, from = 1),
                course("後天的", day = 3, from = 1),
            ),
            periodStart, todayIdx = 1,
        )
        assertEquals("早上的", r?.course?.name)
        assertEquals(1, r?.dayGap)
        assertEquals("明天 08:10 早上的", nextCourseText(r!!))
    }

    @Test
    fun `隔天沒課就往後找並用星期幾稱呼`() {
        // 今天週一，下一堂在週四
        val r = nextCourse(listOf(course("週四的", day = 3, from = 8)), periodStart, todayIdx = 0)
        assertEquals(3, r?.dayGap)
        assertEquals("週四 16:10 週四的", nextCourseText(r!!))
    }

    @Test
    fun `一週只有今天有課時指回下週同一天`() {
        val r = nextCourse(listOf(course("只有今天", day = 0, from = 1)), periodStart, todayIdx = 0)
        assertEquals(7, r?.dayGap)
        assertEquals("下週一 08:10 只有今天", nextCourseText(r!!))
    }

    @Test
    fun `查不到節次時間的課排在同一天的最後`() {
        val r = nextCourse(
            listOf(
                course("沒登記時間", day = 1, from = 99),
                course("有登記時間", day = 1, from = 5),
            ),
            periodStart, todayIdx = 0,
        )
        assertEquals("有登記時間", r?.course?.name)
    }

    @Test
    fun `整份課表都沒課時回 null`() {
        assertNull(nextCourse(emptyList(), periodStart, todayIdx = 0))
    }

    @Test
    fun `查不到時間也還是要講得出是哪一天`() {
        val r = nextCourse(listOf(course("沒登記時間", day = 1, from = 99)), periodStart, 0)
        assertEquals("明天 沒登記時間", nextCourseText(r!!))
    }
}
