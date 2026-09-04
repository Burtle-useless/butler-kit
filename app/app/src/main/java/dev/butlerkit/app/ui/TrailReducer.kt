package dev.butlerkit.app.ui

import dev.butlerkit.app.net.AskRequest
import dev.butlerkit.app.net.BgTask
import dev.butlerkit.app.net.HistoryFile
import dev.butlerkit.app.net.HistoryMsg
import dev.butlerkit.app.net.ServerEvent
import dev.butlerkit.app.net.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一條對話的軌跡狀態：畫面上那一串、還沒定稿的串流、忙不忙、狀態列、背景工作。
 *
 * **每個對話各一份**，包含串流緩衝。先前串流緩衝只有全域一份（`ChatState.streaming`），
 * 於是背景對話的定稿只能拿伺服器那份截到 400 字的文字、切走再切回來尾段就不見
 * ——都是「狀態放錯地方」的病。放在這裡之後，reducer 不需要知道哪條對話正在被看。
 */
data class ConvTrail(
    val items: List<TraceItem> = emptyList(),
    /** 這一輪還沒定稿的串流文字。 */
    val streaming: String = "",
    /** 這一步的思考尾段，狀態列用；定稿就清。 */
    val thinkingTail: String = "",
    /** 這一輪（自 turn.start 起）定稿過任何回覆文字沒有。決定 reply.final 要不要畫。 */
    val committed: Boolean = false,
    val busy: Boolean = false,
    val status: TurnStatus? = null,
    val bgTasks: List<BgTask> = emptyList(),
)

/**
 * reducer 算不出來、要 ViewModel 動手的副作用。reducer 本身不碰網路、不碰 Android。
 */
sealed interface Effect {
    data class Mood(val mood: PetMood, val decayMs: Long = 0) : Effect
    /** 擱著的檔案卡片可以落地了（回合收工）。 */
    data object FlushOffers : Effect
    /** 助理傳了檔案：要不要先擱著由 ViewModel 看該對話忙不忙決定。 */
    data class Offer(val offer: TraceItem.FileOffer) : Effect
    data class Rename(val title: String) : Effect
    data object RefreshAgenda : Effect
    data object DeviceRequest : Effect
    /** 本地軌跡已不可信（斷層、伺服器重啟），清掉用 snapshot 重建。 */
    data object Reload : Effect
}

data class Reduced(val trail: ConvTrail, val effects: List<Effect> = emptyList())

/**
 * 事件 → 軌跡的純函式。ChatViewModel 只負責把結果存回去、投影到畫面、執行副作用。
 *
 * 為什麼抽出來：這段邏輯三週內在 ViewModel 裡被日期化註解記錄了八次回歸，而它
 * 從來沒有一支測試——因為它跟 Prefs、ButlerClient、viewModelScope 綁在一起，
 * 測不動。純函式版可以用事件序列寫回歸測試（`TrailReducerTest`）。
 *
 * 渲染規則（跟電腦版 chat.js 對齊，兩邊看的是同一條對話）：
 *  1. 使用者訊息一律等 `user.message` 回音才畫。
 *  2. 段落以 `step.commit` 為準定稿；本地串流非空就用本地那份（完整），
 *     否則用事件帶的文字（伺服器現在一律帶完整文字）。
 *  3. `reply.final` 只做兩種收尾：還有沒定稿的串流殘留、或這一輪一個字都沒落地過。
 *  4. busy 只在 `turn.done`／`error`／`turn.end ok=false` 清掉——`turn.end` 與
 *     `reply.final` 每一輪都會發，續跑期間不能當閒置。
 *  5. 已經打出來的字永遠留著：turn.start、error、turn.end ok=false 都先把串流釘進軌跡。
 */
object TrailReducer {

    fun reduce(t: ConvTrail, ev: ServerEvent): Reduced {
        val fx = mutableListOf<Effect>()
        var n = t
        fun append(item: TraceItem) { n = n.copy(items = n.items + item) }
        fun pinStreaming() {
            replyOrNull(ev.turnId, n.streaming, ev.atMs)?.let(::append)
        }

        // 標題更新走 status 事件的暗號（conv_renamed:xxx）
        ev.str("note").takeIf { it.startsWith("conv_renamed:") }?.let {
            fx += Effect.Rename(it.removePrefix("conv_renamed:"))
        }

        when (ev.type) {
            "user.message" -> append(
                TraceItem.UserMsg(
                    ev.turnId, ev.str("text"),
                    msgId = ev.str("msg_id"), queued = ev.bool("queued"),
                    steered = ev.bool("steered"),
                    atMs = ev.atMs, attachments = ev.attachments(),
                ),
            )

            // 排著的訊息被讀進這一輪（taken）或隨停止一起取消（dropped）。
            // 只改那幾則的標記，不動內容。
            "message.taken", "message.dropped" -> {
                val ids = ev.strList("msg_ids").toSet()
                val gone = ev.type == "message.dropped"
                n = n.copy(items = n.items.map { item ->
                    if (item is TraceItem.UserMsg && item.msgId in ids) {
                        item.copy(queued = false, dropped = gone)
                    } else {
                        item
                    }
                })
            }

            "agenda.changed" -> fx += Effect.RefreshAgenda

            "file.offer" -> fx += Effect.Offer(
                TraceItem.FileOffer(
                    turnId = ev.turnId,
                    fileId = ev.str("file_id"),
                    name = ev.str("name"),
                    // 伺服器對可傳檔案設了 256MB 上限，Int 裝得下
                    bytes = ev.int("bytes").toLong(),
                    note = ev.str("note"),
                    mime = ev.str("mime"),
                ),
            )

            "device.request" -> fx += Effect.DeviceRequest

            // 提問進軌跡而不是彈視窗：助理問問題時人多半不在 App 裡，
            // Dialog 回來就沒了，軌跡上這一列會一直等在那。
            "ask.request" -> append(TraceItem.AskItem(ev.turnId, AskRequest.from(ev)))

            // 已解決：可能是這台回的、別台回的，也可能是逾時或被停止。
            "ask.resolved" -> {
                val askId = ev.str("ask_id")
                val choice = ev.str("choice_id")
                n = n.copy(items = n.items.map { item ->
                    if (item is TraceItem.AskItem && item.req.askId == askId && item.pending) {
                        item.copy(answeredChoiceId = choice)
                    } else {
                        item
                    }
                })
            }

            // 新回合：還沒定稿的串流先釘進軌跡（打出來的就留著），再清空預覽。
            "turn.start" -> {
                pinStreaming()
                n = n.copy(
                    committed = false, busy = true, streaming = "", thinkingTail = "",
                    // 壓縮完到下一則心跳之間有兩秒空窗，階段旗標不抹會賴在正事上
                    status = n.status?.copy(phase = "", note = ""),
                )
                // 上一輪若沒收到收工事件（斷線、伺服器重啟）會有卡片還擱著，先倒乾淨
                fx += Effect.FlushOffers
                // 助理自己醒來的那一輪：沒有使用者氣泡可當脈絡，先寫一行「為什麼醒來」
                if (ev.str("origin") == "wake") {
                    val reason = runCatching {
                        ev.data["wake"]?.jsonObject?.let(BgTask::from)
                    }.getOrNull()
                    append(TraceItem.WakeNote(ev.turnId, wakeNoteText(reason), ev.atMs))
                }
                fx += Effect.Mood(PetMood.Thinking)
            }

            // 每一輪的結尾（續跑、壓縮核對各一則）。只有 ok=false 才算真的閒下來：
            // 伺服器在 wake 票失效那條路只發它，不補 turn.done 的話忙碌旗標卡死。
            "turn.end" -> {
                n = n.copy(thinkingTail = "")
                if (ev.data["ok"]?.jsonPrimitive?.booleanOrNull == false) {
                    pinStreaming()
                    n = n.copy(busy = false, streaming = "")
                    fx += Effect.Mood(PetMood.Idle)
                }
            }

            // 整則訊息收工。最後一則 reply.final 一定比它早到，擱著的卡片此時落地
            "turn.done" -> {
                n = n.copy(busy = false, streaming = "", thinkingTail = "")
                fx += Effect.FlushOffers
            }

            "thinking.delta" -> {
                n = n.copy(thinkingTail = (n.thinkingTail + ev.str("d")).takeLast(400))
                fx += Effect.Mood(PetMood.Thinking)
            }

            "text.delta" -> {
                n = n.copy(streaming = n.streaming + ev.str("d"))
                fx += Effect.Mood(PetMood.Talking)
            }

            "tool.call" -> {
                val call = ToolCall(
                    tool = ev.str("tool"), icon = ev.str("icon"),
                    summary = ev.str("summary"), raw = ev.str("raw"),
                    dangerous = ev.bool("dangerous"),
                    kind = ev.str("kind"), added = ev.int("added"),
                    removed = ev.int("removed"), file = ev.str("file"),
                )
                n = n.copy(items = appendTool(n.items, ev.turnId, call))
                fx += Effect.Mood(PetMood.Working)
            }

            // 這一步定稿：思考摘要一列、說明文字一則。文字取本地串流（完整）；
            // 沒串流過（背景對話、沒有 delta 的回覆）就用事件帶的那份。
            "step.commit" -> {
                ev.str("think_digest").takeIf { it.isNotBlank() }
                    ?.let { append(TraceItem.Thinking(ev.turnId, it)) }
                val full = n.streaming.ifBlank { ev.str("text") }
                replyOrNull(ev.turnId, full, ev.atMs)?.let {
                    append(it)
                    n = n.copy(committed = true)
                }
                n = n.copy(streaming = "", thinkingTail = "")
            }

            // 這一輪定稿。段落本身在 step.commit 就落地了，這裡只收尾兩種情況：
            // 還有沒定稿的串流殘留、或這一輪一個字都沒落地過（沒有任何 delta）。
            "reply.final" -> {
                val leftover = n.streaming.isNotBlank()
                if (leftover || !n.committed) {
                    replyOrNull(ev.turnId, ev.str("markdown"), ev.atMs)?.let {
                        append(it)
                        n = n.copy(committed = true)
                    }
                }
                AskRequest.inline(ev.data["ask"] as? JsonObject, ev.seq.toString())
                    ?.let { append(TraceItem.AskItem(ev.turnId, it)) }
                n = n.copy(streaming = "")
                fx += Effect.Mood(PetMood.Happy, decayMs = 3000)
            }

            // 出錯或被停止：已經打出來的字留著，錯誤接在後面。自己按的停止不算出錯
            "error" -> {
                pinStreaming()
                val kind = ev.str("kind")
                append(TraceItem.ErrorItem(ev.turnId, kind, ev.str("detail"), ev.atMs))
                n = n.copy(busy = false, streaming = "")
                fx += Effect.FlushOffers
                fx += if (kind == "STOPPED") Effect.Mood(PetMood.Idle)
                else Effect.Mood(PetMood.Error, decayMs = 4000)
            }

            "status" -> {
                val bg = ev.bgTasks("bg")
                n = n.copy(
                    bgTasks = bg,
                    status = TurnStatus(
                        elapsed = ev.dbl("elapsed"), model = ev.str("model"),
                        effort = ev.str("effort"), tools = ev.int("tools"),
                        ctxTokens = ev.int("ctx_tokens"), bg = bg,
                        phase = ev.str("phase"),
                        // note 只在事情發生的那一刻送一次，後續 status 不帶；
                        // 直接覆蓋會讓提示閃一下就消失，所以沿用到這輪結束
                        note = ev.str("note").ifBlank { n.status?.note.orEmpty() },
                    ),
                )
            }

            "bg.state" -> n = n.copy(bgTasks = ev.bgTasks("tasks"))

            // 離線太久，補的起點被 ring buffer 擠掉了：歷史補得回來（snapshot 讀逐字稿）
            "seq.gap" -> fx += Effect.Reload
        }
        return Reduced(n, fx)
    }

    /**
     * 歷史訊息（snapshot 或往前翻的一頁）→ 軌跡項目，舊到新。
     *
     * 每則的還原規則：
     *  - user → UserMsg。
     *  - system → WakeNote：那是伺服器補的脈絡行（「背景工作『…』完成，助理接手」），
     *    不是助理說的話，畫成頭像氣泡會像它憑空講了一句。
     *  - 其餘（assistant）→ 思考、工具、回覆、未答的選項，**照這個順序**：串流當下
     *    就是先看到 💭、再看到工具、最後才是話，歷史長得不一樣的話捲回去會覺得是
     *    另一段對話。回覆走 [replyOrNull]：只有 [[DONE]] 的空回覆不還原，否則重開
     *    App 那顆孤兒頭像又會回到畫面上。
     *
     * [files] 是同一段時間裡傳過的檔案卡片（舊到新），依 atMs 歸併插回訊息之間；
     * 時間相同時卡片排後面（伺服器把登記時間的秒數補到 59.999 就是為了這個），
     * 最後一則之後的接在尾巴。[knownFileIds] 裡的略過——那些已經由事件流放進來了。
     * 歷史沒有 turnId 可用，一律拿 [convId] 頂替。
     */
    fun fromHistory(
        convId: String,
        messages: List<HistoryMsg>,
        files: List<HistoryFile> = emptyList(),
        knownFileIds: Set<String> = emptySet(),
    ): List<TraceItem> {
        val out = mutableListOf<TraceItem>()
        var ci = 0
        fun drainCardsBefore(ms: Long) {
            while (ci < files.size && files[ci].atMs < ms) {
                val c = files[ci]
                ci++
                if (c.fileId in knownFileIds) continue
                out += TraceItem.FileOffer(
                    turnId = convId, fileId = c.fileId, name = c.name,
                    bytes = c.bytes, note = c.note, mime = c.mime,
                )
            }
        }
        messages.forEach { m ->
            drainCardsBefore(m.atMs)
            when (m.role) {
                "user" -> out += TraceItem.UserMsg(convId, m.text, atMs = m.atMs)
                "system" -> out += TraceItem.WakeNote(convId, m.text, m.atMs)
                else -> {
                    m.think.takeIf { it.isNotBlank() }
                        ?.let { out += TraceItem.Thinking(convId, it) }
                    // 從空清單開始摺：此刻 out 的尾巴是思考或上一則的回覆，
                    // 不可能是 Stage，所以第一條工具本來就會另開一段——結果跟
                    // 直接摺在 out 上一樣，但不必每加一條就複製整串
                    out += m.tools.fold(emptyList<TraceItem>()) { acc, call ->
                        appendTool(acc, convId, call)
                    }
                    replyOrNull(convId, m.text, m.atMs)?.let { out += it }
                    // 還沒回答的那組選項要跟著還原（伺服器只在未回答的那則帶 ask）
                    m.ask?.let { out += TraceItem.AskItem(convId, it) }
                }
            }
        }
        drainCardsBefore(Long.MAX_VALUE)
        return out
    }

    /**
     * 往前翻頁的游標：本地最早那個有時間的項目的 atMs。工具、思考、卡片沒有
     * 時間概念（0），跳過。一個都沒有就 null——沒東西可當基準就不翻。
     */
    fun oldestAtMs(items: List<TraceItem>): Long? =
        items.firstOrNull { it.atMs > 0L }?.atMs

    /**
     * 工具進軌跡：累積到最後一個同回合的 Stage 底下。
     * 前面沒有 Stage（CC 沒先說話就動手）就開一個匿名階段。破壞性一律獨立列。
     */
    fun appendTool(items: List<TraceItem>, turnId: String, call: ToolCall): List<TraceItem> {
        if (call.dangerous) return items + TraceItem.DangerTool(turnId, call)
        val last = items.lastOrNull()
        return if (last is TraceItem.Stage && last.turnId == turnId) {
            items.dropLast(1) + last.copy(tools = last.tools + call)
        } else {
            items + TraceItem.Stage(turnId, "", listOf(call))
        }
    }
}
