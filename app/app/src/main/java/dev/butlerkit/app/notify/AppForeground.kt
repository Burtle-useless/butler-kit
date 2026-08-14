package dev.butlerkit.app.notify

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
    @Volatile
    var visible: Boolean = false
}
