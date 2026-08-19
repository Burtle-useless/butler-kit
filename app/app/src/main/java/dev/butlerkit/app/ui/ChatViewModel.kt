package dev.butlerkit.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.butlerkit.app.data.AgendaRepo
import dev.butlerkit.app.data.InboxRepo
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.net.AskRequest
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.ConvInfo
import dev.butlerkit.app.net.Locator
import dev.butlerkit.app.net.ServerEvent
import dev.butlerkit.app.net.SettingsInfo
import dev.butlerkit.app.net.Wire
import dev.butlerkit.app.notify.AppForeground
import dev.butlerkit.app.notify.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    val client = ButlerClient(prefs)

    private val _state = MutableStateFlow(
        ChatState(lastSeq = prefs.lastSeq, busySince = prefs.busySince),
    )
    val state: StateFlow<ChatState> = _state.asStateFlow()

    // 忙碌計時器的兩個記憶點，**每個對話各一份**（理由見 syncBusyClock）。
    // 沒有登記過的對話拿 `prefs.busySince != 0L` 當初值：上次離開時它正在忙的話
    // 就當成「一直忙到現在」，否則 ViewModel 一重建就會把讀回來的起點丟掉，
    // 使用者切出去再切回來看到的就是從頭數的秒數。
    private val wasBusyByConv = mutableMapOf<String, Boolean>()
    private val idleSinceByConv = mutableMapOf<String, Long>()

    // 每個對話各自的軌跡。事件流是全裝置一條，靠 conv_id 分流到這裡；
    // 切對話＝換一個 key 顯示，已收過的內容不會消失。
    private val itemsByConv = mutableMapOf<String, List<TraceItem>>()

    // 已經套用過的最大序號。伺服器保證序號嚴格遞增，所以「不大於它」的一律是重複。
    // 沒有這道關卡時，任何一次重播都會被當成新事件重畫一遍——兩條 SSE 並存、
    // 伺服器重啟、續傳邊界都會踩到，症狀是對話自己倒帶。
    private var appliedSeq = -1L
    private var moodDecay: Job? = null

    // 狀態列（模型、秒數、「整理記憶中」）也是每個對話一份。
    // 先前只有全域一份，同時跑兩個對話時就會互搶：背景那個的心跳蓋掉眼前這個，
    // 切回去又看到別人的殘影。ChatState 裡的那三個欄位是「當前對話的投影」。
    private val statusByConv = mutableMapOf<String, TurnStatus>()
    private val busyByConv = mutableMapOf<String, Boolean>()
    private val busySinceByConv = mutableMapOf<String, Long>()

    // 回合進行中收到的檔案卡片先擱在這（記下當初掛在哪個對話），等**整則訊息**
    // 收工（turn.done）才落地。send_file 是回合中間的工具呼叫，卡片當場插進去會被
    // 後面的回覆文字往上推，使用者得往回滑才看得到——這是他明說的
    // 「預覽要在我講完話之後」。
    //
    // 「整則訊息」不是「一輪」：自動續跑與壓縮核對各會多跑一輪，每輪都有自己的
    // turn.end 與 reply.final。先前擱置條件看的是 busyByConv，而它被 turn.end 清，
    // 於是第一輪一結束就變閒置，之後那些輪傳來的卡片全部當場落地插在中間——
    // 使用者 2026-08-19 回報「這個下載怎麼不是在最下面」。
    private val pendingOffers = mutableListOf<Pair<String, TraceItem.FileOffer>>()

    init {
        connectLoop()
        refreshConversations()
        // 模型／思考強度清單：頂欄的對話設定面板要用，不能等到使用者去過設定頁
        loadSettings()
        // 先把快取畫出來再去要新的：日常頁點開就有東西，不會空一秒
        AgendaRepo.loadCache(app)
        viewModelScope.launch { AgendaRepo.refresh(app, client) }
        viewModelScope.launch { InboxRepo.refresh(client) }
    }

    /**
     * 前景時由這裡連 SSE；進背景就把連線交還給 [ButlerService]。
     *
     * 用 `collectLatest` 而不是在迴圈裡判斷旗標：它會在 visible 變成 false 的當下
     * **取消**底下整個 block（包含正在進行的 `stream(...).collect`），這正是
     * 「同時只有一條連線」需要的動作。原本這個約定只寫在 ButlerService 的註解裡，
     * 實際上進背景時前景這條照跑，兩條一起改 `prefs.lastSeq`——畫面重複或推播漏掉，
     * 兩種症狀都出現過而且看不出關聯。
     */
    private fun connectLoop() = viewModelScope.launch {
        AppForeground.flow.collectLatest { visible ->
            if (!visible) {
                Log.i(ButlerClient.TAG, "App 進背景，前景連線讓給 ButlerService")
                return@collectLatest
            }
            streamForever()
        }
    }

    private suspend fun streamForever() = coroutineScope {
        var backoffMs = 1_000L
        Log.i(ButlerClient.TAG, "connectLoop 啟動 host=${prefs.host} lastSeq=${prefs.lastSeq}")
        while (isActive) {
            runCatching {
                client.stream(prefs.lastSeq).collect { wire ->
                    when (wire) {
                        is Wire.Conn -> {
                            if (wire.connected) {
                                backoffMs = 1_000L
                                refreshConversations()
                                // 每次（重）連上都校正一次：斷線期間可能有回合跑完，
                                // 也可能還在跑，本地 busy 一定是錯的
                                loadSnapshot(_state.value.currentConv)
                            }
                            _state.update {
                                it.copy(
                                    connected = wire.connected,
                                    connError = wire.error,
                                    pet = if (wire.connected) PetMood.Idle else PetMood.Offline,
                                )
                            }
                        }
                        is Wire.Ev -> onEvent(wire.event)
                    }
                }
            }
            if (!isActive) break
            _state.update { it.copy(connected = false, pet = PetMood.Offline) }
            Log.w(ButlerClient.TAG, "連線中斷，${backoffMs}ms 後重試")
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
        }
    }

    private fun onEvent(ev: ServerEvent) {
        val conv = ev.convId.ifBlank { _state.value.currentConv }

        // 伺服器重啟了：本地游標屬於上一個世代，畫面上的軌跡也不保證跟電腦上一致。
        // 丟掉這個對話的本地軌跡改用 snapshot 重建——snapshot 直接讀 CC 的逐字稿，
        // 是唯一的權威來源。清掉才進得去，loadSnapshot 只在本地為空時才填。
        if (ev.type == "stream.reset") {
            appliedSeq = -1L
            prefs.lastSeq = -1L
            itemsByConv.remove(conv)
            loadSnapshot(conv)
            return
        }

        // 重複事件擋在這裡。序號嚴格遞增，所以「不大於已套用過的」就是重播。
        if (ev.seq <= appliedSeq) return
        appliedSeq = ev.seq
        prefs.lastSeq = ev.seq
        val isCurrent = conv == _state.value.currentConv

        fun append(item: TraceItem) {
            itemsByConv[conv] = (itemsByConv[conv] ?: emptyList()) + item
        }

        // 標題更新走 status 事件的暗號（conv_renamed:xxx）
        ev.str("note").takeIf { it.startsWith("conv_renamed:") }?.let {
            val title = it.removePrefix("conv_renamed:")
            _state.update { s ->
                s.copy(conversations = s.conversations.map { c ->
                    if (c.id == conv) c.copy(title = title) else c
                })
            }
        }

        when (ev.type) {
            "user.message" -> append(TraceItem.UserMsg(
                ev.turnId, ev.str("text"),
                msgId = ev.str("msg_id"), queued = ev.bool("queued"),
            ))

            // 排著的訊息被讀進這一輪（taken）或隨停止一起取消（dropped）。
            // 兩者都只改那幾則的標記，不動內容——氣泡從淡的變回正常，
            // 或改口說「已取消」。
            "message.taken", "message.dropped" -> {
                val ids = ev.strList("msg_ids").toSet()
                val gone = ev.type == "message.dropped"
                itemsByConv[conv] = (itemsByConv[conv] ?: emptyList()).map { item ->
                    if (item is TraceItem.UserMsg && item.msgId in ids) {
                        item.copy(queued = false, dropped = gone)
                    } else {
                        item
                    }
                }
            }

            // 助理改了行事曆／鬧鐘／記帳。重拉一次，順帶把鬧鐘重排進 AlarmManager——
            // 少了這一步，它幫你設的鬧鐘只是資料庫裡的一行字，時間到不會響。
            "agenda.changed" -> viewModelScope.launch {
                AgendaRepo.refresh(getApplication(), client)
            }

            // 助理傳檔案來了。事件帶著發起它的 conv_id（伺服器端的傳檔工具改成
            // 一個對話一份之後才有這個值），所以卡片會落回發起的那段對話——
            // 工作對話做出來的報告不會再掉進使用者當下正在看的助理對話裡。
            "file.offer" -> {
                val offer = TraceItem.FileOffer(
                    turnId = ev.turnId,
                    fileId = ev.str("file_id"),
                    name = ev.str("name"),
                    // 伺服器對可傳檔案設了 256MB 上限，Int 裝得下，不必為它加解析器
                    bytes = ev.int("bytes").toLong(),
                    note = ev.str("note"),
                    mime = ev.str("mime"),
                )
                // 舊版伺服器不知道自己是誰叫的，conv_id 送 "-"。那不是空字串，
                // 上面的 ifBlank 接不住，卡片會掉進一個永遠不會被顯示的假對話桶。
                // 這種情況退回當前對話，維持舊行為
                val from = if (conv == NO_CONV) _state.value.currentConv else conv
                // 還在跑就先擱著，等 turn.done 才排到最後一段話後面；
                // 沒在跑代表是回合外送來的（背景任務收工），當場落地就是最下面。
                // busy 看的是**發起那個對話**在不在跑，不是眼前這個
                pendingOffers += from to offer
                if (busyByConv[from] != true) flushOffers()
                viewModelScope.launch { InboxRepo.refresh(client) }
            }

            // 助理要知道他人在哪。**完全靜默**：不進軌跡、不提示，抓完就回報——
            // 助理判斷需要位置才會問，每次都彈個東西只會讓「附近有什麼吃的」
            // 變成兩次互動。前景時是這裡在收，背景時是 ButlerService（同時只有
            // 一條連線），兩邊共用 Locator 那一份實作。
            // launch 出去的理由同 ButlerService：抓位置最多等 15 秒。
            "device.request" -> viewModelScope.launch {
                Locator.onDeviceRequest(getApplication(), client, ev)
            }

            // 提問進軌跡而不是彈視窗：助理問問題時人多半不在 App 裡，
            // Dialog 回來就沒了，軌跡上這一列會一直等在那。
            "ask.request" -> append(TraceItem.AskItem(ev.turnId, AskRequest.from(ev)))

            // 已解決：可能是這台回的、別台回的，也可能是逾時或被停止。
            // choice_id 空字串代表沒人回答，卡片會顯示「沒有回答」。
            "ask.resolved" -> {
                val askId = ev.str("ask_id")
                val choice = ev.str("choice_id")
                itemsByConv[conv] = (itemsByConv[conv] ?: emptyList()).map { item ->
                    if (item is TraceItem.AskItem && item.req.askId == askId && item.pending) {
                        item.copy(answeredChoiceId = choice)
                    } else {
                        item
                    }
                }
            }

            // 新回合要清空串流預覽（見下方狀態更新）。清之前先把還沒定稿的字
            // 釘進軌跡——這是「打出來的就留著」的最後一道防線：伺服器端已經改成
            // 每輪各自定稿，正常情況下這裡是空的，但只要有任何一條路徑漏掉，
            // 使用者看到的就是整段話憑空消失，那個代價比多留一則氣泡高得多。
            "turn.start" -> {
                if (isCurrent) {
                    replyOrNull(ev.turnId, _state.value.streaming)?.let(::append)
                }
                // 上一輪若沒收到 turn.end（斷線、伺服器重啟）就會有卡片還擱著，
                // 新回合開始前先倒乾淨，免得它跟這一輪的內容混在一起
                flushOffers()
                setMood(PetMood.Thinking)
            }

            // turn.end 與 reply.final 都不 flush：它們是「這一輪」的收尾，續跑與
            // 壓縮核對還會再來好幾輪，在那裡落地就是插在對話中間。
            // 改由 turn.done（整則訊息真的收工）flush，純工具回合由 turn.start 兜底。
            "turn.end" -> Unit

            // 整則訊息收工。最後一則 reply.final 一定比它早到（伺服器端 _turn_done
            // 排在 _run_with_recovery 之後），所以卡片穩定落在最後一段文字下面。
            "turn.done" -> flushOffers()

            "thinking.delta" -> setMood(PetMood.Thinking)

            // 回覆逐字生成中＝正在講話，嘴巴動起來
            "text.delta" -> setMood(PetMood.Talking)

            "tool.call" -> {
                val call = ToolCall(
                    tool = ev.str("tool"), icon = ev.str("icon"),
                    summary = ev.str("summary"), raw = ev.str("raw"),
                    dangerous = ev.bool("dangerous"),
                    kind = ev.str("kind"), added = ev.int("added"),
                    removed = ev.int("removed"), file = ev.str("file"),
                )
                itemsByConv[conv] = appendTool(itemsByConv[conv] ?: emptyList(), ev.turnId, call)
                setMood(PetMood.Working)
            }

            "step.commit" -> {
                ev.str("think_digest").takeIf { it.isNotBlank() }
                    ?.let { append(TraceItem.Thinking(ev.turnId, it)) }
                // 這一步的說明文字定稿。
                //
                // **定稿後長得要跟串流時一模一樣**（同一種 Reply 氣泡），這是使用者
                // 明說的要求：「要就是打出來的就留著，要不然就不要打出來」。先前定稿
                // 成 Stage 的灰色小字，畫面上一整段白色氣泡會突然縮成一行淡灰字，
                // 那就是他看到的「打了一堆又收回去」。
                //
                // 內容取本地累積的串流而不是事件裡的 text：伺服器那份截到 400 字，
                // 拿它定稿等於當著使用者的面把已經打出來的話砍掉後半段。
                // 只有背景對話（沒有本地串流可用）才退回用事件那份。
                if (ev.str("text").isNotBlank()) {
                    val full = if (isCurrent) {
                        _state.value.streaming.ifBlank { ev.str("text") }
                    } else {
                        ev.str("text")
                    }
                    replyOrNull(ev.turnId, full)?.let(::append)
                }
            }

            "reply.final" -> {
                replyOrNull(ev.turnId, ev.str("markdown"))?.let(::append)
                // 選項跟著這則訊息一起來。它不會逾時、不會被收回——伺服器已經
                // 收工了，按下去等於送一則新訊息（見 answerAsk 的 inline 分支）。
                AskRequest.inline(ev.data["ask"] as? JsonObject, ev.seq.toString())
                    ?.let { append(TraceItem.AskItem(ev.turnId, it)) }
                setMood(PetMood.Happy, decayMs = 3000)
            }

            "error" -> {
                append(TraceItem.ErrorItem(ev.turnId, ev.str("kind"), ev.str("detail")))
                // 出錯也算講完，不然那一輪傳過來的檔案會一直卡著不出現
                flushOffers()
                setMood(PetMood.Error, decayMs = 4000)
            }

            // 離線太久，要補的起點已經被伺服器的 ring buffer 擠掉了。
            //
            // 原本只 append 一則「補不回來了」就算數，但那等於請使用者自己盯著
            // 一段缺口——而歷史其實補得回來：snapshot 直接讀 CC 的逐字稿，
            // 是比 ring buffer 更權威的來源。所以這裡走跟 stream.reset 同一套
            // 復原動作（清本地軌跡 → 用 snapshot 重建），兩條失效路徑共用一套
            // 邏輯才不會日後長歪。
            //
            // 不 append 提示是刻意的：那則錯誤會讓 itemsByConv 變成非空，
            // 而 loadSnapshot 的填充條件正是「送出請求時本地為空」——留著提示
            // 就等於親手擋掉自己的復原。缺的工具軌跡補不回來（逐字稿沒存），
            // 但話回得來，這個取捨遠好過一片空白。
            //
            // appliedSeq 不重設：斷層之後的事件仍是新的，照常往前走。
            "seq.gap" -> {
                itemsByConv.remove(conv)
                loadSnapshot(conv)
            }
        }

        // 狀態列的歸屬。這一段對每個對話都要記，不能只記眼前這個：
        // 背景對話跑完了要能反映在它自己的狀態上，切回去才不是一片空白，
        // 而它跑到一半的「整理記憶中」也不該出現在使用者正在看的那一頁。
        when (ev.type) {
            "turn.start" -> {
                busyByConv[conv] = true
                // 壓縮完到下一則心跳之間有兩秒空窗，階段旗標不抹會賴在正事上
                statusByConv[conv]?.let { statusByConv[conv] = it.copy(phase = "", note = "") }
            }
            // 只有整則訊息收工（或出錯）才算閒下來。**不要**把 turn.end／reply.final
            // 加回來：那兩個每一輪都會發，續跑期間旗標會被清成閒置，於是送出鍵提早
            // 從停止變回箭頭，回合中傳來的檔案卡片也會誤判成「回合外送來的」而當場落地。
            // 伺服器沒發 turn.done 的情況（舊版、行程被砍）由重連時的 snapshot 校正，
            // 那份 busy 讀的是伺服器真實的 task 狀態。
            "turn.done", "error" -> busyByConv[conv] = false
            "status" -> statusByConv[conv] = TurnStatus(
                elapsed = ev.dbl("elapsed"), model = ev.str("model"),
                effort = ev.str("effort"), tools = ev.int("tools"),
                ctxTokens = ev.int("ctx_tokens"), bg = ev.strList("bg"),
                phase = ev.str("phase"),
                // note 只在事情發生的那一刻送一次，後續 status 不帶；
                // 直接覆蓋會讓提示閃一下就消失，所以沿用到這輪結束
                note = ev.str("note").ifBlank { statusByConv[conv]?.note.orEmpty() },
            )
        }

        _state.update { s ->
            var next = s.copy(lastSeq = ev.seq)
            if (isCurrent) {
                // 狀態一律從上面那份 per-conv 紀錄投影過來，不在這裡另算一次，
                // 免得兩邊邏輯慢慢長歪
                next = when (ev.type) {
                    "turn.start" -> next.copy(
                        busy = true, streaming = "", thinkingTail = "",
                        status = statusByConv[conv],
                    )
                    // busy 交給 turn.done，理由同上面那份 per-conv 紀錄：
                    // 這裡清掉的話，續跑期間送出鍵會提早從停止變回箭頭
                    "turn.end" -> next.copy(thinkingTail = "")
                    // 思考預覽定稿後就清掉，不然會跟剛釘上去的 Thinking 那行重複。
                    //
                    // streaming 只在「這則帶了 text」時清——那代表上面剛把同一段話
                    // 定稿成 Reply 了，清掉的是預覽、留在畫面上的是定稿，兩者內容
                    // 與樣式相同，使用者不會看到任何跳動。
                    // 沒帶 text（有思考但沒動工具）就不能清：那段話還沒有落點，
                    // 清了就是憑空蒸發，要留到 reply.final 才定稿。
                    "step.commit" -> next.copy(
                        thinkingTail = "",
                        streaming = if (ev.str("text").isNotBlank()) "" else s.streaming,
                    )
                    "thinking.delta" ->
                        next.copy(thinkingTail = (s.thinkingTail + ev.str("d")).takeLast(400))
                    "text.delta" -> next.copy(streaming = s.streaming + ev.str("d"))
                    "reply.final" -> next.copy(streaming = "")
                    "turn.done" -> next.copy(busy = false, streaming = "", thinkingTail = "")
                    "error" -> next.copy(busy = false, streaming = "")
                    "status" -> next.copy(status = statusByConv[conv])
                    else -> next
                }
                // 待建立的新對話畫面必須保持空白：這時 currentConv 還指著舊對話，
                // 舊對話若有背景回合在跑，內容會突然冒出來
                if (!next.pendingNew) {
                    next = next.copy(items = itemsByConv[conv] ?: emptyList())
                }
            }
            next
        }
        syncBusyClock(
            if (ev.type == "status") (ev.dbl("elapsed") * 1000).toLong() else null,
        )
        // 計時器只算得出「眼前這個對話」的起點，算完存回去，切走再切回來才不會歸零
        busySinceByConv[_state.value.currentConv] = _state.value.busySince
    }

    /**
     * 維護狀態列計時器的起點。
     *
     * 秒數本地推算而不是直接顯示伺服器的 elapsed，因為那個值會歸零三次：續跑與
     * 重試每輪都是新的 run_turn、斷線續傳會整批重播舊的 status（畫面上就是倒退
     * 重數）、Activity 被回收後什麼都不記得。這裡守住「從我送出到現在」這個
     * 使用者真正在看的量。
     *
     * [serverElapsedMs] 只在 status 事件帶上，用於冷啟動回填——App 剛開起來就發現
     * 電腦上還在跑時，從 0 開始數是騙人的，要用伺服器的真值往前推。
     */
    private fun syncBusyClock(serverElapsedMs: Long?) {
        val s = _state.value
        val conv = s.currentConv
        val now = System.currentTimeMillis()
        // 這兩個記憶點必須跟著對話走。原本全域各一份，於是：對話 A 正在跑，切去
        // B 待超過 BUSY_GAP_MS，B 的閒置把 wasBusy 清掉、idleSince 也推過門檻，
        // 切回 A 時下面的 keep 判成 false，計時器從 0 重數——而 A 那件工作
        // 從頭到尾根本沒停過。起點（busySinceByConv）早就分對話存了，
        // 判斷用的依據卻沒有，兩者對不起來。
        val wasBusy = wasBusyByConv[conv] ?: (prefs.busySince != 0L)
        val idleSince = idleSinceByConv[conv] ?: 0L
        if (!s.busy) {
            if (wasBusy) {
                wasBusyByConv[conv] = false
                idleSinceByConv[conv] = now
            }
            // 起點留在記憶體裡供續跑沿用，但不留給下次冷啟動——那時它已經過期了
            if (prefs.busySince != 0L) prefs.busySince = 0L
            return
        }
        // 續跑與重試之間 busy 會短暫掉成 false 再回來。那不是新的一段工作，
        // 每次都重設起點的話，長任務的計時器會每續跑一輪就歸零一次。
        val keep = s.busySince != 0L &&
            (wasBusy || (idleSince != 0L && now - idleSince <= BUSY_GAP_MS))
        wasBusyByConv[conv] = true
        if (keep) return
        val start = now - (serverElapsedMs ?: 0L)
        prefs.busySince = start
        _state.update { it.copy(busySince = start) }
    }

    /** 設桌寵心情。Happy/Error 這類「瞬間表情」給 decayMs，時間到自動退回 Idle。 */
    private fun setMood(mood: PetMood, decayMs: Long = 0) {
        moodDecay?.cancel()
        _state.update { it.copy(pet = mood) }
        if (decayMs > 0) {
            moodDecay = viewModelScope.launch {
                delay(decayMs)
                _state.update {
                    it.copy(pet = if (it.busy) PetMood.Thinking else PetMood.Idle)
                }
            }
        }
    }

    /**
     * 工具進軌跡：累積到最後一個同回合的 Stage 底下（cc-bot 的 _seg_add）。
     * 前面沒有 Stage（CC 沒先說話就動手）就開一個匿名階段。
     * 破壞性一律獨立列，不併入統計。
     */
    private fun appendTool(
        items: List<TraceItem>, turnId: String, call: ToolCall,
    ): List<TraceItem> {
        if (call.dangerous) return items + TraceItem.DangerTool(turnId, call)
        val last = items.lastOrNull()
        return if (last is TraceItem.Stage && last.turnId == turnId) {
            items.dropLast(1) + last.copy(tools = last.tools + call)
        } else {
            items + TraceItem.Stage(turnId, "", listOf(call))
        }
    }

    /**
     * 把擱著的檔案卡片倒進各自的對話軌跡。
     *
     * 不分回合一次全倒：同時跑兩個回合本來就少見，而每張卡片都記著自己當初
     * 掛在哪條對話，就算真的交錯也不會跑到別人的軌跡上。
     */
    private fun flushOffers() {
        if (pendingOffers.isEmpty()) return
        for ((conv, offer) in pendingOffers) {
            itemsByConv[conv] = (itemsByConv[conv] ?: emptyList()) + offer
        }
        val here = _state.value.currentConv
        val touched = pendingOffers.any { it.first == here }
        pendingOffers.clear()
        // 待建立的新對話畫面要保持空白，這時不能把東西畫上去
        if (touched && !_state.value.pendingNew) {
            _state.update { it.copy(items = itemsByConv[here] ?: emptyList()) }
        }
    }

    // ── 對話管理 ──────────────────────────────────────────────────────────
    fun refreshConversations() = viewModelScope.launch {
        client.listConversations().onSuccess { list ->
            _state.update { it.copy(conversations = list) }
        }
    }

    /**
     * 這個對話現在攤在畫面上，收掉它累積的通知。
     *
     * 三個進入點都要叫：[switchConversation] 涵蓋大部分情況，但 [enterQiTab] 與
     * [enterCcTab] 在「已經停在那個對話」時會提前 return，不補這一刀的話從通知
     * 點進來又切回同一頁的路徑就清不掉。
     */
    private fun markRead(convId: String) {
        Notifier.clearConv(getApplication(), convId)
    }

    fun switchConversation(convId: String) {
        markRead(convId)
        // 走之前先把這個對話的計時起點收好，切回來才接得上原本的秒數
        val from = _state.value.currentConv
        if (from != convId) busySinceByConv[from] = _state.value.busySince
        _state.update {
            it.copy(
                currentConv = convId,
                pendingNew = false,
                items = itemsByConv[convId] ?: emptyList(),
                streaming = "", thinkingTail = "",
                // 狀態列換成新對話自己的那份。少了這三行，切過去看到的是
                // 上一個對話的「整理記憶中」跟它的秒數
                status = statusByConv[convId],
                busy = busyByConv[convId] ?: false,
                busySince = busySinceByConv[convId] ?: 0L,
            )
        }
        loadSnapshot(convId)
    }

    /**
     * 拉對話全貌。事件流只帶「連上之後發生的事」，歷史得另外要——
     * 否則切對話、冷啟動、重裝 App 看到的都是空白畫面。
     * 同時把 busy 校正成伺服器真值：本地 busy 只有 turn.start 會設，
     * 重連時那個事件早就過去了，UI 會顯示空閒但其實還在跑。
     */
    private fun loadSnapshot(convId: String) = viewModelScope.launch {
        // 送出請求「之前」本地有幾則。這個數字是下面判斷的依據，不能等回應到了再問——
        // 一次 HTTP 來回夠事件流塞進好幾則即時訊息，那時再看就已經不是空的了。
        val before = itemsByConv[convId]?.size ?: 0
        client.snapshot(convId).onSuccess { snap ->
            // 只有「送出請求時本地還沒有任何內容」才用歷史填充：
            // 本來就有內容代表歷史早就在了，覆蓋回去會把即時訊息蓋掉。
            //
            // 原本這裡問的是回應到達當下的 `isNullOrEmpty()`，那是個競態：
            // 冷啟動或重連時 snapshot 還在路上，事件流先送到一則即時訊息，
            // 條件就變成 false，**整段歷史於是永遠不載入**——使用者看到的是
            // 一個只有最新一句話的對話，往上滑什麼都沒有。
            if (before == 0 && snap.messages.isNotEmpty()) {
                // 助理那側走 replyOrNull：歷史裡那些只有 [[DONE]] 的空回覆
                // 不還原，否則重開 App 那顆孤兒頭像又會回到畫面上
                //
                // 思考要跟著還原，而且要排在該回合的回覆**前面**——串流當下就是
                // 先看到 💭 再看到話，歷史長得不一樣的話捲回去會覺得是另一段對話。
                // 歷史沒有 turnId 可用，跟 UserMsg 一樣拿 convId 頂替。
                // 等待期間事件流塞進來的那些接在歷史後面，不是丟掉。
                // 它們是「比歷史更新」的訊息，順序上本來就該在最後。
                val live = itemsByConv[convId].orEmpty()
                // 檔案卡片要跟訊息交錯排回去。卡片不在 CC 的逐字稿裡（那是 butler
                // 自己造的東西），所以伺服器另外給一份，兩邊都帶 epoch 毫秒。
                // 不補的話 App 一重啟、或伺服器一重啟，對話裡的下載框就整排消失——
                // 使用者 2026-08-18 回報「更新之後下載框會不見」。
                //
                // messages 與 files 都是舊到新，走一次歸併就夠。時間相同時卡片排後面
                // （伺服器把登記時間的秒數補到 59.999 就是為了這個）：卡片的語意本來
                // 就是「這一輪講完之後才落地」。
                val cards = snap.files
                // 已經由事件流放進來的那些不能再放一次。冷啟動時 snapshot 還在路上，
                // 助理剛好傳了檔案的話那張卡片會同時走即時與歷史兩條路——登記早就存在
                // 伺服器上了，snapshot 當然也會帶回來。pendingOffers 裡的還沒落地，
                // 但等回合結束就會，一樣要算進去。
                val known = (
                    live.filterIsInstance<TraceItem.FileOffer>().map { it.fileId } +
                        pendingOffers.filter { it.first == convId }.map { it.second.fileId }
                    ).toSet()
                var ci = 0
                val history = mutableListOf<TraceItem>()
                fun drainCardsBefore(ms: Long) {
                    while (ci < cards.size && cards[ci].atMs < ms) {
                        val c = cards[ci]
                        ci++
                        if (c.fileId in known) continue
                        // 歷史沒有 turnId 可用，跟 UserMsg 一樣拿 convId 頂替
                        history += TraceItem.FileOffer(
                            turnId = convId, fileId = c.fileId, name = c.name,
                            bytes = c.bytes, note = c.note, mime = c.mime,
                        )
                    }
                }
                snap.messages.forEach { m ->
                    drainCardsBefore(m.atMs)
                    if (m.role == "user") {
                        history += TraceItem.UserMsg(convId, m.text)
                    } else {
                        m.think.takeIf { it.isNotBlank() }
                            ?.let { history += TraceItem.Thinking(convId, it) }
                        replyOrNull(convId, m.text)?.let { history += it }
                        // 還沒回答的那組選項要跟著還原，不然重開 App 就看不到
                        // 助理剛才問了什麼（伺服器只在未回答的那則帶 ask）
                        m.ask?.let { history += TraceItem.AskItem(convId, it) }
                    }
                }
                // 最後一則訊息之後才傳的檔案
                drainCardsBefore(Long.MAX_VALUE)
                // 還在等你回答的提問。排在排隊訊息之前：提問屬於「還沒結束的那一輪」，
                // 排隊訊息則是等這一輪做完才會被讀走的，時序上在後面。
                // 沒補回來的話伺服器會一路等到逾時、當成使用者拒絕，那件事就被擋掉了。
                val liveAskIds = live.filterIsInstance<TraceItem.AskItem>()
                    .map { it.req.askId }.toSet()
                snap.asks.forEach { a ->
                    if (a.askId !in liveAskIds) {
                        history += TraceItem.AskItem(turnId = convId, req = a)
                    }
                }
                // 排著還沒輪到的訊息，接在最後面——它們是最新的，而且同樣不在
                // 逐字稿裡（還沒送進 CC）。不補的話使用者會看不到自己剛剛送出了
                // 什麼，事情卻照跑，最後助理回覆一則他不知道自己問過的問題。
                // 已經由事件流放進來的不重複放，理由同上面的檔案卡片。
                val liveMsgIds = live.filterIsInstance<TraceItem.UserMsg>()
                    .map { it.msgId }.toSet()
                snap.pending.forEach { p ->
                    if (p.msgId !in liveMsgIds) {
                        history += TraceItem.UserMsg(
                            turnId = convId, text = p.text,
                            msgId = p.msgId, queued = true,
                        )
                    }
                }
                itemsByConv[convId] = history + live
            }
            // 伺服器才知道背景那個對話到底還在不在跑，這是唯一的真值來源
            busyByConv[convId] = snap.busy
            _state.update { s ->
                if (s.currentConv != convId) s      // 使用者已經切走了，別亂改畫面
                else s.copy(
                    items = itemsByConv[convId] ?: emptyList(),
                    busy = snap.busy,
                    pet = if (snap.busy) PetMood.Thinking else s.pet,
                    convStatus = ConvStatus(
                        model = snap.model, effort = snap.effort,
                        modelOverride = snap.modelOverride,
                        effortOverride = snap.effortOverride,
                        cwd = snap.cwd, ctxTokens = snap.ctxTokens,
                        ctxLimit = snap.ctxLimit,
                    ),
                )
            }
            // busy 被伺服器真值校正過了，計時器要跟著校正——冷啟動時這是它第一次
            // 知道「電腦上其實還在跑」
            syncBusyClock(null)
        }
    }

    /**
     * 按「新對話」只清畫面，不跟伺服器要 id——真正建立延到第一則訊息送出時
     * （見 [send]）。先前是按一下就落地一筆，清單很快堆滿從沒講過話的空對話。
     */
    fun newConversation() {
        _state.update {
            it.copy(
                pendingNew = true, items = emptyList(),
                streaming = "", thinkingTail = "",
                // 空白畫面上不該掛著舊對話的秒數與階段
                status = null, busy = false, busySince = 0L,
            )
        }
    }

    fun deleteConversation(convId: String) = viewModelScope.launch {
        client.deleteConversation(convId).onSuccess {
            itemsByConv.remove(convId)
            statusByConv.remove(convId)
            busyByConv.remove(convId)
            busySinceByConv.remove(convId)
            wasBusyByConv.remove(convId)
            idleSinceByConv.remove(convId)
            if (ccConv == convId) ccConv = null
            refreshConversations()
            if (_state.value.currentConv == convId) switchConversation(DEFAULT_CONV)
        }
    }

    // ── 分頁 ─────────────────────────────────────────────────────────────
    // 助理頁與 cc-bot 頁看的是同一條事件流的不同對話：助理頁永遠是 DEFAULT_CONV，
    // cc-bot 頁是使用者上次選的那個。分頁切換時把 currentConv 換過去就好，
    // 不需要兩套狀態——itemsByConv 本來就是以 conv_id 為 key 的多對話結構。

    /** cc-bot 頁上次看的對話。null＝還沒選過（該頁顯示空狀態）。 */
    private var ccConv: String? = null

    fun enterQiTab() {
        // pendingNew 也要一併退掉：那是 cc-bot 頁的待建立狀態，
        // 帶進助理頁會讓助理的訊息跑去開一條新的 cc-bot 對話
        val s = _state.value
        markRead(DEFAULT_CONV)
        if (s.currentConv != DEFAULT_CONV || s.pendingNew) switchConversation(DEFAULT_CONV)
    }

    fun enterCcTab() {
        if (_state.value.pendingNew) return   // 正在開新對話，別把它切走
        // 沒選過就挑清單第一個（排除助理的專屬對話）；一個都沒有就維持原樣，
        // 由畫面顯示「還沒有對話」而不是誤把助理的內容當成 cc-bot 的
        val target = ccConv
            ?: _state.value.conversations.firstOrNull { it.id != DEFAULT_CONV }?.id
            ?: return
        ccConv = target
        markRead(target)
        if (_state.value.currentConv != target) switchConversation(target)
    }

    fun switchCcConversation(convId: String) {
        ccConv = convId
        switchConversation(convId)
        // 剛從電腦接管過來的 session 是這一刻才誕生的對話，清單上還沒有它。
        // 不重拉的話抽屜會是空的，看起來像接管失敗。
        refreshConversations()
    }

    // ── 附件 ─────────────────────────────────────────────────────────────
    /**
     * 挑好的檔案立刻上傳，不等到送訊息時才傳。
     *
     * 這樣使用者在打字的時候上傳已經在跑，按送出不會卡住；
     * 而且傳失敗當場就看得到，不會發生「訊息送出去了但助理找不到檔案」。
     */
    fun attach(uri: Uri) = viewModelScope.launch {
        val cr = getApplication<Application>().contentResolver
        val name = queryDisplayName(uri) ?: "file"
        _state.update {
            it.copy(uploading = it.uploading + name, uploadError = null)
        }
        val result = runCatching {
            val bytes = withContext(Dispatchers.IO) {
                cr.openInputStream(uri)?.use { s -> s.readBytes() }
                    ?: error("讀不到這個檔案")
            }
            if (bytes.size > MAX_UPLOAD_BYTES) {
                error("檔案太大（${bytes.size / (1024 * 1024)}MB，上限 32MB）")
            }
            bytes
        }.mapCatching { bytes ->
            val mime = cr.getType(uri) ?: "application/octet-stream"
            client.uploadFile(name, bytes, mime).getOrThrow() to bytes.size.toLong()
        }
        _state.update { s ->
            val rest = s.uploading - name
            result.fold(
                onSuccess = { (path, size) ->
                    s.copy(
                        uploading = rest,
                        attachments = s.attachments + Attachment(name, path, size),
                    )
                },
                onFailure = { e ->
                    s.copy(uploading = rest, uploadError = "$name：${e.message ?: "上傳失敗"}")
                },
            )
        }
    }

    fun removeAttachment(path: String) = _state.update {
        it.copy(attachments = it.attachments.filterNot { a -> a.path == path })
    }

    /** 從 content uri 問出顯示用的檔名。問不到就交給呼叫端給預設值。 */
    private fun queryDisplayName(uri: Uri): String? {
        val cr = getApplication<Application>().contentResolver
        return runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment
    }

    /**
     * 把附件併進訊息文字。
     *
     * 檔案本體不進對話，只給**電腦上的絕對路徑**——助理用內建的 Read 工具讀，
     * 圖片它看得懂。明講一句「手機傳上來的」是因為光給路徑，
     * 模型可能會先去猜這是誰的檔案、要不要動它。
     */
    private fun composeWithAttachments(text: String, files: List<Attachment>): String {
        if (files.isEmpty()) return text
        val list = files.joinToString("\n") { it.path }
        val head = if (text.isBlank()) "" else "$text\n\n"
        return "${head}我從手機傳了這些檔案給你，直接讀：\n$list"
    }

    // ── 訊息 ─────────────────────────────────────────────────────────────
    fun send(text: String) {
        val files = _state.value.attachments
        if (text.isBlank() && files.isEmpty()) return
        val body = composeWithAttachments(text.trim(), files)
        // 送出即清空附件列：路徑已經寫進訊息了，留著會在下一則重複附上
        if (files.isNotEmpty()) _state.update { it.copy(attachments = emptyList()) }
        // 待建立的新對話：這時候才跟伺服器要 id，拿到再送
        if (_state.value.pendingNew) {
            viewModelScope.launch {
                client.createConversation()
                    .onSuccess { id ->
                        ccConv = id
                        _state.update { it.copy(pendingNew = false, currentConv = id) }
                        refreshConversations()
                        deliver(id, body)
                    }
                    .onFailure { e -> reportSendFailure(_state.value.currentConv, e) }
            }
            return
        }
        deliver(_state.value.currentConv, body)
    }

    /** 實際送出。不在本地先畫使用者訊息——伺服器會發 user.message 回音事件，
     *  所有裝置（含自己）都靠那個事件渲染，多裝置同步因此免費。 */
    private fun deliver(conv: String, text: String) {
        // 計時器從按下送出就開始跑，不等伺服器的第一則 status——那要兩秒後才到，
        // 而使用者的等待從按下去那一刻就開始了
        busyByConv[conv] = true
        _state.update { it.copy(busy = true) }
        syncBusyClock(null)
        busySinceByConv[conv] = _state.value.busySince
        viewModelScope.launch {
            client.sendMessage(conv, text).onFailure { e -> reportSendFailure(conv, e) }
        }
    }

    private fun reportSendFailure(conv: String, e: Throwable) {
        val err = TraceItem.ErrorItem(conv, "SEND_FAILED", e.message ?: "送出失敗")
        // 新對話還沒建起來就失敗了。這時 [conv] 是**上一個**對話的 id
        // （newConversation 只清畫面，currentConv 沒動），照原路把
        // `itemsByConv[conv]` 寫回 state.items 等於把舊對話整段歷史
        // 倒進這個應該空白的新對話裡——使用者按了「新對話」卻看到上一段對話重現。
        // 只顯示錯誤本身，itemsByConv 一個字都不要碰。
        if (_state.value.pendingNew) {
            _state.update { it.copy(items = listOf(err), busy = false) }
            syncBusyClock(null)
            return
        }
        itemsByConv[conv] = (itemsByConv[conv] ?: emptyList()) + err
        _state.update { s ->
            s.copy(items = itemsByConv[conv] ?: emptyList(), busy = false)
        }
        syncBusyClock(null)
    }

    /**
     * 回答軌跡上的提問。
     *
     * 先樂觀把卡片標成已答再送出——按下去要立刻有反應，等網路來回才變樣子
     * 會讓人以為沒按到而重按。伺服器的 `ask.resolved` 隨後會再蓋一次同樣的值；
     * 真的送失敗時那則不會來，卡片停在「已送出」也比按鈕當掉好處理。
     *
     * 兩種提問走不同的路（見 [AskRequest.isInline]）：
     *   - 附在訊息上的選項：伺服器早就收工了，按下去就是**送一則新訊息**，
     *     內容是選項本身。所以它不會逾時、放多久都還按得到。
     *   - 伺服器停著在等的（破壞性指令確認）：要回到原本那一輪去，走 HTTP
     *     回填答案。這種**不能**改成非阻塞——逾時當成拒絕是安全底線。
     *
     * [text] 是自己打的回答，用來取代選項；照選項回答時傳 null。
     */
    fun answerAsk(askId: String, choiceId: String, text: String? = null) {
        val own = text?.trim()?.takeIf { it.isNotBlank() }
        if (choiceId.isBlank() && own == null) return   // 兩個都空＝什麼都沒回
        val conv = _state.value.currentConv
        itemsByConv[conv] = (itemsByConv[conv] ?: emptyList()).map { item ->
            if (item is TraceItem.AskItem && item.req.askId == askId && item.pending) {
                item.copy(answeredChoiceId = choiceId, answeredText = own)
            } else {
                item
            }
        }
        _state.update { it.copy(items = itemsByConv[conv] ?: emptyList()) }
        if (askId.startsWith(AskRequest.INLINE_PREFIX)) {
            send(own ?: choiceId)
        } else {
            viewModelScope.launch { client.answerAsk(askId, choiceId, own) }
        }
    }

    fun stop() {
        viewModelScope.launch { client.stopTurn(_state.value.currentConv) }
    }

    // ── 設定 ─────────────────────────────────────────────────────────────
    fun loadSettings() = viewModelScope.launch {
        client.getSettings().onSuccess { s ->
            _state.update { it.copy(settings = s) }
        }
    }

    fun applySettings(model: String?, effort: String?) = viewModelScope.launch {
        client.setSettings(model, effort).onSuccess { loadSettings() }
    }

    /**
     * 只改這一條對話的模型／思考強度（等同 cc-bot 的 /model_session、/effort_session）。
     * 空字串＝清除覆寫、回到跟隨帳號預設。
     *
     * 成功後重拉 snapshot 而不是本地推算：伺服器會 drop client 讓下回合重建，
     * 生效值由它算，本地猜錯的話面板會顯示成已改、實際還是舊模型。
     */
    fun applyConvSettings(model: String?, effort: String?) = viewModelScope.launch {
        val conv = _state.value.currentConv
        client.setConvSettings(conv, model, effort).onSuccess { loadSnapshot(conv) }
    }

    companion object {
        const val DEFAULT_CONV = "main"
        /** 伺服器用來表示「不屬於任何對話」的佔位值（事件的 conv_id／turn_id）。 */
        private const val NO_CONV = "-"
        /** 忙碌中斷多久以內算同一段工作（續跑與重試之間的空檔是毫秒級的）。 */
        private const val BUSY_GAP_MS = 5_000L
        /** 與伺服器 files.MAX_UPLOAD_BYTES 同值：本地先擋，省掉白傳一趟才收到 413。 */
        private const val MAX_UPLOAD_BYTES = 32 * 1024 * 1024
    }
}
