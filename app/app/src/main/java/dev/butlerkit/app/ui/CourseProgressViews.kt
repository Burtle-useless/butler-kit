package dev.butlerkit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.net.CourseInfo
import dev.butlerkit.app.net.LEVELS
import dev.butlerkit.app.net.LogRow
import dev.butlerkit.app.net.ProgressRow

/**
 * 課程資料的幾個共用小件：程度標籤、三級計數、理解進度表、提問紀錄列、課程清單。
 *
 * 資料是另一個 session 維護的（見 `net/Courses.kt`），這裡只負責畫。兩件事是
 * 契約明訂、不能走鐘的：程度只有「懂／半懂／不會」三種；**「依據」欄一定要顯示**
 * ——絕大多數會是「提問推斷」，不標的話他會把它當成績。
 */

/** 程度標籤的深淺：單色印刷風，用底色濃淡分，不用紅黃綠。 */
@Composable
private fun levelAlpha(level: String): Float = when (level) {
    "懂" -> 0.22f
    "半懂" -> 0.12f
    else -> 0.05f
}

@Composable
internal fun LevelChip(level: String, count: Int? = null) {
    Box(
        Modifier.clip(Radii.Chip)
            .background(Palette.Text.copy(alpha = levelAlpha(level)))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            if (count == null) level else "$level $count",
            color = Palette.Text, fontSize = Type.Tiny,
            fontWeight = if (level == "懂") FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** 三個數字一律顯示，全是 0 也顯示——「0／0／0」本身就是「還沒開始記錄」的資訊。 */
@Composable
internal fun CountsRow(c: CourseInfo) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        LEVELS.forEach { LevelChip(it, c.count(it)) }
    }
}

/** 清單裡的一列：課名、老師・教室、右側三個數量。 */
@Composable
internal fun CourseRow(c: CourseInfo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(Radii.Tiny)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                c.title, color = Palette.Text, fontSize = Type.Body,
                fontWeight = FontWeight.Medium,
            )
            val who = listOf(c.teacher, c.room).filter { it.isNotBlank() }
            val sub = buildList {
                if (who.isNotEmpty()) add(who.joinToString("・"))
                if (c.logCount > 0) add("${c.logCount} 則提問")
            }
            if (sub.isNotEmpty()) {
                Text(sub.joinToString("　"), color = Palette.TextDim, fontSize = Type.Meta)
            }
        }
        CountsRow(c)
    }
}

/**
 * 課程清單。[courses] 是 null 代表連快取都還沒有；空清單代表電腦上真的沒課程資料夾。
 * 拉取失敗的字句在有舊資料時只當註腳，沒資料時才是主文。
 */
@Composable
internal fun CourseList(
    courses: List<CourseInfo>?,
    error: String?,
    onPick: (CourseInfo) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when {
            courses == null && error != null -> Text(error, color = Palette.TextFaint, fontSize = Type.Meta)
            courses == null -> Text("還沒拿到資料。", color = Palette.TextFaint, fontSize = Type.Meta)
            courses.isEmpty() -> Text(
                "電腦上還沒有課程資料夾。", color = Palette.TextFaint, fontSize = Type.Meta,
            )
            else -> {
                courses.forEach { c -> CourseRow(c) { onPick(c) } }
                if (error != null) {
                    Text(
                        "更新失敗，看的是上次的：$error",
                        color = Palette.TextFaint, fontSize = Type.Tiny,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** 理解進度表全文。 */
@Composable
internal fun ProgressTable(rows: List<ProgressRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rows.isEmpty()) {
            Text(
                "還沒有記錄。上課時在對話裡問問題，課程助教會記下來、下課整理進這裡。",
                color = Palette.TextFaint, fontSize = Type.Meta, lineHeight = Type.MetaLine,
            )
            return@Column
        }
        rows.forEach { p ->
            Column(
                Modifier.fillMaxWidth().clip(Radii.Tiny)
                    .background(Palette.Text.copy(alpha = 0.04f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.topic, color = Palette.Text, fontSize = Type.Body,
                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                    )
                    LevelChip(p.level)
                }
                // 依據一定要露出來：這是「他問過什麼」推出來的，不是考出來的
                Text(
                    "依據：${p.basis.ifBlank { "？" }}" +
                        (if (p.updated.isNotBlank()) "　更新 ${p.updated}" else ""),
                    color = Palette.TextFaint, fontSize = Type.Tiny,
                )
                if (p.stuck.isNotBlank()) {
                    Text("卡在：${p.stuck}", color = Palette.TextDim, fontSize = Type.Meta)
                }
            }
        }
    }
}

/** 提問紀錄，最新的在上面——他要看的是「剛剛問了什麼」，不是開學第一天。 */
@Composable
internal fun LogList(rows: List<LogRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rows.isEmpty()) {
            Text(
                "還沒有提問。在對話裡問的每一題都會記一行在這裡。",
                color = Palette.TextFaint, fontSize = Type.Meta, lineHeight = Type.MetaLine,
            )
            return@Column
        }
        rows.asReversed().forEach { r ->
            Column(
                Modifier.fillMaxWidth().clip(Radii.Tiny)
                    .background(Palette.Text.copy(alpha = 0.04f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.topic.ifBlank { "（沒標主題）" }, color = Palette.Text,
                        fontSize = Type.Body, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(r.time, color = Palette.TextFaint, fontSize = Type.Tiny)
                }
                if (r.asked.isNotBlank()) {
                    Text(r.asked, color = Palette.Text, fontSize = Type.Meta, lineHeight = Type.MetaLine)
                }
                if (r.verdict.isNotBlank()) {
                    Text("判讀：${r.verdict}", color = Palette.TextDim, fontSize = Type.Tiny)
                }
            }
        }
    }
}
