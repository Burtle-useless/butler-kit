package dev.butlerkit.app.ui

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.data.AgendaRepo
import dev.butlerkit.app.net.Alarm
import dev.butlerkit.app.net.ButlerClient
import dev.butlerkit.app.net.Course
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 日常頁：行事曆、課表、鬧鐘、記帳。
 *
 * 這幾樣的資料都在電腦上，助理可以直接改（它有對應的工具）；這一頁是「你自己動手」
 * 的那一半。兩邊寫的是同一份資料，所以助理設的鬧鐘會出現在這裡，
 * 你在這裡記的帳它問起來也答得出來。
 *
 * [Sub.tint] 是這個子分頁的重點色，見 [Accents]。四頁的結構其實很像（一排選擇 ＋
 * 一份清單 ＋ 一個新增表單），沒有顏色的話切過去只有那排 chip 會動，容易以為沒切成功。
 */
private enum class Sub(val label: String) {
    Cal("行事曆"),
    Course("課表"),
    Alarms("鬧鐘"),
    Money("記帳"),
}

/** 重點色要讀當前主題，enum 的建構子拿不到，所以做成 composable 的擴充屬性。 */
private val Sub.tint: Color
    @Composable get() = when (this) {
        Sub.Cal -> Accents.Cal
        Sub.Course -> Accents.Course
        Sub.Alarms -> Accents.Alarm
        Sub.Money -> Accents.Money
    }

/**
 * [initialSub] 是從桌面 widget 點進來時要落在哪個子分頁（值見 widget 那邊的
 * `SUB_COURSE`／`SUB_CAL`）。用字串不用 [Sub]：那是這個畫面的內部狀態，
 * 為了讓 widget 指定一個分頁而把它公開，等於把這頁的實作細節變成對外介面。
 *
 * 切過去之後一定要呼叫 [onSubConsumed] 讓呼叫端把它清成 null，否則這個值會留著——
 * 之後每次離開日常頁再回來都被強制拉回課表，使用者自己選的分頁按不住。
 */
@Composable
fun AgendaScreen(
    client: ButlerClient,
    initialSub: String? = null,
    onSubConsumed: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by AgendaRepo.data.collectAsState()
    val error by AgendaRepo.error.collectAsState()
    // rememberSaveable：切到別的分頁再回來要停在原本那一格。切分頁會把這整頁移出
    // 組合樹（見 MainActivity 的 SaveableStateProvider），普通 remember 會歸零回
    // 行事曆。enum 本身是 Serializable，autoSaver 存得動，不必自己寫 Saver。
    var sub by rememberSaveable { mutableStateOf(Sub.Cal) }

    // 認得的才切。不認得就留在預設分頁——widget 舊版送來的字串不該讓畫面變空白
    LaunchedEffect(initialSub) {
        if (initialSub == null) return@LaunchedEffect
        when (initialSub) {
            "course" -> sub = Sub.Course
            "cal" -> sub = Sub.Cal
        }
        onSubConsumed()
    }

    // 右上角這行日期四個分頁都用得上（記帳看「這個月」、鬧鐘看「星期幾」），
    // 放在標題列各分頁就不必各自再寫一次。
    // 走 rememberToday 而不是 `remember { Calendar.getInstance() }`——後者是
    // 「進這個分頁的那一刻」，跨午夜之後標題會一直停在昨天（見 rememberToday）
    val today = rememberToday()

    Column(Modifier.fillMaxSize()) {
        PageTitle("日常") {
            Text(
                "${today.substring(5, 7).trimStart('0')} 月 " +
                    "${today.substring(8, 10).trimStart('0')} 日" +
                    " 週${WEEK[todayDay()]}",
                color = Palette.TextFaint, fontSize = Type.Meta,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        SubTabs(sub) { sub = it }
        error?.let {
            Text(
                // 點下去要真的做事。原本只是把這行紅字關掉，而載入失敗時資料是空的，
                // 關掉之後畫面就是一片空白加一句「還沒有行程」——看起來像沒東西，
                // 其實是沒拿到，而且再也沒有任何地方能叫它重試。
                "$it（點一下重試）", color = Palette.Danger, fontSize = Type.Meta,
                modifier = Modifier.fillMaxWidth()
                    .background(Palette.DangerSoft)
                    .clickable(role = Role.Button) {
                        AgendaRepo.clearError()
                        scope.launch { AgendaRepo.refresh(ctx, client) }
                    }
                    .padding(horizontal = Space.Screen, vertical = 8.dp),
            )
        }
        when (sub) {
            Sub.Cal -> CalendarPane(ctx, scope, client, data.events, today)
            Sub.Course -> CoursePane(ctx, scope, client, data.courses, data.periods, today)
            Sub.Alarms -> AlarmPane(ctx, scope, client, data.alarms)
            Sub.Money -> MoneyPane(ctx, scope, client, data.ledger, data.categories)
        }
    }
}

/**
 * 子分頁：膠囊。選中的用該頁的 [Sub.tint] 實色，沒選中的只有一圈邊框——
 * 舊版沒選中的也有實心底，四顆一起亮著看不出哪顆是現在這頁。
 */
@Composable
private fun SubTabs(current: Sub, onPick: (Sub) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.Screen, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sub.entries.forEach { s ->
            val on = s == current
            Text(
                s.label,
                color = if (on) Palette.Bg else Palette.TextDim,
                fontSize = Type.Body,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f)
                    .background(if (on) s.tint else Color.Transparent, Radii.Chip)
                    .border(
                        if (on) 0.dp else 1.dp,
                        if (on) Color.Transparent else Palette.Line,
                        Radii.Chip,
                    )
                    .clickable(role = Role.RadioButton) { onPick(s) }
                    .padding(vertical = 9.dp),
            )
        }
    }
}


/**
 * 星期標籤。索引就是伺服器的 day 編號（0=週一 6=週日），課表與鬧鐘共用這一份。
 *
 * 行事曆那邊的 [WEEK_HEAD] 是週日起始，兩者刻意不一致：月曆的慣例是週日在最左邊，
 * 而「星期幾」當成編號用時 0=週一 比較順（平日就是 0 到 4）。改任何一邊之前先想清楚
 * 動的是哪一種。
 */
internal val WEEK = listOf("一", "二", "三", "四", "五", "六", "日")

// 共用小元件（Card／Field／StripeRow／AddPanel …）搬到 Components.kt，
// 工具頁與聊天頁要用同一份，留在這裡的話兩邊只能各寫一份長得像的

// ── 格式與選擇器 ─────────────────────────────────────────────────────────────
/** "2026-08-20T15:00" → "08/20 15:00"；今年的年份不用一直看 */
internal fun fmtStamp(s: String): String =
    if (s.length < 16) s else "${s.substring(5, 7)}/${s.substring(8, 10)} ${s.substring(11)}"

internal fun fmtMoney(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.2f", v)

internal fun two(n: Int) = if (n < 10) "0$n" else "$n"

internal fun thisMonth(): String {
    val c = Calendar.getInstance()
    return "${c.get(Calendar.YEAR)}-${two(c.get(Calendar.MONTH) + 1)}"
}

private fun todayStr(): String {
    val c = Calendar.getInstance()
    return "${c.get(Calendar.YEAR)}-${two(c.get(Calendar.MONTH) + 1)}-" +
        two(c.get(Calendar.DAY_OF_MONTH))
}

/**
 * 「今天」，跨過午夜會自己變。
 *
 * 原本各處寫的是 `remember { todayStr() }`——沒有 key，等於「進這個分頁的那一天」，
 * 之後永遠不動。停在日常分頁把 App 放著過夜就會出事，而且**不只是顯示錯**：
 * 行事曆的「加到今天」用的是同一個值，按下去會把行程寫進昨天，產生錯誤資料。
 *
 * 每分鐘醒一次而不是算到午夜精準排一發：省下時區、日光節約、使用者手動改系統時間
 * 這三種校正，成本是一天 1440 次字串比較。
 */
@Composable
private fun rememberToday(): String {
    var today by remember { mutableStateOf(todayStr()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            val now = todayStr()
            if (now != today) today = now
        }
    }
    return today
}

/** 新行程預設排在下一個整點：多數行程是「等一下要做的事」。 */
internal fun nextHour(): String {
    val c = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }
    return "${two(c.get(Calendar.HOUR_OF_DAY))}:00"
}

/** 月份加減。用 Calendar 而不是自己算，跨年那一格才不會錯。 */
internal fun shiftMonth(m: String, delta: Int): String {
    val c = monthStart(m).apply { add(Calendar.MONTH, delta) }
    return "${c.get(Calendar.YEAR)}-${two(c.get(Calendar.MONTH) + 1)}"
}

private fun monthStart(m: String): Calendar = Calendar.getInstance().apply {
    clear()
    set(m.substring(0, 4).toInt(), m.substring(5, 7).toInt() - 1, 1)
}

/**
 * 攤平成整週的格子（"YYYY-MM-DD"），從當月 1 號往前補到週日、往後補到週六。
 *
 * 前後月那幾格照樣給日期而不是留白：月底月初的行程常常黏在一起，
 * 留白會讓那一週看起來斷掉。週數依當月實際需要算，不固定六列——
 * 固定的話短月份下面會多掛一整排空格。
 */
internal fun monthCells(m: String): List<String> {
    val c = monthStart(m)
    val dim = c.getActualMaximum(Calendar.DAY_OF_MONTH)
    val lead = c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
    c.add(Calendar.DAY_OF_MONTH, -lead)
    val total = (lead + dim + 6) / 7 * 7
    return buildList {
        repeat(total) {
            add(
                "${c.get(Calendar.YEAR)}-${two(c.get(Calendar.MONTH) + 1)}-" +
                    two(c.get(Calendar.DAY_OF_MONTH)),
            )
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
    }
}

/** "2026-08-11" → "8月11日"；就是今天的話直接講「今天」。 */
internal fun fmtDayTitle(date: String, today: String): String =
    if (date == today) "今天"
    else "${date.substring(5, 7).trimStart('0')}月${date.substring(8).trimStart('0')}日"

/** 用系統原生對話框而不是 Compose 版：使用者已經認得它，而且少寫一百行。 */
internal fun pickTime(ctx: Context, current: String, onPick: (String) -> Unit) {
    // 解不出來就開在 08:00。current 可能是空字串（新增的節次還沒填時間），
    // 硬切會閃退——而使用者按下這顆按鈕正是為了把它填好，那時閃退最說不過去。
    val t = minsOrNull(current) ?: (8 * 60)
    TimePickerDialog(ctx, { _, hh, mm ->
        onPick("${two(hh)}:${two(mm)}")
    }, t / 60, t % 60, true).show()
}
