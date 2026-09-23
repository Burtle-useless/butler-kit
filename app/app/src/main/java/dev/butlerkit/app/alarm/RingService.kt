package dev.butlerkit.app.alarm

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.butlerkit.app.R
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.notify.NotifyKind
import dev.butlerkit.app.notify.Notifier

/**
 * 鬧鐘響鈴。
 *
 * 為什麼要一個前景服務，而不是在 Activity 裡播：使用者**正在用手機**的時候，
 * 全螢幕意圖不會直接開 Activity（系統改成顯示 heads-up 通知），
 * 那時候只有這個服務在播鈴聲。反過來在鎖屏時，全螢幕意圖會把 RingActivity
 * 直接拉起來——兩種情況都要響，所以聲音的擁有者是服務不是畫面。
 *
 * 音訊走 USAGE_ALARM：手機轉靜音、媒體音量拉到零，鬧鐘照樣響。
 */
class RingService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val autoStop = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(AlarmScheduler.EXTRA_ID).orEmpty()
        val label = intent?.getStringExtra(AlarmScheduler.EXTRA_LABEL).orEmpty()
            .ifBlank { "鬧鐘" }
        val time = intent?.getStringExtra(AlarmScheduler.EXTRA_TIME).orEmpty()

        // Android 10 起要明講服務型別，14 起不講會直接拋例外。
        // 用 mediaPlayback 而不是 dataSync：這個服務真正在做的事就是播聲音。
        val notif = buildNotification(id, label, time)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
        startRinging()

        // 沒人理就別響到天荒地老（也別把電池吸乾）。五分鐘後自己收工。
        // 先清掉前一個鬧鐘排的收工：兩個鬧鐘接連響時，第一個的計時會把第二個提早切掉。
        autoStop.removeCallbacksAndMessages(null)
        autoStop.postDelayed({ stopSelf() }, AUTO_STOP_MS)
        return START_NOT_STICKY
    }

    private fun buildNotification(id: String, label: String, time: String) =
        NotificationCompat.Builder(this, NotifyKind.Alarm.channelId)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(label)
            .setContentText(if (time.isBlank()) "時間到了" else time)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            // 鎖屏或螢幕關著時，這個意圖會被系統直接拉成全螢幕畫面。
            //
            // Android 14 起這需要 USE_FULL_SCREEN_INTENT 的**執行期許可**——
            // 安裝時只自動給「核心功能是鬧鐘或通話」的 App，而使用者仍可在設定裡關掉。
            // 關掉之後 setFullScreenIntent 不報錯也不生效，只會降級成一般橫幅：
            // 鬧鐘照響，但螢幕不會亮起來、也沒有那個蓋滿畫面的關閉鈕。
            // 這裡至少把它記進 log，不然睡過頭之後完全查不出原因。
            .apply {
                if (canFullScreen()) {
                    setFullScreenIntent(ringIntent(id, label, time), true)
                } else {
                    Log.w(
                        ButlerClient.TAG,
                        "沒有全螢幕通知許可，鬧鐘只會出現橫幅不會蓋滿畫面",
                    )
                }
            }
            .setContentIntent(ringIntent(id, label, time))
            .build()

    /** Android 14 以下一律可用；14 起要問系統。 */
    private fun canFullScreen(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = getSystemService(NotificationManager::class.java) ?: return false
        return nm.canUseFullScreenIntent()
    }

    private fun ringIntent(id: String, label: String, time: String): PendingIntent {
        val i = Intent(this, RingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(AlarmScheduler.EXTRA_ID, id)
            putExtra(AlarmScheduler.EXTRA_LABEL, label)
            putExtra(AlarmScheduler.EXTRA_TIME, time)
        }
        return PendingIntent.getActivity(
            this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun startRinging() {
        Notifier.ensureChannels(this)
        // 前一個鬧鐘還在響（兩個鬧鐘接連觸發，同一個服務實例會再收到一次 onStartCommand）：
        // 先把它停掉放掉。先前直接 new 一個新的蓋掉 `player`，舊的那個繼續循環播放、
        // 再也沒有任何參照停得掉它——按「關掉」只停得了第二個。
        stopRinging()
        runCatching {
            // 手機沒設預設鬧鈴時前兩個都可能是 null，setDataSource(null) 拋例外、
            // 被 runCatching 吞掉，鬧鐘就完全靜音。系統預設鬧鈴的 URI 一定在。
            val uri = RingtoneManager.getActualDefaultRingtoneUri(
                this, RingtoneManager.TYPE_ALARM,
            ) ?: RingtoneManager.getActualDefaultRingtoneUri(
                this, RingtoneManager.TYPE_NOTIFICATION,
            ) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            player = MediaPlayer().apply {
                setDataSource(this@RingService, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Log.w(ButlerClient.TAG, "鬧鈴播不出來：${it.message}") }

        runCatching {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VibratorManager::class.java)).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            val pattern = longArrayOf(0, 600, 700)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }

    /** 停掉並放掉目前的鈴聲與震動。重複呼叫無害。 */
    private fun stopRinging() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
    }

    override fun onDestroy() {
        autoStop.removeCallbacksAndMessages(null)
        stopRinging()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 90210
        private const val AUTO_STOP_MS = 5 * 60 * 1000L

        /** 給 RingActivity 按下「關掉」時用。 */
        fun stop(ctx: android.content.Context) {
            ctx.stopService(Intent(ctx, RingService::class.java))
        }
    }
}
