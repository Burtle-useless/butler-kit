package dev.butlerkit.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 把 [MathNode] 畫出來。語法解析在 `Math.kt`，這裡只管排版與繪製。
 *
 * 排版模型跟 TeX 同一套但簡化過：每個東西量出來是「寬、基線以上、基線以下」
 * 三個數字，父節點拿這三個數字決定孩子放哪。**用基線而不是方框中心**是重點——
 * 分數、根號、上下標之所以看起來對得齊，靠的全是基線對齊；改成中心對齊的話
 * `F = ma` 的等號會浮在半空中。
 *
 * **所有長度一律是像素。** 字級傳進來是 sp，進門第一件事就換算成 px——
 * 混用的話分數線的位置會差一個 density 倍，
 * 而那種錯畫出來「只是有點怪」，不會報錯。
 *
 * 畫在 Canvas 上而不是用 Text 組合：分數要在分子分母中間畫線、根號要畫勾，
 * 那些在文字流裡做不出來。代價是選不起來也複製不了，所以獨立式長按可以
 * 複製原始的 LaTeX（見 [MathBlock]）。
 */

/** 量好的一個節點。[draw] 收到的 y 是**基線**的位置，不是頂端。 */
private class Laid(
    val w: Float,
    val ascent: Float,
    val descent: Float,
    val draw: DrawScope.(x: Float, baseline: Float) -> Unit,
) {
    val h: Float get() = ascent + descent
}

private val EMPTY = Laid(0f, 0f, 0f) { _, _ -> }

private class MathLayout(
    private val measurer: TextMeasurer,
    private val density: Density,
    private val color: Color,
    /** 行內模式：分數的分子分母縮小，整體高度才壓得進一行文字裡。
     *  TeX 分行內（textstyle）與獨立（displaystyle）兩種樣式，原因就是這個——
     *  照獨立式的比例塞進句子裡，那一行會把上下兩行擠開。 */
    private val inline: Boolean = false,
) {
    /** 上下標縮到這個比例。TeX 用 0.7，手機上太小看不清，抬到 0.72。 */
    private val scriptScale = 0.72f

    private fun px(size: TextUnit): Float = with(density) { size.toPx() }

    fun measure(n: MathNode, size: TextUnit): Laid = when (n) {
        is MathNode.Sym -> atom(n.text, size, n.italic, FontFamily.Serif)
        is MathNode.Plain -> atom(n.text, size, false, FontFamily.SansSerif)
        is MathNode.Row -> row(n.items, size)
        is MathNode.Script -> script(n, size)
        is MathNode.Frac -> frac(n, size)
        is MathNode.Sqrt -> sqrt(n, size)
        is MathNode.Accent -> accent(n, size)
    }

    private fun atom(text: String, size: TextUnit, italic: Boolean,
                     family: FontFamily): Laid {
        if (text.isEmpty()) return EMPTY
        val style = TextStyle(
            fontSize = size, color = color, fontFamily = family,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        )
        val r = measurer.measure(AnnotatedString(text), style)
        val base = r.firstBaseline
        return Laid(r.size.width.toFloat(), base, r.size.height - base) { x, y ->
            drawText(r, topLeft = Offset(x, y - base))
        }
    }

    /** 兩個東西之間，只要任一邊是關係／運算子就留一點氣。 */
    private fun spaced(n: MathNode?): Boolean =
        n is MathNode.Sym && n.text.length == 1 && n.text[0] in MATH_SPACED

    private fun row(items: List<MathNode>, size: TextUnit): Laid {
        if (items.isEmpty()) return EMPTY
        val laid = items.map { measure(it, size) }
        val gap = px(size) * 0.18f
        val xs = FloatArray(laid.size)
        var total = 0f
        for (k in laid.indices) {
            if (k > 0 && (spaced(items[k]) || spaced(items[k - 1]))) total += gap
            xs[k] = total
            total += laid[k].w
        }
        val asc = laid.maxOf { it.ascent }
        val desc = laid.maxOf { it.descent }
        return Laid(total, asc, desc) { x, y ->
            laid.forEachIndexed { k, l -> l.draw(this, x + xs[k], y) }
        }
    }

    private fun script(n: MathNode.Script, size: TextUnit): Laid {
        val base = measure(n.base, size)
        val small = (size.value * scriptScale).sp
        val sup = n.sup?.let { measure(it, small) }
        val sub = n.sub?.let { measure(it, small) }
        val u = px(size)
        val supShift = u * 0.46f       // 上標基線往上抬多少
        val subShift = u * 0.20f       // 下標基線往下壓多少
        val w = base.w + maxOf(sup?.w ?: 0f, sub?.w ?: 0f)
        val asc = maxOf(base.ascent, sup?.let { it.ascent + supShift } ?: 0f)
        val desc = maxOf(base.descent, sub?.let { it.descent + subShift } ?: 0f)
        return Laid(w, asc, desc) { x, y ->
            base.draw(this, x, y)
            sup?.draw(this, x + base.w, y - supShift)
            sub?.draw(this, x + base.w, y + subShift)
        }
    }

    /** 這個東西寫成斜線形式時需不需要括號：`a+b` 要，`kq_2q_3` 不用。 */
    private fun loose(n: MathNode): Boolean = when (n) {
        is MathNode.Sym -> n.text.length == 1 &&
            (n.text[0] in MATH_SPACED || n.text[0] == '-')
        is MathNode.Row -> n.items.any { loose(it) }
        else -> false
    }

    private fun paren(n: MathNode): MathNode =
        if (loose(n)) {
            MathNode.Row(listOf(MathNode.Sym("("), n, MathNode.Sym(")")))
        } else {
            n
        }

    private fun frac(n: MathNode.Frac, size: TextUnit): Laid {
        // 行內一律寫成 a/b。橫線分數有兩層樓高，塞進句子裡不是被下一行蓋掉、
        // 就是把整段行距撐開，兩種都試過都不行。排版慣例本來就是行內用斜線、
        // 獨立式才畫橫線，這裡照著做。
        if (inline) {
            return row(listOf(paren(n.num), MathNode.Sym("/"), paren(n.den)), size)
        }
        val num = measure(n.num, size)
        val den = measure(n.den, size)
        val u = px(size)
        val axis = u * 0.30f           // 分數線在基線上方多高（數學軸）
        val gap = u * 0.16f
        val pad = u * 0.14f
        val thick = maxOf(1f, u * 0.055f)
        val w = maxOf(num.w, den.w) + pad * 2
        val asc = axis + gap + num.descent + num.ascent
        val desc = maxOf(gap + den.ascent + den.descent - axis, 0f)
        return Laid(w, asc, desc) { x, y ->
            val lineY = y - axis
            drawLine(
                color = color,
                start = Offset(x + pad * 0.4f, lineY),
                end = Offset(x + w - pad * 0.4f, lineY),
                strokeWidth = thick,
            )
            num.draw(this, x + (w - num.w) / 2f, lineY - gap - num.descent)
            den.draw(this, x + (w - den.w) / 2f, lineY + gap + den.ascent)
        }
    }

    private fun sqrt(n: MathNode.Sqrt, size: TextUnit): Laid {
        val body = measure(n.body, size)
        val u = px(size)
        val hook = u * 0.52f           // 勾佔的寬度
        val lid = u * 0.16f            // 上蓋離內容頂端多遠
        val pad = u * 0.12f
        val thick = maxOf(1f, u * 0.05f)
        val w = hook + body.w + pad * 2
        val asc = body.ascent + lid + thick
        return Laid(w, asc, body.descent) { x, y ->
            val top = y - asc
            val bottom = y + body.descent
            // 勾：左下起一個 V，再往右上拉一條橫蓋罩住內容
            val p = Path().apply {
                moveTo(x, bottom - body.h * 0.42f)
                lineTo(x + hook * 0.34f, bottom - body.h * 0.22f)
                lineTo(x + hook * 0.70f, bottom)
                lineTo(x + hook, top + thick / 2f)
                lineTo(x + w, top + thick / 2f)
            }
            drawPath(p, color, style = Stroke(width = thick))
            body.draw(this, x + hook + pad, y)
        }
    }

    private fun accent(n: MathNode.Accent, size: TextUnit): Laid {
        val base = measure(n.base, size)
        val u = px(size)
        val lift = u * 0.10f           // 記號離字頂多遠
        val hi = u * 0.22f             // 記號自己佔多高
        val thick = maxOf(1f, u * 0.05f)
        val asc = base.ascent + lift + hi
        return Laid(base.w, asc, base.descent) { x, y ->
            base.draw(this, x, y)
            val top = y - base.ascent - lift
            val cx = x + base.w / 2f
            val half = base.w / 2f
            when (n.kind) {
                AccentKind.Vec -> {
                    val ly = top - hi / 2f
                    drawLine(color, Offset(x, ly), Offset(x + base.w, ly),
                        strokeWidth = thick)
                    val tip = Offset(x + base.w, ly)
                    drawLine(color, tip, Offset(tip.x - hi * 0.5f, tip.y - hi * 0.4f),
                        strokeWidth = thick)
                    drawLine(color, tip, Offset(tip.x - hi * 0.5f, tip.y + hi * 0.4f),
                        strokeWidth = thick)
                }
                AccentKind.Hat -> {
                    drawLine(color, Offset(cx - half * 0.7f, top),
                        Offset(cx, top - hi), strokeWidth = thick)
                    drawLine(color, Offset(cx, top - hi),
                        Offset(cx + half * 0.7f, top), strokeWidth = thick)
                }
                AccentKind.Bar -> drawLine(
                    color, Offset(x, top - hi / 2f),
                    Offset(x + base.w, top - hi / 2f), strokeWidth = thick,
                )
                AccentKind.Dot -> drawCircle(
                    color, radius = thick, center = Offset(cx, top - hi / 2f),
                )
                AccentKind.Tilde -> {
                    val p = Path().apply {
                        moveTo(cx - half * 0.8f, top - hi * 0.3f)
                        quadraticTo(cx - half * 0.3f, top - hi * 1.1f, cx, top - hi * 0.5f)
                        quadraticTo(cx + half * 0.3f, top + hi * 0.1f,
                            cx + half * 0.8f, top - hi * 0.6f)
                    }
                    drawPath(p, color, style = Stroke(width = thick))
                }
            }
        }
    }
}

/**
 * 式子畫出來會佔多大（像素）。
 *
 * 行內式要塞進文字流得先告訴 Compose 它多大（`Placeholder` 的規矩），
 * 而那個尺寸只有真的排版過才知道。顏色對尺寸沒有影響，所以這裡隨便給一個。
 */
internal fun mathSizePx(measurer: TextMeasurer, density: Density,
                        src: String, size: TextUnit,
                        inline: Boolean = false): Pair<Float, Float> {
    val laid = MathLayout(measurer, density, Color.Black, inline)
        .measure(parseMath(src), size)
    return laid.w to laid.h
}

/**
 * 一個數學式。[size] 預設跟內文同級——行內式混在句子裡要一樣大才不會突兀。
 *
 * 量出來的尺寸直接變成 Canvas 的大小，所以式子多寬就佔多寬；放不下時要橫捲
 * 還是要縮由呼叫端決定，這裡不自作主張。
 */
@Composable
internal fun MathView(src: String, modifier: Modifier = Modifier,
                      size: TextUnit = Type.Body, inline: Boolean = false) {
    val measurer = rememberTextMeasurer()
    val color = Palette.Text
    val density = LocalDensity.current
    val node = remember(src) { parseMath(src) }
    val laid = remember(node, size, color, density, inline) {
        MathLayout(measurer, density, color, inline).measure(node, size)
    }
    val w = with(density) { laid.w.toDp() }
    val h = with(density) { laid.h.toDp() }
    Canvas(modifier.size(w, h)) {
        laid.draw(this, 0f, laid.ascent)
    }
}

/**
 * 獨立式（`$$...$$`）：上下留白，太寬先縮，縮到底還是放不下才橫捲。
 *
 * 先縮再捲的順序是有理由的：一條式子被切在畫面外，使用者得先發現它可以捲
 * 才讀得到後半段——而畫面上沒有任何東西在說「這裡還有」。縮小是無聲的，
 * 捲動不是。下限壓在內文字級，再小就得瞇著眼看，那時候寧可捲。
 */
@Composable
internal fun MathBlock(src: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val avail = with(density) { maxWidth.toPx() }
        val natural = remember(src, density) {
            mathSizePx(measurer, density, src, Type.Title).first
        }
        val size = if (natural > avail && avail > 0f) {
            maxOf(Type.Title.value * avail / natural, Type.Meta.value).sp
        } else {
            Type.Title
        }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            MathView(src, size = size)
        }
    }
}
