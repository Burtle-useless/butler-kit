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
import dev.butlerkit.app.net.BgTask
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.ConvInfo
import dev.butlerkit.app.net.Locator
import dev.butlerkit.app.net.NetMonitor
import dev.butlerkit.app.net.ServerEvent
import dev.butlerkit.app.net.SettingsInfo
import dev.butlerkit.app.net.ToolCall
import dev.butlerkit.app.net.Wire
import dev.butlerkit.app.net.humanError
import dev.butlerkit.app.notify.AppForeground
import dev.butlerkit.app.notify.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    val client = ButlerClient(prefs)

    // 打到一半的訊息，每個對話一份。開 App 就從 Prefs 讀回來——這份東西存在的
    // 唯一理由就是撐過「切出去再回來」，只活在記憶體裡等於沒做。
    private val draftByConv = prefs.drafts.toMutableMap()

    private val _state = MutableStateFlow(
        ChatState(
            lastSeq = prefs.lastSeq,
            busySince = prefs.busySince,
            draft = draftByConv[DEFAULT_CONV] ?: "",
        ),
    )
    val state: StateFlow<ChatState> = _state.asStateFlow()

    // 操作失敗的一句話（停止、回答提問、改設定、刪對話……），由 MainActivity 的
    // Snackbar 收。這些操作先前失敗一律靜默：按了沒反應、人以為沒按到就再按。
    // 沒人在收的時候直接丟掉（extraBufferCapacity 只是讓 tryEmit 不會因為
    // 訂閱者還沒掛上而回 false）——這種提示過了那一刻就沒意義。
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toasts: SharedFlow<String> = _toasts

    private fun toast(e: Throwable) { _toasts.tryEmit(humanError(e)) }

    // 忙碌計時器的兩個記憶點，**每個對話各一份**（理由見 syncBusyClock）。
    // 沒有登記過的對話拿 `prefs.busySince != 0L` 當初值：上次離開時它正在忙的話
    // 就當成「一直忙到現在」，否則 ViewModel 一重建就會把讀回來的起點丟掉，
    // 使用者切出去再切回來看到的就是從頭數的秒數。
    private val wasBusyByConv = mutableMapOf<String, Boolean>()
    private val idleSinceByConv = mutableMapOf<String, Long>()

    // 每個對話各自的軌跡（含串流緩衝、忙碌、狀態列、背景工作）。事件流是全裝置
    // 一條，靠 conv_id 分流到這裡；切對話＝換一個 key 投影，已收過的內容不會消失。
    // 怎麼變由 TrailReducer 決定，這裡只存與投影。
    private val trails = mutableMapOf<String, ConvTrail>()

    // 哪幾條對話已經把歷史接回來了。
    //
    // 這件事不能拿「本地有沒有內容」當代理指標。事件流是全裝置一條，背景對話
    // 跑起來時它的軌跡照樣寫進 itemsByConv——等使用者切過去，那條對話的本地
    // 內容早就不是空的，歷史於是永遠不補，畫面上只剩剛才收到的那幾則、往上滑
    // 什麼都沒有（使用者 2026-08-25 回報「偶爾對話會沒辦法往上滑」）。
    private val historyLoaded = mutableSetOf<String>()

    // 上一次成功拉到 snapshot 的時刻與它帶回的對話層狀態，每個對話一份。
    //
    // 切分頁、切對話不再每次都重拉整份 snapshot：本地 trails 在連線期間跟著事件流走，
    // 已經是準的；只有「歷史還沒接回來」或「上次拉太久了」才值得再跑一趟
    // （見 [needsSnapshot]）。convStatus 原本只有 snapshot 會填，不重拉就得自己留一份。
    private val snapshotAtByConv = mutableMapOf<String, Long>()
    private val convStatusByConv = mutableMapOf<String, ConvStatus>()

    // 往前翻頁的兩個本地狀態，每個對話一份。不放 ConvTrail：那是事件流的投影，
    // 由 reducer 管；這兩個跟 historyLoaded 一樣是「歷史接回來了多少」的載入狀態。
    // hasMore 由 snapshot 與每一頁的回應更新；olderLoading 保證同時只跑一趟。
    private val hasMoreByConv = mutableMapOf<String, Boolean>()
    private val olderLoadingByConv = mutableMapOf<String, Boolean>()

    // 已經套用過的最大序號。伺服器保證序號嚴格遞增，所以「不大於它」的一律是重複。
    // 沒有這道關卡時，任何一次重播都會被當成新事件重畫一遍——兩條 SSE 並存、
    // 伺服器重啟、續傳邊界都會踩到，症狀是對話自己倒帶。
    private var appliedSeq = -1L

    // 這個 process 還沒連過線。決定第一次要不要帶續傳游標，理由見 streamForever。
    private var coldStart = true
    private var moodDecay: Job? = null

    // 計時起點每個對話一份（狀態列、忙碌、背景工作都在 trails 裡，這個不在：
    // 它是本地推算的時鐘，不是伺服器事件的投影）。
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

    /** 重連退避。放成員而不是區域變數：重置點在 collectStream（連上那一刻）。 */
    private var backoffMs = 1_000L

    private suspend fun streamForever() = coroutineScope {
        backoffMs = 1_000L
        Log.i(ButlerClient.TAG, "connectLoop 啟動 host=${prefs.host} lastSeq=${prefs.lastSeq}")
        while (isActive) {
            runCatching {
                // 這個 process 第一次連線**不續傳**，只要新的事件。
                //
                // `prefs.lastSeq` 存在磁碟上，跨得過 process 的死亡；`itemsByConv`
                // 跨不過。所以更新 App、或被系統回收後重開時，游標還指著上一個
                // process 看到的位置，而軌跡是空的——SSE 於是從那裡重播一整段
                // 舊事件，snapshot 又同時帶回一份完整歷史，兩份在 loadSnapshot
                // 裡接成 `history + live`。那行接法的前提是「live 比 history 新」，
                // 這種情況下前提不成立，接出來就是同一段講兩次而且順序錯亂。
                // 使用者 2026-08-25 回報「有訊息會因為更新丟失」。
                //
                // 冷啟動本來就不需要事件流補歷史——那是 snapshot 的職責，而且它
                // 直接讀 CC 的逐字稿，是唯一的權威來源。續傳真正要救的是「切背景
                // 再回前景」，那時 process 還活著、軌跡還在，續傳剛好接得上，
                // 所以只有這個 process 的第一次要跳過。
                val since = if (coldStart) -1L else prefs.lastSeq
                coldStart = false
                streamOnCurrentNet(since)
            }
            if (!isActive) break
            _state.update { it.copy(connected = false, pet = PetMood.Offline) }
            Log.w(ButlerClient.TAG, "連線中斷，${backoffMs}ms 後重試（網路一恢復就提早）")
            // 退避等待可以被網路訊號打斷：出電梯、切回 Wi-Fi 的那一刻就該重連，
            // 不是把剩下的秒數睡完（2026-09-05 使用者：「斷線重連做得很差」）
            withTimeoutOrNull(backoffMs) { NetMonitor.signals.first() }
            backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
        }
    }

    /** 連一次 SSE，網路換了就掐掉讓外層立刻重連（見 NetMonitor.guard）。 */
    private suspend fun streamOnCurrentNet(since: Long) =
        NetMonitor.guard(ButlerClient.TAG) { collectStream(since) }

    private suspend fun collectStream(since: Long) {
        client.stream(since).collect { wire ->
            when (wire) {
                is Wire.Conn -> {
                    if (wire.connected) {
                        backoffMs = 1_000L
                        refreshConversations()
                        // 模型清單也重拉：伺服器重啟後第一次連線前它只有內建的
                        // 後備清單，本地那份是開 App 時抓的，不重拉就一直少模型
                        // （2026-09-03 使用者回報面板少了 Fable）
                        loadSettings()
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

    private fun onEvent(ev: ServerEvent) {
        val conv = ev.convId.ifBlank { _state.value.currentConv }

        // 伺服器重啟了：本地游標屬於上一個世代，畫面上的軌跡也不保證跟電腦上一致。
        // 丟掉這個對話的本地軌跡改用 snapshot 重建——snapshot 直接讀 CC 的逐字稿，
        // 是唯一的權威來源。清掉才進得去，loadSnapshot 只在本地為空時才填。
        if (ev.type == "stream.reset") {
            appliedSeq = -1L
            prefs.lastSeq = -1L
            reload(conv)
            return
        }

        // 重複事件擋在這裡。序號嚴格遞增，所以「不大於已套用過的」就是重播。
        if (ev.seq <= appliedSeq) return
        appliedSeq = ev.seq
        // 逐字事件不落盤：每個 delta 都重寫整份 SharedPreferences XML（同一份檔
        // 還裝著行事曆與用量快取）是寫入風暴。續傳游標差幾則 delta 沒有損失，
        // 下一個定稿事件就補上了
        if (ev.type != "text.delta" && ev.type != "thinking.delta") prefs.lastSeq = ev.seq

        // 舊版伺服器不知道事件是誰的，conv_id 送 "-"。那不是空字串，上面的 ifBlank
        // 接不住，會掉進一個永遠不會被顯示的假對話桶。退回當前對話，維持舊行為
        val target = if (conv == NO_CONV) _state.value.currentConv else conv

        // 軌跡怎麼變是 TrailReducer 的事（純函式，有事件序列測試）。
        // 這裡只做三件事：存回去、執行它說的副作用、投影到畫面。
        val reduced = TrailReducer.reduce(trail(target), ev)
        trails[target] = reduced.trail
        reduced.effects.forEach { applyEffect(target, it, ev) }
        project(target, ev)
    }

    private fun trail(conv: String): ConvTrail = trails[conv] ?: ConvTrail()

    private fun applyEffect(conv: String, fx: Effect, ev: ServerEvent) {
        when (fx) {
            is Effect.Mood -> setMood(fx.mood, fx.decayMs)
            Effect.FlushOffers -> flushOffers()
            is Effect.Offer -> onOffer(conv, fx.offer)
            // 標題存在伺服器，側欄是自己一份複本，不接這個事件就要等下次重拉清單
            is Effect.Rename -> _state.update { s ->
                s.copy(conversations = s.conversations.map { c ->
                    if (c.id == conv) c.copy(title = fx.title) else c
                })
            }
            // 助理改了行事曆／鬧鐘／記帳。重拉一次，順帶把鬧鐘重排進 AlarmManager——
            // 少了這一步，它幫你設的鬧鐘只是資料庫裡的一行字，時間到不會響。
            Effect.RefreshAgenda -> viewModelScope.launch {
                AgendaRepo.refresh(getApplication(), client)
            }
            // 助理要知道他人在哪。**完全靜默**：不進軌跡、不提示，抓完就回報。
            // 前景時是這裡在收，背景時是 ButlerService，兩邊共用 Locator 那一份實作。
            Effect.DeviceRequest -> viewModelScope.launch {
                Locator.onDeviceRequest(getApplication(), client, ev)
            }
            // 離線太久補不回來、或伺服器重啟：清本地軌跡用 snapshot 重建。
            // 不 append 提示是刻意的：那則錯誤會讓本地變成非空，而 loadSnapshot 的
            // 填充條件正是「送出請求時本地為空」——留著提示就等於親手擋掉自己的復原
            Effect.Reload -> reload(conv)
        }
    }

    /** 本地軌跡不可信了：丟掉、用 snapshot 重建。stream.reset 與 seq.gap 共用。 */
    private fun reload(conv: String) {
        trails.remove(conv)
        historyLoaded.remove(conv)
        loadSnapshot(conv)
    }

    /**
     * 助理傳檔案來了。事件帶著發起它的 conv_id，卡片會落回發起的那段對話。
     * 還在跑就先擱著，等 turn.done 才排到最後一段話後面；沒在跑代表是回合外
     * 送來的（背景任務收工），當場落地就是最下面。busy 看的是**發起那個對話**。
     */
    private fun onOffer(conv: String, offer: TraceItem.FileOffer) {
        pendingOffers += conv to offer
        if (!trail(conv).busy) flushOffers()
        viewModelScope.launch { InboxRepo.refresh(client) }
        // 傳來的是新版 App 的話，Wi-Fi 下先抓好，卡片上的按鈕直接是「安裝」。
        // at 與 gone 由清單端點才算得出來，這裡填空值不影響下載
        InboxRepo.onOffer(
            getApplication(), client,
            dev.butlerkit.app.net.OfferedFile(
                id = offer.fileId, name = offer.name, bytes = offer.bytes,
                note = offer.note, at = "", gone = false,
            ),
        )
    }

    /**
     * 把某條對話的軌跡投影到畫面。只有它正好是眼前這條時才動 items 等欄位；
     * 背景對話的事件只推進游標。狀態一律從 trails 投影過來，不在這裡另算一次，
     * 免得兩邊邏輯慢慢長歪。
     */
    private fun project(conv: String, ev: ServerEvent?) {
        val t = trail(conv)
        val isCurrent = conv == _state.value.currentConv
        _state.update { s ->
            var next = s.copy(lastSeq = ev?.seq ?: s.lastSeq)
            if (isCurrent) {
                next = next.copy(
                    busy = t.busy, streaming = t.streaming, thinkingTail = t.thinkingTail,
                    status = t.status, bgTasks = t.bgTasks,
                )
                // 待建立的新對話畫面必須保持空白：這時 currentConv 還指著舊對話，
                // 舊對話若有背景回合在跑，內容會突然冒出來
                if (!next.pendingNew) next = next.copy(items = t.items)
            }
            next
        }
        syncBusyClock(
            if (isCurrent && ev?.type == "status") (ev.dbl("elapsed") * 1000).toLong() else null,
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
     * 把擱著的檔案卡片倒進各自的對話軌跡。
     *
     * 不分回合一次全倒：同時跑兩個回合本來就少見，而每張卡片都記著自己當初
     * 掛在哪條對話，就算真的交錯也不會跑到別人的軌跡上。
     */
    private fun flushOffers() {
        if (pendingOffers.isEmpty()) return
        for ((conv, offer) in pendingOffers) {
            val t = trail(conv)
            trails[conv] = t.copy(items = t.items + offer)
        }
        val here = _state.value.currentConv
        val touched = pendingOffers.any { it.first == here }
        pendingOffers.clear()
        // 待建立的新對話畫面要保持空白，這時不能把東西畫上去
        if (touched && !_state.value.pendingNew) {
            _state.update { it.copy(items = trail(here).items) }
        }
    }

    // ── 對話管理 ──────────────────────────────────────────────────────────
    fun refreshConversations() = viewModelScope.launch {
        client.listConversations().onSuccess { list ->
            _state.update { it.copy(conversations = list) }
            // 玖分頁在清單還沒到就被點開時會停在「還沒有對話」（enterCcTab 提前
            // return，而 MainActivity 只在換分頁時再叫它）。清單到了就補切一次
            if (wantCc) enterCcTab()
        }
    }

    /** 玖分頁點開時清單還沒載到，等清單到了要補切過去。 */
    private var wantCc = false

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
                items = trail(convId).items,
                // 串流緩衝也是那條對話自己的：切回來時它正在講的話接著顯示，
                // 不再像先前那樣切走就把尾段弄丟
                streaming = trail(convId).streaming,
                thinkingTail = trail(convId).thinkingTail,
                // 狀態列換成新對話自己的那份。少了這幾行，切過去看到的是
                // 上一個對話的「整理記憶中」跟它的秒數
                status = trail(convId).status,
                bgTasks = trail(convId).bgTasks,
                busy = trail(convId).busy,
                busySince = busySinceByConv[convId] ?: 0L,
                // 草稿也跟著換。共用一份的話，在 A 打到一半切去 B，
                // 那句話會出現在 B 的輸入框裡，然後被送給 B
                draft = draftByConv[convId] ?: "",
                // 上次 snapshot 帶回的對話層狀態；沒拉過就是 null，面板會說「還沒拿到」
                convStatus = convStatusByConv[convId],
                hasMore = hasMoreByConv[convId] ?: false,
                olderLoading = olderLoadingByConv[convId] ?: false,
            )
        }
        if (needsSnapshot(convId)) loadSnapshot(convId)
    }

    /**
     * 這條對話值不值得再拉一次 snapshot。
     *
     * 歷史沒接回來的一定要；接回來過的，本地軌跡在連線期間跟事件流走已經是準的，
     * 只在距上次超過 [SNAPSHOT_TTL_MS] 才重拉一次校正（busy 真值、ctx 用量）。
     * 連線剛建立與 seq.gap／stream.reset 不走這裡，那兩條路無條件重拉。
     */
    private fun needsSnapshot(convId: String): Boolean {
        if (convId !in historyLoaded) return true
        val at = snapshotAtByConv[convId] ?: return true
        return System.currentTimeMillis() - at > SNAPSHOT_TTL_MS
    }

    /**
     * 記下輸入框現在的內容。
     *
     * 每次改字都寫一次 SharedPreferences：`apply()` 只更新記憶體、磁碟寫入丟到
     * 背景，打字這種頻率撐得住。改成離開畫面才寫則救不了真正要救的情況——
     * App 被系統直接回收時沒有任何人會通知我們。
     */
    fun setDraft(text: String) {
        val conv = _state.value.currentConv
        if (text.isEmpty()) draftByConv.remove(conv) else draftByConv[conv] = text
        prefs.drafts = draftByConv.toMap()
        _state.update { it.copy(draft = text) }
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
        val before = trail(convId).items.size
        // 本地一則都沒有、歷史又還沒接回來：讓畫面畫骨架，別先閃一下空狀態
        if (before == 0 && convId !in historyLoaded) {
            _state.update { s ->
                if (s.currentConv == convId) s.copy(historyLoading = true) else s
            }
        }
        val result = client.snapshot(convId)
        // 不管成敗，骨架都要收掉——失敗時留著骨架會像永遠載不完
        _state.update { s ->
            if (s.currentConv == convId && s.historyLoading) s.copy(historyLoading = false) else s
        }
        result.onSuccess { snap ->
            // 條件問的是「這條對話的歷史接回來了沒」，不是「本地現在有沒有內容」。
            //
            // 這裡踩過兩次：第一次問的是回應到達當下的 `isNullOrEmpty()`，那是個
            // 競態——snapshot 還在路上時事件流先送到一則即時訊息，條件就變成 false，
            // 整段歷史於是永遠不載入。改成請求前的 `before == 0` 之後競態沒了，
            // 但那個判準本身還是錯的：事件流是全裝置一條，**背景對話跑起來時它的
            // 軌跡照樣寫進 itemsByConv**，等使用者切過去 before 早就大於 0，歷史
            // 同樣永遠不補。兩次的症狀是同一個——只有最新那幾則、往上滑什麼都沒有。
            if (convId !in historyLoaded && snap.messages.isNotEmpty()) {
                // 助理那側走 replyOrNull：歷史裡那些只有 [[DONE]] 的空回覆
                // 不還原，否則重開 App 那顆孤兒頭像又會回到畫面上
                //
                // 思考要跟著還原，而且要排在該回合的回覆**前面**——串流當下就是
                // 先看到 💭 再看到話，歷史長得不一樣的話捲回去會覺得是另一段對話。
                // 歷史沒有 turnId 可用，跟 UserMsg 一樣拿 convId 頂替。
                // 等待期間事件流塞進來的那些接在歷史後面，不是丟掉。
                // 它們是「比歷史更新」的訊息，順序上本來就該在最後。
                //
                // 但那只在「本地原本是空的」時候成立。背景對話累積下來的軌跡是另一
                // 回事：那些回合早就寫進逐字稿了，snapshot 這次全都帶回來，再接一份
                // 就是每則講兩次。所以本地原本非空的話一則都不留，以 snapshot 為準。
                // 代價是那段期間的工具呼叫看不到了（逐字稿本來就不存工具軌跡，切進
                // 任何一條舊對話都是如此），換來的是完整的歷史。
                val live = if (before == 0) trail(convId).items else emptyList()
                // 檔案卡片要跟訊息交錯排回去。卡片不在 CC 的逐字稿裡（那是 butler
                // 自己造的東西），所以伺服器另外給一份，兩邊都帶 epoch 毫秒。
                // 不補的話 App 一重啟、或伺服器一重啟，對話裡的下載框就整排消失——
                // 使用者 2026-08-18 回報「更新之後下載框會不見」。
                //
                // messages 與 files 都是舊到新，走一次歸併就夠。時間相同時卡片排後面
                // （伺服器把登記時間的秒數補到 59.999 就是為了這個）：卡片的語意本來
                // 就是「這一輪講完之後才落地」。
                // 已經由事件流放進來的那些不能再放一次。冷啟動時 snapshot 還在路上，
                // 助理剛好傳了檔案的話那張卡片會同時走即時與歷史兩條路——登記早就存在
                // 伺服器上了，snapshot 當然也會帶回來。pendingOffers 裡的還沒落地，
                // 但等回合結束就會，一樣要算進去。
                val known = (
                    live.filterIsInstance<TraceItem.FileOffer>().map { it.fileId } +
                        pendingOffers.filter { it.first == convId }.map { it.second.fileId }
                    ).toSet()
                // 訊息 → 項目的規則（思考在前、system 變 WakeNote、卡片歸併）在
                // TrailReducer.fromHistory，跟往前翻頁那條路共用同一份
                val history = TrailReducer.fromHistory(
                    convId, snap.messages, files = snap.files, knownFileIds = known,
                ).toMutableList()
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
                trails[convId] = trail(convId).copy(items = history + live)
                historyLoaded += convId
            }
            // snapshot 只帶最後一頁；前面還有沒有，伺服器說了算（舊伺服器一律 false）
            hasMoreByConv[convId] = snap.hasMore
            // 伺服器才知道背景那個對話到底還在不在跑，這是唯一的真值來源
            trails[convId] = trail(convId).copy(busy = snap.busy, bgTasks = snap.bg)
            val status = ConvStatus(
                model = snap.model, effort = snap.effort,
                modelOverride = snap.modelOverride,
                effortOverride = snap.effortOverride,
                cwd = snap.cwd, ctxTokens = snap.ctxTokens,
                ctxLimit = snap.ctxLimit,
            )
            convStatusByConv[convId] = status
            snapshotAtByConv[convId] = System.currentTimeMillis()
            _state.update { s ->
                if (s.currentConv != convId) s      // 使用者已經切走了，別亂改畫面
                else s.copy(
                    items = trail(convId).items,
                    busy = snap.busy,
                    bgTasks = snap.bg,
                    pet = if (snap.busy) PetMood.Thinking else s.pet,
                    convStatus = status,
                    hasMore = snap.hasMore,
                )
            }
            // busy 被伺服器真值校正過了，計時器要跟著校正——冷啟動時這是它第一次
            // 知道「電腦上其實還在跑」
            syncBusyClock(null)
        }
    }

    /**
     * 往前翻一頁歷史。畫面捲到最上面那列「載入更早」時呼叫。
     *
     * 游標是本地最早那個有時間的項目的 atMs（[TrailReducer.oldestAtMs]），
     * 伺服器回 `at_ms < before_ms` 的最後一頁，接在軌跡**最前面**。
     * 同時只跑一趟：LazyColumn 的位置觀察會在載入期間反覆觸發，第二趟會拿同一個
     * 游標再拉同一頁，接兩次就是每則講兩次。
     *
     * 這一頁回來時對話若已被 reload（伺服器重啟、seq.gap）就丟掉：那時軌跡正等
     * 新的 snapshot 重建，把舊游標拉回來的頁接上去會跟新的歷史交錯。
     */
    fun loadOlder(convId: String) {
        if (olderLoadingByConv[convId] == true) return
        if (hasMoreByConv[convId] != true) return
        val before = TrailReducer.oldestAtMs(trail(convId).items) ?: return
        olderLoadingByConv[convId] = true
        projectPaging(convId)
        viewModelScope.launch {
            val result = client.historyBefore(convId, before)
            olderLoadingByConv[convId] = false
            result.onSuccess { page ->
                if (convId in historyLoaded) {
                    val older = TrailReducer.fromHistory(convId, page.messages)
                    val t = trail(convId)
                    trails[convId] = t.copy(items = older + t.items)
                    hasMoreByConv[convId] = page.hasMore
                }
            }.onFailure(::toast)
            projectPaging(convId)
        }
    }

    /** 把翻頁狀態（與可能剛被前置過的項目）投影到畫面；不是眼前這條就只存不畫。 */
    private fun projectPaging(convId: String) {
        _state.update { s ->
            if (s.currentConv != convId || s.pendingNew) s
            else s.copy(
                items = trail(convId).items,
                hasMore = hasMoreByConv[convId] ?: false,
                olderLoading = olderLoadingByConv[convId] ?: false,
            )
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
                // 空白畫面上不該掛著舊對話的秒數、階段，或它的背景工作
                status = null, busy = false, busySince = 0L,
                bgTasks = emptyList(),
                hasMore = false, olderLoading = false,
            )
        }
    }

    fun deleteConversation(convId: String) = viewModelScope.launch {
        client.deleteConversation(convId).onSuccess {
            trails.remove(convId)
            busySinceByConv.remove(convId)
            wasBusyByConv.remove(convId)
            idleSinceByConv.remove(convId)
            historyLoaded.remove(convId)
            snapshotAtByConv.remove(convId)
            convStatusByConv.remove(convId)
            hasMoreByConv.remove(convId)
            olderLoadingByConv.remove(convId)
            if (ccConv == convId) ccConv = null
            refreshConversations()
            if (_state.value.currentConv == convId) switchConversation(DEFAULT_CONV)
        }.onFailure(::toast)
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
        // 由畫面顯示「還沒有對話」而不是誤把別人的內容當成 cc-bot 的
        val target = ccConv
            ?: _state.value.conversations
                .firstOrNull { it.id != DEFAULT_CONV }?.id
        if (target == null) {
            wantCc = true       // 清單到了再補（見 refreshConversations）
            return
        }
        wantCc = false
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
            Triple(client.uploadFile(name, bytes, mime).getOrThrow(), bytes.size.toLong(), mime)
        }
        _state.update { s ->
            val rest = s.uploading - name
            result.fold(
                onSuccess = { (path, size, mime) ->
                    s.copy(
                        uploading = rest,
                        attachments = s.attachments + Attachment(name, path, size, mime),
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


    // ── 訊息 ─────────────────────────────────────────────────────────────
    fun send(text: String) {
        val files = _state.value.attachments
        if (text.isBlank() && files.isEmpty()) return
        val body = text.trim()
        // 送出即清空附件列，否則下一則會重複附上。附件本身跟著這一則送出去，
        // 是訊息的結構化欄位而不是拼進本文的路徑（見 ConversationApi.sendMessage）
        if (files.isNotEmpty()) _state.update { it.copy(attachments = emptyList()) }
        // 待建立的新對話：這時候才跟伺服器要 id，拿到再送
        if (_state.value.pendingNew) {
            viewModelScope.launch {
                client.createConversation()
                    .onSuccess { id ->
                        ccConv = id
                        _state.update { it.copy(pendingNew = false, currentConv = id) }
                        refreshConversations()
                        deliver(id, body, files)
                    }
                    .onFailure { e -> reportSendFailure(_state.value.currentConv, e) }
            }
            return
        }
        deliver(_state.value.currentConv, body, files)
    }

    /** 實際送出。不在本地先畫使用者訊息——伺服器會發 user.message 回音事件，
     *  所有裝置（含自己）都靠那個事件渲染，多裝置同步因此免費。 */
    private fun deliver(conv: String, text: String, files: List<Attachment> = emptyList()) {
        // 計時器從按下送出就開始跑，不等伺服器的第一則 status——那要兩秒後才到，
        // 而使用者的等待從按下去那一刻就開始了
        // 軌跡也一起標成忙碌：下一則事件投影時才不會把它蓋回閒置、送出鍵閃一下
        trails[conv] = trail(conv).copy(busy = true)
        _state.update { it.copy(busy = true) }
        syncBusyClock(null)
        busySinceByConv[conv] = _state.value.busySince
        viewModelScope.launch {
            client.sendMessage(conv, text, files)
                .onFailure { e -> reportSendFailure(conv, e) }
        }
    }

    private fun reportSendFailure(conv: String, e: Throwable) {
        val err = TraceItem.ErrorItem(
            conv, "SEND_FAILED", e.message ?: "送出失敗", System.currentTimeMillis(),
        )
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
        val t = trail(conv)
        trails[conv] = t.copy(items = t.items + err, busy = false)
        _state.update { s ->
            s.copy(items = trail(conv).items, busy = false)
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
        val t = trail(conv)
        trails[conv] = t.copy(items = t.items.map { item ->
            if (item is TraceItem.AskItem && item.req.askId == askId && item.pending) {
                item.copy(answeredChoiceId = choiceId, answeredText = own)
            } else {
                item
            }
        })
        _state.update { it.copy(items = trail(conv).items) }
        if (askId.startsWith(AskRequest.INLINE_PREFIX)) {
            send(own ?: choiceId)
        } else {
            viewModelScope.launch {
                client.answerAsk(askId, choiceId, own).onFailure { e ->
                    // 沒送到：卡片退回等待中，讓人看得出來要再按一次
                    val t2 = trail(conv)
                    trails[conv] = t2.copy(items = t2.items.map { item ->
                        if (item is TraceItem.AskItem && item.req.askId == askId) {
                            item.copy(answeredChoiceId = null, answeredText = null)
                        } else {
                            item
                        }
                    })
                    _state.update { s ->
                        if (s.currentConv == conv) s.copy(items = trail(conv).items) else s
                    }
                    toast(e)
                }
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            client.stopTurn(_state.value.currentConv).onFailure(::toast)
        }
    }

    /**
     * 停掉一件還在跑的背景工作。跟 [stop] 是兩回事：那個停的是助理現在這一輪，
     * 這個停的是它稍早丟出去、現在還在自己跑的那件事。
     *
     * 不在這裡改本地狀態：伺服器停掉之後會送一則 `bg.state`，卡片由那條路更新。
     * 先在本地標成「已停」再等伺服器確認的話，網路一斷就會留下一張假的完成卡片。
     */
    fun stopBgTask(taskId: String) {
        val conv = _state.value.currentConv
        viewModelScope.launch { client.stopBgTask(conv, taskId).onFailure(::toast) }
    }

    // ── 設定 ─────────────────────────────────────────────────────────────
    fun loadSettings() = viewModelScope.launch {
        client.getSettings().onSuccess { s ->
            _state.update { it.copy(settings = s) }
        }
    }

    fun applySettings(model: String?, effort: String?) = viewModelScope.launch {
        client.setSettings(model, effort).onSuccess { loadSettings() }.onFailure(::toast)
    }

    // 結果由設定頁自己的內嵌訊息呈現，這裡不再丟 Snackbar，免得同一個錯講兩次
    suspend fun setCwd(path: String): Result<Unit> = client.setCwd(_state.value.currentConv, path)

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
            .onFailure(::toast)
    }

    companion object {
        const val DEFAULT_CONV = "main"
        /** 伺服器用來表示「不屬於任何對話」的佔位值（事件的 conv_id／turn_id）。 */
        private const val NO_CONV = "-"
        /** 忙碌中斷多久以內算同一段工作（續跑與重試之間的空檔是毫秒級的）。 */
        private const val BUSY_GAP_MS = 5_000L
        /** 切回同一條對話時，上次 snapshot 超過這麼久才重拉一次（見 needsSnapshot）。 */
        private const val SNAPSHOT_TTL_MS = 60_000L
        /** 與伺服器 files.MAX_UPLOAD_BYTES 同值：本地先擋，省掉白傳一趟才收到 413。 */
        private const val MAX_UPLOAD_BYTES = 32 * 1024 * 1024
    }
}
