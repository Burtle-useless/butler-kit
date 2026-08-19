package dev.butlerkit.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import kotlin.math.sin

/**
 * 桌寵的心情，由 ViewModel 依真實事件流驅動。
 * 這是它比靜態立繪「活」的原因：表情反映的是 App 當下實際在做的事。
 */
enum class PetMood { Offline, Idle, Thinking, Working, Talking, Happy, Error }

/**
 * 桌寵的「螢幕臉」——AMAS 機體的表情部分。
 *
 * 只取機器人的臉：白色機體外框＋深色螢幕＋發光眼睛。
 *
 * 表情與動作：
 *   Offline  螢幕暗、眼睛只剩兩顆極暗的點
 *   Idle     正常眼、每四秒眨一次、偶爾左右張望、緩慢漂浮
 *   Thinking 眼睛飄上方緩慢游移＋三顆點依序亮
 *   Working  瞇眼專注＋視線左右掃
 *   Talking  正常眼＋嘴巴開合（回覆逐字生成中）
 *   Happy    ^ ^ 眼＋微笑＋整臉彈一下
 *   Error    > < 眼＋螢幕泛紅＋左右搖頭
 */
@Composable
fun PetFace(mood: PetMood, size: Dp, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "kei")

    val bob by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "bob",
    )
    val blink by inf.animateFloat(
        initialValue = 1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 4200
                1f at 0; 1f at 3900
                0.08f at 3990; 1f at 4080
            },
        ),
        label = "blink",
    )
    // 快相位：點點輪播、嘴巴開合、掃視
    val phase by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "phase",
    )
    // 慢相位：待機時的眼神游移（8 秒一輪，偶爾看看旁邊）
    val wander by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)),
        label = "wander",
    )

    // 心情切換的一次性動作：Happy 彈跳、Error 搖頭。
    // 用 mood 當 key 觸發，spring 回彈做出「動一下就停」的效果。
    var kick by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(mood) {
        if (mood == PetMood.Happy || mood == PetMood.Error) kick = 1f
    }
    val kickAnim by animateFloatAsState(
        targetValue = kick,
        animationSpec = spring(dampingRatio = 0.28f, stiffness = Spring.StiffnessMedium),
        finishedListener = { kick = 0f },
        label = "kick",
    )

    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 100f
        val bobY = sin(bob * 2 * Math.PI).toFloat() * 2f * u
        // Happy：垂直彈跳；Error：水平搖頭。共用同一個 spring。
        val jumpY = if (mood == PetMood.Happy) -kickAnim * 7f * u else 0f
        val shakeX = if (mood == PetMood.Error)
            sin(kickAnim * 5 * Math.PI).toFloat() * 4f * u * kickAnim else 0f
        val pop = if (mood == PetMood.Happy) 1f + kickAnim * 0.05f else 1f

        translate(left = shakeX, top = bobY + jumpY) {
            scale(pop, pivot = center) {
                // 報紙插畫：方角、墨線框。機體是紙色，畫在紙色背景上沒有外框
                // 會直接隱形——深底時代白機體自己就浮得出來，現在不行了
                drawRoundRect(
                    Palette.PetBody,
                    topLeft = Offset(6f * u, 14f * u),
                    size = Size(88f * u, 72f * u),
                    cornerRadius = CornerRadius.Zero,
                )
                drawRoundRect(
                    Palette.PetScreen,
                    topLeft = Offset(6f * u, 14f * u),
                    size = Size(88f * u, 72f * u),
                    cornerRadius = CornerRadius.Zero,
                    style = Stroke(width = 2.5f * u),
                )
                val screen = if (mood == PetMood.Error) Palette.Danger else Palette.PetScreen
                drawRoundRect(
                    screen,
                    topLeft = Offset(13f * u, 21f * u),
                    size = Size(74f * u, 58f * u),
                    cornerRadius = CornerRadius.Zero,
                )
                drawFace(u, mood, blink, phase, wander)
            }
        }
    }
}

private fun DrawScope.drawFace(
    u: Float, mood: PetMood, blink: Float, phase: Float, wander: Float,
) {
    val eyeY = 47f * u
    val lx = 36f * u
    val rx = 64f * u
    // 紙色眼睛在墨黑螢幕上——印刷負片。朱紅只留給 Error 的螢幕，
    // 平常就頂著紅眼睛會一直像出事了
    val eye = Palette.PetBody
    val stroke = Stroke(width = 4.5f * u, cap = StrokeCap.Round)

    when (mood) {
        PetMood.Offline -> {
            drawCircle(Palette.TextFaint.copy(alpha = 0.35f), 2.4f * u, Offset(lx, eyeY))
            drawCircle(Palette.TextFaint.copy(alpha = 0.35f), 2.4f * u, Offset(rx, eyeY))
        }
        PetMood.Idle -> {
            // 眼神游移：8 秒週期裡只有中段偏移（大多數時間看正前方）
            val w = sin(wander * 2 * Math.PI).toFloat()
            val drift = if (w > 0.7f) (w - 0.7f) / 0.3f * 4f * u
            else if (w < -0.7f) (w + 0.7f) / 0.3f * 4f * u else 0f
            val h = 13f * u * blink
            for (x in listOf(lx, rx)) {
                drawRoundRect(
                    eye, topLeft = Offset(x - 4.5f * u + drift, eyeY - h / 2),
                    size = Size(9f * u, h), cornerRadius = CornerRadius(4f * u),
                )
            }
        }
        PetMood.Thinking -> {
            // 眼睛上飄＋緩慢游移，像在半空中找答案
            val drift = sin(wander * 4 * Math.PI).toFloat() * 3f * u
            val h = 9f * u
            for (x in listOf(lx, rx)) {
                drawRoundRect(
                    eye, topLeft = Offset(x - 4f * u + drift, eyeY - 7f * u - h / 2),
                    size = Size(8f * u, h), cornerRadius = CornerRadius(3.5f * u),
                )
            }
            val active = (phase * 3).toInt() % 3
            for (i in 0..2) {
                val a = if (i == active) 0.9f else 0.25f
                drawCircle(
                    eye.copy(alpha = a), 2.2f * u,
                    Offset((41f + i * 9f) * u, 68f * u),
                )
            }
        }
        PetMood.Working -> {
            val sweep = sin(phase * 2 * Math.PI).toFloat() * 3f * u
            for (x in listOf(lx, rx)) {
                drawRoundRect(
                    eye, topLeft = Offset(x - 6f * u + sweep, eyeY - 2.5f * u),
                    size = Size(12f * u, 5f * u), cornerRadius = CornerRadius(2.5f * u),
                )
            }
        }
        PetMood.Talking -> {
            // 正常眼＋嘴巴開合。嘴高用兩個 sin 疊出不規律的節奏，比單一頻率更像在講話
            val h = 12f * u * blink
            for (x in listOf(lx, rx)) {
                drawRoundRect(
                    eye, topLeft = Offset(x - 4.5f * u, eyeY - h / 2),
                    size = Size(9f * u, h), cornerRadius = CornerRadius(4f * u),
                )
            }
            val talk = (sin(phase * 6 * Math.PI) * 0.6 + sin(phase * 10 * Math.PI) * 0.4)
                .toFloat() * 0.5f + 0.5f
            val mh = (1.5f + talk * 5f) * u
            drawRoundRect(
                eye.copy(alpha = 0.85f),
                topLeft = Offset(50f * u - 4f * u, 66f * u - mh / 2),
                size = Size(8f * u, mh),
                cornerRadius = CornerRadius(3f * u),
            )
        }
        PetMood.Happy -> {
            for (x in listOf(lx, rx)) {
                val p = Path().apply {
                    moveTo(x - 7f * u, eyeY + 3f * u)
                    lineTo(x, eyeY - 4.5f * u)
                    lineTo(x + 7f * u, eyeY + 3f * u)
                }
                drawPath(p, eye, style = stroke)
            }
            // 微笑：淺淺一條上彎弧
            val smile = Path().apply {
                moveTo(44f * u, 66f * u)
                quadraticBezierTo(50f * u, 70f * u, 56f * u, 66f * u)
            }
            drawPath(smile, eye, style = Stroke(width = 3f * u, cap = StrokeCap.Round))
        }
        PetMood.Error -> {
            val red = Palette.Danger
            val p1 = Path().apply {
                moveTo(lx - 6f * u, eyeY - 5f * u); lineTo(lx + 4f * u, eyeY)
                lineTo(lx - 6f * u, eyeY + 5f * u)
            }
            val p2 = Path().apply {
                moveTo(rx + 6f * u, eyeY - 5f * u); lineTo(rx - 4f * u, eyeY)
                lineTo(rx + 6f * u, eyeY + 5f * u)
            }
            drawPath(p1, red, style = stroke)
            drawPath(p2, red, style = stroke)
            // 扁嘴
            drawRoundRect(
                red.copy(alpha = 0.8f),
                topLeft = Offset(45f * u, 66f * u),
                size = Size(10f * u, 2.5f * u),
                cornerRadius = CornerRadius(1.2f * u),
            )
        }
    }
}
