package dev.butlerkit.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.parseAgenda

/**
 * 重開機後把鬧鐘排回去。
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
        val data = parseAgenda(Prefs(ctx).agendaCache)
        if (data == null) {
            Log.i(ButlerClient.TAG, "開機重排：沒有快取，等 App 打開再說")
            return
        }
        AlarmScheduler.sync(ctx, data)
    }
}
