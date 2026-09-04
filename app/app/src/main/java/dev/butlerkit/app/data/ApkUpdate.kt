package dev.butlerkit.app.data

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 更新自己。
 *
 * 助理沒有上架，新版只能側載——這是自用 App 唯一的更新管道，不是偷懶。
 * 原本的流程是「下載到公用下載夾 → 使用者自己開檔案管理器 → 找到 → 點 → 裝」，
 * 中間三步都在 App 外面，而且下載夾會一路堆滿 40MB 的安裝檔。
 *
 * 這裡把 APK 跟一般檔案分開處理：
 * - 存進 **cacheDir**，不進公用下載夾。安裝檔在裝完之後就是垃圾（系統已經把
 *   程式複製進 /data/app），留著只是佔空間；放 cache 還多一層保險——手機空間
 *   不足時系統會自己清掉。
 * - **同時只留一份**。舊的那支永遠不會再被裝，留著沒有任何意義。
 * - 裝完由 [dev.butlerkit.app.alarm.BootReceiver] 收 MY_PACKAGE_REPLACED 清掉。
 *
 * 系統那道「要安裝更新嗎」的確認畫面拿不掉：Android 不允許非系統 App 靜默安裝。
 * 所以最短路徑就是「按一下安裝 → 系統問一次 → 好了」。
 */
object ApkUpdate {

    /** cacheDir 底下的暫存目錄名。跟 res/xml/file_paths.xml 的宣告必須一致。 */
    private const val DIR = "updates"

    fun isApk(name: String): Boolean = name.endsWith(".apk", ignoreCase = true)

    private fun dir(ctx: Context): File =
        File(ctx.cacheDir, DIR).apply { mkdirs() }

    /**
     * 已經下載好、等著被安裝的那一份，沒有就回 null。
     *
     * 檔名用 file_id 而不是原本的檔名：重開 App 之後記憶體裡的狀態全沒了，
     * 但掃一次目錄就知道「清單上這一列是不是已經備好了」。原檔名不重要——
     * 系統的安裝畫面顯示的是 APK 裡面的 App 名稱，不是檔名。
     */
    fun staged(ctx: Context, fileId: String): File? =
        File(dir(ctx), "$fileId.apk").takeIf { it.isFile && it.length() > 0L }

    /** 目錄裡現有的所有 file_id。App 啟動時用它還原「哪些已經備好」。 */
    fun stagedIds(ctx: Context): Set<String> =
        dir(ctx).listFiles()?.mapNotNull { f ->
            f.name.removeSuffix(".apk").takeIf { it != f.name && f.length() > 0L }
        }?.toSet() ?: emptySet()

    /** 下載目標。**取得的同時清掉舊的**，所以這個目錄永遠只有一個檔。 */
    fun target(ctx: Context, fileId: String): File {
        clear(ctx)
        return File(dir(ctx), "$fileId.apk")
    }

    fun clear(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    /**
     * 系統設定裡的「允許安裝未知應用程式」開了沒。
     *
     * 這是一次性的：使用者開過之後就不會再問。沒開就直接送安裝 Intent 的話，
     * 系統會自己跳一個提示，但那個提示的措辭跟來源不清不楚，先自己判斷再引導
     * 比較不會讓人以為是 App 壞了。
     */
    fun allowed(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** 跳到「允許這個來源安裝」的設定頁。開完他得自己回來再按一次安裝。 */
    fun openPermissionSettings(ctx: Context) {
        ctx.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * 把系統的安裝畫面叫出來。
     *
     * 一定要走 FileProvider 給 content:// URI：Android 7 起傳 file:// 給別的 App
     * 會直接拋 FileUriExposedException，而且這個檔案在 cacheDir 裡，套件安裝器
     * 本來就讀不到，得靠 FLAG_GRANT_READ_URI_PERMISSION 臨時授權。
     *
     * NEW_TASK 是因為呼叫端可能不是 Activity（自動下載完成時是背景 scope）。
     */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    /**
     * 現在是不是接著 Wi-Fi。
     *
     * 自動下載只在 Wi-Fi 下做。一支 APK 四十幾 MB，替使用者決定用掉行動網路
     * 不是幫忙——這條跟 [InboxRepo] 原本「不自動下載」的理由是同一個，
     * 只是對 APK 放寬到「不用錢的時候就先備好」。
     */
    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
