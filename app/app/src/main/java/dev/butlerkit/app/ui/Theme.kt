package dev.butlerkit.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
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
 * 1. **[LightColors] 與 [DarkColors] 的色值**（就在下面）。整個 App 的顏色只從
 *    這兩份來，元件裡沒有寫死的 hex，所以改這兩段就會全頁生效。
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

/**
 * 一套色票。淺色與深色各一份，畫面上一律透過 [Palette] 讀當前那份。
 *
 * 加欄位要三個地方一起加：這個類別、[LightColors] 與 [DarkColors] 兩份值、
 * 還有下面 [Palette] 的 getter。少一個編譯就會擋下來，不會靜默漏掉。
 */
@Immutable
class Colors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceHi: Color,
    val line: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val accentSoft: Color,
    val userBubble: Color,
    val botBubble: Color,
    val danger: Color,
    val dangerSoft: Color,
    val ok: Color,
    val warn: Color,
    val petDot: Color,
)

/** 淺色：白底、深灰字、一個藍色強調色。中性到沒有立場——這正是佔位版該有的樣子。 */
val LightColors = Colors(
    isDark = false,
    bg = Color(0xFFFFFFFF),          // 底
    surface = Color(0xFFFAFAFA),     // 浮起的面（卡片、sheet）
    surfaceHi = Color(0xFFF0F0F0),   // 凹下去的面（輸入框、選中的底）
    line = Color(0xFFDDDDDD),        // 分隔線

    text = Color(0xFF1F1F1F),        // 主要文字
    textDim = Color(0xFF5F5F5F),     // 次要
    textFaint = Color(0xFF9A9A9A),   // 附屬資訊（時間、統計、已完成）

    accent = Color(0xFF4A6FA5),      // 強調色。全 App 只有這一個彩色
    accentSoft = Color(0x1F4A6FA5),  // 強調色襯底

    // 訊息泡泡
    userBubble = Color(0xFFEFEFEF),
    botBubble = Color(0xFFF7F7F7),

    // 語意色
    danger = Color(0xFFC0392B),
    dangerSoft = Color(0x17C0392B),
    ok = Color(0xFF2E7D52),
    warn = Color(0xFFB07C1E),

    // 吉祥物：預設只是一顆點，所以只需要一個顏色。
    // 換成真的吉祥物時，需要幾個色就在這裡加幾個（見 PetBot.kt）
    petDot = Color(0xFF3A3A3A),
)

/**
 * 深色：中性灰階，跟淺色是同一個立場的兩面。
 *
 * **不是把淺色的值反過來就好。** 兩件事一定要自己重挑：
 * - 底不要用純黑（#000）。純黑上的彩色會過度躍出，而且 OLED 上滑動時容易出現拖影；
 *   #121212 那一階是 Material 的預設起點，通用而不搶戲。
 * - 強調色與語意色要**提亮一階**。深底上的深藍會直接沉掉，看起來像沒有那個元件。
 *   這裡的藍是淺色那顆 #4A6FA5 提亮後的版本，色相一樣、明度不同。
 */
val DarkColors = Colors(
    isDark = true,
    bg = Color(0xFF121212),
    surface = Color(0xFF1C1C1C),
    surfaceHi = Color(0xFF262626),
    line = Color(0xFF3A3A3A),

    text = Color(0xFFE8E8E8),
    textDim = Color(0xFFABABAB),
    textFaint = Color(0xFF7A7A7A),

    accent = Color(0xFF7FA0D4),
    accentSoft = Color(0x2E7FA0D4),

    userBubble = Color(0xFF262626),
    botBubble = Color(0xFF1C1C1C),

    danger = Color(0xFFE57368),
    dangerSoft = Color(0x24E57368),
    ok = Color(0xFF6FBF8F),
    warn = Color(0xFFD4AB5E),

    petDot = Color(0xFFC8C8C8),
)

/** 當前主題的色票。[ButlerTheme] 提供；沒包在裡面的地方（鬧鐘響鈴頁）拿到淺色。 */
val LocalPalette = staticCompositionLocalOf { LightColors }

/**
 * 呼叫端照舊寫 `Palette.Text`，每個屬性都是讀 [LocalPalette] 的 composable getter，
 * 淺深色切換不必動任何引用點。**只能在 composable 裡讀**——Canvas 的 draw lambda、
 * `remember {}` 的計算式、enum 建構子都不行，那些地方先在外面
 * `val p = LocalPalette.current` 再帶進去。
 */
object Palette {
    private val c: Colors @Composable @ReadOnlyComposable get() = LocalPalette.current

    val Bg: Color @Composable @ReadOnlyComposable get() = c.bg
    val Surface: Color @Composable @ReadOnlyComposable get() = c.surface
    val SurfaceHi: Color @Composable @ReadOnlyComposable get() = c.surfaceHi
    val Line: Color @Composable @ReadOnlyComposable get() = c.line
    val Text: Color @Composable @ReadOnlyComposable get() = c.text
    val TextDim: Color @Composable @ReadOnlyComposable get() = c.textDim
    val TextFaint: Color @Composable @ReadOnlyComposable get() = c.textFaint
    val Accent: Color @Composable @ReadOnlyComposable get() = c.accent
    val AccentSoft: Color @Composable @ReadOnlyComposable get() = c.accentSoft
    val UserBubble: Color @Composable @ReadOnlyComposable get() = c.userBubble
    val BotBubble: Color @Composable @ReadOnlyComposable get() = c.botBubble
    val Danger: Color @Composable @ReadOnlyComposable get() = c.danger
    val DangerSoft: Color @Composable @ReadOnlyComposable get() = c.dangerSoft
    val Ok: Color @Composable @ReadOnlyComposable get() = c.ok
    val Warn: Color @Composable @ReadOnlyComposable get() = c.warn
    val PetDot: Color @Composable @ReadOnlyComposable get() = c.petDot
}

/**
 * 子分頁的「重點色」。預設全部指向同一個強調色——四頁各配一色是個選擇，
 * 不是必然，想那樣做就把這四個值改掉。呼叫端到處都是，改這裡就好。
 */
object Accents {
    val Cal: Color @Composable @ReadOnlyComposable get() = Palette.Accent
    val Course: Color @Composable @ReadOnlyComposable get() = Palette.Accent
    val Alarm: Color @Composable @ReadOnlyComposable get() = Palette.Accent
    val Money: Color @Composable @ReadOnlyComposable get() = Palette.Accent
}

/** 襯底：色條旁的淡底、選中格的暈。 */
fun Color.soft(): Color = copy(alpha = 0.10f)

/**
 * 字型。全 App 的預設由 [ButlerTheme] 鋪一層 LocalTextStyle 套上去，
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
 * 把一套 [Colors] 映射成 M3 的 colorScheme。
 *
 * 顏色只在 [Colors] 定義一次，元件不要再寫死 hex——沒被手動指定顏色的地方
 * （ripple、Switch、游標、對話框按鈕、下拉選單底）才會自動長對。
 */
fun Colors.toScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = if (isDark) Color(0xFF10161F) else Color(0xFFFFFFFF),
        primaryContainer = surfaceHi,
        onPrimaryContainer = text,
        secondary = textDim,
        onSecondary = bg,
        tertiary = textDim,
        onTertiary = bg,
        background = bg,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceHi,
        onSurfaceVariant = textDim,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceHi,
        surfaceContainerHighest = surfaceHi,
        error = danger,
        onError = if (isDark) Color(0xFF1F1010) else Color(0xFFFFFFFF),
        errorContainer = dangerSoft,
        onErrorContainer = danger,
        outline = line,
        outlineVariant = line,
        // 淺底上的 scrim 要重一點，淺色蓋淺色才分得出誰浮著
        scrim = Color(0xB3000000),
    )
}

/**
 * 全 App 的主題入口：跟隨系統日夜挑色票、鋪 M3 的顏色、套上預設字型。
 *
 * **跟隨系統，沒有 App 內的切換開關。** 要做那顆開關的話，把
 * `isSystemInDarkTheme()` 換成讀 `Prefs` 的三態（跟隨系統／固定淺／固定深）即可，
 * 底下所有畫面都不必動——它們讀的是 [LocalPalette]，不是某一份具名色票。
 *
 * `MainActivity` 與 `TalkActivity` 都包這個，不要各自再組一次。系統列與啟動畫面的
 * 顏色不歸這裡管，那是 `res/values/themes.xml` 與 `res/values-night/themes.xml`。
 */
@Composable
fun ButlerTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    CompositionLocalProvider(LocalPalette provides colors) {
        MaterialTheme(colorScheme = colors.toScheme()) {
            ProvideTextStyle(TextStyle(fontFamily = Fonts.Base), content)
        }
    }
}
