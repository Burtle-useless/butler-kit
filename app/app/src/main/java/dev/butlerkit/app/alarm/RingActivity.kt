package dev.butlerkit.app.alarm

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.butlerkit.app.ui.PetFace
import dev.butlerkit.app.ui.Palette
import dev.butlerkit.app.ui.PetMood
import dev.butlerkit.app.ui.Radii
import dev.butlerkit.app.ui.Type

/**
 * 鬧鐘響的時候蓋在整個畫面上的東西。
 *
 * 鎖屏也要能顯示、螢幕關著要能自己亮起來——這兩個旗標是「鬧鐘」跟「通知」
 * 的實質差別。聲音不在這裡播（在 RingService），所以就算使用者直接滑掉這個畫面，
 * 鈴聲也還在響，得按「關掉」才會停。
 */
class RingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val id = intent?.getStringExtra(AlarmScheduler.EXTRA_ID).orEmpty()
        val label = intent?.getStringExtra(AlarmScheduler.EXTRA_LABEL).orEmpty()
            .ifBlank { "鬧鐘" }
        val time = intent?.getStringExtra(AlarmScheduler.EXTRA_TIME).orEmpty()

        setContent {
            RingUi(
                label = label,
                time = time,
                onDismiss = {
                    RingService.stop(this)
                    finish()
                },
                onSnooze = {
                    RingService.stop(this)
                    AlarmScheduler.snooze(this, id, label, SNOOZE_MIN)
                    finish()
                },
            )
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }

    companion object {
        const val SNOOZE_MIN = 5
    }
}

@Composable
private fun RingUi(
    label: String,
    time: String,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Palette.Bg).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 半夜被吵醒第一眼看到的是它。用 Talking（嘴巴開合）——是助理在叫你，不是系統在響
        PetFace(PetMood.Talking, 96.dp)
        Spacer(Modifier.height(28.dp))
        if (time.isNotBlank()) {
            Text(time, color = Palette.Accent, fontSize = 56.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
        }
        Text(label, color = Palette.Text, fontSize = 22.sp)
        Spacer(Modifier.height(48.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 貪睡放左邊、關掉放右邊且是實色鈕：半夢半醒時最常按的是右邊那顆，
            // 讓「真的關掉」比較難誤觸不是好意——關不掉才是災難
            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier.weight(1f),
                shape = Radii.Card,
            ) {
                Text("再睡 ${RingActivity.SNOOZE_MIN} 分",
                    color = Palette.TextDim, fontSize = Type.Body)
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                shape = Radii.Card,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Accent, contentColor = Palette.Bg,
                ),
            ) { Text("關掉", fontSize = Type.Body) }
        }
    }
}
