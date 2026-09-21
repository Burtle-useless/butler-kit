package dev.butlerkit.app.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * 圖表的資料格式。畫在 [ChartView]。
 *
 * 模型在回覆裡寫一個 ```chart 區塊就會變成一張圖：
 *
 *     ```chart
 *     {"kind":"line","title":"庫侖力隨距離",
 *      "x":"r (m)","y":"F (N)",
 *      "series":[{"name":"q=2nC","points":[[1,18],[2,4.5],[3,2]]}]}
 *     ```
 *
 * **只收算好的點，不收函數式。** 要畫 1/r² 就自己取樣幾十個點——在這裡塞一個
 * 表達式求值器等於多養一個小語言，而模型算點這件事本來就做得又快又對。
 *
 * 長條圖與分類軸用另一種寫法，省得每個點都寫成 `[0,值]`：
 *
 *     {"kind":"bar","labels":["一月","二月"],"series":[{"name":"收入","values":[100,200]}]}
 *
 * 解析不出來就回 null，呼叫端退回「照原樣顯示這段程式碼」——模型寫壞了的時候
 * 看得到自己寫了什麼，比看到一個「圖表格式錯誤」的空框有用得多。
 */

internal enum class ChartKind { Line, Bar, Scatter, Area }

internal data class Pt(val x: Float, val y: Float)

internal data class ChartSeries(val name: String, val pts: List<Pt>)

internal data class ChartSpec(
    val kind: ChartKind,
    val title: String = "",
    val xLabel: String = "",
    val yLabel: String = "",
    /** 分類軸的標籤。空的話 x 軸是數值軸。 */
    val labels: List<String> = emptyList(),
    val series: List<ChartSeries> = emptyList(),
    /** 折線要不要畫成平滑曲線。預設要——會用到折線的多半是連續量
     *  （力隨距離、溫度隨時間），那種東西本來就不是一段一段折的。
     *  真的是階段性資料（每月營收）就傳 false，直線比較誠實。 */
    val smooth: Boolean = true,
) {
    val xs: List<Float> get() = series.flatMap { s -> s.pts.map { it.x } }
    val ys: List<Float> get() = series.flatMap { s -> s.pts.map { it.y } }
}

private fun kindOf(s: String): ChartKind = when (s.trim().lowercase()) {
    "bar", "column", "長條", "柱狀" -> ChartKind.Bar
    "scatter", "point", "散佈", "散點" -> ChartKind.Scatter
    "area", "面積" -> ChartKind.Area
    else -> ChartKind.Line
}

/** `[[x,y],…]`、`[{"x":1,"y":2},…]` 兩種寫法都收。 */
private fun points(arr: JSONArray?): List<Pt> {
    if (arr == null) return emptyList()
    val out = mutableListOf<Pt>()
    for (i in 0 until arr.length()) {
        when (val item = arr.opt(i)) {
            is JSONArray -> if (item.length() >= 2) {
                out += Pt(item.optDouble(0).toFloat(), item.optDouble(1).toFloat())
            }
            is JSONObject -> out += Pt(
                item.optDouble("x", i.toDouble()).toFloat(),
                item.optDouble("y", 0.0).toFloat(),
            )
        }
    }
    return out.filter { it.x.isFinite() && it.y.isFinite() }
}

/** `values` 的寫法：x 用序號補上，配合 labels 就是分類軸。 */
private fun values(arr: JSONArray?): List<Pt> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val v = arr.optDouble(i).toFloat()
        if (v.isFinite()) Pt(i.toFloat(), v) else null
    }
}

private fun strings(arr: JSONArray?): List<String> =
    if (arr == null) emptyList()
    else (0 until arr.length()).map { arr.optString(it) }

internal fun parseChart(src: String): ChartSpec? = try {
    val o = JSONObject(src.trim())
    val rawSeries = o.optJSONArray("series")
    val series = mutableListOf<ChartSeries>()
    if (rawSeries != null) {
        for (i in 0 until rawSeries.length()) {
            val s = rawSeries.optJSONObject(i) ?: continue
            val pts = points(s.optJSONArray("points")).ifEmpty {
                values(s.optJSONArray("values"))
            }
            if (pts.isNotEmpty()) {
                series += ChartSeries(s.optString("name"), pts)
            }
        }
    } else {
        // 只有一組資料時可以省掉 series 這層：{"values":[…]} 或 {"points":[…]}
        val pts = points(o.optJSONArray("points")).ifEmpty {
            values(o.optJSONArray("values"))
        }
        if (pts.isNotEmpty()) series += ChartSeries(o.optString("name"), pts)
    }
    if (series.isEmpty()) null
    else ChartSpec(
        kind = kindOf(o.optString("kind", o.optString("type"))),
        title = o.optString("title"),
        xLabel = o.optString("x", o.optString("xlabel")),
        yLabel = o.optString("y", o.optString("ylabel")),
        labels = strings(o.optJSONArray("labels")),
        series = series,
        smooth = o.optBoolean("smooth", true),
    )
} catch (e: Exception) {
    null
}

// ── 座標軸的刻度 ────────────────────────────────────────────────────────
//
// 軸上的數字要落在「好看的位置」——0、5、10 這種，不是 0、3.7、7.4。
// 這件事沒做的話圖看起來就是半成品，而它跟畫圖本身無關，純粹是算術，
// 所以放在這裡跟著單元測試跑。

/** 一格跨多少。[target] 是希望有幾格。 */
internal fun niceStep(range: Float, target: Int = 4): Float {
    if (range <= 0f || !range.isFinite()) return 1f
    val raw = range / target.coerceAtLeast(1)
    val mag = Math.pow(10.0, Math.floor(Math.log10(raw.toDouble()))).toFloat()
    val norm = raw / mag
    val step = when {
        norm <= 1f -> 1f
        norm <= 2f -> 2f
        norm <= 2.5f -> 2.5f
        norm <= 5f -> 5f
        else -> 10f
    }
    return step * mag
}

/** 把資料範圍撐到刻度上。回傳 (最小, 最大, 一格)。 */
internal fun niceRange(lo: Float, hi: Float, target: Int = 4): Triple<Float, Float, Float> {
    // 全部一樣高的資料（常數序列）撐出一點範圍，否則會除以零
    if (!lo.isFinite() || !hi.isFinite()) return Triple(0f, 1f, 1f)
    if (lo == hi) {
        val pad = if (lo == 0f) 1f else Math.abs(lo) * 0.5f
        return Triple(lo - pad, hi + pad, niceStep(pad * 2, target))
    }
    val step = niceStep(hi - lo, target)
    val start = Math.floor(lo / step.toDouble()).toFloat() * step
    val end = Math.ceil(hi / step.toDouble()).toFloat() * step
    return Triple(start, end, step)
}

/** 刻度標籤。整數就不要拖著 `.0`，很小或很大的數字改用科學記號。 */
internal fun tickLabel(v: Float, step: Float): String {
    if (v == 0f) return "0"
    val a = Math.abs(v)
    if (a >= 100000f || a < 0.001f) {
        val exp = Math.floor(Math.log10(a.toDouble())).toInt()
        val mant = v / Math.pow(10.0, exp.toDouble()).toFloat()
        val m = if (Math.abs(mant - Math.round(mant)) < 0.05f) "${Math.round(mant)}"
        else String.format("%.1f", mant)
        return "${m}e$exp"
    }
    // 小數位數跟著格距走：一格 0.5 就給一位，一格 5 就不給
    val decimals = when {
        step >= 1f -> 0
        step >= 0.1f -> 1
        step >= 0.01f -> 2
        else -> 3
    }
    return String.format("%.${decimals}f", v)
}
