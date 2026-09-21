package dev.butlerkit.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 圖表的資料解析與座標軸刻度。
 *
 * 畫得好不好看要用眼睛驗，但「寫壞的 JSON 要退回顯示原文」與「軸上的數字要落在
 * 整齊的位置」是可以釘死的。後者特別容易被當成小事——軸上出現 0、3.7、7.4
 * 的圖看起來就是壞的，而那跟畫圖的程式碼一點關係都沒有，純粹是算術錯了。
 */
class ChartTest {

    @Test
    fun `標準寫法解得出來`() {
        val spec = parseChart(
            """{"kind":"line","title":"庫侖力","x":"r (m)","y":"F (N)",
               "series":[{"name":"q=2nC","points":[[1,18],[2,4.5],[3,2]]}]}""",
        )!!
        assertEquals(ChartKind.Line, spec.kind)
        assertEquals("庫侖力", spec.title)
        assertEquals("r (m)", spec.xLabel)
        assertEquals(1, spec.series.size)
        assertEquals(3, spec.series[0].pts.size)
        assertEquals(18f, spec.series[0].pts[0].y, 0.001f)
    }

    @Test
    fun `type 是 kind 的別名`() {
        assertEquals(ChartKind.Bar, parseChart("""{"type":"bar","values":[1,2]}""")!!.kind)
    }

    @Test
    fun `values 配 labels 就是分類軸`() {
        val spec = parseChart(
            """{"kind":"bar","labels":["一月","二月"],
               "series":[{"name":"收入","values":[100,200]}]}""",
        )!!
        assertEquals(listOf("一月", "二月"), spec.labels)
        assertEquals(listOf(0f, 1f), spec.series[0].pts.map { it.x })
        assertEquals(listOf(100f, 200f), spec.series[0].pts.map { it.y })
    }

    @Test
    fun `只有一組資料時可以省掉 series 那層`() {
        val spec = parseChart("""{"kind":"line","points":[[0,1],[1,4]]}""")!!
        assertEquals(1, spec.series.size)
        assertEquals(2, spec.series[0].pts.size)
    }

    @Test
    fun `點也可以寫成物件`() {
        val spec = parseChart("""{"points":[{"x":1,"y":2},{"x":3,"y":4}]}""")!!
        assertEquals(listOf(1f, 3f), spec.series[0].pts.map { it.x })
    }

    @Test
    fun `多組資料各自成一條`() {
        val spec = parseChart(
            """{"series":[{"name":"a","values":[1,2]},{"name":"b","values":[3,4]}]}""",
        )!!
        assertEquals(listOf("a", "b"), spec.series.map { it.name })
    }

    @Test
    fun `折線預設平滑，可以明確關掉`() {
        assertTrue(parseChart("""{"kind":"line","values":[1,2,3]}""")!!.smooth)
        assertTrue(!parseChart("""{"kind":"line","smooth":false,"values":[1,2,3]}""")!!.smooth)
    }

    @Test
    fun `壞掉的 JSON 回 null 讓呼叫端退回顯示原文`() {
        assertNull(parseChart("這不是 JSON"))
        assertNull(parseChart("""{"kind":"line"}"""))        // 沒有資料
        assertNull(parseChart("""{"series":[]}"""))
        assertNull(parseChart(""))
    }

    @Test
    fun `無限大與非數字的點被丟掉，不會讓整張圖爆掉`() {
        val spec = parseChart("""{"points":[[0,1],[1,"x"],[2,3]]}""")
        // "x" 那個點解出來是 NaN，濾掉之後還有兩個
        assertEquals(2, spec!!.series[0].pts.size)
    }

    // ── 刻度 ────────────────────────────────────────────────────────────
    @Test
    fun `格距落在 1 2 5 10 這種好看的數上`() {
        assertEquals(2.5f, niceStep(10f, 4), 0.001f)
        assertEquals(25f, niceStep(100f, 4), 0.001f)
        assertEquals(0.25f, niceStep(1f, 4), 0.001f)
    }

    @Test
    fun `範圍會被撐到刻度上`() {
        val (lo, hi, step) = niceRange(2f, 18f)
        assertTrue("下界要含住資料", lo <= 2f)
        assertTrue("上界要含住資料", hi >= 18f)
        assertEquals(0f, lo % step, 0.001f)
        assertEquals(0f, hi % step, 0.001f)
    }

    @Test
    fun `常數序列不會除以零`() {
        val (lo, hi, step) = niceRange(5f, 5f)
        assertTrue(hi > lo)
        assertTrue(step > 0f)
    }

    @Test
    fun `刻度標籤的小數位數跟著格距走`() {
        assertEquals("0", tickLabel(0f, 1f))
        assertEquals("5", tickLabel(5f, 5f))
        assertEquals("0.5", tickLabel(0.5f, 0.5f))
        assertEquals("0.25", tickLabel(0.25f, 0.05f))
    }

    @Test
    fun `很小很大的數用科學記號，不要拖一排零`() {
        assertTrue(tickLabel(0.0000001f, 0.0000001f).contains("e"))
        assertTrue(tickLabel(1200000f, 100000f).contains("e"))
    }
}
