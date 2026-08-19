package dev.butlerkit.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.parseAgenda
import dev.butlerkit.app.notify.ButlerService
import dev.butlerkit.app.widget.WidgetTick

/**
 * 重開機後把鬧鐘排回去、把背景連線接回來。
 *
 * AlarmManager 的排程不會跨重開機保留，而開機那一刻 App 沒開、可能連網路都還沒好，
 * 所以只能讀本地快取（Prefs.agendaCache）。快取是每次同步時順手存的。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        // 排程 id 是上一世代的，開機後那些 PendingIntent 早就不存在了；
        // 清掉才不會讓 sync 去取消一堆不存在的東西
        Prefs(ctx).scheduledIds = emptySet()
        // widget 的時間邊界也是 AlarmManager 排的，同樣不跨重開機。
        // 排在 null 檢查之前：沒有快取時 widget 是空的，但「跨到明天」這個邊界
        // 照樣存在，不排的話它會一直停在開機那一刻
        WidgetTick.reschedule(ctx)
        // 背景連線也不跨重開機。少了這一句，開機後在使用者「打開 App 再切出去」之前
        // 服務永遠不會起——助理改的資料推不到手機，widget 就一直是開機那一刻的樣子。
        // 開機廣播是系統允許啟動前景服務的少數時機之一，錯過就得等 App 自己被打開。
        if (Prefs(ctx).isConfigured()) ButlerService.start(ctx)
        val data = parseAgenda(Prefs(ctx).agendaCache)
        if (data == null) {
            Log.i(ButlerClient.TAG, "開機重排：沒有快取，等 App 打開再說")
            return
        }
        AlarmScheduler.sync(ctx, data)
    }
}
