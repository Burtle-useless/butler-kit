package dev.butlerkit.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.parseAgenda
import dev.butlerkit.app.notify.NotifyKind
import dev.butlerkit.app.notify.Notifier

/**
 * 時間到了。
 *
 * 這裡跑在主執行緒且只有幾秒壽命，所以只做兩件事：把響鈴交給前景服務、
 * 把「下一次什麼時候響」重新排好。任何網路動作都不能放這裡。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val type = intent.getStringExtra(AlarmScheduler.EXTRA_TYPE) ?: return
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ID).orEmpty()
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL).orEmpty()
        val time = intent.getStringExtra(AlarmScheduler.EXTRA_TIME).orEmpty()
        Log.i(ButlerClient.TAG, "鬧鐘觸發 type=$type id=$id")

        if (type == AlarmScheduler.TYPE_ALARM) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, RingService::class.java)
                    .putExtra(AlarmScheduler.EXTRA_ID, id)
                    .putExtra(AlarmScheduler.EXTRA_LABEL, label)
                    .putExtra(AlarmScheduler.EXTRA_TIME, time),
            )
        } else {
            Notifier.notify(
                ctx, NotifyKind.Reminder,
                title = label.ifBlank { "行程提醒" },
                body = if (time.isBlank()) "時間快到了" else "$time 開始",
            )
        }

        // 重複鬧鐘的下一次要現在就排——AlarmManager 沒有「每週重複」這種東西，
        // 每響一次就得自己接上下一次。順手把整批重排，過期的一次性鬧鐘會自動落榜。
        parseAgenda(Prefs(ctx).agendaCache)?.let { AlarmScheduler.sync(ctx, it) }
    }
}
