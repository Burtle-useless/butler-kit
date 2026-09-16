package dev.butlerkit.app.net

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 「他人在哪」：抓一次 GPS、轉成地址回報給伺服器。
 *
 * 兩條路徑共用同一份實作：
 * 1. **定期回報**（[reportPeriodically]）——服務跑著的時候每 [INTERVAL_MS] 送一次，
 *    讓助理隨時有一筆夠新的位置可用。
 * 2. **被問才抓**（[onDeviceRequest]）——助理需要當下位置時發 `device.request`。
 *
 * 兩條路徑不重複做工：伺服器那邊的 `where_am_i` 預設接受五分鐘內的位置，
 * 定期回報一直在餵，所以助理多半直接命中快取，根本不會發請求下來。真的發下來
 * 的時候（快取過期、或它指定要最新的），才是這裡第二條路徑派上用場的時機。
 *
 * 為什麼是 object 而不是寫在服務裡：**同時只有一條 SSE 連線**，前景時是
 * ViewModel、背景時是 ButlerService（見 ButlerService 的類別說明）。只掛在服務上
 * 的話，「開著 App 的時候問位置反而沒人回」——而那正是最常發生的情況。
 * 兩邊各自在事件分派處呼叫 [onDeviceRequest]，共用這一份實作。
 *
 * 被問到時失敗一律要回報。伺服器那端在等，不回的話它只能空等到逾時，
 * 而「權限被關掉」跟「手機沒開」對助理來說該講出不一樣的話。
 */
object Locator {

    /**
     * 定期回報的間隔。
     *
     * 15 分鐘是拿電量換新鮮度的取捨點：搭配 BALANCED_POWER_ACCURACY（不強制開 GPS，
     * 吃 Wi-Fi 與基地台），一天約 96 次，實際耗電遠低於「一直訂閱位置更新」。
     * 再短下去省不到什麼——人的位置本來就不會每五分鐘變一次，而伺服器的快取
     * 就是五分鐘，比它短只是重複覆蓋同一筆。
     */
    private const val INTERVAL_MS = 15 * 60 * 1000L

    /** 允許系統回收多舊的定位結果。剛好有 30 秒內的就直接用，省一次冷啟動。 */
    private const val MAX_AGE_MS = 30_000L

    /** 最多等多久。伺服器那端等 20 秒，這裡留 5 秒回報的餘裕。 */
    private const val TIMEOUT_MS = 15_000L

    /** 事件是不是在要位置。不是的話呼叫端不用理它。 */
    fun isLocationRequest(ev: ServerEvent): Boolean =
        ev.type == "device.request" && ev.str("kind") == "location"

    /**
     * 定期回報位置，直到呼叫端的 scope 被取消。
     *
     * **一定要跑在前景服務的生命週期裡。** App 沒有 `ACCESS_BACKGROUND_LOCATION`，
     * 純背景抓位置會被系統擋掉；靠的是 ButlerService 那個帶 `location` 型別的
     * 前景服務所給的豁免（見 AndroidManifest 的說明）。搬去 WorkManager 就失去
     * 這個豁免，得回頭要那個要使用者進系統設定選「一律允許」的權限。
     *
     * 沒權限時**不送任何東西**，只是等下一輪再看一次。這跟被問到時的行為刻意不同：
     * 那邊有人在等回覆，不回會讓它空等到逾時；這邊沒有人在等，每 15 分鐘往伺服器
     * 送一次「權限沒開」只會洗版，而且會把一筆本來還堪用的舊位置蓋掉。
     * 每輪都重查權限而不是進來檢查一次就決定：使用者可能中途才在設定頁按同意，
     * 那之後不該還要重啟服務才會開始回報。
     */
    suspend fun reportPeriodically(ctx: Context, client: ButlerClient) {
        Log.i(ButlerClient.TAG, "定期回報位置：啟動，每 ${INTERVAL_MS / 60_000} 分鐘一次")
        while (currentCoroutineContext().isActive) {
            if (missingPermission(ctx) == null) {
                // report 內部已經吞掉自己的例外，這裡再包一層是為了「連線整個掛掉」
                // 這種它管不到的狀況——定期回報失敗不該讓迴圈直接結束，
                // 那會安靜地再也不回報，而且沒有任何跡象。
                runCatching { report(ctx, client) }
                    .onFailure { Log.w(ButlerClient.TAG, "定期回報位置失敗：${it.message}") }
            }
            delay(INTERVAL_MS)
        }
    }

    /**
     * 處理一則 `device.request`。抓位置、轉地址、回報，全程不顯示任何 UI。
     *
     * 使用者不會知道發生過這件事——這是刻意的：助理判斷需要位置才會問，
     * 每次都彈一個提示只會讓「附近有什麼吃的」變成兩次互動。
     */
    suspend fun onDeviceRequest(ctx: Context, client: ButlerClient, ev: ServerEvent) {
        if (!isLocationRequest(ev)) return
        report(ctx, client, ev.str("req_id"))
    }

    /**
     * 抓一次位置並回報。[reqId] 空字串代表主動回報（不是在回應誰的請求）。
     *
     * 回報成功與否都不拋例外：這條路徑跑在 SSE 的事件迴圈裡，
     * 讓它炸掉會把整條連線一起帶走。
     */
    suspend fun report(ctx: Context, client: ButlerClient, reqId: String = "") {
        val body = JSONObject().apply { if (reqId.isNotBlank()) put("req_id", reqId) }
        // 每條路徑都要留下痕跡。少了任何一條，「位置沒有出現在伺服器上」就分不出
        // 是沒權限、抓不到、還是送不出去——這三件事要做的處置完全不同。
        // 座標本身不進 log：logcat 任何 App 都讀得到，寫進去等於外洩。
        val denied = missingPermission(ctx)
        if (denied != null) {
            Log.w(ButlerClient.TAG, "回報位置：$denied")
            client.reportLocation(body.put("error", denied))
            return
        }
        val loc = runCatching { current(ctx) }.getOrNull()
        if (loc == null) {
            // 權限有、位置卻拿不到：定位服務關著、室內收不到訊號、或等超時了
            Log.w(ButlerClient.TAG, "回報位置：抓不到（逾時或沒訊號）")
            client.reportLocation(body.put("error", "抓不到位置，可能是定位服務關著或收不到訊號"))
            return
        }
        val addr = addressOf(ctx, loc)
        val res = client.reportLocation(body.apply {
            put("lat", loc.latitude)
            put("lon", loc.longitude)
            put("accuracy_m", loc.accuracy.toDouble())
            put("address", addr)
        })
        Log.i(
            ButlerClient.TAG,
            "回報位置：${if (res.isSuccess) "成功" else "送出失敗 " + res.exceptionOrNull()?.message}" +
                "，精度 ${loc.accuracy.toInt()}m，地址${if (addr.isBlank()) "查不到" else "已帶上"}",
        )
    }

    /** 缺哪個權限，都齊了回 null。 */
    private fun missingPermission(ctx: Context): String? {
        val fine = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        // 只給粗略位置也能用（幾百公尺對「我在哪個區」已經夠了），
        // 所以判準是「兩個都沒有」而不是「沒有精確的」。
        return if (fine || coarse) null else "定位權限沒開"
    }

    /**
     * 抓一次目前位置。
     *
     * 用 `getCurrentLocation` 而不是 `requestLocationUpdates`：後者是訂閱，要自己記得
     * 取消，忘了就變成背景一直在定位——那正是這個設計要避免的東西。
     *
     * 用 Fused 而不是原生 LocationManager：原生的一次性 API（`getCurrentLocation`）
     * 是 API 30 才有的，而 minSdk 是 26，舊版只能用已棄用的訂閱式 API 自己收尾。
     * Fused 一路支援下來，而且會合併 GPS／Wi-Fi／基地台，室內也拿得到。
     */
    @SuppressLint("MissingPermission")     // 上面 missingPermission() 檢查過了
    private suspend fun current(ctx: Context): Location? =
        suspendCancellableCoroutine { cont ->
            val req = CurrentLocationRequest.Builder()
                // BALANCED 而不是 HIGH_ACCURACY：後者強制開 GPS，室內要等很久又耗電，
                // 而回答「我在哪」用不到公尺級精度。每 15 分鐘一次的背景回報更要省。
                //
                // **在模擬器上驗證定位時這一行會讓你以為程式壞了**：BALANCED 走
                // network/Wi-Fi，而 AVD 的 network provider 沒有任何資料
                // （`adb shell dumpsys location` 看得到 network 的 locations=0），
                // `adb emu geo fix` 注入的只有 gps。結果是等滿 15 秒逾時回 null，
                // 一路安靜到「伺服器上沒有位置」。真機有 Wi-Fi 與基地台不會這樣。
                // 要在 AVD 上驗整條鏈路，把這行暫時換成 PRIORITY_HIGH_ACCURACY。
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateAgeMillis(MAX_AGE_MS)
                .setDurationMillis(TIMEOUT_MS)
                .build()
            LocationServices.getFusedLocationProviderClient(ctx)
                .getCurrentLocation(req, null)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener {
                    Log.w(ButlerClient.TAG, "抓位置失敗：${it.message}")
                    if (cont.isActive) cont.resume(null)
                }
        }

    /**
     * 座標轉地址。轉不出來回空字串——沒網路時會發生，這時仍要把座標送出去。
     *
     * Android 13 起同步版被棄用（在主執行緒上呼叫會拋例外），但這裡本來就在
     * IO 執行緒，而非同步版要 API 33。為了一個版本分支引進 callback 包裝不划算，
     * 舊路徑照樣走同步版。
     */
    @Suppress("DEPRECATION")
    private suspend fun addressOf(ctx: Context, loc: Location): String =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext ""
            runCatching {
                Geocoder(ctx, Locale.TAIWAN)
                    .getFromLocation(loc.latitude, loc.longitude, 1)
                    ?.firstOrNull()
                    // getAddressLine(0) 是完整地址字串。自己組 admin+locality+
                    // thoroughfare 在台灣會漏掉巷弄，而且各地供應商填的欄位不一致。
                    ?.getAddressLine(0)
                    .orEmpty()
            }.getOrDefault("")
        }
}
