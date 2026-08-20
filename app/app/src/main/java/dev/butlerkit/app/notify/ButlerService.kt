package dev.butlerkit.app.notify

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dev.butlerkit.app.data.AgendaRepo
import dev.butlerkit.app.data.InboxRepo
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.Locator
import dev.butlerkit.app.net.ServerEvent
import dev.butlerkit.app.net.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 背景保活：App 不在前景時，由這個前景服務持有 SSE 連線並發通知。
 *
 * 為什麼需要：App 一進背景，系統會掐斷長連線，於是①長任務跑完不會知道
 * ②助理問問題時人不在前景，等滿 300 秒逾時被當成拒絕，而使用者從頭到尾不知道它問過。
 * cc-bot 沒這問題是因為蹭 Discord 的原生推播，助理自己做 App 就得自己實作。
 *
 * **為什麼一直開著**：曾經改成「只在助理手上有事時才連」，讓待命期間那則常駐通知消失，
 * 但使用者要的是連線一直在。前景服務在 Android 上一定要掛一則通知（系統規定，壓到
 * IMPORTANCE_MIN 也藏不掉），所以常駐連線就等於常駐一則通知，這是接受的代價——
 * 換來的是助理在電腦端自己動手做的事（設鬧鐘、傳檔案）能即時推到手機，不必等打開 App。
 *
 * 架構上刻意讓「同時只有一條連線」：前景時 ViewModel 連、背景時這個服務連，
 * 由 ProcessLifecycleOwner 切換（見 ButlerApp）。兩邊各開一條雖然也能運作
 * （伺服器是廣播給所有訂閱者），但會白白多一份流量與電。
 */
class ButlerService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var job: Job? = null
    private var locJob: Job? = null

    // 連線與定期回報共用同一份。ButlerClient 每個實例自己建兩個 OkHttpClient，
    // 各建一個等於多一組連線池與執行緒，而它們講的是同一台伺服器。
    private val prefs by lazy { Prefs(applicationContext) }
    private val client by lazy { ButlerClient(prefs) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        Log.i(
            ButlerClient.TAG,
            "服務 onStartCommand：連線 job=${if (job == null) "要起" else "已在"}，" +
                "定位 job=${if (locJob == null) "要起" else "已在"}，" +
                "定位權限=${hasLocationPermission()}",
        )
        if (job == null) job = scope.launch { connectLoop() }
        // 定期回報位置，讓助理隨時有一筆夠新的可用。獨立一個 job 而不是塞進
        // connectLoop：那條迴圈會因為斷線而反覆重跑，位置回報的節奏不該跟著
        // 網路狀況忽快忽慢，斷線期間也沒有理由停止抓位置。
        if (locJob == null) {
            locJob = scope.launch { Locator.reportPeriodically(applicationContext, client) }
        }
        // 被系統殺掉後要自己回來——這是「背景保活」的重點
        return START_STICKY
    }

    /**
     * 把服務推上前景。**這裡的每一種失敗都必須接住，否則整個 App 進崩潰迴圈。**
     *
     * `onStartCommand` 拋出的例外會直接變成 FATAL EXCEPTION，而這個服務是
     * START_STICKY——系統重啟它、再崩一次，使用者看到的是「助理 一直停止運作」，
     * 連聊天都打不開。2026-08-18 就是這樣炸的，兩種例外都遇到了。
     */
    private fun startForegroundCompat() {
        val n = Notifier.serviceNotification(this, "有事會通知你")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(SERVICE_ID, n)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 10～13 還沒有 specialUse 這個型別，也還不檢查型別與 manifest 是否相符。
            // 這條路一樣要接住例外：Android 12 起就有「不准從背景啟動前景服務」了。
            tryForeground(n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)?.let {
                Log.w(ButlerClient.TAG, "起不了前景服務，先收掉：${it.message}")
                stopSelf()
            }
            return
        }
        // specialUse 而不是 dataSync：後者在 targetSdk 35 上一天只能累計跑 6 小時，
        // 額度用完系統會逼服務停掉、之後連重啟都被拒。理由詳見 AndroidManifest。
        //
        // 有 location 這個型別，服務跑著的時候就能在背景抓位置，不必去要
        // ACCESS_BACKGROUND_LOCATION（那個要使用者進系統設定選「一律允許」）。
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        // **權限給了不代表現在用得了。** ACCESS_FINE/COARSE_LOCATION 是「前景限定」
        // 權限：App 在背景時（開機自啟、widget 排程叫醒），系統當它不存在，帶著
        // location 型別啟動就是 SecurityException。光檢查 granted 擋不掉這條路——
        // 「現在算不算在可用狀態」的規則複雜（前景、豁免清單、暫時允許…），
        // 與其自己重算一遍不如試了再說。
        val wantLocation = hasLocationPermission()
        val first = tryForeground(
            n,
            if (wantLocation) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else base,
        ) ?: return

        // 只有「型別要的權限現在不成立」值得退一步重試。被擋的是啟動本身的話，
        // 換型別也一樣起不來，再試只是多炸一次。
        if (first is SecurityException && wantLocation) {
            Log.i(ButlerClient.TAG, "現在不能帶 location 型別（多半是從背景啟動），改用 specialUse")
            if (tryForeground(n, base) == null) return
        }
        // 真的推不上前景。這一次就別跑了——下次 App 進背景時
        // ProcessLifecycleOwner 會在系統允許的狀態下再起一次。
        Log.w(ButlerClient.TAG, "起不了前景服務，先收掉：${first.message}")
        stopSelf()
    }

    /**
     * 試著把服務推上前景。成功回 null，失敗回攔下來的例外。
     *
     * 一定要接住不能外拋：`onStartCommand` 丟出去就是 FATAL EXCEPTION，
     * 而 START_STICKY 會讓系統再啟動一次、再炸一次，變成崩潰迴圈。
     * `ForegroundServiceStartNotAllowedException` 是 IllegalStateException
     * 的子類（API 31+），用父類別接才不必為了它加版本分支。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun tryForeground(n: android.app.Notification, types: Int): Exception? = try {
        startForeground(SERVICE_ID, n, types)
        null
    } catch (e: SecurityException) {
        e
    } catch (e: IllegalStateException) {
        e
    }

    private fun hasLocationPermission(): Boolean =
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * 系統宣告這個前景服務時間到了（Android 15 起）。
     *
     * 型別已經換成 specialUse，照理不會再走到這裡。但**沒實作這個方法的下場是
     * 系統丟 `RemoteServiceException` 直接讓 App 崩掉**——不是安靜地停掉服務。
     * 成本一行，留著。
     *
     * 這裡只能 stopSelf，不能試著重啟：額度耗盡後 `startForegroundService` 會丟
     * `ForegroundServiceStartNotAllowedException`，要等使用者把 App 帶到前景才會重置。
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int) {
        Log.w(ButlerClient.TAG, "前景服務被系統宣告逾時，自己收掉（背景通知會停到下次打開 App）")
        stopSelf()
    }

    private suspend fun connectLoop() {
        var backoff = 2_000L

        while (scope.isActive) {
            runCatching {
                // 走 bgSeq 而不是 lastSeq。這條連線只發通知、不存任何內容，
                // 共用畫面那個游標會把它推過頭，使用者點通知回到前景時，
                // 伺服器判定「都收過了」而不重播，ViewModel 手上卻一則都沒有——
                // 就是「通知看得到、進 App 空白」的成因（詳見 Prefs.bgSeq）。
                client.stream(prefs.bgSeq).collect { wire ->
                    when (wire) {
                        is Wire.Conn -> if (wire.connected) backoff = 2_000L
                        is Wire.Ev -> {
                            val ev = wire.event
                            prefs.bgSeq = ev.seq
                            when (ev.type) {
                                "ask.request" -> onAsk(ev)
                                // 只認 turn.done，**不要**改回 reply.final：後者
                                // 每一輪都會發（續跑、壓縮核對各一則），綁在那上面
                                // 就是「通知來了、點進去助理還在思考」。
                                "turn.done" -> onTurnDone(ev)
                                "notify" -> Notifier.notify(
                                    this@ButlerService, NotifyKind.Reminder,
                                    ev.str("title").ifBlank { "提醒" },
                                    ev.str("body"), ev.convId,
                                )
                                // App 在背景時助理設的鬧鐘也要排上。不重排的話
                                // 「幫我明天七點叫我」講完就把手機收起來，隔天不會響。
                                "agenda.changed" -> AgendaRepo.refresh(
                                    applicationContext, client,
                                )
                                // 助理傳檔案來了。這裡只通知與更新清單，不自動下載——
                                // 上限 256MB，替使用者決定用掉行動網路不是幫忙。
                                "file.offer" -> {
                                    val name = ev.str("name")
                                    // convId 一定要帶。通知 id 是 idOf(kind, convId)
                                    // 算出來的，省略的話整類固定同一個 id，
                                    // 助理連傳兩個檔案時後面那則會把前面那則**蓋掉**——
                                    // 使用者只看得到最後一個，前一個檔案就這樣錯過。
                                    Notifier.notify(
                                        this@ButlerService, NotifyKind.FileReady,
                                        "助理傳了 $name 給你",
                                        ev.str("note").ifBlank { "在工具頁可以下載" },
                                        ev.convId,
                                    )
                                    InboxRepo.refresh(client)
                                    // 例外是 APK：那不是「一個檔案」是「一次更新」，
                                    // Wi-Fi 下先抓好，等他打開 App 就只剩按一下安裝
                                    InboxRepo.onOffer(
                                        applicationContext, client,
                                        dev.butlerkit.app.net.OfferedFile(
                                            id = ev.str("file_id"), name = name,
                                            bytes = ev.int("bytes").toLong(),
                                            note = ev.str("note"), at = "", gone = false,
                                        ),
                                    )
                                }
                                // 助理要知道他在哪。不顯示任何東西，抓完就回報。
                                // **一定要 launch 出去**：抓位置最多等 15 秒，
                                // 擋在事件迴圈裡的話這段時間所有事件都不處理，
                                // 連「助理在等你回答」的通知都會遲到 15 秒。
                                "device.request" -> scope.launch {
                                    Locator.onDeviceRequest(applicationContext, client, ev)
                                }
                                "error" -> onError(ev)
                            }
                        }
                    }
                }
            }
            if (!scope.isActive) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private fun onAsk(ev: ServerEvent) {
        // 這是最需要推播的一種：沒人回答，那件事就卡在那裡直到逾時被拒絕
        Notifier.notify(
            this, NotifyKind.NeedsYou,
            "助理在等你回答",
            ev.str("title").ifBlank { "需要你做個決定" },
            ev.convId,
        )
    }

    /**
     * 整則訊息真的收工了（伺服器的 `turn.done`）。這是「做完了」推播的唯一來源。
     *
     * 判斷依據全部由伺服器給，App 不再自己數：
     *  - `pending_ask`：停在提問上。少了這道閘門，通知欄會同時出現「做完了」與
     *    「助理在等你回答」兩則，而前者的語意是錯的。
     *  - `notify`：值不值得吵人（動過工具，或超過 `NOTIFY_AFTER_SEC`）。門檻
     *    只留伺服器那一份——先前 60 這個數字是抄在這裡的，改伺服器沒有效果。
     *    而那兩個素材 App 自己算都會算錯：`tool.call` 會被續跑的 turn.start
     *    洗掉（最後一輪往往只回一句話不動工具），耗時拿 turn.start 當起點則
     *    只量得到最後一輪——跑了十分鐘的事只要最後一輪快就整個不推播。
     */
    private fun onTurnDone(ev: ServerEvent) {
        if (ev.bool("pending_ask")) return
        if (!ev.bool("notify")) return
        val body = ev.str("markdown").take(120).replace("\n", " ")
        Notifier.notify(this, NotifyKind.TaskDone, "做完了", body, ev.convId)
    }

    private fun onError(ev: ServerEvent) {
        val kind = ev.str("kind")
        if (kind == "STOPPED") return       // 自己按的停止，不用通知
        Notifier.notify(
            this, NotifyKind.TaskDone, "出狀況了",
            ev.str("detail").ifBlank { kind }, ev.convId,
        )
    }

    override fun onDestroy() {
        Log.i(ButlerClient.TAG, "背景服務結束")
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SERVICE_ID = 4201

        fun start(ctx: Context) {
            val i = Intent(ctx, ButlerService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            }.onFailure {
                // 從背景啟動前景服務被擋（ForegroundServiceStartNotAllowedException）
                // 是這裡唯一預期得到的失敗，而它代表**背景連線沒有被救回來**——
                // 使用者的體感是「推播突然就不來了」。原本整段靜默吞掉，
                // 連查都無從查起，至少要留下一行。
                Log.w(ButlerClient.TAG, "啟動背景服務被擋：${it.message}")
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ButlerService::class.java)) }
        }
    }
}
