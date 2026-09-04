package dev.butlerkit.app.voice

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.butlerkit.app.ui.Palette
import dev.butlerkit.app.ui.Radii

/**
 * 對著手機講，字進輸入框。
 *
 * 三個刻意的決定：
 *  - **不自動送出**。辨識偶爾會出錯字，送出前讓人改一下比重講一遍快，
 *    而且講到一半想補打幾個字也行。
 *  - **辨識結果接在現有文字後面**，不覆蓋。講一段、打一段、再講一段是常見用法。
 *  - **手機上沒有辨識引擎就整顆不出現**，而不是點了才說不行。
 *
 * 失敗訊息走 Toast。這是全 App 唯一用 Toast 的地方——輸入列上下都沒有空位放
 * 一行錯誤字，而它本身也是講完就該消失的東西。
 */
@Composable
fun DictateKey(onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val speech = remember { SpeechInput(ctx) }
    // 引擎有沒有裝，一輩子不會變，查一次就好
    val usable = remember { speech.available() }
    if (!usable) return

    val heard by speech.state.collectAsState()
    // 下面那條 collect 只跑一次，直接捕捉 onText 會鎖在第一次重繪的那個 lambda
    val emit by rememberUpdatedState(onText)

    val askMic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 剛授權就直接開始聽，不要求他再按一次
        if (granted) speech.start(TalkLang.ZH)
        else Toast.makeText(ctx, "沒有麥克風權限", Toast.LENGTH_SHORT).show()
    }

    DisposableEffect(Unit) {
        onDispose { speech.release() }
    }

    // 定稿與失敗是**事件**，用 collect 收而不是看 state 快照：
    // 連續講兩句一樣的話時 Heard.Final 的內容相同，靠 key 變化的寫法會漏掉第二次。
    LaunchedEffect(Unit) {
        speech.state.collect { h ->
            when (h) {
                is Heard.Final -> emit(h.text)
                is Heard.Failed -> Toast.makeText(ctx, h.why, Toast.LENGTH_LONG).show()
                else -> Unit
            }
        }
    }

    val listening = heard is Heard.Listening
    Box(
        // 方框墨線，收音中換朱紅框——印刷風的按鈕是框不是圓（同輸入列其他鈕）
        modifier.size(44.dp)
            .clip(Radii.Field)
            .background(if (listening) Palette.AccentSoft else Palette.Bg)
            .border(1.dp, if (listening) Palette.Accent else Palette.Line, Radii.Field)
            // 圖示是字元（◉／■），讀螢幕軟體會唸成標點，說明要另外給
            .semantics { contentDescription = if (listening) "停止聽寫" else "講話填字" }
            .clickable(role = Role.Button) {
                when {
                    listening -> speech.stop()
                    ContextCompat.checkSelfPermission(
                        ctx, Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED -> speech.start(TalkLang.ZH)
                    else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (listening) "■" else "◉",
            fontSize = if (listening) 13.sp else 17.sp,
            fontWeight = FontWeight.Bold,
            color = if (listening) Palette.Accent else Palette.TextDim,
        )
    }
}
