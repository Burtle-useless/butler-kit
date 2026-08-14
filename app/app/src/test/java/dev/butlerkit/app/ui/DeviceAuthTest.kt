package dev.butlerkit.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 破壞性指令執行前，什麼情況下要跳系統的本人確認。 */
class DeviceAuthTest {

    @Test
    fun `伺服器要求且按的是執行時要驗身分`() {
        assertTrue(needsDeviceAuth(require = true, isDangerChoice = true, deviceSecure = true))
    }

    @Test
    fun `取消不必驗身分`() {
        // 取消是「不做事」，擋在鎖屏後面只會讓人更難喊停
        assertFalse(needsDeviceAuth(require = true, isDangerChoice = false, deviceSecure = true))
    }

    @Test
    fun `伺服器沒要求就不驗`() {
        assertFalse(needsDeviceAuth(require = false, isDangerChoice = true, deviceSecure = true))
    }

    @Test
    fun `沒設鎖屏的手機直接放行`() {
        // 擋下去的話這台手機永遠批准不了任何指令，助理會一路卡到逾時
        assertFalse(needsDeviceAuth(require = true, isDangerChoice = true, deviceSecure = false))
    }
}
