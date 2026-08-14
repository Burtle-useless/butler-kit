package dev.butlerkit.app.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.getSystemService

/**
 * 執行破壞性指令前的本人確認。
 *
 * 走系統的「裝置憑證」畫面（指紋／臉／PIN／圖形都能過），不引入 androidx.biometric——
 * 那個要求 Activity 是 FragmentActivity，為了一道確認去換掉整個 App 的 base class
 * 不划算，而使用者要的保障是「撿到手機的人按不下去」，鎖屏憑證就滿足了。
 *
 * **沒設鎖屏的手機一律直接放行**（見 [needsDeviceAuth]）：擋下去的話那台手機永遠
 * 批准不了任何指令，助理會卡在那裡等到逾時，比沒有這道鎖更糟。
 */

/**
 * 這次「執行」該不該先要一次本人確認。
 *
 * @param require 伺服器有沒有要求（AskRequest.requireBiometric）
 * @param isDangerChoice 按的是不是那個會真的執行下去的選項——取消不必驗
 * @param deviceSecure 這台手機有沒有設鎖屏
 */
fun needsDeviceAuth(require: Boolean, isDangerChoice: Boolean, deviceSecure: Boolean): Boolean =
    require && isDangerChoice && deviceSecure

/** 從 Compose 的 Context 往上找到 Activity（launcher 與系統畫面都需要它）。 */
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * 回傳一個「先驗身分再做事」的呼叫器。
 *
 * 拿到的 lambda 收一個 action：需要驗證就跳系統畫面、通過才執行；
 * 不需要（或這台手機沒鎖屏）就當場執行。驗證失敗或使用者按返回＝什麼都不做，
 * 卡片維持在等待狀態，人可以再按一次。
 */
@Composable
fun rememberDeviceAuth(): DeviceAuth {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val km = remember(ctx) { ctx.getSystemService<KeyguardManager>() }
    // 通過後要執行的動作暫存在這；系統畫面是另一個 Activity，回來時原本的
    // lambda 已經不在呼叫堆疊上了，只能靠這個欄位接回來。
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pending.value
        pending.value = null
        if (result.resultCode == Activity.RESULT_OK) action?.invoke()
    }
    return remember(km) {
        DeviceAuth { require, isDangerChoice, action ->
            val secure = km?.isDeviceSecure == true
            if (!needsDeviceAuth(require, isDangerChoice, secure)) {
                action()
                return@DeviceAuth
            }
            val intent = km?.createConfirmDeviceCredentialIntent(
                "確認是本人", "這個指令會動到電腦上的東西",
            )
            if (intent == null) {
                // 理論上 isDeviceSecure 為真就拿得到，拿不到時放行而非卡住
                action()
            } else {
                pending.value = action
                launcher.launch(intent)
            }
        }
    }
}

/** [rememberDeviceAuth] 的回傳型別：`auth(require, isDangerChoice) { 真正要做的事 }`。 */
fun interface DeviceAuth {
    operator fun invoke(require: Boolean, isDangerChoice: Boolean, action: () -> Unit)
}
