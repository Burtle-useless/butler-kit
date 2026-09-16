package dev.butlerkit.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.compose.ui.text.font.FontFamily
import dev.butlerkit.app.net.ButlerClient
import java.io.File

/**
 * 中文襯線字型的取得方式。
 *
 * **`FontFamily.Serif` 在中文上是無效的。** Android 的 `fonts.xml` 裡，
 * `serif` 這個 family 只掛了拉丁字母的四個檔（NotoSerif-Regular/Bold/Italic
 * /BoldItalic），中文字在裡面找不到字形，就會靜默 fallback 回 NotoSansCJK。
 * 畫面不會壞、不會報錯，只是襯線感整個消失——2026-08-19 在 AVD 上量到
 * serif 與 sans 的中文字寬完全相同（都是 384.0）才發現。
 *
 * 但 **`NotoSerifCJK-Regular.ttc` 本來就在 /system/fonts 裡**，只是沒被掛進
 * 那個 family。直接指名載入那個檔就拿得到真正的中文襯線，不必把幾 MB 的
 * 字型檔塞進 APK。
 *
 * 找不到檔案時回 `FontFamily.Serif`：拉丁字仍然有襯線，中文退回無襯線，
 * 版面不會壞——報紙感主要來自版面結構，字型是加分不是命脈。
 */
object SerifProbe {

    /** 系統上可能放中文襯線字的位置，依序試。 */
    private val CANDIDATES = listOf(
        "/system/fonts/NotoSerifCJK-Regular.ttc",
        "/system/fonts/NotoSerifCJKtc-Regular.otf",
        "/system/fonts/NotoSerifTC-Regular.otf",
        "/system/fonts/SourceHanSerifTC-Regular.otf",
    )

    /** 給 Compose 用的中文襯線字族。找不到就退回系統 serif。 */
    val Serif: FontFamily by lazy { resolve() }

    /** 這台機器真的有中文襯線嗎。false 代表上面那個是退而求其次的結果。 */
    var hasCjkSerif: Boolean = false
        private set

    private fun resolve(): FontFamily {
        val path = CANDIDATES.firstOrNull { File(it).exists() }
        if (path == null) {
            Log.w(ButlerClient.TAG, "找不到中文襯線字型，中文會用無襯線")
            hasCjkSerif = false
            return FontFamily.Serif
        }
        return runCatching {
            val tf = Typeface.createFromFile(path)
            // 真的量一次再宣告成功。檔案存在不代表載得起來，也不代表它含中文字形。
            val ok = differsFromSans(tf)
            hasCjkSerif = ok
            Log.i(
                ButlerClient.TAG,
                "中文襯線字型：${path.substringAfterLast('/')}，實測${if (ok) "有效" else "無效（退回無襯線）"}",
            )
            if (ok) FontFamily(tf) else FontFamily.Serif
        }.getOrElse {
            Log.w(ButlerClient.TAG, "載入中文襯線字型失敗：${it.message}")
            hasCjkSerif = false
            FontFamily.Serif
        }
    }

    /**
     * 把同一個字用兩種字型畫出來比像素，判斷是不是真的換到了。
     *
     * **不能用字寬比。** 中文是全形等寬，`NotoSerifCJK` 與 `NotoSansCJK` 的
     * 同一串中文寬度完全相同（都是 1em），量出來永遠一樣。字寬只分得出
     * 「有沒有 fallback 到完全不同的字型」，分不出兩個都含中文字形的字型——
     * 2026-08-19 就是這樣誤判了一次，明明載到了 NotoSerifCJK 卻報「無效」。
     *
     * 拉丁字母同樣不能當判準：任何襯線字型都有拉丁字形，測不出中文的狀況。
     * 只有畫出來比字形才問得到正確答案。
     */
    private fun differsFromSans(tf: Typeface): Boolean {
        fun render(t: Typeface): IntArray {
            val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawText(
                "書", 4f, SIZE - 10f,
                Paint().apply { typeface = t; textSize = 52f; isAntiAlias = true },
            )
            val px = IntArray(SIZE * SIZE)
            bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
            bmp.recycle()
            return px
        }
        val a = render(tf)
        val b = render(Typeface.SANS_SERIF)
        // 逐點比。同一個字用襯線與無襯線畫出來，筆畫末端的襯腳與粗細變化
        // 會讓數百個點不一樣；完全相同就是根本沒換到字型。
        val diff = a.indices.count { a[it] != b[it] }
        Log.i(ButlerClient.TAG, "字形比對：$diff 個像素不同")
        return diff > 50
    }

    private const val SIZE = 64
}
