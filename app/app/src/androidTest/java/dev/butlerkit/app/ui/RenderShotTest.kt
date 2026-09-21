package dev.butlerkit.app.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * 把幾段代表性的回覆渲染出來、截圖存檔，人再用眼睛看一遍。
 *
 * 數學式與圖表是**畫出來的**，錯的樣子是「上下標疊在一起」「分數線偏掉」
 * 「軸標籤被切掉」——這些單元測試一個都看不到，它們只驗得了解析結果對不對。
 * 所以這支測試不做任何斷言，它的產出是 PNG：跑完用
 * `adb pull /sdcard/Pictures/butler-shots` 取出來看。
 *
 * 跑法：`gradlew connectedDebugAndroidTest`
 * （只打包單一 ABI 時，模擬器跑不起來，見 build.gradle.kts 的 abiFilters。）
 */
private const val SHOT_DIR = "butler-shots"

class RenderShotTest {

    @get:Rule
    val rule = createComposeRule()

    /** 樣本放 assets 不放程式碼：`${'$'}` 在 Kotlin 字串裡是模板符號，
     *  而數學式整段都是錢字號——寫在原始碼裡每一個都要跳脫，改一行就錯一片。 */
    private fun sample(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("$name.md").bufferedReader().use { it.readText() }

    private fun shoot(name: String, md: String) {
        rule.setContent {
            ButlerTheme {
                Column(
                    Modifier.fillMaxSize()
                        .background(Palette.Bg)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    MarkdownText(md)
                }
            }
        }
        rule.waitForIdle()
        val bmp: Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // 存進相簿而不是 app 自己的目錄，理由是「取得出來」：
        //   內部儲存        shell 身分讀不到
        //   external files  Android 11 起對 adb 隱藏
        //   app 目錄整個    測試跑完 AGP 會把 app 解除安裝，連檔案一起帶走
        // 相簿是公共目錄，adb pull 拿得到，而且 app 寫自己的 MediaStore 項目
        // 不需要任何權限。
        // 同名的先刪掉。MediaStore 遇到重名會自己加 `(1)`，跑第二輪之後
        // 拉出來的就分不清哪張是新的
        ctx.contentResolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf("%$SHOT_DIR%", "$name.png"),
        )
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$SHOT_DIR")
        }
        val uri = ctx.contentResolver
            .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("相簿寫不進去")
        ctx.contentResolver.openOutputStream(uri)?.use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        println("SHOT $name ${bmp.width}x${bmp.height} -> Pictures/$SHOT_DIR")
    }

    @Test
    fun 數學式() {
        shoot("math", sample("math"))
    }

    @Test
    fun 圖表() {
        shoot("chart", sample("chart"))
    }

    @Test
    fun 混合內容() {
        shoot("mixed", sample("mixed"))
    }
}
