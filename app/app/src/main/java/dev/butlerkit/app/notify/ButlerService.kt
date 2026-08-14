package dev.butlerkit.app.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import dev.butlerkit.app.data.AgendaRepo
import dev.butlerkit.app.data.InboxRepo
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.net.ButlerClient
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (job == null) job = scope.launch { connectLoop() }
        // 被系統殺掉後要自己回來——這是「背景保活」的重點
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val n = Notifier.serviceNotification(this, "有事會通知你")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // specialUse 而不是 dataSync：後者在 targetSdk 35 上一天只能累計跑 6 小時，
            // 額度用完系統會逼服務停掉、之後連重啟都被拒。理由詳見 AndroidManifest。
            startForeground(SERVICE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10～13 還沒有 specialUse 這個型別，也還不檢查型別與 manifest 是否相符
            startForeground(SERVICE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(SERVICE_ID, n)
        }
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
        val prefs = Prefs(applicationContext)
        val client = ButlerClient(prefs)
        var backoff = 2_000L
        // 記住每個回合是否動過工具：只有「像樣的工作」跑完才值得通知，
        // 隨口聊兩句也推播會很煩
        var sawTool = false
        var turnStart = 0L

        while (scope.isActive) {
            runCatching {
                client.stream(prefs.lastSeq).collect { wire ->
                    when (wire) {
                        is Wire.Conn -> if (wire.connected) backoff = 2_000L
                        is Wire.Ev -> {
                            val ev = wire.event
                            prefs.lastSeq = ev.seq
                            when (ev.type) {
                                "turn.start" -> {
                                    sawTool = false
                                    turnStart = System.currentTimeMillis()
                                }
                                "tool.call" -> sawTool = true
                                "ask.request" -> onAsk(ev)
                                "reply.final" -> onReplyFinal(ev, sawTool, turnStart)
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
                                    Notifier.notify(
                                        this@ButlerService, NotifyKind.FileReady,
                                        "助理傳了 $name 給你",
                                        ev.str("note").ifBlank { "在工具頁可以下載" },
                                    )
                                    InboxRepo.refresh(client)
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

    private fun onReplyFinal(ev: ServerEvent, sawTool: Boolean, turnStart: Long) {
        // 後面緊接著一個問題，這輪還沒結束。少了這道閘門，通知欄會同時出現
        // 「做完了」與「助理在等你回答」兩則，而前者的語意是錯的。
        // 判斷由伺服器給（reply.final 的 pending_ask）——兩個事件只差幾毫秒，
        // App 這邊等一下再看是猜的，源頭在發第一則時就已經知道答案。
        if (ev.bool("pending_ask")) return
        val elapsed = System.currentTimeMillis() - turnStart
        // 對齊伺服器的 NOTIFY_AFTER_SEC=60：短任務跑完不吵人
        if (!sawTool && elapsed < 60_000) return
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
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ButlerService::class.java)) }
        }
    }
}
