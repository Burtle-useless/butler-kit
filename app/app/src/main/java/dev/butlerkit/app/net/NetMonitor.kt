package dev.butlerkit.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * 系統網路變化的即時訊號。
 *
 * 沒有它之前，斷線重連只靠「45 秒 readTimeout ＋ 指數退避」兩層被動等待：
 * 切 Wi-Fi／行動網路、出電梯恢復訊號之後，App 還要傻等最多一分鐘才重連
 * （2026-09-05 使用者：「斷線重連做得很差」）。系統其實會主動廣播網路來了，
 * 這裡把 NetworkCallback 接成 Flow，讓兩條連線迴圈拿它做兩件事：
 *   1. 退避等待中網路一恢復，立刻結束等待去重連；
 *   2. 連線活著但底下的網路換了（舊 socket 多半已是殭屍），主動掐掉重連。
 */
object NetMonitor {

    private val _signals = MutableSharedFlow<Long>(extraBufferCapacity = 8)

    /** 每次「預設網路變了」發射一次，值是 Network 的 handle（辨識是不是換了網路）。 */
    val signals: SharedFlow<Long> = _signals

    /** 目前預設網路的 handle；0＝沒有網路。 */
    @Volatile
    var current: Long = 0L
        private set

    private var started = false

    /**
     * 跑 [body] 直到它自己結束；期間預設網路換了就取消它，讓呼叫端立刻重連。
     *
     * 切網時舊 socket 常常不會立刻報錯（路由沒了但 TCP 還掛著），要等 45 秒
     * readTimeout 才被發現——這段期間畫面顯示連著、實際什麼都收不到。
     */
    suspend fun guard(tag: String, body: suspend () -> Unit): Unit = coroutineScope {
        val netAtStart = current
        val job = launch { body() }
        val watcher = launch {
            signals.collect { handle ->
                if (handle != netAtStart) {
                    Log.i(tag, "網路換了（$netAtStart → $handle），掐掉舊連線重連")
                    job.cancel()
                }
            }
        }
        job.join()
        watcher.cancel()
    }

    /** 在 Application.onCreate 叫一次。重複呼叫無害。 */
    fun start(ctx: Context) {
        if (started) return
        started = true
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        // registerDefaultNetworkCallback：跟著「目前這個 App 會用的那個網路」走，
        // Wi-Fi ↔ 行動網路切換時是一次 onAvailable(新) 而不用自己比較能力
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                current = network.networkHandle
                _signals.tryEmit(network.networkHandle)
            }

            override fun onLost(network: Network) {
                if (current == network.networkHandle) current = 0L
            }
        })
    }
}
