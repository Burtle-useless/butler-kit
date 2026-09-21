package dev.butlerkit.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 數學式的解析。
 *
 * 排版畫得好不好看要用眼睛驗，但「這段字到底是不是公式」「上標吃到哪為止」
 * 是可以釘死的——而這兩件事錯了的症狀都很難查：前者會把「$5 美金」變成一塊
 * 畫出來的東西，後者會讓 `10^-9 N` 變成「10 的 -9N 次方」，兩種在畫面上
 * 都只是「怪怪的」，不會報錯。
 */
class MathTest {

    /** 把樹壓成一行好讀的字串，方便直接比對結構。 */
    private fun dump(n: MathNode): String = when (n) {
        is MathNode.Sym -> n.text
        is MathNode.Plain -> "«${n.text}»"
        is MathNode.Row -> n.items.joinToString("") { dump(it) }
        is MathNode.Script -> buildString {
            append(dump(n.base))
            n.sub?.let { append("_[").append(dump(it)).append("]") }
            n.sup?.let { append("^[").append(dump(it)).append("]") }
        }
        is MathNode.Frac -> "frac(${dump(n.num)},${dump(n.den)})"
        is MathNode.Sqrt -> "sqrt(${dump(n.body)})"
        is MathNode.Accent -> "${n.kind.name.lowercase()}(${dump(n.base)})"
    }

    private fun p(src: String) = dump(parseMath(src))

    // ── 解析 ────────────────────────────────────────────────────────────
    @Test
    fun `上標只吃一個字元，除非用大括號框起來`() {
        assertEquals("x^[2]y", p("x^2y"))
        assertEquals("x^[2y]", p("x^{2y}"))
    }

    @Test
    fun `負指數要整個括起來才算在指數上`() {
        // 物理題最常見的寫法：10^{-9} 後面接單位
        assertEquals("10^[-9]«N»", p("10^{-9}\\text{N}"))
        // 沒括號的話只有負號上去，這是 TeX 的規矩，照做
        assertEquals("10^[-]9", p("10^-9"))
    }

    @Test
    fun `上下標同時存在時疊在同一個底上`() {
        assertEquals("F_[13]^[2]", p("F_{13}^2"))
        assertEquals("F_[13]^[2]", p("F^2_{13}"))
    }

    @Test
    fun `分數與根號`() {
        assertEquals("frac(a,b)", p("\\frac{a}{b}"))
        assertEquals("frac(kq_[1]q_[2],r^[2])", p("\\frac{kq_1q_2}{r^2}"))
        assertEquals("sqrt(x^[2]+y^[2])", p("\\sqrt{x^2+y^2}"))
    }

    @Test
    fun `向量與帽子`() {
        assertEquals("vec(F)", p("\\vec{F}"))
        assertEquals("hat(r)_[23]", p("\\hat{r}_{23}"))
        assertEquals("vec(F)_[13]", p("\\vec{F}_{13}"))
    }

    @Test
    fun `希臘字母與運算符號換成真正的字元`() {
        assertEquals("π", p("\\pi"))
        assertEquals("Δ", p("\\Delta"))
        assertEquals("8.99×10^[9]", p("8.99\\times10^9"))
        assertEquals("≤", p("\\leq"))
    }

    @Test
    fun `函式名用正體`() {
        assertEquals("«sin»θ", p("\\sin\\theta"))
    }

    @Test
    fun `不認得的指令原樣留著，不會整段消失`() {
        assertTrue(p("\\begin{matrix}").contains("\\begin"))
    }

    @Test
    fun `空白不照抄，間距交給排版`() {
        assertEquals("F=ma", p("F = ma"))
    }

    @Test
    fun `大括號裡的東西是一組`() {
        assertEquals("frac(a+b,2)", p("\\frac{a+b}{2}"))
    }

    // ── 找出公式的位置 ──────────────────────────────────────────────────
    @Test
    fun `行內式抓得到`() {
        val got = findMath("由 \$F=ma\$ 可知")
        assertEquals(1, got.size)
        assertEquals("F=ma", got[0].body)
        assertTrue(!got[0].display)
    }

    @Test
    fun `獨立式標成 display`() {
        val got = findMath("\$\$E=mc^2\$\$")
        assertEquals(1, got.size)
        assertEquals("E=mc^2", got[0].body)
        assertTrue(got[0].display)
    }

    @Test
    fun `一段話裡有好幾個公式`() {
        val got = findMath("\$a\$ 跟 \$b\$ 還有 \$c\$")
        assertEquals(listOf("a", "b", "c"), got.map { it.body })
    }

    @Test
    fun `金額不會被當成公式`() {
        // 兩個錢字號之間是中文與空白，不像式子
        assertTrue(findMath("這個 \$5 美金，那個 \$10 美金").isEmpty())
    }

    @Test
    fun `跳脫的錢字號不算邊界`() {
        assertTrue(findMath("價格是 \\\$5").isEmpty())
    }

    @Test
    fun `公式不跨行`() {
        assertTrue(findMath("第一行 \$a\n第二行 b\$").isEmpty())
    }

    @Test
    fun `落單的錢字號不會吃掉整段`() {
        assertTrue(findMath("只有一個 \$ 符號").isEmpty())
    }

    @Test
    fun `切出來的範圍剛好蓋住整段公式`() {
        val src = "由 \$F=ma\$ 可知"
        val s = findMath(src)[0]
        assertEquals("\$F=ma\$", src.substring(s.range.first, s.range.last + 1))
    }

    @Test
    fun `獨立式的範圍也蓋得剛好`() {
        val src = "前面 \$\$E=mc^2\$\$ 後面"
        val s = findMath(src)[0]
        assertEquals("\$\$E=mc^2\$\$", src.substring(s.range.first, s.range.last + 1))
    }
}
