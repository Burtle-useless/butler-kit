package dev.butlerkit.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.butlerkit.app.MainActivity

/**
 * 三張 widget 的共用外殼與色票。
 *
 * 色票是 [dev.butlerkit.app.ui.Palette] 的搬運而不是 import 後轉型：Glance 要的是
 * `ColorProvider`，而且 widget 畫在別人的桌布上，能用的顏色數比 App 內少得多——
 * 這裡只挑用得到的那幾個，多搬只會讓人以為整套色彩系統都能用。
 *
 * **不跟隨系統日夜**：App 本體就只有深色一種，widget 跟著深色才不會兩個地方長得不像。
 */
internal object W {
    // 這幾個值是 Palette 的抄本，每個值後面標的是它抄自哪個欄位。
    // **換色票時這裡要跟著改**——差幾個色階單看 widget 不明顯，但點進 App
    // 那一瞬間會覺得換了個地方。
    val Bg = ColorProvider(Color(0xFFFAFAFA))       // Palette.Surface
    val Text = ColorProvider(Color(0xFF1F1F1F))     // Palette.Text
    val Dim = ColorProvider(Color(0xFF5F5F5F))      // Palette.TextDim
    val Faint = ColorProvider(Color(0xFF9A9A9A))    // Palette.TextFaint
    val Accent = ColorProvider(Color(0xFF4A6FA5))   // Palette.Accent
    val Line = ColorProvider(Color(0xFFDDDDDD))     // Palette.Line
    val Ok = ColorProvider(Color(0xFF2E7D52))       // Palette.Ok
    val Warn = ColorProvider(Color(0xFFB07C1E))     // Palette.Warn
    val Danger = ColorProvider(Color(0xFFC0392B))   // Palette.Danger
    val Now = ColorProvider(Color(0x1F4A6FA5))      // Palette.AccentSoft：正在進行中的那列
    val Bar = ColorProvider(Color(0x664A6FA5))      // 長條圖的過去幾天，今天那根才是實色

    // 課表 widget 跟 App 內的課表頁一致（Accents 預設全指向 Accent）
    val Course = ColorProvider(Color(0xFF4A6FA5))       // Accents.Course
    val CourseNow = ColorProvider(Color(0x1F4A6FA5))    // 同色的襯底
}

/**
 * 點 widget 要落在哪一頁。字串而不是共用 enum：底欄的 `Tab` 與日常頁的 `Sub`
 * 都是各自畫面的 private enum，為了 widget 把它們公開等於讓兩個畫面的內部狀態
 * 變成對外介面。這裡只約定四個字串，MainActivity 那端負責認。
 */
internal const val TAB_DAILY = "daily"
internal const val TAB_TOOLS = "tools"
internal const val SUB_COURSE = "course"
internal const val SUB_CAL = "cal"

/** 點 widget 要開到 App 的哪裡。值對應 [MainActivity] 認得的 extra。 */
internal fun openAppIntent(ctx: Context, tab: String, sub: String? = null): Intent =
    Intent(ctx, MainActivity::class.java).apply {
        // FLAG_ACTIVITY_NEW_TASK 是從 widget 啟動 Activity 的必要條件（沒有 Activity
        // context 可用）；SINGLE_TOP 讓 App 已經開著時走 onNewIntent 而不是疊一層新的
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(MainActivity.EXTRA_TAB, tab)
        sub?.let { putExtra(MainActivity.EXTRA_SUB, it) }
        // widget 每次重畫都會重建 PendingIntent，action 帶上目的地才不會讓系統
        // 認為兩張 widget 的 intent「相同」而共用同一個（那會讓其中一張點了跳錯頁）
        action = "dev.butlerkit.app.OPEN_$tab${sub?.let { "_$it" } ?: ""}"
    }

/**
 * 標題列 ＋ 內容的統一外框。
 *
 * [right] 放在標題右邊，通常是時間或「幾分鐘前」這種次要資訊。整張卡片可點，
 * 點了開 App——widget 上放小按鈕在手機上很難點準，整片當一個目標最實在。
 */
@Composable
internal fun WidgetFrame(
    ctx: Context,
    title: String,
    right: String,
    tab: String,
    sub: String? = null,
    // ColumnScope 而不是單純的 () -> Unit：內容要能用 defaultWeight() 把剩餘高度
    // 吃掉（今日行程那張的清單要撐開，鬧鐘才會被推到最底下）
    content: @Composable androidx.glance.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(W.Bg)
            // 跟 App 裡的卡片同一個圓角（Radii.Card = 18dp）
            .cornerRadius(18.dp)
            .padding(12.dp)
            .clickable(actionStartActivity(openAppIntent(ctx, tab, sub))),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = W.Text,
                ),
            )
            Spacer(GlanceModifier.defaultWeight())
            if (right.isNotBlank()) {
                Text(text = right, style = TextStyle(fontSize = 11.sp, color = W.Faint))
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        content()
    }
}

/**
 * 一個 Glance 容器放得下的子元素數。
 *
 * **超過的會被靜默丟掉**——不是報錯、不是截斷提示，是尾端的東西直接不見，
 * 只在 logcat 留一行 `Truncated` 的 warning。桌面上看起來就像「那個功能壞了」。
 *
 * 這個數字約束的是**每一層容器各自的直接子元素**，不是整張 widget 的總數。
 * 所以清單類的內容一律再包一層 Column：外層只算它一個，內層自己再守這條線。
 * 一列一個元素也是同理——列尾用 padding 拉開間距而不是插 Spacer，
 * 一插就是每列吃兩格，四列就滿了。
 */
internal const val GLANCE_MAX_CHILDREN = 10

/** 沒東西可顯示時的一行字。空白的 widget 看起來像壞掉，一定要說明是哪種空。 */
@Composable
internal fun EmptyLine(text: String) {
    Text(
        text = text,
        style = TextStyle(fontSize = 12.sp, color = W.Faint),
        modifier = GlanceModifier.padding(top = 6.dp),
    )
}

/** 左邊一條色帶，用來標「正在進行中」。Glance 沒有 Canvas，色塊就是這樣做出來的。 */
@Composable
internal fun Stripe(color: ColorProvider) {
    Spacer(
        GlanceModifier.width(3.dp).height(28.dp).background(color).cornerRadius(2.dp),
    )
}

/**
 * "08:10" → 490（當天第幾分鐘）。解不出來回 null。
 *
 * 不用 `LocalTime.parse`：它只認零填充的 "08:10"，而節次時間表是使用者手打的，
 * "8:10" 這種寫法很常見，那時整張課表會因為一個例外而全空。
 */
internal fun hhmm(s: String): Int? {
    val p = s.trim().split(":")
    if (p.size != 2) return null
    val h = p[0].toIntOrNull() ?: return null
    val m = p[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

/** 分鐘轉回 "08:10"，畫面上一律零填充。 */
internal fun hhmmText(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
