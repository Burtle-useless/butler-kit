package dev.butlerkit.app.voice

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * 帶使用者去下載離線語音辨識包。
 *
 * Android **沒有**公開 Intent 直接開「離線語音辨識」那一頁——它是 Google App 自己的
 * 設定畫面，各家 ROM 擺的位置還不一樣。所以只能一路往回退：先試系統的語音輸入設定，
 * 開不起來就丟到設定首頁，最後一步讓他自己點。
 *
 * 也不能只在錯誤訊息裡寫「請到設定 → Google → 語音」就算了：那條路要點五層，
 * 而 App 自己就知道現在缺哪個語言。
 */
fun openVoiceInputSettings(context: Context) {
    // 由準到粗排；ACTION_VOICE_INPUT_SETTINGS 在部分 ROM 上不存在
    val routes = listOf(
        Settings.ACTION_VOICE_INPUT_SETTINGS,
        Settings.ACTION_SETTINGS,
    )
    for (action in routes) {
        try {
            context.startActivity(
                Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        } catch (e: ActivityNotFoundException) {
            Log.w("butler-voice", "開不了 $action", e)
        }
    }
    // 連設定首頁都開不起來時要說話。靜默不動作看起來就是按鈕壞了。
    Toast.makeText(
        context,
        "開不了設定。自己到「設定 → Google → 所有服務 → 語音 → 離線語音辨識」下載。",
        Toast.LENGTH_LONG,
    ).show()
}
