package dev.butlerkit.app.net

import dev.butlerkit.app.BuildConfig
import okhttp3.Interceptor

/**
 * Cloudflare Access 的服務憑證。**沒設就是完全不動作**，可以整支忽略。
 *
 * 走 Cloudflare Tunnel 對外的人（見 `docs/transport.md`）該在通道前面再擋一層
 * Cloudflare Access：沒有這兩個標頭的請求在 Cloudflare 那一層就被回 403，
 * 連你的電腦都碰不到。那是取代「進得了 tailnet 才看得到這個 port」的那道牆——
 * 隧道把服務公開到網際網路上，device token 是最後一道而不是唯一一道。
 *
 * 憑證由 Gradle 從 `app/local.properties` 注入（見 `app/build.gradle.kts`）：
 *
 *     cfAccessClientId=xxxxx.access
 *     cfAccessClientSecret=yyyyy
 *
 * 那個檔在 .gitignore 裡，**不要把憑證寫進原始碼**——這個 repo 是公開的，
 * 硬編等於把「任何人都能打進這台電腦」的鑰匙貼上 GitHub。
 *
 * 讀不到就是空字串，這時候一個標頭都不加：走 Tailscale、區網或其他 VPN 直連的人
 * 沒有這條通道，硬塞標頭只是多送兩個沒人看的欄位。
 *
 * 帶著憑證去連區網位址也無害（伺服器不認得就忽略），所以這裡不按網址分流——
 * 分流意味著要判斷「現在連的是哪一條」，而那個判斷會在使用者手動改主機欄位時出錯。
 *
 * device token（Authorization: Bearer）是另一層，兩者都要過。Access 管的是
 * 「這個請求能不能進到這台電腦」，device token 管的是「這支手機還在你手上嗎」。
 */
object AccessAuth {

    private val clientId: String = BuildConfig.CF_ACCESS_CLIENT_ID
    private val clientSecret: String = BuildConfig.CF_ACCESS_CLIENT_SECRET

    /** 這個 build 有沒有帶憑證。設定頁要顯示連線方式時用得到。 */
    val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    /** 套在 OkHttpClient 上，所有請求（含 SSE 與健康檢查）一律帶到。 */
    val interceptor = Interceptor { chain ->
        val req = chain.request()
        if (!configured) return@Interceptor chain.proceed(req)
        chain.proceed(
            req.newBuilder()
                .header("CF-Access-Client-Id", clientId)
                .header("CF-Access-Client-Secret", clientSecret)
                .build()
        )
    }
}
