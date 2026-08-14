package dev.butlerkit.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.notify.AppForeground
import dev.butlerkit.app.notify.ButlerService
import dev.butlerkit.app.notify.Notifier

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

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // 回前景：ViewModel 接手連線
                AppForeground.visible = true
                ButlerService.stop(this@ButlerApp)
                // 打開 App 就等於讀過那些「做完了」「傳了檔案」——不在這裡收掉，
                // 通知欄會一路堆到使用者自己去清
                Notifier.clearSeen(this@ButlerApp)
            }

            override fun onStop(owner: LifecycleOwner) {
                AppForeground.visible = false
                // 進背景：沒設定過連線資訊就不用起服務（還在設定頁）
                if (Prefs(this@ButlerApp).isConfigured()) {
                    ButlerService.start(this@ButlerApp)
                }
            }
        })
    }
}
