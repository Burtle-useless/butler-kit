package dev.butlerkit.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.butlerkit.app.net.ButlerClient

/**
 * widget 的統一刷新入口。
 *
 * 分成 agenda 與 usage 兩組而不是一律全刷：三張 widget 的資料來源不同，
 * 課表改了去重畫用量那張只是白費一次 RemoteViews 更新。
 *
 * **為什麼是發廣播而不是 Glance 的 `updateAll`**：實測 `updateAll` 只有在
 * 內容從無到有時會真的推出新畫面，反過來（課刪光了、行程清空了）它會安靜地
 * 什麼都不做，桌面上就一直掛著已經不存在的課。快取確實是新的、provideGlance
 * 也讀得到新值，卡在 Glance 那層沒把 RemoteViews 送出去。
 * 發 ACTION_APPWIDGET_UPDATE 走的是系統原生那條路，GlanceAppWidgetReceiver
 * 收到後會完整重跑一次，兩個方向都準。
 *
 * 每支都吞例外。widget 沒裝、或系統正在重啟時查詢 id 會拋，
 * 而這些呼叫點全都掛在「拉完資料之後」——為了畫不出桌面小工具而讓資料流程炸掉，
 * 因果完全顛倒。
 */
object Widgets {

    /** 課表與今日行程。兩張都吃 agendaCache，所以一起刷。 */
    fun refreshAgenda(ctx: Context) {
        poke(ctx, CourseWidgetReceiver::class.java)
        poke(ctx, TodayWidgetReceiver::class.java)
    }

    fun refreshUsage(ctx: Context) {
        poke(ctx, UsageWidgetReceiver::class.java)
    }

    /**
     * 叫某一張 widget 重畫。桌面上沒有這張就直接返回——
     * 對空的 id 陣列發廣播，某些 launcher 會當成「更新全部」。
     */
    private fun poke(ctx: Context, cls: Class<*>) {
        runCatching {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            if (ids.isEmpty()) return
            ctx.sendBroadcast(
                Intent(ctx, cls).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }.onFailure { Log.w(ButlerClient.TAG, "刷新 widget 失敗：${it.message}") }
    }
}
