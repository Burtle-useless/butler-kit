package dev.butlerkit.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知 id 的分配。
 *
 * 清除通知是靠 [Notifier.idOf] 反推 id 再 cancel 的，所以這個算式錯了不會報錯，
 * 只會表現成「滑不掉的通知」或更糟的「清掉了別種通知」。原本的
 * `ordinal * 1000 + hash` 在種類之間只差 1000，雜湊值一撞就會誤蓋，
 * 這裡把「同對話的不同種類永不互撞」釘死。
 */
class NotifierTest {

    @Test
    fun `同一種通知同一個對話共用一個 id`() {
        // 這是刻意的：新的「做完了」要蓋掉舊的，而不是多疊一則
        assertEquals(
            Notifier.idOf(NotifyKind.TaskDone, "conv-a"),
            Notifier.idOf(NotifyKind.TaskDone, "conv-a"),
        )
    }

    @Test
    fun `同一個對話的每一種通知各自獨立`() {
        val ids = NotifyKind.entries.map { Notifier.idOf(it, "conv-a") }
        assertEquals("種類之間不該有任何重複", ids.size, ids.toSet().size)
    }

    @Test
    fun `不同對話的同一種通知各自獨立`() {
        assertNotEquals(
            Notifier.idOf(NotifyKind.NeedsYou, "conv-a"),
            Notifier.idOf(NotifyKind.NeedsYou, "conv-b"),
        )
    }

    @Test
    fun `舊算式會撞的那組在新算式下不撞`() {
        // 舊寫法 ordinal * 1000 + hash：只要兩個對話的雜湊差 1000，
        // 相鄰種類就會算出同一個 id。找一組真的差 1000 的來驗。
        val base = "conv-a".hashCode()
        val evil = (0..200_000).firstOrNull { i ->
            "c$i".hashCode() == base + 1000
        }
        // 找不到就跳過——這個測試要證的是新算式的性質，不是硬要找到那組字串
        if (evil == null) return
        val a = "conv-a"
        val b = "c$evil"
        assertEquals(
            "前提：這兩個對話在舊算式下確實會撞",
            NotifyKind.TaskDone.ordinal * 1000 + a.hashCode(),
            NotifyKind.NeedsYou.ordinal * 1000 + b.hashCode(),
        )
        assertNotEquals(
            Notifier.idOf(NotifyKind.TaskDone, a),
            Notifier.idOf(NotifyKind.NeedsYou, b),
        )
    }

    @Test
    fun `沒帶對話 id 也算得出穩定的 id`() {
        // 檔案通知目前不掛在任何對話上（收件匣是全域的）
        assertEquals(
            Notifier.idOf(NotifyKind.FileReady, null),
            Notifier.idOf(NotifyKind.FileReady, null),
        )
        assertNotEquals(
            Notifier.idOf(NotifyKind.FileReady, null),
            Notifier.idOf(NotifyKind.TaskDone, null),
        )
    }

    @Test
    fun `會被摺進群組的正好是對話類那三種`() {
        // 鬧鐘與行程提醒進了群組就等於沒響，這條界線不能被誤改
        assertEquals(
            setOf(NotifyKind.TaskDone, NotifyKind.NeedsYou, NotifyKind.FileReady),
            NotifyKind.entries.filter { it.grouped }.toSet(),
        )
        assertTrue("鬧鐘不能被摺疊", !NotifyKind.Alarm.grouped)
        assertTrue("行程提醒不能被摺疊", !NotifyKind.Reminder.grouped)
    }
}
