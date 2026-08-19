package dev.butlerkit.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.butlerkit.app.data.Prefs
import dev.butlerkit.app.net.DayUsage
import dev.butlerkit.app.net.PlanLimit
import dev.butlerkit.app.net.UsageReport
import dev.butlerkit.app.net.parseUsage
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 訂閱額度。
 *
 * 跟 [dev.butlerkit.app.ui.UsageCard] 守同一條規矩：**完全不談錢**。訂閱制是吃到飽，
 * 那個 cost 只是照 API 定價換算的估算值，放在桌面上天天看只會讓人以為自己在燒錢。
 * 桌面上該有的就一件事——還剩多少可以用、多久重置。
 *
 * 資料來自 [Prefs.usageCache]，由 [UsageWorker] 在背景拉。widget 自己不連網。
 */
class UsageWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = Prefs(context)
        provideContent {
            // 理由同 CourseWidget：在這一層讀就等於把值寫死進 composition
            val raw by remember { prefs.watch(Prefs.KEY_USAGE) }
                .collectAsState(initial = prefs.usageCache)
            val report = remember(raw) {
                runCatching { parseUsage(JSONObject(raw)) }.getOrNull()
            }
            // usageAt 跟著 usageCache 一起寫，重組時再讀就是新的
            Content(context, report, prefs.usageAt)
        }
    }
}

class UsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UsageWidget()
}

@Composable
private fun Content(ctx: Context, r: UsageReport?, at: Long) {
    WidgetFrame(ctx, "用量", atText(at), TAB_TOOLS) {
        if (r == null) {
            EmptyLine("還沒拉到資料")
            return@WidgetFrame
        }
        if (r.limits.isEmpty()) {
            // 伺服器的 ACCOUNT_PLAN 沒設時就沒有額度資料。這不是錯誤，
            // 但也沒什麼好畫的，講清楚比留白好
            EmptyLine("沒有方案額度資料")
        } else {
            // 額度條包一層 Column，外層只算它一格。攤在外層的話子元素數是 2L+6，
            // Max 方案的三條額度（5 小時／週／週-Opus）就是 12——超過
            // GLANCE_MAX_CHILDREN，長條圖跟「今天 N 回合」整組被靜默丟掉，
            // 桌面上只剩三條百分比，看起來像圖表功能壞了。
            //
            // 條與條之間用固定間距，可壓縮的空白留給圖表前面那個——額度條是一組
            // 相關的東西，被 weight 撐開會看起來像兩塊不相干的區域。
            // 間距做在 LimitBar 自己的 padding 上，這層才不會又變成一條吃兩格。
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                r.limits.forEachIndexed { i, l -> LimitBar(l, topGap = i > 0) }
            }
        }

        // 逐日長條。跟 UsageCard 同一個口徑：本機掃得到就用全機的帳
        val days = r.local?.days?.takeIf { it.isNotEmpty() } ?: r.days
        Spacer(GlanceModifier.defaultWeight())
        // 同樣包一層：DayBars 自己會產出三個元素（標題列、間隔、柱子那排）
        Column(modifier = GlanceModifier.fillMaxWidth()) { DayBars(days) }

        // 額度是主角，回合數只是附註，所以放最底下且用最小的字。
        // 跟 UsageCard 一樣以全機的帳為準——額度是整個帳號共用的
        val b = r.local?.today?.takeIf { it.turns > 0 } ?: r.today
        Text(
            text = "今天 ${b.turns} 回合 · 產出 ${fmtTokens(b.output)}",
            style = TextStyle(fontSize = 11.sp, color = W.Faint),
            modifier = GlanceModifier.padding(top = 6.dp),
        )
    }
}

/** 長條圖的高度。widget 上再高就換不到資訊，只會把額度條擠掉。 */
private const val BAR_H = 56f

/**
 * 最近幾天的送出＋產出。搬 [dev.butlerkit.app.ui.UsageCard] 的 DayBars。
 *
 * Glance 沒有 Canvas，柱子是一排有背景色的 Spacer 疊出來的——寬度交給
 * `defaultWeight` 均分，高度自己按比例算。App 那條平均虛線畫不出來
 * （沒有辦法把東西疊在指定的高度上），改成把平均值寫進標題列，資訊不掉。
 */
@Composable
private fun DayBars(days: List<DayUsage>) {
    val values = days.map { (it.input + it.output).toFloat() }
    // 全零的話 peak 會是 0，柱子高度算出 NaN。這時整塊不畫
    val peak = values.maxOrNull()?.takeIf { it > 0f } ?: return
    val avg = values.average().toFloat()
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        Text(
            text = "近 ${values.size} 天",
            style = TextStyle(fontSize = 11.sp, color = W.Dim),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            text = "平均 ${fmtTokens(avg.toLong())}",
            style = TextStyle(fontSize = 11.sp, color = W.Faint),
        )
    }
    Spacer(GlanceModifier.height(4.dp))
    // Glance 的容器只吃得下 10 個子元素，超過的會被**靜默丟掉**——14 根柱子直接
    // 畫成 5 根，還不報錯。拆成兩排七根塞進外層的兩格，每一層都在上限內
    val per = if (values.size > 7) (values.size + 1) / 2 else values.size
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(BAR_H.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.chunked(per).forEachIndexed { g, chunk ->
            Row(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                verticalAlignment = Alignment.Bottom,
            ) {
                chunk.forEachIndexed { j, v ->
                    // 有用過的日子至少給 2dp，不然「用了一點點」跟「完全沒用」長得一樣
                    val h = if (v <= 0f) 1.5f else (v / peak * BAR_H).coerceAtLeast(2f)
                    // 寬度的 weight 與高度必須分兩層：同一個 Spacer 上既給 defaultWeight
                    // 又給 height，Glance 只會吃寬度，柱子會塌成一條線
                    Box(
                        modifier = GlanceModifier.defaultWeight().fillMaxHeight()
                            .padding(horizontal = 1.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Spacer(
                            GlanceModifier.fillMaxWidth().height(h.dp)
                                .background(
                                    if (g * per + j == values.lastIndex) W.Accent else W.Bar,
                                )
                                .cornerRadius(2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LimitBar(l: PlanLimit, topGap: Boolean) {
    // 顏色的門檻跟 UsageCard 一致（90 紅 / 70 黃），兩處看到的警示等級必須一樣，
    // 否則同一個數字在桌面是黃的、點進去變紅的
    val color = when {
        l.pct >= 90f -> W.Danger
        l.pct >= 70f -> W.Warn
        else -> W.Accent
    }
    Column(
        modifier = GlanceModifier.fillMaxWidth()
            .padding(top = if (topGap) 10.dp else 0.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = l.label,
                maxLines = 1,
                style = TextStyle(fontSize = 14.sp, color = W.Text),
                modifier = GlanceModifier.defaultWeight(),
            )
            // 百分比是這張 widget 唯一要在一公尺外看清楚的東西，字級跟標題拉開
            Text(
                text = "${l.pct.toInt()}%",
                style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color),
            )
        }
        Spacer(GlanceModifier.height(5.dp))
        LinearProgressIndicator(
            progress = (l.pct / 100f).coerceIn(0f, 1f),
            // 4dp 的條在桌布上根本看不出長度差；加粗到看得出「用掉多少」才有意義
            modifier = GlanceModifier.fillMaxWidth().height(12.dp).cornerRadius(6.dp),
            color = color,
            backgroundColor = W.Line,
        )
        resetText(l.resetsAt)?.let {
            Text(
                text = it,
                style = TextStyle(fontSize = 11.sp, color = W.Faint),
                modifier = GlanceModifier.padding(top = 4.dp),
            )
        }
    }
}

/** 「3 小時 12 分後重置」。跟 UsageCard 同一套說法，只是把天數也壓成一行。 */
private fun resetText(at: Instant?): String? {
    if (at == null) return null
    val secs = Duration.between(Instant.now(), at).seconds
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
 * 資料是什麼時候拉的。
 *
 * 一定要顯示：widget 上的數字看起來永遠像即時的，但這份可能是半小時前的快取，
 * 沒有這行的話「還剩 20%」會被當成當下的狀況去做決定。
 *
 * **給絕對時間而不是「N 分鐘前」**：這行字是 provideGlance 當下算好就烙在
 * RemoteViews 上的，widget 自己不會重算。相對說法會凍在寫入那一刻——實測過
 * 二十分鐘前的資料一直掛著「剛更新」，比不寫還糟，等於在騙人。
 * 絕對時間放多久都還是對的。
 */
private fun atText(at: Long): String {
    if (at <= 0L) return ""
    val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDateTime()
    val hm = "%02d:%02d".format(t.hour, t.minute)
    // 跨日的快取要標日期，否則「08:30 更新」看起來像今天早上、其實是上週
    return if (t.toLocalDate() == LocalDate.now()) "$hm 更新"
    else "${t.monthValue}/${t.dayOfMonth} $hm"
}

private fun fmtTokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.0fk".format(n / 1_000.0)
    else -> n.toString()
}
