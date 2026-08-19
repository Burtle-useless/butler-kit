package dev.butlerkit.app.widget

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.net.ButlerClient
import java.util.concurrent.TimeUnit

/**
 * 定時把用量拉進 [Prefs.usageCache]，好讓用量 widget 有東西可畫。
 *
 * 課表與行程不需要這個——助理改資料時伺服器會推 agenda.changed，App 收到就重拉並
 * 順手刷 widget，是事件驅動的。而額度是 Anthropic 那邊的數字，**沒有人會通知我們**，
 * 只能自己定期去問。
 */
class UsageWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        // 還沒配對就不要浪費一次喚醒去打一個必定 401 的請求
        if (!prefs.isConfigured()) return Result.success()

        return ButlerClient(prefs).getUsageRaw()
            .onSuccess { raw ->
                // 先寫時間再寫內容：widget 訂閱的是內容那個 key，
                // 反過來寫的話它會拿新數字配上一輪的時間戳
                prefs.usageAt = System.currentTimeMillis()
                prefs.usageCache = raw
                refreshUsageWidget()
            }
            .onFailure { Log.w(ButlerClient.TAG, "拉用量失敗：${it.message}") }
            // 失敗給 retry：電腦可能只是暫時關著，WorkManager 會照退避重試。
            // 舊快取仍然留著，widget 上的「N 小時前」會誠實地愈變愈大
            .fold({ Result.success() }, { Result.retry() })
    }

    private suspend fun refreshUsageWidget() = Widgets.refreshUsage(applicationContext)

    companion object {
        private const val NAME = "usage-widget"
        private const val NAME_NOW = "usage-widget-now"

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * 排定期拉取。15 分鐘是 WorkManager 允許的最小週期。
         *
         * 本來放 30 分鐘想省電，但實測會出事：5 小時額度在重度使用時半小時能跳十個
         * 百分點，桌面上那個數字跟著錯半小時，而使用者是照它決定要不要繼續操。
         * 一次請求的成本遠小於看錯額度的代價。
         *
         * UPDATE 而不是 KEEP：週期改過了，KEEP 會讓已經裝著的舊排程原封不動留著。
         */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UsageWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.UPDATE, req,
            )
        }

        /**
         * 立刻拉一次。給「時間邊界醒來」與「App 回到前景」這兩個時機用。
         *
         * KEEP：連續觸發時只跑第一次就好，重複打同一個端點拿同一個數字沒有意義。
         */
        fun runOnce(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<UsageWorker>()
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                NAME_NOW, ExistingWorkPolicy.KEEP, req,
            )
        }
    }
}
