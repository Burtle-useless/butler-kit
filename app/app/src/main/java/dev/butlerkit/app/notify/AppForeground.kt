package dev.butlerkit.app.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App 現在是不是開著的（使用者看得到畫面）。
 *
 * 存在的理由是「看著對話畫面就不要另外跳通知」——每個通訊軟體都是這個邏輯。
 * 照理說背景服務只在背景跑、前景時早就被停掉了，但 stopService 是非同步的，
 * 服務的 SSE 連線還會活一小段，而且 START_STICKY 會讓系統把它拉回來；
 * 靠「服務有沒有在跑」間接判斷前景，就是使用者看到的那些多餘通知。
 * 這裡改成直接記錄事實，由 [dev.butlerkit.app.ButlerApp] 的生命週期回呼維護。
 *
 * 用 @Volatile 而不是 ProcessLifecycleOwner 直接查：那個只能在主執行緒讀，
 * 而通知是在服務的背景協程裡發的。
 */
object AppForeground {
    private val _flow = MutableStateFlow(false)

    /**
     * 同一件事的可觀察版本。
     *
     * ViewModel 的 SSE 迴圈要靠它決定「現在該不該由我連」——架構上約定同時只有一條
     * 連線（前景 ViewModel、背景 ButlerService），但那個約定原本只寫在註解裡，
     * 進背景時**沒有任何程式碼真的把前景那條停掉**。兩條並存的後果不只是多一份
     * 流量：它們共用 `prefs.lastSeq` 這一個游標，互相覆寫之後畫面會重複、
     * 或者背景那條的續傳基準被拉走而漏掉推播。
     */
    val flow: StateFlow<Boolean> = _flow.asStateFlow()

    @Volatile
    var visible: Boolean = false
        set(v) {
            field = v
            _flow.value = v
        }
}
