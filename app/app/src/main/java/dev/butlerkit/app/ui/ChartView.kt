package dev.butlerkit.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/**
 * 畫一張圖表。資料格式與解析在 `Chart.kt`。
 *
 * 高度是固定的：手機上圖表要跟文字一起捲，讓它按資料量長高會把整段回覆撐爆。
 * 寬度吃滿，格線與刻度隨可用空間算——不預設幾格，算出來幾格就幾格。
 *
 * 配色走主題的既有色而不是另配一套：一張圖夾在對話中間，自己帶一套彩虹色
 * 會像貼上去的。多序列時依序取 [SERIES_COLORS]，超過就繞回來——
 * 手機上同時看五條以上的線本來就讀不動，繞回來比硬湊出第六個顏色誠實。
 */

private val PLOT_HEIGHT = 190.dp

@Composable
private fun seriesColors(): List<Color> = listOf(
    Palette.Accent, Palette.Ok, Palette.Warn, Palette.TextDim, Palette.Danger,
)

// FlowRow 還掛著實驗標記，但它從 Compose 1.4 起介面沒動過，而圖例需要的
// 「排不下就換行」用 Row 做不到——序列名稱長度不可控，硬排會被切掉。
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChartView(spec: ChartSpec, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val colors = seriesColors()
    val gridColor = Palette.Line
    val axisColor = Palette.TextFaint
    val labelColor = Palette.TextDim

    Column(
        modifier.fillMaxWidth()
            .clip(Radii.Card)
            .background(Palette.Surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (spec.title.isNotBlank()) {
            Text(
                spec.title, color = Palette.Text, fontSize = Type.Meta,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(PLOT_HEIGHT)) {
            drawChart(spec, measurer, colors, gridColor, axisColor, labelColor)
        }
        if (spec.xLabel.isNotBlank()) {
            Text(
                spec.xLabel, color = Palette.TextFaint, fontSize = Type.Tiny,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        // 圖例。只有一組資料時不畫——標題已經說了那是什麼
        if (spec.series.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                spec.series.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(9.dp).clip(Radii.Tiny)
                                .background(colors[i % colors.size]),
                        )
                        Text(
                            s.name.ifBlank { "第 ${i + 1} 組" },
                            color = Palette.TextDim, fontSize = Type.Tiny,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 真正畫圖的地方。
 *
 * 邊距是量出來的不是猜的：y 軸標籤可能是「0」也可能是「1.5e-9」，寫死一個
 * 左邊距不是把數字切掉就是留一大塊空白。
 */
private fun DrawScope.drawChart(
    spec: ChartSpec,
    measurer: TextMeasurer,
    colors: List<Color>,
    gridColor: Color,
    axisColor: Color,
    labelColor: Color,
) {
    val ys = spec.ys
    if (ys.isEmpty()) return
    val bars = spec.kind == ChartKind.Bar
    val fromZero = bars || spec.kind == ChartKind.Area
    val (yLo, yHi, yStep) = niceRange(
        if (fromZero) minOf(0f, ys.min()) else ys.min(),
        if (fromZero) maxOf(0f, ys.max()) else ys.max(),
    )
    if (yHi <= yLo) return

    val tick = TextStyle(fontSize = Type.Tiny, color = labelColor,
        fontFamily = FontFamily.SansSerif)
    fun label(s: String) = measurer.measure(AnnotatedString(s), tick)

    // y 刻度先量出來，最寬的那個決定左邊距
    val yTicks = buildList {
        var v = yLo
        while (v <= yHi + yStep * 0.001f) { add(v); v += yStep }
    }
    val yLabels = yTicks.map { label(tickLabel(it, yStep)) }
    val padLeft = (yLabels.maxOfOrNull { it.size.width } ?: 0) + 8f
    val padBottom = (yLabels.firstOrNull()?.size?.height ?: 12).toFloat() + 6f
    val padTop = 6f
    val padRight = 6f
    val plotW = size.width - padLeft - padRight
    val plotH = size.height - padTop - padBottom
    if (plotW <= 0f || plotH <= 0f) return

    fun yAt(v: Float) = padTop + plotH * (1f - (v - yLo) / (yHi - yLo))

    // x 軸：有 labels 就是分類軸（每格一個類別），否則照數值鋪開
    val cats = spec.labels
    val xs = spec.xs
    val xLo: Float
    val xHi: Float
    if (cats.isNotEmpty() || bars) {
        // 分類軸左右各留半格，長條才不會貼著邊
        val n = maxOf(cats.size, spec.series.maxOf { it.pts.size })
        xLo = -0.5f
        xHi = n - 0.5f
    } else {
        // 數值軸左右各留一點邊：不留的話第一個與最後一個資料點會長在軸線上，
        // 圓點被切掉一半，看起來像圖畫壞了
        val lo = xs.min()
        val hi = if (xs.max() > xs.min()) xs.max() else xs.min() + 1f
        val pad = (hi - lo) * 0.04f
        xLo = lo - pad
        xHi = hi + pad
    }
    fun xAt(v: Float) = padLeft + plotW * ((v - xLo) / (xHi - xLo))

    // 水平格線與 y 標籤
    val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 6f), 0f)
    yTicks.forEachIndexed { i, v ->
        val y = yAt(v)
        val zero = v == 0f && yLo < 0f
        drawLine(
            color = if (zero) axisColor else gridColor,
            start = Offset(padLeft, y), end = Offset(size.width - padRight, y),
            strokeWidth = if (zero) 1.5f else 1f,
            pathEffect = if (zero) null else dash,
        )
        val lb = yLabels[i]
        drawText(lb, topLeft = Offset(padLeft - 6f - lb.size.width,
            y - lb.size.height / 2f))
    }

    // x 軸標籤。密的時候跳著畫，不要疊在一起
    if (cats.isNotEmpty()) {
        val each = plotW / cats.size
        val sample = label(cats.maxByOrNull { it.length } ?: "")
        val every = maxOf(1, Math.ceil((sample.size.width + 8f) / each.toDouble()).toInt())
        cats.forEachIndexed { i, c ->
            if (i % every != 0) return@forEachIndexed
            val lb = label(c)
            drawText(lb, topLeft = Offset(
                (xAt(i.toFloat()) - lb.size.width / 2f)
                    .coerceIn(0f, size.width - lb.size.width),
                size.height - padBottom + 4f,
            ))
        }
    } else {
        val (_, _, xStep) = niceRange(xLo, xHi, 3)
        var v = Math.ceil(xLo / xStep.toDouble()).toFloat() * xStep
        while (v <= xHi + xStep * 0.001f) {
            val lb = label(tickLabel(v, xStep))
            drawText(lb, topLeft = Offset(
                (xAt(v) - lb.size.width / 2f).coerceIn(0f, size.width - lb.size.width),
                size.height - padBottom + 4f,
            ))
            v += xStep
        }
    }

    // 資料
    spec.series.forEachIndexed { si, s ->
        val color = colors[si % colors.size]
        when (spec.kind) {
            ChartKind.Bar -> {
                val slot = plotW / maxOf(1, (xHi - xLo).toInt())
                val bw = (slot * 0.7f) / spec.series.size
                s.pts.forEach { p ->
                    val cx = xAt(p.x) - (slot * 0.35f) + bw * si
                    val top = yAt(maxOf(p.y, 0f))
                    val bottom = yAt(minOf(p.y, 0f))
                    drawRect(
                        color = color,
                        topLeft = Offset(cx, top),
                        size = Size(bw, maxOf(bottom - top, 1f)),
                    )
                }
            }
            ChartKind.Scatter -> s.pts.forEach { p ->
                drawCircle(color, radius = 3.5f, center = Offset(xAt(p.x), yAt(p.y)))
            }
            ChartKind.Line, ChartKind.Area -> {
                val pts = s.pts.sortedBy { it.x }
                if (pts.isEmpty()) return@forEachIndexed
                val path = if (spec.smooth && pts.size >= 3) {
                    smoothPath(pts.map { Offset(xAt(it.x), yAt(it.y)) })
                } else {
                    Path().apply {
                        pts.forEachIndexed { i, p ->
                            if (i == 0) moveTo(xAt(p.x), yAt(p.y))
                            else lineTo(xAt(p.x), yAt(p.y))
                        }
                    }
                }
                if (spec.kind == ChartKind.Area) {
                    val fill = Path().apply {
                        addPath(path)
                        lineTo(xAt(pts.last().x), yAt(maxOf(yLo, 0f)))
                        lineTo(xAt(pts.first().x), yAt(maxOf(yLo, 0f)))
                        close()
                    }
                    drawPath(fill, color.copy(alpha = 0.18f))
                }
                drawPath(path, color, style = Stroke(width = 2.2f))
                // 點少的時候標出每一點：三五個點的折線不標的話看不出資料在哪
                if (pts.size <= 12) {
                    pts.forEach { p -> drawCircle(color, 3f, Offset(xAt(p.x), yAt(p.y))) }
                }
            }
        }
    }

    // 軸線最後畫，壓在資料上面
    drawLine(axisColor, Offset(padLeft, padTop), Offset(padLeft, padTop + plotH),
        strokeWidth = 1f)
    drawLine(axisColor, Offset(padLeft, padTop + plotH),
        Offset(size.width - padRight, padTop + plotH), strokeWidth = 1f)
}

/**
 * 把一串點連成平滑曲線（Catmull-Rom 轉三次貝茲）。
 *
 * **控制點的 y 要夾在該段兩端之間**，這是重點：不夾的話曲線會在轉折處過衝——
 * 庫侖力那種單調遞減的資料會彎出一個不存在的反彈，甚至跌到負的。圖是拿來讀
 * 數字的，寧可少一點曲率也不能畫出資料裡沒有的東西。
 *
 * 夾的是 y 不是 x：x 方向的過衝只會讓曲線左右鬆緊不一，看不出來；
 * y 方向的過衝是在說謊。
 */
private fun smoothPath(p: List<Offset>): Path = Path().apply {
    moveTo(p[0].x, p[0].y)
    for (i in 0 until p.size - 1) {
        val p0 = p[maxOf(0, i - 1)]
        val p1 = p[i]
        val p2 = p[i + 1]
        val p3 = p[minOf(p.size - 1, i + 2)]
        val lo = minOf(p1.y, p2.y)
        val hi = maxOf(p1.y, p2.y)
        cubicTo(
            p1.x + (p2.x - p0.x) / 6f, (p1.y + (p2.y - p0.y) / 6f).coerceIn(lo, hi),
            p2.x - (p3.x - p1.x) / 6f, (p2.y - (p3.y - p1.y) / 6f).coerceIn(lo, hi),
            p2.x, p2.y,
        )
    }
}
