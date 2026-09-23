package dev.butlerkit.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.butlerkit.app.net.ButlerClient
import java.io.File

/**
 * 中文襯線字型的取得方式。想讓畫面用中文襯線就從這裡拿（見 Theme.kt 的說明）。
 *
 * **`FontFamily.Serif` 在中文上靠不住。** Android 的 `fonts.xml` 裡，中文的襯線只以
 * `fallbackFor="serif"` 掛在各語言的 fallback family 底下，而且**要看裝置語系**：
 * 語系是英文的機器（例如 AVD）上，中文字在 serif 裡找不到字形，靜默退回 NotoSansCJK。
 * 畫面不會壞、不會報錯，只是襯線感整個消失——在 AVD 上量得到 serif 與 sans
 * 的中文字形完全相同。
 *
 * 但 **`NotoSerifCJK-Regular.ttc` 本來就在 /system/fonts 裡**，直接指名載入就拿得到
 * 真正的中文襯線，不必把幾 MB 的字型檔塞進 APK。兩件事要做對：
 *
 * 1. **ttc 要指定第幾套。** 那個檔是五套字的合集（日、韓、簡、繁、港，fonts.xml 裡
 *    zh-Hant 用的是 index 3）。`Typeface.createFromFile` 只會拿第一套——**日文字形**，
 *    「骨」「讀」「說」這類字的筆畫跟台灣的寫法不一樣。
 * 2. **粗體要自己給一份。** `FontFamily(Typeface)` 包出來的字族，Compose 會原封不動
 *    拿去用、不管要求的字重，於是 `**粗體**` 用了襯線就整個不見。
 *    這裡給 Compose 一個兩種字重的字族：正常那套、加上系統合成的粗體。
 *
 * 找不到檔案時回 `FontFamily.Serif`：拉丁字仍然有襯線，中文退回無襯線，版面不會壞。
 */
object SerifProbe {

    /** 系統上可能放中文襯線字的位置，依序試。ttc 的第幾套見 [TTC_TC]。 */
    private val CANDIDATES = listOf(
        "/system/fonts/NotoSerifCJK-Regular.ttc",
        "/system/fonts/NotoSerifCJKtc-Regular.otf",
        "/system/fonts/NotoSerifTC-Regular.otf",
        "/system/fonts/SourceHanSerifTC-Regular.otf",
    )

    /** NotoSerifCJK-Regular.ttc 裡繁體中文那一套（fonts.xml 的 zh-Hant）。 */
    private const val TTC_TC = 3

    /** 給 Compose 用的中文襯線字族（正常＋粗體）。找不到就退回系統 serif。 */
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
            val regular = load(path)
            // 真的量一次再宣告成功。檔案存在不代表載得起來，也不代表它含中文字形。
            val ok = differsFromSans(regular)
            hasCjkSerif = ok
            Log.i(
                ButlerClient.TAG,
                "中文襯線字型：${path.substringAfterLast('/')}，實測${if (ok) "有效" else "無效（退回無襯線）"}",
            )
            if (!ok) return@runCatching FontFamily.Serif
            // 系統沒有粗的那套；BOLD 樣式的 Typeface 畫的時候由系統合成粗體
            val bold = Typeface.create(regular, Typeface.BOLD)
            FontFamily(LoadedFont(regular, FontWeight.Normal), LoadedFont(bold, FontWeight.Bold))
        }.getOrElse {
            Log.w(ButlerClient.TAG, "載入中文襯線字型失敗：${it.message}")
            hasCjkSerif = false
            FontFamily.Serif
        }
    }

    /** ttc 指定繁中那一套；單一字型檔直接載。 */
    private fun load(path: String): Typeface =
        if (path.endsWith(".ttc", ignoreCase = true)) {
            Typeface.Builder(File(path)).setTtcIndex(TTC_TC).build()
        } else {
            Typeface.createFromFile(path)
        }

    /**
     * 把同一個字用兩種字型畫出來比像素，判斷是不是真的換到了。
     *
     * **不能用字寬比。** 中文是全形等寬，`NotoSerifCJK` 與 `NotoSansCJK` 的
     * 同一串中文寬度完全相同（都是 1em），量出來永遠一樣。只有畫出來比字形
     * 才問得到正確答案。
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

/**
 * 一個已經載好的 [Typeface]，用指定的字重掛進 Compose 的字族。
 *
 * 不用 `FontFamily(Typeface)`：那種字族 Compose 會原封不動拿去用、不看要求的字重
 * （見 [SerifProbe] 的第 2 點）。字族裡有宣告成 Bold 的這一個，粗體的字才會換過來。
 */
@OptIn(ExperimentalTextApi::class)
private class LoadedFont(
    val typeface: Typeface,
    override val weight: FontWeight,
) : AndroidFont(FontLoadingStrategy.Blocking, Loader, FontVariation.Settings()) {
    override val style: FontStyle = FontStyle.Normal

    private object Loader : TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): Typeface =
            (font as LoadedFont).typeface

        override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface =
            (font as LoadedFont).typeface
    }
}
