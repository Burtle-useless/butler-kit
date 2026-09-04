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
            listOf("https://koboyo.com/mcp" to "https://koboyo.com/mcp"),
            links("設定在 https://koboyo.com/mcp。點進去登入就好"),
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
            listOf("清大圖書館" to "https://www.lib.nthu.edu.tw/"),
            links("[清大圖書館](https://www.lib.nthu.edu.tw/) 要先開 VPN"),
        )
        // 標題那段字本身不能留下方括號
        assertTrue(inline("[清大](https://a.b)").text == "清大")
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
    fun `沒帶 https 的網址也能點，開的時候自己補上`() {
        // 助理報網址時十之八九寫成這樣，2026-08-25 使用者截圖回報「超連結沒做」
        assertEquals(
            listOf("demo.example.com/tree/" to "https://demo.example.com/tree/"),
            links("做了個頁面，手機上直接點：\n\ndemo.example.com/tree/"),
        )
    }

    @Test
    fun `檔名不是網址`() {
        // 對話裡滿地都是這種字串，通用的 x-y 規則會把它們全變成連結
        for (name in listOf(
            "main.py", "chat.js", "app.css", "Markdown.kt", "README.md",
            "build.sh", "logo.ai", "版本 1.2.3", "server/web/md.js",
        )) {
            assertEquals("誤判：$name", emptyList<Pair<String, String>>(), links("改了 $name 這個"))
        }
    }

    @Test
    fun `信箱不會被切出一個網域`() {
        assertEquals(
            emptyList<Pair<String, String>>(),
            links("寄到 youteng330@gmail.com 就好"),
        )
    }

    @Test
    fun `頂級網域不可以只吃前面幾個字`() {
        // foo.community 不是 foo.com
        assertEquals(emptyList<Pair<String, String>>(), links("那個 foo.community 專案"))
    }

    @Test
    fun `方括號寫法沒帶 https 一樣能點`() {
        assertEquals(
            listOf("看這裡" to "https://demo.example.com/tree/"),
            links("[看這裡](demo.example.com/tree/) 就是了"),
        )
    }

    @Test
    fun `反引號包起來的無 scheme 網址也不可點`() {
        assertEquals(emptyList<Pair<String, String>>(), links("設定檔在 `demo.example.com` 裡"))
    }

    @Test
    fun `沒有網址時原文一字不差`() {
        val src = "帳記完了，**一千二**，走 `ledger_add`"
        assertEquals(emptyList<Pair<String, String>>(), links(src))
        assertEquals("帳記完了，一千二，走 ledger_add", inline(src).text)
    }

    /**
     * 2026-08-25 使用者第二次截圖回報「又不能按超連結了」。上次補的是無 scheme
     * （`demo.example.com/tree/`），這次的洞不一樣：助理報網址時會順手加粗，
     * 在網址外面再包一層星號當粗體。
     *
     * 粗體那一段的起點在網址之前，排序後先被套用、游標整段跳過，
     * 裡面的網址就再也輪不到——**兩種語法各自都對，疊在一起才壞**。
     */
    @Test
    fun `粗體包住的網址還是能點`() {
        assertEquals(
            listOf("demo.example.com/room/" to "https://demo.example.com/room/"),
            links("好，換你拉。\n\n**demo.example.com/room/**\n\n怎麼用："),
        )
        assertEquals(
            listOf("https://x.com/a" to "https://x.com/a"),
            links("**https://x.com/a** 就是了"),
        )
    }

    /** 粗體只包住一半的句子時，網址跟其他字都不可以掉。 */
    @Test
    fun `粗體裡的網址被認出來之後原文不變`() {
        assertEquals("看這裡：demo.example.com/room/ 謝謝",
            inline("看這裡：**demo.example.com/room/** 謝謝").text)
    }

    /** 粗體裡的反引號照樣是行內程式碼，而反引號裡的網址一樣不可點。 */
    @Test
    fun `粗體裡的行內程式碼還是程式碼`() {
        assertEquals(emptyList<Pair<String, String>>(), links("**設定檔 `demo.example.com` 在這**"))
        assertEquals("設定檔 demo.example.com 在這", inline("**設定檔 `demo.example.com` 在這**").text)
    }
}
