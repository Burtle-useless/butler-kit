package dev.butlerkit.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.notify.AppForeground
import dev.butlerkit.app.notify.ButlerService
import dev.butlerkit.app.notify.Notifier
import dev.butlerkit.app.ui.SerifProbe
import dev.butlerkit.app.widget.UsageWorker
import dev.butlerkit.app.widget.WidgetTick

/**
 * 前景／背景交接：同時只留一條 SSE 連線。
 *
 * 前景時由 ChatViewModel 連（要即時渲染），背景時交給 ButlerService 連（只發通知）。
 * 兩邊同時連也能運作——伺服器是廣播給所有訂閱者——但那是白白多一份流量與電。
 */
class ButlerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        // 先解析中文襯線字型（見 SerifProbe）。整套視覺建立在襯線字上，而
        // FontFamily.Serif 對中文是無效的，要直接指名系統上那個 CJK 檔。
        SerifProbe.Serif
        // widget 的兩條更新路線。放 Application 而不是 MainActivity：widget 貼在桌面上
        // 不代表使用者會打開 App，掛在 Activity 上的話重開機後可能好幾天都沒排到。
        // 兩支都是冪等的（unique work 用 KEEP、alarm 用同一個 requestCode 覆蓋）
        UsageWorker.schedule(this)
        WidgetTick.reschedule(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // 回前景：ViewModel 接手連線
                AppForeground.visible = true
                ButlerService.stop(this@ButlerApp)
                // 打開 App 的當下最可能低頭看桌面 widget，順手把額度拉新一次。
                // 定期工作最快也要 15 分鐘才輪到，中間那段就是靠這裡補
                UsageWorker.runOnce(this@ButlerApp)
                // 打開 App 就等於讀過那些「做完了」「傳了檔案」——不在這裡收掉，
                // 通知欄會一路堆到使用者自己去清
                Notifier.clearSeen(this@ButlerApp)
            }

            override fun onStop(owner: LifecycleOwner) {
                AppForeground.visible = false
                // 進背景：沒設定過連線資訊就不用起服務（還在設定頁）
                val prefs = Prefs(this@ButlerApp)
                if (prefs.isConfigured()) {
                    // 交接的同時把背景游標快進到畫面已經看到的位置。
                    //
                    // 前景期間 bgSeq 完全不動——那條連線是停著的，只有 ViewModel 在
                    // 推進 lastSeq。所以一退出 App，服務就帶著進前景之前的老游標重連，
                    // 伺服器照規矩把中間的事件整段重播，onReplyFinal 分不出那是新的還是
                    // 補的，於是剛剛在畫面上看完的回覆又變成一則「做完了」。
                    // 使用者 2026-08-18 回報「看著助理的時候不跳，退出才跳，但我已經看完了」
                    // 就是這個。人在前景看過的，對通知來說就等於已讀。
                    //
                    // 取 max 而不是直接指派：App 在背景待久了 bgSeq 會比 lastSeq 新，
                    // 這時打開 App 又馬上退出（ViewModel 還沒補完事件），直接指派會
                    // 把游標往回拉，那些真的沒看過的就會被重播成重複通知。
                    prefs.bgSeq = maxOf(prefs.bgSeq, prefs.lastSeq)
                    ButlerService.start(this@ButlerApp)
                }
            }
        })
    }
}
