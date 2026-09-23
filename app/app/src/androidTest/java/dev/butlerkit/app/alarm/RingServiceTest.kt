package dev.butlerkit.app.alarm

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 兩個鬧鐘接連響時，第一個要停得掉。
 *
 * 同一個服務實例第二次 onStartCommand 時若直接 new 一個 MediaPlayer
 * 蓋掉 `player`，第一個繼續循環播放、再也沒有參照停得掉它——按「關掉」只停第二個。
 * 用系統的播放清單數「正在播的鬧鈴」有幾個：修好是 1，舊版是 2。
 */
@RunWith(AndroidJUnit4::class)
class RingServiceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun alarmPlayers(): Int =
        ctx.getSystemService(AudioManager::class.java).activePlaybackConfigurations
            .count { it.audioAttributes.usage == AudioAttributes.USAGE_ALARM }

    private fun ring(id: String) {
        val i = Intent(ctx, RingService::class.java)
            .putExtra(AlarmScheduler.EXTRA_ID, id)
            .putExtra(AlarmScheduler.EXTRA_LABEL, "測試鬧鐘 $id")
            .putExtra(AlarmScheduler.EXTRA_TIME, "07:00")
        ContextCompat.startForegroundService(ctx, i)
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(100)
        }
        return cond()
    }

    @After
    fun tearDown() {
        RingService.stop(ctx)
        waitFor(3000) { alarmPlayers() == 0 }
    }

    @Test
    fun secondAlarmReplacesFirstAndStopSilencesAll() {
        ring("a")
        waitFor(5000) { alarmPlayers() >= 1 }
        assertEquals("第一個鬧鐘在響", 1, alarmPlayers())

        ring("b")
        Thread.sleep(1500)          // 給第二次 onStartCommand 跑完
        assertEquals("第二個鬧鐘響時只有一個在播", 1, alarmPlayers())

        RingService.stop(ctx)
        waitFor(3000) { alarmPlayers() == 0 }
        assertEquals("按關掉之後全部安靜", 0, alarmPlayers())
    }
}
