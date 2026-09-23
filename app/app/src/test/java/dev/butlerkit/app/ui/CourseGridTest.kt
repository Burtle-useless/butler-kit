package dev.butlerkit.app.ui

import dev.butlerkit.app.net.Course
import dev.butlerkit.app.net.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 課表週一覽的排版邏輯。
 *
 * 這幾條錯了畫面不會崩，只會排錯——而課表排錯的人不會發現自己看錯了，
 * 會直接走去錯的教室。所以連續節次合併、空堂對齊、衝堂不吞掉都要釘住。
 */
class CourseGridTest {

    private fun c(name: String, day: Int, from: Int, to: Int = from) = Course(
        id = "$name-$day-$from", name = name, day = day,
        fromPeriod = from, toPeriod = to, teacher = "", room = "", note = "",
    )

    @Test
    fun `連續節次合併成一格`() {
        val slots = layoutDay(listOf(c("微積分", 0, 2, 4)), 1, 5)
        // 第1節空、第2-4節一格、第5節空
        assertEquals(3, slots.size)
        assertNull(slots[0].course)
        assertEquals(3, slots[1].span)
        assertEquals("微積分", slots[1].course?.name)
        assertNull(slots[2].course)
    }

    @Test
    fun `空堂一節一格才對得齊隔壁欄`() {
        // 空堂若合併成一格，隔壁有課那欄的高度就對不上
        val slots = layoutDay(emptyList(), 1, 4)
        assertEquals(4, slots.size)
        assertTrue(slots.all { it.span == 1 && it.course == null })
    }

    @Test
    fun `衝堂不吞掉_標在格子上`() {
        val slots = layoutDay(listOf(c("國文", 0, 3), c("英文", 0, 3)), 3, 3)
        assertEquals(1, slots.size)
        assertEquals(1, slots[0].clash)
        assertTrue(slots[0].course != null)
    }

    @Test
    fun `課程超出顯示範圍時裁掉不外溢`() {
        // 節次表被改小了，某堂課的 toPeriod 落在範圍外
        val slots = layoutDay(listOf(c("體育", 0, 4, 9)), 1, 5)
        val cell = slots.first { it.course != null }
        assertEquals(4, cell.from)
        assertEquals(2, cell.span)          // 只畫到第 5 節
        assertEquals(5, slots.sumOf { it.span })
    }

    @Test
    fun `起點在範圍之前的課從第一格畫起`() {
        val slots = layoutDay(listOf(c("早自習", 0, 1, 3)), 2, 4)
        assertEquals("早自習", slots[0].course?.name)
        assertEquals(2, slots[0].from)
        assertEquals(2, slots[0].span)      // 第 2、3 節
    }

    @Test
    fun `範圍反了不會炸`() {
        assertEquals(emptyList<GridSlot>(), layoutDay(listOf(c("x", 0, 1)), 5, 3))
    }

    @Test
    fun `只畫有課的天`() {
        val days = gridDays(listOf(c("a", 0, 1), c("b", 2, 1), c("c", 0, 3)))
        assertEquals(listOf(0, 2), days)
    }

    @Test
    fun `一堂課都沒有時給週一到週五`() {
        assertEquals(listOf(0, 1, 2, 3, 4), gridDays(emptyList()))
    }

    @Test
    fun `節次範圍掐頭去尾`() {
        // 沒人從第一節排到第十二節，上下留白只是浪費螢幕
        assertEquals(3..6, gridPeriods(listOf(c("a", 0, 3, 4), c("b", 1, 6)), 12))
    }

    @Test
    fun `沒有課時給預設節次範圍`() {
        assertEquals(1..8, gridPeriods(emptyList(), 12))
        assertEquals(1..4, gridPeriods(emptyList(), 4))
    }

    @Test
    fun `節次表比課還短時不外溢`() {
        assertEquals(2..5, gridPeriods(listOf(c("a", 0, 2, 9)), 5))
    }

    @Test
    fun `課名在冒號處截短_括號不動`() {
        // 通識課的全名是「分類：課名」，冒號**前面**是分類。取前面那段的話，
        // 同一個分類的幾門課在課表上都叫同一個名字，分不出哪一堂
        assertEquals("志工服務與社區參與", shortName("通識：志工服務與社區參與"))
        assertEquals("音樂欣賞", shortName("通識:音樂欣賞"))
        // （一）是用來分班的，砍掉會認錯課
        assertEquals("微積分（一）", shortName("微積分（一）"))
        assertEquals("普通化學實驗（一）", shortName("普通化學實驗（一）"))
        // 冒號在頭尾的怪名字不要砍成空字串
        assertEquals("：開頭", shortName("：開頭"))
        assertEquals("結尾：", shortName("結尾："))
        assertEquals("", shortName(""))
    }

    @Test
    fun `有工作區資料夾名就用它_只限帶分類的課名`() {
        fun course(name: String, folder: String) =
            Course("x", name, 0, 1, 1, "", "", "", folder = folder)
        // 伺服器對過的資料夾名（人手取的短名）優先：跟課程頁的清單同一個名字
        assertEquals("天文學", shortName(course("通識：生活中的天文學", "天文學")))
        assertEquals("音樂欣賞", shortName(course("通識：音樂欣賞與創作", "音樂欣賞")))
        // 沒有冒號的課名照原樣——「微積分（一）」比資料夾名「微積分一」好讀
        assertEquals("微積分（一）", shortName(course("微積分（一）", "微積分一")))
        // 舊資料還沒寫回資料夾名：取冒號後面
        assertEquals("生活中的天文學", shortName(course("通識：生活中的天文學", "")))
    }

    private val periods = listOf(
        Period(1, "08:10", "09:00"), Period(2, "09:10", "10:00"),
        Period(3, "10:10", "11:00"), Period(4, "11:10", "12:00"),
        Period(5, "13:10", "14:00"), Period(6, "14:10", "15:00"),
    )
    private fun mins(h: Int, m: Int) = h * 60 + m

    @Test
    fun `現在在上課`() {
        val todays = listOf(c("Python", 4, 2, 4), c("英文簡報", 4, 5, 6))
        val now = nowSlot(periods, todays, mins(10, 30))     // 第 3 節
        assertEquals(3, now.period)
        assertEquals("Python", now.current?.name)
        assertEquals("英文簡報", now.next?.name)
        assertEquals("13:10", now.nextStart)
    }

    @Test
    fun `下課時間_period 是 null 但下一堂照算`() {
        // 剛下課最常看這行：想知道下一堂幾點、在哪
        val todays = listOf(c("Python", 4, 2, 4), c("英文簡報", 4, 5, 6))
        val now = nowSlot(periods, todays, mins(12, 30))
        assertNull(now.period)
        assertNull(now.current)
        assertEquals("英文簡報", now.next?.name)
    }

    @Test
    fun `今天課都上完了`() {
        val now = nowSlot(periods, listOf(c("Python", 4, 2, 4)), mins(16, 0))
        assertNull(now.next)
        assertEquals("", now.nextStart)
    }

    @Test
    fun `空堂_有節次但沒課`() {
        val now = nowSlot(periods, listOf(c("英文簡報", 4, 5, 6)), mins(8, 30))   // 第 1 節沒課
        assertEquals(1, now.period)
        assertNull(now.current)
        assertEquals("英文簡報", now.next?.name)
    }

    // 課名對課程資料夾的比對 2026-09-07 搬到伺服器（test_courses_api.py 的名稱對照段）

    @Test
    fun `同一節多堂時每次都取到同一堂`() {
        // 重點是**穩定**：來源順序變了畫面不能跟著跳，不然每次重繪看到的課都不一樣。
        // 取哪一堂不重要（衝堂本來就要點開看），一致就好
        val a = layoutDay(listOf(c("乙", 0, 1), c("甲", 0, 1)), 1, 1)
        val b = layoutDay(listOf(c("甲", 0, 1), c("乙", 0, 1)), 1, 1)
        assertEquals(a[0].course?.name, b[0].course?.name)
        assertEquals(1, a[0].clash)
    }
}
