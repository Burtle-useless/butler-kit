package dev.butlerkit.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 色票與尺度：**這是佔位用的預設，請換掉。**
 *
 * 這裡刻意只給一套毫無個性的灰白配色——能跑、看得清楚、不難看，但也毫無記憶點。
 * 它存在的目的是「讓你第一次編譯就有東西看」，不是「拿來用」。
 * 原封不動出貨的話，你的 App 會跟每一個沒改過的 butler-kit 長得一模一樣。
 *
 * ── 要換的話，動這三個地方就夠 ────────────────────────────────────────────
 *
 * 1. **[Palette] 的色值**（就在下面）。整個 App 的顏色只從這裡來，元件裡沒有
 *    寫死的 hex，所以改這一段就會全頁生效。
 * 2. **[Radii] 的圓角**。形狀語彙比顏色更決定「看起來像什麼」：全圓角是軟的、
 *    親切的；全直角是硬的、印刷感的。混著用通常會顯得沒想清楚。
 * 3. **[Fonts].Base 的字型**。中文襯線要走 [SerifProbe]（`FontFamily.Serif`
 *    對中文無效，那個檔的註解寫了為什麼）。
 *
 * 吉祥物是第四個地方，在 `PetBot.kt`，那支也是佔位的。
 *
 * ── 兩句設計上的忠告 ──────────────────────────────────────────────────────
 *
 * - **先決定一個立場再挑色。** 「科技感」「溫暖」這種形容詞挑不出顏色，
 *   「深夜工作時不刺眼」「像便條紙」這種具體場景才挑得出來。
 * - **強調色只留一個。** 三個以上的彩色互相搶，結果是哪個都不突出。
 *   層級用字級、留白、線條分，比用色相分耐看。
 */
object Palette {
    val Bg = Color(0xFFFFFFFF)          // 底
    val Surface = Color(0xFFFAFAFA)     // 浮起的面（卡片、sheet）
    val SurfaceHi = Color(0xFFF0F0F0)   // 凹下去的面（輸入框、選中的底）
    val Line = Color(0xFFDDDDDD)        // 分隔線

    val Text = Color(0xFF1F1F1F)        // 主要文字
    val TextDim = Color(0xFF5F5F5F)     // 次要
    val TextFaint = Color(0xFF9A9A9A)   // 附屬資訊（時間、統計、已完成）

    val Accent = Color(0xFF4A6FA5)      // 強調色。全 App 只有這一個彩色
    val AccentSoft = Color(0x1F4A6FA5)  // 強調色襯底

    // 訊息泡泡
    val UserBubble = Color(0xFFEFEFEF)
    val BotBubble = Color(0xFFF7F7F7)

    // 語意色
    val Danger = Color(0xFFC0392B)
    val DangerSoft = Color(0x17C0392B)
    val Ok = Color(0xFF2E7D52)
    val Warn = Color(0xFFB07C1E)

    // 吉祥物：預設只是一顆點，所以只需要一個顏色。
    // 換成真的吉祥物時，需要幾個色就在這裡加幾個（見 PetBot.kt）
    val PetDot = Color(0xFF3A3A3A)
}

/**
 * 子分頁的「重點色」。預設全部指向同一個強調色——四頁各配一色是個選擇，
 * 不是必然，想那樣做就把這四個值改掉。呼叫端到處都是，改這裡就好。
 */
object Accents {
    val Cal = Palette.Accent
    val Course = Palette.Accent
    val Alarm = Palette.Accent
    val Money = Palette.Accent
}

/** 襯底：色條旁的淡底、選中格的暈。 */
fun Color.soft(): Color = copy(alpha = 0.10f)

/**
 * 字型。全 App 的預設由 `MainActivity` 鋪一層 LocalTextStyle 套上去，
 * 沒有自己指定 fontFamily 的 Text 都吃這個值。
 *
 * 想換中文襯線就改成 `SerifProbe.Serif`——**不要**寫 `FontFamily.Serif`，
 * 那個對中文無效，理由見 `SerifProbe.kt`。
 */
object Fonts {
    val Base: FontFamily = FontFamily.SansSerif
}

/** 圓角。這裡給的是四平八穩的中間值，硬一點軟一點都行，但整套要一致。 */
object Radii {
    val Bubble = RoundedCornerShape(12.dp)
    val Card = RoundedCornerShape(8.dp)
    val Chip = RoundedCornerShape(16.dp)
    val Field = RoundedCornerShape(8.dp)
    val Tiny = RoundedCornerShape(4.dp)
}

object Type {
    val Display = 24.sp     // 頁面最大的那一行，一頁只有一個
    val DisplayLine = 32.sp
    val Head = 20.sp        // 版面標題
    val Title = 16.sp       // 條目標題
    val Body = 15.sp        // 內文
    val BodyLine = 22.sp
    val Meta = 12.sp        // 時間、附註
    val MetaLine = 17.sp
    val Tiny = 11.sp        // 標籤、角落註記
    val TinyLine = 15.sp
    val Mono = 13.sp
    val Metric = 28.sp      // 大數字（金額、額度）
}

object Space {
    val Screen = 16.dp
    val Between = 12.dp
    val Inner = 16.dp
}

/**
 * 把 [Palette] 映射成 M3 的 colorScheme（淺色）。
 *
 * 顏色只在這裡定義一次，元件不要再寫死 hex——沒被手動指定顏色的地方
 * （ripple、Switch、游標、對話框按鈕、下拉選單底）才會自動長對。
 *
 * 要做深色主題的話，把 `lightColorScheme` 換成 `darkColorScheme` 之外，
 * [Palette] 的明暗關係也要整組重來，不是把值反過來就好。
 */
val ButlerColors = lightColorScheme(
    primary = Palette.Accent,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Palette.SurfaceHi,
    onPrimaryContainer = Palette.Text,
    secondary = Palette.TextDim,
    onSecondary = Palette.Bg,
    tertiary = Palette.TextDim,
    onTertiary = Palette.Bg,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHi,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHi,
    surfaceContainerHighest = Palette.SurfaceHi,
    error = Palette.Danger,
    onError = Color(0xFFFFFFFF),
    errorContainer = Palette.DangerSoft,
    onErrorContainer = Palette.Danger,
    outline = Palette.Line,
    outlineVariant = Palette.Line,
    // 淺底上的 scrim 要重一點，淺色蓋淺色才分得出誰浮著
    scrim = Color(0xB3000000),
)
