package dev.butlerkit.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.butlerkit.app.MainActivity
import dev.butlerkit.app.R

/**
 * 通知種類。
 *
 * 刻意一開始就分類而不是只做「任務完成通知」：行事曆、鬧鐘、排程提醒這些
 * 「時間到了要主動找人」的功能全部建在這條管道上，沒有分類的話它們只能共用
 * 同一種重要度——鬧鐘該吵、任務完成該安靜，混在一起兩邊都不對。
 * 每一種是獨立的系統通知頻道，使用者可以在系統設定裡逐一開關。
 */
enum class NotifyKind(
    val channelId: String,
    val channelName: String,
    val importance: Int,
    val desc: String,
    /**
     * App 開著的時候要不要安靜。
     *
     * 對話類的通知一律安靜——人就在看畫面，內容已經在眼前了，再推一則只是噪音，
     * 每個通訊軟體都是這個邏輯。時間類的（鬧鐘、排程提醒）不受影響：那是「時間到了」
     * 而不是「有新訊息」，人在看手機也還是要提醒他。
     */
    val quietInForeground: Boolean = false,
    /**
     * 要不要摺進「助理」那一組。
     *
     * 目前跟 quietInForeground 剛好同一批，但這是兩件事：那個管的是「現在該不該出聲」，
     * 這個管的是「事後在通知欄怎麼排」。鬧鐘與行程提醒絕對不能進組——那兩種的重點
     * 就是攤在畫面上讓人看見，被摺起來等於沒響。
     */
    val grouped: Boolean = false,
) {
    /**
     * 長任務跑完，或是出了狀況。
     *
     * channel id 帶 v2 是因為**通知類別一旦建立，重要度就改不掉了**——
     * 原本是 IMPORTANCE_LOW，只會安靜躺在通知列裡看不到，使用者回報「不明顯」。
     * 換一個 id 才能真的變成會蓋在畫面上的橫幅，舊的那個在 ensureChannels 裡刪掉。
     */
    TaskDone("task_done_v2", "任務完成", NotificationManager.IMPORTANCE_HIGH,
        "工作做完或出狀況時通知你", quietInForeground = true, grouped = true),

    /** 助理在等你回答。會出聲——沒人回答的話那件事就卡住了。 */
    NeedsYou("needs_you", "等你回答", NotificationManager.IMPORTANCE_HIGH,
        "助理需要你做決定才能繼續", quietInForeground = true, grouped = true),

    /** 排程提醒。 */
    Reminder("reminder", "提醒", NotificationManager.IMPORTANCE_HIGH,
        "你交代的排程提醒"),

    /** 助理把電腦上的檔案傳過來了。 */
    FileReady("file_ready", "助理傳來的檔案", NotificationManager.IMPORTANCE_DEFAULT,
        "助理把電腦上的檔案傳給你時通知你", quietInForeground = true, grouped = true),

    /** 鬧鐘：最高優先，要能吵醒人。 */
    Alarm("alarm", "鬧鐘", NotificationManager.IMPORTANCE_HIGH,
        "鬧鐘，會出聲並蓋在畫面上"),

    /** 前景服務的常駐通知。最低重要度，只是系統規定要有。 */
    Service("service", "背景連線", NotificationManager.IMPORTANCE_MIN,
        "維持與電腦的連線，讓助理在背景也能通知你"),
}

object Notifier {

    /**
     * 對話類通知的群組鍵。系統會把同組的摺成一則，助理在通知欄永遠只佔一格。
     * 進組的條件見 [NotifyKind.grouped]。
     */
    private const val GROUP_CHAT = "dev.butlerkit.app.CHAT"

    /** 群組摘要的 id。**必須固定**，否則每次更新都是多疊一則摘要而不是改寫。 */
    private const val SUMMARY_ID = 4300

    /**
     * 通知 id。低 3 位放種類、其餘放對話的雜湊。
     *
     * 原本是 `ordinal * 1000 + hash`：種類之間只差 1000，雜湊值一撞就會誤蓋掉
     * 另一種通知。清除邏輯正是靠這個算式反推 id 的（見 [clearConv]），
     * 撞了就會清錯人，所以改成不可能互撞的位元排法。
     * （internal 是為了單元測試看得到。）
     */
    internal fun idOf(kind: NotifyKind, convId: String?): Int =
        ((convId?.hashCode() ?: 0) shl 3) or kind.ordinal

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        NotifyKind.entries.forEach { k ->
            val ch = NotificationChannel(k.channelId, k.channelName, k.importance).apply {
                description = k.desc
                if (k == NotifyKind.Service) setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }
        // 舊的低重要度「任務完成」類別。留著的話系統設定裡會有兩筆同名項目，
        // 而且使用者若曾經調整過它，那些設定會誤導人（它已經不發任何通知了）。
        mgr.deleteNotificationChannel("task_done")
    }

    /** 點通知的落點：回到 App 並帶上要開啟的對話。 */
    private fun openIntent(ctx: Context, convId: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            convId?.let { putExtra(EXTRA_CONV, it) }
        }
        return PendingIntent.getActivity(
            ctx, convId.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 子通知被滑掉時回來重算摘要的落點。見 [NotifyDismissReceiver]。 */
    private fun dismissIntent(ctx: Context, notifId: Int): PendingIntent {
        val i = Intent(ctx, NotifyDismissReceiver::class.java)
            .putExtra(NotifyDismissReceiver.EXTRA_ID, notifId)
        return PendingIntent.getBroadcast(
            ctx, notifId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 子通知被滑掉之後重算摘要。[gone] 是剛被移除的那一則，避免它還列在 active 裡。 */
    internal fun onChildDismissed(ctx: Context, gone: Int) {
        syncSummary(ctx, setOf(gone))
    }

    fun notify(
        ctx: Context,
        kind: NotifyKind,
        title: String,
        body: String,
        convId: String? = null,
    ) {
        // 人就在看畫面，內容已經在眼前了。這道閘門擋的是「服務還沒完全停下來」
        // 的那段空窗——不能靠「背景服務有沒有在跑」間接判斷，見 AppForeground。
        if (kind.quietInForeground && AppForeground.visible) return
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, kind.channelId)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openIntent(ctx, convId))
            .setAutoCancel(true)
            .setPriority(
                when (kind) {
                    NotifyKind.Service -> NotificationCompat.PRIORITY_MIN
                    else -> NotificationCompat.PRIORITY_HIGH
                },
            )
            .apply {
                // Android 8 之後決定會不會蓋在畫面上的是 channel 的重要度，
                // 這兩行只對更舊的系統有效，但拿掉的話那些機器就退回靜默通知。
                if (kind != NotifyKind.Service) setDefaults(Notification.DEFAULT_ALL)
                // 鬧鐘與等你回答歸在「來電」類：這類在勿擾模式下仍可能穿透，
                // 而且系統會給它更長的橫幅停留時間。任務完成不該搶到這個層級。
                if (kind == NotifyKind.NeedsYou || kind == NotifyKind.Alarm) {
                    setCategory(NotificationCompat.CATEGORY_CALL)
                }
                if (kind.grouped) {
                    setGroup(GROUP_CHAT)
                    // 被滑掉時要能收到消息。系統移除子通知不會通知 App，摘要於是
                    // 留在那裡說「3 則」而底下一則都不剩——點開什麼都沒有的空殼。
                    // 點掉那條路有 App 啟動時的 clearSeen 兜著，滑掉這條原本沒人接。
                    setDeleteIntent(dismissIntent(ctx, idOf(kind, convId)))
                    // 出聲的是子通知本身，摘要只負責摺疊。這個值必須在摘要與
                    // 每一則子通知上都設成一樣，只設一邊不會生效。
                    setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                }
            }
            .build()
        // 權限沒給時 notify 會拋 SecurityException，不能讓它炸掉呼叫端
        runCatching { NotificationManagerCompat.from(ctx).notify(idOf(kind, convId), n) }
        if (kind.grouped) syncSummary(ctx)
    }

    // ── 摺疊與清除 ────────────────────────────────────────────────────────

    /**
     * 重算群組摘要。
     *
     * 系統不會在子通知被清光時自己收掉摘要，那會留下一則點開什麼都沒有的空殼，
     * 所以每次發送與清除之後都要重算一次。
     *
     * 摘要掛在「任務完成」這個類別下：摘要本身也必須屬於某個類別，而子通知橫跨三個，
     * 只能挑一個。代價是使用者若在系統設定裡關掉「任務完成」，摘要會消失、剩下的
     * 通知退回各自獨立顯示——會醜，但不會漏掉任何一則。
     *
     * @param ignore 剛送出取消的 id。cancel 是非同步的，activeNotifications 當下
     *   可能還讀得到它們，不排掉就會重建出一則數字錯誤的摘要。
     */
    private fun syncSummary(ctx: Context, ignore: Set<Int> = emptySet()) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        val nm = NotificationManagerCompat.from(ctx)
        val kids = runCatching {
            mgr.activeNotifications.filter {
                it.id != SUMMARY_ID && it.id !in ignore && it.notification.group == GROUP_CHAT
            }
        }.getOrDefault(emptyList())
        if (kids.isEmpty()) {
            runCatching { nm.cancel(SUMMARY_ID) }
            return
        }
        val style = NotificationCompat.InboxStyle()
        kids.take(6).forEach { sbn ->
            val t = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)
            if (t != null) style.addLine(t)
        }
        val summary = NotificationCompat.Builder(ctx, NotifyKind.TaskDone.channelId)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("助理")
            .setContentText("${kids.size} 則")
            .setStyle(style)
            .setGroup(GROUP_CHAT)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(openIntent(ctx, null))
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        runCatching { nm.notify(SUMMARY_ID, summary) }
    }

    /**
     * 這個對話已經被打開看過了，收掉它的通知。
     *
     * 在此之前程式裡沒有任何一處會取消通知——不手動滑掉就會一直堆著，
     * 這正是使用者說的「累積成百上千則」。
     */
    fun clearConv(ctx: Context, convId: String?) {
        val nm = NotificationManagerCompat.from(ctx)
        val ids = NotifyKind.entries.filter { it.grouped }.map { idOf(it, convId) }.toSet()
        ids.forEach { runCatching { nm.cancel(it) } }
        syncSummary(ctx, ids)
    }

    /** 只收掉某對話的某一種通知（例如題目在別台答完了，收「等你回答」）。 */
    fun clearKind(ctx: Context, kind: NotifyKind, convId: String?) {
        val id = idOf(kind, convId)
        runCatching { NotificationManagerCompat.from(ctx).cancel(id) }
        if (kind.grouped) syncSummary(ctx, setOf(id))
    }

    /**
     * App 回到前景：清掉「純告知」那類——做完了、傳了檔案，人一打開就看得到了。
     *
     * 「等你回答」刻意留著：那件事還卡在那裡等人決定，只是把 App 點開不算處理過，
     * 要真的進到那個對話才算（見 [clearConv]）。
     */
    fun clearSeen(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        val seen = setOf(NotifyKind.TaskDone.channelId, NotifyKind.FileReady.channelId)
        // 這裡不能用 idOf 反推：告知類通知橫跨所有對話，有哪些 conv_id 只有系統知道。
        // 摘要也在 TaskDone 類別下，會一起被收掉，之後由 syncSummary 依剩下的重建。
        val gone = runCatching {
            mgr.activeNotifications
                .filter { it.notification.channelId in seen }
                .map { it.id }
        }.getOrDefault(emptyList()).toSet()
        val nm = NotificationManagerCompat.from(ctx)
        gone.forEach { runCatching { nm.cancel(it) } }
        syncSummary(ctx, gone + SUMMARY_ID)
    }

    /**
     * 前景服務的通知。
     *
     * 不再寫「助理在待命」：服務現在只在助理真的手上有事時才開，做完就自己收掉
     * （見 ButlerService）。掛著一則說自己在待命的通知，正是使用者要求拿掉的東西。
     */
    fun serviceNotification(ctx: Context, text: String): Notification {
        ensureChannels(ctx)
        return NotificationCompat.Builder(ctx, NotifyKind.Service.channelId)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("助理正在做事")
            .setContentText(text)
            .setContentIntent(openIntent(ctx, null))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    const val EXTRA_CONV = "conv_id"
}
