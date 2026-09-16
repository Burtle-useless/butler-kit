package dev.butlerkit.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 使用者把某一則子通知滑掉了。
 *
 * 存在的唯一理由是重算群組摘要。系統移除子通知時不會告訴 App，摘要於是繼續掛著
 * 「3 則」而底下一則都不剩——點開是空的，只能自己再滑一次。
 * 點掉那條路徑有 App 啟動時的 `clearSeen` 兜著，滑掉這條原本沒有任何人接。
 */
class NotifyDismissReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notifier.onChildDismissed(ctx, intent.getIntExtra(EXTRA_ID, 0))
    }

    companion object {
        const val EXTRA_ID = "notif_id"
    }
}
