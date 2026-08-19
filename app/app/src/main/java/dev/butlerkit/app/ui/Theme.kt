package dev.butlerkit.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 色票與尺度：報紙。
 *
 * 2026-08-19 整套翻掉。前一版是「深藍黑底＋冰藍 accent」的科技感，使用者的評價
 * 是「超土超爛超 AI」——看了 39 個方向後選定報紙：米白紙底、墨黑、細線分欄、
 * 唯一的彩色是紅印章那種朱紅。分層靠字級、留白與線，不靠色相和光暈。
 *
 * 三條紀律，改東西之前先讀：
 * 1. **顏色只有紙、墨、灰、朱紅四種。** 想加第五種顏色時，答案幾乎一定是
 *    「用字級或線來分」。報紙一百多年只用黑紅兩色就分完了所有層級。
 * 2. **圓角一律為零。** 印刷品沒有圓角。膠囊、圓 chip、圓卡片全部改方。
 * 3. **標題與內文用襯線（SerifProbe.Serif），時間、統計、標籤用無襯線。**
 *    這是真報紙的做法：內文是明體，圖說與欄外資訊是黑體。
 */
object Palette {
    val Bg = Color(0xFFF4F2EC)          // 紙：帶一點米的白，不是純白
    val Surface = Color(0xFFFBFAF5)     // 浮起的紙面（卡片、sheet）
    val SurfaceHi = Color(0xFFEAE7DE)   // 凹下去的面（輸入框、選中的底）
    val Line = Color(0xFFC9C5BC)        // 細分隔線。粗規線直接用 Text/Ink 畫

    val Text = Color(0xFF1A1A1A)        // 墨
    val TextDim = Color(0xFF57534A)     // 次要
    val TextFaint = Color(0xFF8A867E)   // 欄外資訊（時間、統計、已完成）

    val Accent = Color(0xFFB0342A)      // 朱紅：紅印章、紅筆批註。全 App 唯一彩色
    val AccentSoft = Color(0x1FB0342A)  // 朱紅襯底

    // 訊息：使用者的話放在凹面上（像剪報貼上來的），助理的話直接印在紙上
    val UserBubble = Color(0xFFEAE7DE)
    val BotBubble = Color(0x00000000)   // 透明——助理的字就是報紙內文，不裝盒子

    // 語意色也走印刷邏輯：暗一階、灰一點，像油墨不像螢光筆
    val Danger = Color(0xFFA8322D)
    val DangerSoft = Color(0x17A8322D)
    val Ok = Color(0xFF3E6B4F)
    val Warn = Color(0xFF8A6420)

    // 桌寵：報紙上的單色插畫——紙、墨、朱紅，沒有第四種顏色
    val PetBody = Color(0xFFFBFAF5)     // 紙面
    val PetJoint = Color(0xFF8A867E)    // 灰墨線
    val PetScreen = Color(0xFF1A1A1A)   // 墨
    val PetBook = Color(0xFFB0342A)     // 頭頂的書：朱紅
}

/**
 * 子分頁的「重點色」。報紙只有黑與紅，四頁不再各配一色——
 * 分頁靠選中的黑底反白與紅底線區分，不靠色相。全部指向墨色，
 * 保留這個 object 是因為呼叫端到處都是，值收斂了介面不用跟著改。
 */
object Accents {
    val Cal = Palette.Text
    val Course = Palette.Text
    val Alarm = Palette.Text
    val Money = Palette.Text
}

/** 襯底：色條旁的淡底、選中格的暈。印刷風下就是淡灰紙面。 */
fun Color.soft(): Color = copy(alpha = 0.10f)

/**
 * 圓角：零。印刷品沒有圓角，分界靠線不靠弧。
 * Chip 也一樣是方的——報紙的標籤是方框字（急、獨家、廣告）。
 */
object Radii {
    val Bubble = RoundedCornerShape(0.dp)
    val Card = RoundedCornerShape(0.dp)
    val Chip = RoundedCornerShape(0.dp)
    val Field = RoundedCornerShape(0.dp)
    val Tiny = RoundedCornerShape(0.dp)
}

object Type {
    val Display = 26.sp     // 報頭。一頁只有一個
    val DisplayLine = 34.sp
    val Head = 20.sp        // 版面標題（「今天」「日常版」）
    val Title = 17.sp       // 條目標題
    val Body = 15.sp        // 內文
    val BodyLine = 25.sp    // 襯線中文行高要比黑體再鬆一點才不糊
    val Meta = 13.sp        // 時間、圖說（無襯線）
    val MetaLine = 19.sp
    val Tiny = 11.sp        // 方框標籤、欄外註記（無襯線）
    val TinyLine = 16.sp
    val Mono = 13.sp
    val Metric = 30.sp      // 大數字（金額、額度）
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
 */
val ButlerColors = lightColorScheme(
    primary = Palette.Text,             // 主動作是墨色。朱紅留給「要注意」不給「可以按」
    onPrimary = Palette.Bg,
    primaryContainer = Palette.SurfaceHi,
    onPrimaryContainer = Palette.Text,
    secondary = Palette.Accent,
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
    onError = Palette.Bg,
    errorContainer = Palette.DangerSoft,
    onErrorContainer = Palette.Danger,
    outline = Palette.Line,
    outlineVariant = Palette.Line,
    // 淺底上的 scrim 要重一點，紙蓋紙才分得出誰浮著
    scrim = Color(0xB3000000),
)
