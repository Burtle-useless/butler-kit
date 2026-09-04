package dev.butlerkit.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.DayUsage
import dev.butlerkit.app.net.LocalUsage
import dev.butlerkit.app.net.PlanLimit
import dev.butlerkit.app.net.UsageBucket
import dev.butlerkit.app.net.UsageReport
import dev.butlerkit.app.net.humanError

/**
 * 用量表。
 *
 * 這裡**完全不談錢**。訂閱制是吃到飽，SDK 給的 total_cost_usd 只是「照 API 定價
 * 換算會是多少」的估算，跟實際帳單無關——擺在畫面上只會讓人以為自己被扣了這筆。
 * 真正該看的是額度用掉多少、還剩多久重置，那些在上面的進度條。
 *
 * **只記一份帳（2026-08-14 改）**：原本上半是「走助理的回合」、下半是「這台電腦全部」，
 * 兩套數字疊在同一張卡上，光是回合數就差了三百倍，沒人分得清該看哪個。額度是整個
 * 帳號共用的，會撞上限的是全機那份，走助理的只是其中一小塊——所以主體一律用全機的帳，
 * 助理自己的帳只在掃描失敗時當備援。
 */
@Composable
fun UsageCard(client: ButlerClient) {
    var report by remember { mutableStateOf<UsageReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // 重試用。原本是 LaunchedEffect(Unit)，一輩子只跑一次——開設定頁時電腦剛好
    // 沒開，之後電腦開了這張卡也永遠停在那行紅字，只能退出設定頁再進來一次。
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        error = null
        client.getUsage(14)
            .onSuccess { report = it }
            // 這裡跟聊天頁、日常頁共用同一套翻譯：三個地方各寫一份的話，
            // 同一次連不上會在三頁顯示成三種說法
            .onFailure { error = humanError(it) }
    }

    Column(
        Modifier.fillMaxWidth().background(Palette.Surface, Radii.Card)
            .border(1.dp, Palette.Line, Radii.Card)
            .padding(Space.Inner),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "用量", color = Palette.Text, fontSize = Type.Title,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            )
            report?.let {
                Text(it.monthKey, color = Palette.TextFaint, fontSize = Type.Tiny)
            }
        }

        val r = report
        // 方案額度放最前面：「這個月打了幾個字」是回顧，「還剩多少可以用」
        // 才是他打開這頁真正想知道的事。拿不到就整塊不顯示。
        r?.limits?.forEach { LimitBar(it) }
        if (!r?.limits.isNullOrEmpty()) {
            HorizontalDivider(color = Palette.Line, thickness = 0.6.dp)
        }

        // 全機的帳當主體，助理自己的帳只在本機掃描失敗時頂上。
        // 兩者格式一樣，下面的畫法完全不用分支。
        val l = r?.local?.takeIf { it.month.turns > 0 }
        val days = l?.days?.takeIf { it.isNotEmpty() } ?: r?.days.orEmpty()
        when {
            // 點下去要真的重拉，照日常頁那行紅字的同一套做法
            error != null -> Text(
                "${error}（點一下重試）", color = Palette.Danger, fontSize = Type.Meta,
                modifier = Modifier.fillMaxWidth().clickable { reload++ },
            )
            r == null -> Text("讀取中…", color = Palette.TextFaint, fontSize = Type.Meta)
            days.all { it.turns == 0 } && (l?.month ?: r.month).turns == 0 ->
                Text("這個月還沒用過。", color = Palette.TextFaint, fontSize = Type.Meta)
            else -> {
                DayBars(days)
                UsageRow("今天", l?.today ?: r.today)
                UsageRow("本月", l?.month ?: r.month)
                l?.let { KindRows(it) }
                (l?.models ?: r.models).take(3).forEach { m ->
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(shortModel(m.model), color = Palette.TextDim,
                            fontSize = Type.Tiny, maxLines = 1)
                        Text("${m.turns} 回合 · 產出 ${fmtTokens(m.output)}",
                            color = Palette.TextFaint, fontSize = Type.Tiny)
                    }
                }
                // 數字的口徑要講清楚，不然他會拿這裡的回合數去對聊天視窗裡的則數。
                Text(
                    if (l != null) {
                        "這台電腦上所有 Claude Code 的總和，含 cc-bot、終端機與子代理。" +
                            // take(5) 只留 HH:mm：伺服器的 scanned_at 現在含秒
                            // （新鮮度判斷要秒精度才不會亂跳），但畫面上不需要那一位
                            "最後掃描 ${l.scannedAt.substringAfter('T').take(5)}。"
                    } else {
                        "只含走助理的回合——本機掃描這次沒成功。"
                    },
                    color = Palette.TextFaint, fontSize = Type.Tiny,
                    lineHeight = Type.MetaLine,
                )
            }
        }
    }
}

/**
 * 主線與子代理分開列，因為兩者的成本結構完全不同：子代理每開一個就是一份
 * 全新 context，資料整包重灌、吃不到快取，回合數只佔一成卻可能拿走一半以上的
 * 新輸入。子代理反超主線時標成警告色——那是「該少開幾個」的訊號。
 */
@Composable
private fun KindRows(l: LocalUsage) {
    val mainIn = l.main?.input ?: 0L
    listOfNotNull(
        l.main?.let { Triple("主線", it, false) },
        l.subagent?.let { Triple("子代理", it, it.input > mainIn) },
    ).forEach { (label, k, hot) ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Palette.TextDim, fontSize = Type.Tiny)
            Text(
                "${k.turns} 回合 · 新輸入 ${fmtTokens(k.input)}",
                color = if (hot) Palette.Warn else Palette.TextFaint,
                fontSize = Type.Tiny,
            )
        }
    }
}

/**
 * 一條方案額度：標題、百分比、進度條、重置倒數。
 *
 * 顏色是有意義的訊號不是裝飾：九成以上轉紅，因為那代表「今天可能做不完手上的事」，
 * 要讓他掃一眼就看到。七成以上轉黃提前預警。
 */
@Composable
private fun LimitBar(l: PlanLimit) {
    val color = when {
        l.pct >= 90f -> Palette.Danger
        l.pct >= 70f -> Palette.Warn
        else -> Palette.Accent
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(l.label, color = Palette.TextDim, fontSize = Type.Meta)
            Text("${l.pct.toInt()}%", color = color, fontSize = Type.Meta)
        }
        val track = Palette.SurfaceHi   // draw lambda 裡讀不到 Palette，先取出來
        Canvas(Modifier.fillMaxWidth().height(5.dp)) {
            val radius = CornerRadius(2.5.dp.toPx())
            drawRoundRect(color = track, size = size, cornerRadius = radius)
            val w = size.width * (l.pct / 100f)
            if (w > 0f) {
                drawRoundRect(color = color, size = Size(w, size.height),
                    cornerRadius = radius)
            }
        }
        resetText(l.resetsAt)?.let {
            Text(it, color = Palette.TextFaint, fontSize = Type.Tiny)
        }
    }
}

/**
 * 「3 小時 12 分後重置」。
 *
 * 刻意給倒數而不是時間點：他看到「06:20 重置」還要自己算現在幾點、差多久，
 * 而他想知道的從來就只有「還要等多久」。
 * 已經過了重置時間就不顯示——那是快取還沒更新，寫「0 分後」只會讓人困惑。
 */
private fun resetText(at: java.time.Instant?): String? {
    if (at == null) return null
    val secs = java.time.Duration.between(java.time.Instant.now(), at).seconds
    if (secs <= 0) return null
    val d = secs / 86400
    val h = secs % 86400 / 3600
    val m = secs % 3600 / 60
    return when {
        d > 0 -> "$d 天 $h 小時後重置"
        h > 0 -> "$h 小時 $m 分後重置"
        else -> "$m 分後重置"
    }
}

/**
 * 最近 14 天的長條圖。伺服器已經補滿天數，這裡不用管缺口。
 *
 * 原本只有 52dp 高，柱子矮到只看得出「那天有沒有用」，看不出差幾倍
 * （2026-08-14 放大到 112dp）。同時補了兩樣讓圖自己會說話的東西：一條平均虛線，
 * 今天那根有沒有過線一眼就知道；還有首尾日期，不然不知道這張圖涵蓋多長。
 */
@Composable
private fun DayBars(days: List<DayUsage>) {
    if (days.isEmpty()) return
    val values = days.map { (it.input + it.output).toFloat() }
    val peak = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
    val avg = values.average().toFloat()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("每天送出＋產出", color = Palette.TextDim, fontSize = Type.Tiny)
            Text("最高 ${fmtTokens(peak.toLong())}", color = Palette.TextFaint,
                fontSize = Type.Tiny)
        }
        val accent = Palette.Accent     // 同上，draw lambda 外先取
        val faint = Palette.TextFaint
        Canvas(Modifier.fillMaxWidth().height(112.dp)) {
            val n = values.size
            val gap = 4.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            values.forEachIndexed { i, v ->
                // 有用過的日子至少給 2dp，不然「用了一點點」跟「完全沒用」長得一樣
                val h = if (v <= 0f) 1.5.dp.toPx()
                else (v / peak * size.height).coerceAtLeast(2.dp.toPx())
                drawRoundRect(
                    color = if (i == n - 1) accent
                    else accent.copy(alpha = 0.35f),
                    topLeft = Offset(i * (w + gap), size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
            // 平均線畫在柱子之後：被柱子蓋掉就沒有比較基準了
            if (avg > 0f) {
                val y = size.height - avg / peak * size.height
                drawLine(
                    color = faint,
                    start = Offset(0f, y), end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f)),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(days.first().date.drop(5).replace('-', '/'),
                color = Palette.TextFaint, fontSize = Type.Tiny)
            Text("虛線＝平均 ${fmtTokens(avg.toLong())}",
                color = Palette.TextFaint, fontSize = Type.Tiny)
            Text("今天", color = Palette.Accent, fontSize = Type.Tiny)
        }
    }
}

@Composable
private fun UsageRow(label: String, b: UsageBucket) = Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
) {
    Text(label, color = Palette.TextDim, fontSize = Type.Meta)
    Text(
        "${b.turns} 回合 · 送出 ${fmtTokens(b.totalIn)} · 產出 ${fmtTokens(b.output)}",
        color = Palette.Text, fontSize = Type.Meta,
    )
}

private fun fmtTokens(n: Long): String = when {
    // 全機的快取讀取一個月就破十億，沒有這一階會印成「3800.0M」，
    // 位數多到一眼讀不出來是幾百萬還是幾十億
    n >= 1_000_000_000 -> "%.1fB".format(n / 1_000_000_000.0)
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.0fK".format(n / 1000.0)
    else -> n.toString()
}

/** 型號名稱在手機上放不下，砍掉共同前綴與日期尾巴。 */
private fun shortModel(m: String): String = m
    .removePrefix("claude-")
    .replace(Regex("-\\d{8}$"), "")
