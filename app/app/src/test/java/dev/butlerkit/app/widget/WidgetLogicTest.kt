package dev.butlerkit.app.widget

import androidx.compose.ui.unit.dp
import dev.butlerkit.app.net.Alarm
import dev.butlerkit.app.net.Course
import dev.butlerkit.app.net.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ── 今日清單：預覽與列數 ────────────────────────────────────────────────

    @Test
    fun `預覽列那一天的課照時間排、沒時間的墊底`() {
        val r = previewCourses(
            listOf(
                course("下午", day = 2, from = 5),
                course("沒時間", day = 2, from = 99),
                course("早上", day = 2, from = 1),
                course("別天的", day = 3, from = 1),
            ),
            periodStart, dayIdx = 2,
        )
        assertEquals(listOf("早上", "下午", "沒時間"), r.map { it.name })
    }

    @Test
    fun `列數跟著高度走但夾在 2 到 6 之間`() {
        // 最小尺寸也要看得到兩堂；再高也不超過容器格子數算出來的 6
        assertEquals(2, listRows(110.dp))
        assertEquals(6, listRows(600.dp))
        // 3×3 那張大約 270dp。每列有教室與狀態一行（48dp），放得下 4 列
        assertEquals(4, listRows(270.dp))
    }

    @Test
    fun `剩不到一小時講分鐘、超過就講幾點下課`() {
        assertEquals("還有 25 分", remainText(nowMin = 9 * 60 + 35, endMin = 10 * 60))
        assertEquals("還有 60 分", remainText(nowMin = 9 * 60, endMin = 10 * 60))
        // 三節連上：09:50 到 12:00 還有 130 分，寫分鐘要人心算
        assertEquals("到 12:00", remainText(nowMin = 9 * 60 + 50, endMin = 12 * 60))
    }

    // ── 整週網格：節次軸上的「現在」 ────────────────────────────────────────

    private val periods = listOf(
        Period(1, "08:10", "09:00"),
        Period(2, "09:10", "10:00"),
        Period(5, "13:10", "14:00"),
    )

    @Test
    fun `上課中標那一節且是實心`() {
        assertEquals(2 to true, axisMark(periods, 1..5, nowMin = 9 * 60 + 30))
    }

    @Test
    fun `下課時間標下一節但是空心`() {
        // 09:05 是第 1、2 節中間；剛下課的人要看的是接下來那節
        assertEquals(2 to false, axisMark(periods, 1..5, nowMin = 9 * 60 + 5))
        // 午休：下一節是第 5 節
        assertEquals(5 to false, axisMark(periods, 1..5, nowMin = 12 * 60))
    }

    @Test
    fun `全部上完或範圍外就不標`() {
        assertNull(axisMark(periods, 1..5, nowMin = 15 * 60))
        // 網格只畫到第 2 節，第 5 節不在軸上就不能被標
        assertNull(axisMark(periods, 1..2, nowMin = 12 * 60))
    }

    @Test
    fun `節次時間解不出來的那節跳過`() {
        val broken = listOf(Period(1, "八點", "九點"), Period(2, "09:10", "10:00"))
        assertEquals(2 to false, axisMark(broken, 1..2, nowMin = 8 * 60 + 30))
    }

    // ── 上課中的色塊與下課的倒數 ────────────────────────────────────────────

    @Test
    fun `上到幾成夾在 0 到 1`() {
        assertEquals(0.5f, progressOf(nowMin = 10 * 60, startMin = 9 * 60 + 30, endMin = 10 * 60 + 30), 0.001f)
        assertEquals(0f, progressOf(nowMin = 9 * 60, startMin = 9 * 60 + 30, endMin = 10 * 60 + 30), 0.001f)
        assertEquals(1f, progressOf(nowMin = 11 * 60, startMin = 9 * 60 + 30, endMin = 10 * 60 + 30), 0.001f)
        // 節次表寫壞（結束早於開始）不能除出負數或無限大
        assertEquals(0f, progressOf(nowMin = 10 * 60, startMin = 10 * 60, endMin = 9 * 60), 0.001f)
    }

    @Test
    fun `下一堂一小時內講幾分後、再遠講幾點開始`() {
        assertEquals("25 分後開始", startsInText(nowMin = 13 * 60 + 5, startMin = 13 * 60 + 30))
        assertEquals("15:25 開始", startsInText(nowMin = 13 * 60, startMin = 15 * 60 + 25))
    }

    @Test
    fun `上課中與上課前一小時每五分鐘重畫一次`() {
        val data = dev.butlerkit.app.net.AgendaData(
            courses = listOf(course("程式設計", day = 2, from = 2, to = 2)),
            periods = listOf(Period(1, "08:10", "09:00"), Period(2, "09:10", "10:00")),
        )
        val marks = WidgetTick.progressMarks(data, todayIdx = 2)
        assertTrue("上課中每五分鐘", 9 * 60 + 30 in marks && 9 * 60 + 55 in marks)
        assertTrue("下課那一刻是節次交界，不歸這裡", 10 * 60 !in marks)
        assertTrue("上課前一小時開始倒數", 8 * 60 + 10 in marks && 9 * 60 + 5 in marks)
        assertTrue("開始那一刻也是交界", 9 * 60 + 10 !in marks)
        assertTrue("別天的課不排", WidgetTick.progressMarks(data, todayIdx = 3).isEmpty())
    }
}
