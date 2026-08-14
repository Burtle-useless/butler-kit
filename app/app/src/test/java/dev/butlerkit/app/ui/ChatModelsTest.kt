package dev.butlerkit.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
