package dev.butlerkit.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import kotlin.math.sin

/**
 * 助理的狀態，由 ViewModel 依真實事件流驅動。
 *
 * 這七個狀態是**功能**不是裝飾——它是使用者判斷「電腦那邊到底在幹嘛」的主要
 * 訊號，尤其人在外面、看不到電腦螢幕的時候。
 *
 * 什麼時候會是哪一個：
 *   Offline  連不上電腦
 *   Idle     閒著，等你說話
 *   Thinking 模型在想（還沒開始輸出）
 *   Working  正在跑工具（讀檔、跑指令）
 *   Talking  回覆正在逐字生成
 *   Happy    整則訊息做完了
 *   Error    出錯了
 */
enum class PetMood { Offline, Idle, Thinking, Working, Talking, Happy, Error }

/**
 * **這裡放你的吉祥物。**
 *
 * 現在畫的只是一顆會呼吸的點——它是佔位用的，只做到「看得出現在是哪個狀態」
 * 而已。這是整個 App 裡最有記憶點的位置，一顆點顯然不會是你要的樣子。
 *
 * ── 換的方法 ──────────────────────────────────────────────────────────────
 *
 * 這支整個換掉就好，對外只暴露兩樣東西，其餘四十幾個檔案都不必動：
 *   - [PetMood]（七個狀態，不要增減）
 *   - `PetFace(mood, size, modifier)` 這個簽名
 *
 * 不一定要用 Canvas 畫。換成一組圖檔、Lottie 動畫、一張會變表情的臉、甚至
 * 一顆會轉的方塊都行，只要吃得下 mood 跟 size。用 Canvas 的好處是顏色跟著
 * 色票走、放到任何尺寸都不糊、APK 不會變大。
 *
 * 設計時記得兩件事：
 *
 * 1. **它會出現在四種尺寸**：120dp（空對話的大圖）、96dp（鬧鈴畫面）、
 *    32dp（每則回覆旁邊）、24dp（底部分頁列）。**縮到 24dp 還分得出七個
 *    狀態**，比畫得精緻重要得多。顏色是最耐縮的區分方式，形狀細節不是。
 * 2. **七個狀態要真的看得出差別**。只換皮、七態長得差不多的話，使用者就
 *    失去了「它到底在跑還是卡住了」這個判斷依據——那是這東西的本業。
 */
@Composable
fun PetFace(mood: PetMood, size: Dp, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "pet")
    val slow by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "slow",
    )
    val fast by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "fast",
    )

    // 狀態靠顏色分，動態只是輔助——縮到 24dp 時看得出來的是顏色不是節奏
    val color = when (mood) {
        PetMood.Offline -> Palette.PetDot.copy(alpha = 0.3f)
        PetMood.Thinking, PetMood.Working -> Palette.Accent
        PetMood.Happy -> Palette.Ok
        PetMood.Error -> Palette.Danger
        else -> Palette.PetDot
    }
    val grow = when (mood) {
        PetMood.Offline -> 0.55f                        // 縮著不動
        PetMood.Idle -> 0.62f + wave(slow) * 0.05f      // 緩慢呼吸
        PetMood.Thinking -> 0.45f + wave(slow) * 0.40f  // 大幅脈動
        PetMood.Working -> 0.55f + wave(fast) * 0.20f   // 小幅快跳
        PetMood.Talking -> 0.55f + talk(fast) * 0.30f   // 跟著說話的節奏
        PetMood.Happy -> 0.95f                          // 撐到最大
        PetMood.Error -> 0.62f
    }

    Canvas(modifier.size(size)) {
        drawCircle(color, this.size.minDimension * 0.22f * grow, center)
    }
}

/** 0..1 的相位轉成 0..1 的正弦波。 */
private fun wave(t: Float): Float = (sin(t * 2 * Math.PI).toFloat() + 1f) / 2f

/** 說話的節奏：兩個頻率疊起來才不規律，單一頻率聽起來像機器在打拍子。 */
private fun talk(t: Float): Float =
    ((sin(t * 6 * Math.PI) * 0.6 + sin(t * 10 * Math.PI) * 0.4).toFloat() + 1f) / 2f
