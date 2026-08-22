package dev.butlerkit.app.notify

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 檔案通知什麼時候該發。
 *
 * 災情：助理把 APK 傳過來，通知當場就跳，但那一輪它到七分半鐘後才講完話——
 * 使用者收到通知過來，看到的是還在思考的畫面。要的是東西都給完了再跳通知。
 *
 * 這裡釘住兩件事：跑到一半的檔案要按住（不然災情重演），以及按住的東西
 * **每一條路徑都放得掉**（不然檔案通知會整個消失，比原本的問題更糟）。
 */
class FileNotifyGateTest {

    @Test
    fun `回合跑到一半傳的檔案先按住，收工時一起發`() {
        val g = FileNotifyGate()
        g.onTurnStart("c1")
        assertEquals("跑到一半不該通知", emptyList<String>(), g.onFile("c1", "a.apk"))
        assertEquals(listOf("a.apk"), g.onTurnEnd("c1"))
    }

    @Test
    fun `沒有回合在跑就立刻通知`() {
        // 助理不是在回話時傳的（排程、外部腳本登記），沒有「等它講完」可言
        val g = FileNotifyGate()
        assertEquals(listOf("a.png"), g.onFile("c1", "a.png"))
    }

    @Test
    fun `同一輪傳的多個檔案併成一次發出`() {
        // 分開發也沒用：同種類同對話是同一個通知 id，後面那則會蓋掉前面那則
        val g = FileNotifyGate()
        g.onTurnStart("c1")
        g.onFile("c1", "a.png")
        g.onFile("c1", "b.png")
        assertEquals(listOf("a.png", "b.png"), g.onTurnEnd("c1"))
    }

    @Test
    fun `出錯收場也放得掉`() {
        // 出錯的回合不發 turn.done，這條漏接的話對話會永遠卡在跑中
        val g = FileNotifyGate()
        g.onTurnStart("c1")
        g.onFile("c1", "a.log")
        assertEquals(listOf("a.log"), g.onTurnEnd("c1"))
        assertEquals("放掉之後不該還是跑中", listOf("b.log"), g.onFile("c1", "b.log"))
    }

    @Test
    fun `上一輪沒收好的檔案在下一輪開始時補發`() {
        // 伺服器重啟、連線斷在半路都會讓收工事件不見
        val g = FileNotifyGate()
        g.onTurnStart("c1")
        g.onFile("c1", "a.zip")
        assertEquals(listOf("a.zip"), g.onTurnStart("c1"))
        assertEquals("補發過就不該再發一次", emptyList<String>(), g.onTurnEnd("c1"))
    }

    @Test
    fun `收工兩次不會重複發`() {
        val g = FileNotifyGate()
        g.onTurnStart("c1")
        g.onFile("c1", "a.zip")
        g.onTurnEnd("c1")
        assertEquals(emptyList<String>(), g.onTurnEnd("c1"))
    }

    @Test
    fun `錯過 turn start 時靠心跳也認得出跑中`() {
        // 背景連線續傳斷層太大時，turn.start 那一則會整個錯過。沒有這條兜底，
        // 閘門會靜默失效——表現得跟修好之前一模一樣，最難查的那種。
        val g = FileNotifyGate()
        g.onRunning("c1")
        assertEquals(emptyList<String>(), g.onFile("c1", "a.apk"))
        assertEquals(listOf("a.apk"), g.onTurnEnd("c1"))
    }

    @Test
    fun `心跳不會把按住的檔案放掉`() {
        // 心跳每兩秒一次，誤走補發那條路的話等於完全沒按住
        val g = FileNotifyGate()
        g.onTurnStart("c1")
        g.onFile("c1", "a.apk")
        g.onRunning("c1")
        g.onRunning("c1")
        assertEquals(listOf("a.apk"), g.onTurnEnd("c1"))
    }

    @Test
    fun `對話之間互不影響`() {
        val g = FileNotifyGate()
        g.onTurnStart("c1")
        // c2 沒有回合在跑，它的檔案照樣立刻通知
        assertEquals(listOf("b.png"), g.onFile("c2", "b.png"))
        g.onFile("c1", "a.png")
        assertEquals(emptyList<String>(), g.onTurnEnd("c2"))
        assertEquals(listOf("a.png"), g.onTurnEnd("c1"))
    }
}
