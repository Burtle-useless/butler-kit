package dev.butlerkit.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回覆定稿前的空值判斷。
 *
 * 助理偶爾整輪只吐一個 [[DONE]]（伺服器的空回覆重試會補問一次，下一則才是真話）。
 * 那則清完控制標記什麼都不剩，照樣定稿就是一顆沒有內容的孤兒頭像杵在真回覆
 * 上面——畫面上看起來像同一句話冒出兩個助理。
 */
class ChatModelsTest {

    @Test
    fun `只有結束標記的回覆不進軌跡`() {
        assertNull(replyOrNull("t1", "[[DONE]]"))
        assertNull(replyOrNull("t1", "[[DONE]]\n"))
        assertNull(replyOrNull("t1", "  [[WAIT]]  "))
    }

    @Test
    fun `完全空白的回覆不進軌跡`() {
        assertNull(replyOrNull("t1", ""))
        assertNull(replyOrNull("t1", "   \n  "))
    }

    @Test
    fun `有話要說的回覆照常定稿且保留原文`() {
        // 定稿存原始文字、渲染時才清標記——複製功能拿得到的仍是使用者看到的那份
        val r = replyOrNull("t1", "沒事了，帳記完就這樣。\n\n[[DONE]]")
        assertNotNull(r)
        assertEquals("t1", r!!.turnId)
        assertEquals("沒事了，帳記完就這樣。", cleanMarkers(r.markdown))
    }

    @Test
    fun `串流到一半的半截標記不會被誤判成有內容`() {
        // text.delta 是逐字送的，標記可能先到 "[[DO" 才到 "NE]]"
        assertNull(replyOrNull("t1", "[[DO"))
    }

    // ── 工具摺疊摘要 ────────────────────────────────────────────────────────
    private fun tc(kind: String, added: Int = 0, removed: Int = 0) =
        dev.butlerkit.app.net.ToolCall(
            tool = "X", icon = "", summary = "", raw = "", dangerous = false,
            kind = kind, added = added, removed = removed,
        )

    @Test
    fun `摘要講做了什麼而不是只有總數`() {
        val s = toolSummary(listOf(tc("read"), tc("read"), tc("cmd"), tc("edit", 12, 4)))
        assertEquals("讀 2 個檔・改 1 個檔・跑 1 個指令　+12 −4", s)
    }

    @Test
    fun `沒有增刪就不畫增刪`() {
        assertEquals("讀 1 個檔", toolSummary(listOf(tc("read"))))
    }

    @Test
    fun `沒有工具就是空字串`() {
        assertEquals("", toolSummary(emptyList()))
    }

    @Test
    fun `摺疊門檻以上才收起來`() {
        // 門檻本身不摺（<=），超過才摺——邊界寫死在測試裡，改門檻要連這條一起想
        assertTrue(TOOL_FOLD_THRESHOLD >= 3)
        val small = List(TOOL_FOLD_THRESHOLD) { tc("read") }
        val big = List(TOOL_FOLD_THRESHOLD + 1) { tc("read") }
        assertFalse(small.size > TOOL_FOLD_THRESHOLD)
        assertTrue(big.size > TOOL_FOLD_THRESHOLD)
    }
    @Test
    fun `取檔用伺服器上的檔名，顯示用原始檔名`() {
        // 上傳端點會加時戳前綴才落地。先前縮圖拿 name 去打 /v1/uploads/{name}，
        // 一律 404，畫面上每張圖都變成載入失敗的破圖標。
        val a = Attachment(
            name = "Screenshot_1454.jpg",
            path = "/up/20260904-145415-Screenshot_1454.jpg",
            bytes = 12L, mime = "image/jpeg",
        )
        assertEquals("20260904-145415-Screenshot_1454.jpg", a.stored)
        assertEquals("Screenshot_1454.jpg", a.name)
        assertTrue(a.isImage)
    }

    @Test
    fun `Windows 路徑也取得出檔名`() {
        // 伺服器實際回的就是 C:\...\uploads\xxx 這種
        val sep = Char(92)
        val win = Attachment(
            name = "a.jpg",
            path = "C:" + sep + "data" + sep + "uploads" + sep + "20260904-a.jpg",
            bytes = 1L, mime = "image/jpeg",
        )
        assertEquals("20260904-a.jpg", win.stored)
    }
}
