package dev.butlerkit.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行內連結的切割。
 *
 * 使用者要的是在聊天裡直接點網址，不要複製貼上。能點與不能點的差別只在
 * 那一段字有沒有掛上 LinkAnnotation，而範圍算錯一個字元就是開錯網址——
 * 這種事在實機上只會看到「怎麼跳到 404」，所以邏輯在這裡先釘死。
 */
class MarkdownTest {

    /** 取出所有連結：(顯示文字, 目標網址)。 */
    private fun links(src: String): List<Pair<String, String>> {
        val s = inline(src)
        return s.getLinkAnnotations(0, s.length).map { r ->
            val url = (r.item as androidx.compose.ui.text.LinkAnnotation.Url).url
            s.text.substring(r.start, r.end) to url
        }
    }

    @Test
    fun `裸網址可以點，句末的句號不算在網址裡`() {
        assertEquals(
            listOf("https://example.com/setup" to "https://example.com/setup"),
            links("設定在 https://example.com/setup。點進去登入就好"),
        )
    }

    @Test
    fun `中文全形標點與括號結尾都會被剝掉`() {
        for (tail in listOf("，", "、", "！", "？", "）", "」", ")", ".", "…")) {
            assertEquals(
                "尾巴是 $tail",
                listOf("https://x.com/a" to "https://x.com/a"),
                links("看 https://x.com/a$tail 這個"),
            )
        }
    }

    @Test
    fun `markdown 連結顯示標題、點進去是網址`() {
        assertEquals(
            listOf("圖書館" to "https://example.org/library/"),
            links("[圖書館](https://example.org/library/) 要先開 VPN"),
        )
        // 標題那段字本身不能留下方括號
        assertTrue(inline("[圖書館](https://a.b)").text == "圖書館")
    }

    @Test
    fun `markdown 連結裡的網址不會被再標一次`() {
        assertEquals(1, links("[看這裡](https://x.com/y)").size)
    }

    @Test
    fun `反引號包起來的網址不可點`() {
        // 行內程式碼是要原樣複製的東西，變成連結反而會誤觸
        assertEquals(emptyList<Pair<String, String>>(), links("跑 `https://x.com/y` 這行"))
    }

    @Test
    fun `一段話裡兩個網址各自獨立`() {
        assertEquals(
            listOf(
                "https://a.com" to "https://a.com",
                "https://b.com/p?q=1" to "https://b.com/p?q=1",
            ),
            links("先看 https://a.com，再看 https://b.com/p?q=1 就懂了"),
        )
    }

    @Test
    fun `沒有網址時原文一字不差`() {
        val src = "帳記完了，**一千二**，走 `ledger_add`"
        assertEquals(emptyList<Pair<String, String>>(), links(src))
        assertEquals("帳記完了，一千二，走 ledger_add", inline(src).text)
    }
}
