package dev.butlerkit.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 色票與尺度：冷調科技感。
 *
 * 方向是「深藍黑底 + 冰藍 accent」——對應助理 AMAS 機體的發光眼色。
 * 粉紅（頭頂那本書的顏色）是唯一的第二彩色，**只准用在桌寵身上**，
 * 介面本體仍然遵守單一 accent 的紀律。
 * 圓角刻意收小：科技感靠銳利邊緣，圓潤是上一版暖色系的語言。
 */
object Palette {
    val Bg = Color(0xFF0B0F17)          // 深藍黑：夜間儀表板，不是純黑
    val Surface = Color(0xFF121826)     // 卡片
    val SurfaceHi = Color(0xFF1A2233)   // 浮起的卡片（輸入框）
    val Line = Color(0xFF243044)        // 分隔與邊框

    val Text = Color(0xFFE6EAF2)        // 冷白
    val TextDim = Color(0xFF8B96AB)     // 次要
    val TextFaint = Color(0xFF5A6478)   // 背景資訊（思考、工具）

    val Accent = Color(0xFF5CCFE6)      // 冰藍：唯一的介面彩色（助理的眼睛色）
    val AccentSoft = Color(0x2E5CCFE6)  // 選取態、指示條

    // 訊息氣泡：實色分層（半透明在深底上對比不夠，是「畫面很鬆」的主因之一）
    val UserBubble = Color(0xFF17394A)  // 深青：一眼認出是自己說的
    val BotBubble = Color(0xFF161D2B)   // 比 Surface 再亮半階，浮在背景上

    val Danger = Color(0xFFE05C6E)
    val DangerSoft = Color(0x1FE05C6E)
    val Ok = Color(0xFF7DD3A0)
    val Warn = Color(0xFFE0A85C)        // 額度快用完的預警，介於 Ok 與 Danger 之間

    // 桌寵專用色，不得用於介面本體
    val PetBody = Color(0xFFEDF0F5)     // 機體白
    val PetJoint = Color(0xFF9AA4B5)    // 關節灰
    val PetScreen = Color(0xFF0E1420)   // 螢幕深色
    val PetBook = Color(0xFFE87CA0)     // 頭頂的書：粉紅
}

/**
 * 圓角三階用到底不混搭。冷色版整體收小 4-6dp。
 */
object Radii {
    val Bubble = RoundedCornerShape(12.dp)   // 對話氣泡
    val Card = RoundedCornerShape(8.dp)      // 卡片、對話框
    val Chip = RoundedCornerShape(6.dp)      // 小元件、程式碼區塊
}

object Type {
    val Body = 16.sp
    val BodyLine = 25.sp
    val Title = 17.sp
    val Meta = 13.sp
    val MetaLine = 19.sp
    val Tiny = 12.sp
    val Mono = 13.sp
}

object Space {
    val Screen = 16.dp
    val Between = 14.dp
    val Inner = 14.dp
}
