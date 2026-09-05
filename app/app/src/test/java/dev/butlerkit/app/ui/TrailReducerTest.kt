package dev.butlerkit.app.ui

import dev.butlerkit.app.net.HistoryFile
import dev.butlerkit.app.net.HistoryMsg
import dev.butlerkit.app.net.ServerEvent
import dev.butlerkit.app.net.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件序列 → 軌跡的回歸測試。每一條都對應一次真的出過的病。
 */
class TrailReducerTest {

    private var seq = 100L

    private fun ev(type: String, json: String = "{}", turn: String = "t-1"): ServerEvent {
        seq += 1
        val body = json.trimStart('{')
        val payload = "{\"conv_id\":\"c\",\"turn_id\":\"$turn\"" +
            (if (body.trim() == "}") "}" else ",$body")
        return ServerEvent.parse(seq.toString(), type, payload)!!
    }

    private fun run(vararg events: ServerEvent): ConvTrail {
        var t = ConvTrail()
        for (e in events) t = TrailReducer.reduce(t, e).trail
        return t
    }

    private fun replies(t: ConvTrail) =
        t.items.filterIsInstance<TraceItem.Reply>().map { it.markdown }

    @Test
    fun `沒有任何 delta 的回覆也要畫出來`() {
        // 2026-09-02：額度用盡那句、很短的回答——沒串流過就整則消失
        val t = run(
            ev("turn.start"),
            ev("reply.final", """{"markdown":"OK"}"""),
            ev("turn.done"),
        )
        assertEquals(listOf("OK"), replies(t))
        assertFalse(t.busy)
    }

    @Test
    fun `串流過的回覆只畫一次`() {
        val t = run(
            ev("turn.start"),
            ev("text.delta", """{"d":"你好"}"""),
            ev("text.delta", """{"d":"，世界"}"""),
            ev("step.commit", """{"text":"你好，世界"}"""),
            ev("reply.final", """{"markdown":"你好，世界"}"""),
            ev("turn.done"),
        )
        assertEquals(listOf("你好，世界"), replies(t))
    }

    @Test
    fun `多步驟各自定稿、最後不重複`() {
        val t = run(
            ev("turn.start"),
            ev("text.delta", """{"d":"先看檔案"}"""),
            ev("tool.call", """{"tool":"Read","icon":"📄","summary":"a.py","raw":"Read a.py"}"""),
            ev("step.commit", """{"text":"先看檔案"}"""),
            ev("text.delta", """{"d":"看完了"}"""),
            ev("step.commit", """{"text":"看完了"}"""),
            ev("reply.final", """{"markdown":"看完了"}"""),
            ev("turn.done"),
        )
        assertEquals(listOf("先看檔案", "看完了"), replies(t))
        assertTrue(t.items.any { it is TraceItem.Stage })
    }

    @Test
    fun `背景對話沒串流時用事件帶的完整文字`() {
        val long = "x".repeat(2000)
        val t = run(
            ev("turn.start"),
            ev("step.commit", """{"text":"$long"}"""),
            ev("reply.final", """{"markdown":"$long"}"""),
        )
        assertEquals(listOf(long), replies(t))
    }

    @Test
    fun `按停止時已經打出來的字要留著`() {
        val t = run(
            ev("turn.start"),
            ev("text.delta", """{"d":"講到一半"}"""),
            ev("error", """{"kind":"STOPPED","detail":"好，我停下了。"}"""),
        )
        assertEquals(listOf("講到一半"), replies(t))
        assertTrue(t.items.last() is TraceItem.ErrorItem)
        assertFalse(t.busy)
        assertEquals("", t.streaming)
    }

    @Test
    fun `turn end 帶 ok=false 要算閒置`() {
        // wake 票失效那條路只發 turn.end，先前 busy 卡到下次重連
        val t = run(ev("turn.start"), ev("turn.end", """{"ok":false}"""))
        assertFalse(t.busy)
        val still = run(ev("turn.start"), ev("turn.end", """{"ok":true}"""))
        assertTrue("續跑輪之間不能清 busy", still.busy)
    }

    @Test
    fun `助理自己醒來的回合先寫一行理由`() {
        val t = run(
            ev("turn.start", """{"origin":"wake","wake":{"id":"b1","desc":"跑腳本","status":"completed"}}"""),
        )
        val note = t.items.first() as TraceItem.WakeNote
        assertTrue(note.text.contains("跑腳本"))
        assertTrue(note.text.contains("完成"))
    }

    @Test
    fun `狀態列的說明要沿用到這輪結束`() {
        val t = run(
            ev("status", """{"elapsed":1.0,"model":"m","effort":"high","tools":0,"ctx_tokens":0,"bg":[],"note":"整理記憶"}"""),
            ev("status", """{"elapsed":3.0,"model":"m","effort":"high","tools":0,"ctx_tokens":0,"bg":[]}"""),
        )
        assertEquals("整理記憶", t.status?.note)
        val next = TrailReducer.reduce(t, ev("turn.start")).trail
        assertEquals("", next.status?.note)
    }

    @Test
    fun `排隊訊息被讀走與取消只改標記`() {
        val t = run(
            ev("user.message", """{"text":"A","msg_id":"m1","queued":true}"""),
            ev("user.message", """{"text":"B","msg_id":"m2","queued":true}"""),
            ev("message.taken", """{"msg_ids":["m1"]}"""),
            ev("message.dropped", """{"msg_ids":["m2"]}"""),
        )
        val msgs = t.items.filterIsInstance<TraceItem.UserMsg>()
        assertFalse(msgs[0].queued)
        assertFalse(msgs[0].dropped)
        assertTrue(msgs[1].dropped)
    }

    @Test
    fun `副作用由 reducer 說出來`() {
        val r = TrailReducer.reduce(ConvTrail(), ev("file.offer", """{"file_id":"f","name":"a.apk","bytes":10,"note":"","mime":"x"}"""))
        assertTrue(r.effects.any { it is Effect.Offer })
        val g = TrailReducer.reduce(ConvTrail(), ev("seq.gap"))
        assertTrue(g.effects.contains(Effect.Reload))
        val s = TrailReducer.reduce(ConvTrail(), ev("status", """{"note":"conv_renamed:新標題","elapsed":0,"model":"","effort":"","tools":0,"ctx_tokens":0,"bg":[]}"""))
        assertTrue(s.effects.contains(Effect.Rename("新標題")))
    }

    @Test
    fun `日期分隔與 atMs 帶上`() {
        // 2026-09-03：伺服器事件開始帶 ts（epoch 秒），軌跡要記下來、跨日才插日期
        val d1 = 1_756_800_000.0        // 2025-09-02 08:00 UTC → 台北 09-02 16:00
        val d2 = d1 + 86_400            // 隔天同時刻
        val t = run(
            ev("user.message", """{"text":"A","ts":$d1}"""),
            ev("turn.start", """{"ts":$d1}"""),
            ev("tool.call", """{"tool":"Read","icon":"","summary":"a","raw":"","ts":$d1}"""),
            ev("reply.final", """{"markdown":"B","ts":${d1 + 60}}"""),
            ev("turn.done"),
            ev("user.message", """{"text":"C","ts":$d2}"""),
        )
        val items = t.items
        assertEquals((d1 * 1000).toLong(), (items[0] as TraceItem.UserMsg).atMs)
        assertEquals(((d1 + 60) * 1000).toLong(), (items[2] as TraceItem.Reply).atMs)
        val zone = java.time.ZoneId.of("Asia/Taipei")
        assertEquals("2025年9月2日 週二", dayLabelBefore(items, 0, zone))
        assertNull(dayLabelBefore(items, 1, zone))   // 工具列沒時間，不插
        assertNull(dayLabelBefore(items, 2, zone))   // 同一天
        assertEquals("2025年9月3日 週三", dayLabelBefore(items, 3, zone))
        assertEquals("16:00", clockLabel((items[3] as TraceItem.UserMsg).atMs, zone))
        // 沒帶 ts 的舊事件：0，不插分隔
        val old = run(ev("user.message", """{"text":"Z"}"""))
        assertEquals(0L, (old.items[0] as TraceItem.UserMsg).atMs)
        assertNull(dayLabelBefore(old.items, 0, zone))
    }

    // ── 歷史 → 軌跡（snapshot 與往前翻頁共用）─────────────────────────────

    private fun tool(name: String) = ToolCall(
        tool = name, icon = "", summary = name, raw = "", dangerous = false,
    )

    @Test
    fun `歷史還原：system 變 WakeNote、思考與工具排在回覆前面`() {
        val items = TrailReducer.fromHistory(
            "c",
            listOf(
                HistoryMsg("user", "幫我看一下", atMs = 1_000L),
                HistoryMsg(
                    "assistant", "看完了", think = "先讀檔", atMs = 2_000L,
                    tools = listOf(tool("Read"), tool("Grep")),
                ),
                HistoryMsg("system", "背景工作「整理」完成，助理接手", atMs = 3_000L),
                HistoryMsg("assistant", "[[DONE]]", atMs = 4_000L),
            ),
        )
        val kinds = items.map { it::class.simpleName }
        // 兩條工具摺進同一個 Stage；只有 [[DONE]] 的那則不還原
        assertEquals(listOf("UserMsg", "Thinking", "Stage", "Reply", "WakeNote"), kinds)
        assertEquals("先讀檔", (items[1] as TraceItem.Thinking).text)
        assertEquals(listOf("Read", "Grep"), (items[2] as TraceItem.Stage).tools.map { it.tool })
        assertEquals(2_000L, (items[3] as TraceItem.Reply).atMs)
        assertEquals("背景工作「整理」完成，助理接手", (items[4] as TraceItem.WakeNote).text)
        // 每一項的 turnId 都拿 convId 頂替
        assertTrue(items.all { it.turnId == "c" })
    }

    @Test
    fun `歷史還原：檔案卡片依時間插回訊息之間，已知的略過`() {
        val items = TrailReducer.fromHistory(
            "c",
            listOf(
                HistoryMsg("user", "傳圖", atMs = 1_000L),
                HistoryMsg("assistant", "傳了", atMs = 2_000L),
                HistoryMsg("user", "謝", atMs = 5_000L),
            ),
            files = listOf(
                HistoryFile("f1", "a.png", 10L, atMs = 2_500L),
                HistoryFile("f2", "b.png", 10L, atMs = 2_600L),
                HistoryFile("f3", "c.png", 10L, atMs = 9_000L),
            ),
            knownFileIds = setOf("f2"),
        )
        assertEquals(
            listOf("UserMsg", "Reply", "FileOffer", "UserMsg", "FileOffer"),
            items.map { it::class.simpleName },
        )
        assertEquals("f1", (items[2] as TraceItem.FileOffer).fileId)
        assertEquals("f3", (items[4] as TraceItem.FileOffer).fileId)
    }

    @Test
    fun `往前翻頁的游標是最早那個有時間的項目，工具與思考跳過`() {
        val page = TrailReducer.fromHistory(
            "c",
            listOf(
                HistoryMsg("assistant", "先做", think = "想", atMs = 7_000L, tools = listOf(tool("Bash"))),
                HistoryMsg("user", "好", atMs = 8_000L),
            ),
        )
        // 最前面兩項是 Thinking 與 Stage（atMs＝0），游標要落在那則回覆上
        assertEquals(7_000L, TrailReducer.oldestAtMs(page))
        assertNull(TrailReducer.oldestAtMs(emptyList()))
        // 更早的一頁接在前面之後，游標往前推到新的一頁
        val older = TrailReducer.fromHistory("c", listOf(HistoryMsg("user", "更早", atMs = 1_000L)))
        assertEquals(1_000L, TrailReducer.oldestAtMs(older + page))
    }

    @Test
    fun `插話與排隊要分得出來`() {
        // 2026-09-04：steered 一直有送，App 整個沒用，插話與排隊的氣泡長得一樣
        val t = run(
            ev("user.message", """{"text":"插話","msg_id":"m1","queued":false,"steered":true}"""),
            ev("user.message", """{"text":"排隊","msg_id":"m2","queued":true}"""),
        )
        val msgs = t.items.filterIsInstance<TraceItem.UserMsg>()
        assertTrue(msgs[0].steered)
        assertFalse(msgs[0].queued)
        assertFalse(msgs[1].steered)
        assertTrue(msgs[1].queued)
    }

    @Test
    fun `附件是欄位不是本文`() {
        val t = run(
            ev(
                "user.message",
                """{"text":"看這張","msg_id":"m1","attachments":[{"name":"a.jpg",""" +
                    """"path":"/up/a.jpg","bytes":"12","mime":"image/jpeg"}]}""",
            ),
        )
        val m = t.items.filterIsInstance<TraceItem.UserMsg>().first()
        assertEquals("看這張", m.text)          // 本文不含路徑
        assertEquals(1, m.attachments.size)
        assertEquals("a.jpg", m.attachments[0].name)
        assertTrue(m.attachments[0].isImage)
        assertEquals(12L, m.attachments[0].bytes)
        // 沒有附件欄位就是空清單，不是 null
        val none = run(ev("user.message", """{"text":"x","msg_id":"m2"}"""))
        assertTrue(none.items.filterIsInstance<TraceItem.UserMsg>().first().attachments.isEmpty())
    }

    @Test
    fun `打字回覆也解掉掛著的 inline 提問`() {
        // 2026-09-05：[[ASK:]] 的規約是「下一則輸入當答案」，但先前只有點按鈕會
        // 標已答——直接打字的話「等你回答」那條 bar 永遠釘著不消失
        val t = run(
            ev("reply.final", """{"markdown":"要選哪個？","ask":{"title":"怎麼做",
                "choices":[{"id":"A","label":"A"},{"id":"B","label":"B"}]}}"""),
            ev("user.message", """{"text":"用第三種做法","msg_id":"m1"}"""),
        )
        val ask = t.items.filterIsInstance<TraceItem.AskItem>().single()
        assertTrue(!ask.pending)
        assertEquals("用第三種做法", ask.answeredText)
    }

    @Test
    fun `伺服器停著在等的提問不被打字解掉`() {
        // 那種要走正式回填（有自己的 resolve 事件），亂標會讓確認框看起來已處理
        val t = run(
            ev("ask.request", """{"ask_id":"srv-1","kind":"confirm","title":"要刪嗎",
                "body":"","raw":"rm -rf x","choices":[]}"""),
            ev("user.message", """{"text":"等等","msg_id":"m1"}"""),
        )
        val ask = t.items.filterIsInstance<TraceItem.AskItem>().single()
        assertTrue(ask.pending)
    }
}
