package dev.butlerkit.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.butlerkit.app.data.AgendaRepo
import dev.butlerkit.app.net.Alarm
import dev.butlerkit.app.net.ButlerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ── 鬧鐘 ─────────────────────────────────────────────────────────────────────
@Composable
internal fun AlarmPane(
    ctx: Context, scope: CoroutineScope, client: ButlerClient, alarms: List<Alarm>,
) {
    var time by remember { mutableStateOf("07:00") }
    var label by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(setOf<Int>()) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.Screen),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            AddPanel(
                label = "設一個鬧鐘",
                tint = Accents.Alarm,
                canSubmit = true,
                submitText = "設定鬧鐘",
                onSubmit = {
                    scope.launch {
                        val ok = AgendaRepo.add(ctx, client, "alarms", JSONObject().apply {
                            put("time", time)
                            put("label", label.trim())
                            put("days", JSONArray(days.sorted()))
                        })
                        if (ok) { label = ""; days = emptySet() }
                    }
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        time, color = Accents.Alarm, fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(role = Role.Button) {
                            pickTime(ctx, time) { time = it }
                        },
                    )
                    Text(
                        "  點一下改時間", color = Palette.TextFaint, fontSize = Type.Meta,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WEEK.forEachIndexed { i, w ->
                        val on = i in days
                        Text(
                            w,
                            color = if (on) Palette.Bg else Palette.TextDim,
                            fontSize = Type.Meta,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.size(34.dp)
                                .background(
                                    if (on) Accents.Alarm else Palette.SurfaceHi, Radii.Chip,
                                )
                                .clickable(role = Role.Checkbox) {
                                    days = if (on) days - i else days + i
                                }
                                .padding(top = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                Text(
                    if (days.isEmpty()) "一天都沒選＝只響一次" else "每週響",
                    color = Palette.TextFaint, fontSize = Type.Meta,
                )
                Field(label, "叫你做什麼（可留白）") { label = it }
            }
        }
        item {
            SectionHead(
                "鬧鐘",
                hint = alarms.count { it.enabled }.takeIf { it > 0 }?.let { "$it 個開著" },
                tint = Accents.Alarm,
            )
        }
        if (alarms.isEmpty()) {
            item { Empty("沒有鬧鐘。") }
        }
        items(alarms, key = { it.id }) { a ->
            StripeRow(
                tint = Accents.Alarm,
                dimmed = !a.enabled,
                // 26sp 的「07:20」實寬約 80dp，加上前導欄的左右內縮要 102dp。
                // 給不夠寬不會報錯，而是把時間折成「07:2 / 0」兩行
                leadWidth = 102.dp,
                lead = {
                    Text(
                        a.time,
                        color = if (a.enabled) Palette.Text else Palette.TextFaint,
                        fontSize = 26.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                },
                actions = {
                    Switch(
                        checked = a.enabled,
                        onCheckedChange = { on ->
                            scope.launch {
                                AgendaRepo.patch(ctx, client, "alarms", a.id,
                                    JSONObject().put("enabled", on))
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.Bg,
                            checkedTrackColor = Accents.Alarm,
                        ),
                    )
                    IconBtn(Icons.Filled.Close, "刪掉這個鬧鐘") {
                        scope.launch { AgendaRepo.remove(ctx, client, "alarms", a.id) }
                    }
                },
            ) {
                Text(
                    repeatText(a),
                    color = if (a.enabled) Accents.Alarm else Palette.TextFaint,
                    fontSize = Type.Meta, fontWeight = FontWeight.Medium,
                )
                if (a.label.isNotBlank()) {
                    Text(
                        a.label, color = Palette.TextDim, fontSize = Type.Body,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

private fun repeatText(a: Alarm): String = when {
    a.days.size == 7 -> "每天"
    // getOrNull 而不是 WEEK[it]：這裡是**唯一**用外部資料當索引的地方，
    // 伺服器的 _need_days 雖然擋掉 0..6 以外的值，手改過 agenda.json 就繞得過。
    // 一個 7（ISO 慣例的週日）會讓整個鬧鐘分頁畫不出來——為了一筆髒資料
    // 讓所有鬧鐘都消失，代價完全不成比例。
    a.days.isNotEmpty() -> a.days.sorted().joinToString("") { WEEK.getOrNull(it) ?: "?" }
    a.date != null -> "${a.date} 響一次"
    else -> "響一次"
}
