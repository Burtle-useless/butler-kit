package dev.butlerkit.app.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.core.content.ContextCompat
import dev.butlerkit.app.ui.ButlerColors
import dev.butlerkit.app.ui.SerifProbe

/**
 * 面對面翻譯的獨立畫面。
 *
 * 兩個只有 Activity 層級才做得到的事，就是它不當分頁的原因：
 *  - [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]：講話中間手機不能暗掉，
 *    對話進行時沒人會每十五秒去摸一下螢幕。
 *  - 全螢幕、沒有底部導覽列來吃掉對面那半邊的空間。
 */
class TalkActivity : ComponentActivity() {

    private var micGranted by mutableStateOf(false)

    private val askMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> micGranted = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) askMic.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            // 跟 MainActivity 用同一份完整色盤。這裡原本只覆寫 surface 與 background
            // 兩個欄位，其餘全是 M3 預設的淡紫——水波紋、輸入框游標與邊框、
            // 下拉選單底色全都跟 App 其他地方不一樣，一進翻譯畫面就像換了個 App。
            MaterialTheme(colorScheme = ButlerColors) {
                // 預設襯線，跟 MainActivity 同一套（報紙內文）
                ProvideTextStyle(TextStyle(fontFamily = SerifProbe.Serif)) {
                    TalkScreen(micGranted = micGranted, onClose = { finish() })
                }
            }
        }
    }
}
