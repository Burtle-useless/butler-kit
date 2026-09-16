package dev.butlerkit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.net.ModelInfo

/**
 * 模型與思考強度的選擇面板。帳號預設與單一對話覆寫**共用同一個**。
 *
 * 先前兩處長相不同：設定頁用 DropdownMenu 列完整 id（`claude-opus-4-8`），對話面板用
 * 晶片列縮寫（`opus-5`），而且都看不出「這個模型是幹嘛的」。清單現在跟著官方走
 * （伺服器從 CLI 拿：Default (recommended)／Opus (1M context)／Fable／Sonnet／Haiku，
 * 各帶一句說明與支援的思考等級），面板照官方 app 的 model switcher 做：底部面板、
 * 顯示名粗體、說明小字、目前選的打勾，思考強度依所選模型給。
 *
 * [followLabel] 非 null 時最上面多一列「跟隨預設」（送空字串＝清除覆寫），括號裡
 * 寫目前實際生效的是哪一個——只知道「沒覆寫」沒有用，人要知道現在跑的是什麼。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    infos: List<ModelInfo>,
    efforts: List<String>,
    selectedModel: String,
    selectedEffort: String,
    followLabel: String?,
    effectiveModel: String,
    effectiveEffort: String,
    onPickModel: (String) -> Unit,
    onPickEffort: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Palette.Surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "模型", color = Palette.Text, fontSize = Type.Title,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            if (followLabel != null) {
                ModelRow(
                    name = followLabel,
                    description = effectiveModel.takeIf { it.isNotBlank() }
                        ?.let { "現在生效的是 ${modelDisplay(it, infos)}" } ?: "",
                    selected = selectedModel.isBlank(),
                ) { onPickModel("") }
            }
            infos.forEach { m ->
                ModelRow(
                    name = m.name,
                    description = m.description,
                    selected = selectedModel == m.value || selectedModel == m.resolved,
                ) { onPickModel(m.value) }
            }

            // 思考強度依所選模型給：haiku 這種沒得調的就直說，不擺一排按了沒用的按鈕
            val current = infos.firstOrNull {
                it.value == selectedModel || it.resolved == selectedModel
            } ?: infos.firstOrNull {
                it.value == effectiveModel || it.resolved == effectiveModel
            }
            val levels = when {
                current == null -> efforts
                current.supportsEffort -> current.efforts.ifEmpty { efforts }
                else -> emptyList()
            }
            Text(
                "思考強度", color = Palette.Text, fontSize = Type.Title,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
            )
            if (levels.isEmpty()) {
                Text(
                    "這個模型沒有思考強度可調。",
                    color = Palette.TextFaint, fontSize = Type.Meta,
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (followLabel != null) {
                        EffortChip(
                            label = "跟隨預設" + effectiveEffort.takeIf { it.isNotBlank() }
                                ?.let { "（$it）" }.orEmpty(),
                            on = selectedEffort.isBlank(),
                        ) { onPickEffort("") }
                    }
                    levels.forEach { lv ->
                        EffortChip(label = lv, on = selectedEffort == lv) { onPickEffort(lv) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    name: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) = Row(
    Modifier.fillMaxWidth()
        .clip(Radii.Field)
        .background(if (selected) Palette.SurfaceHi else Palette.Surface)
        .clickable(role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f)) {
        Text(
            name, color = Palette.Text, fontSize = Type.Meta,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        if (description.isNotBlank()) {
            Text(
                description, color = Palette.TextDim, fontSize = Type.Tiny,
                lineHeight = Type.TinyLine, modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
    if (selected) {
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.Check, null, tint = Palette.Accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun EffortChip(label: String, on: Boolean, onClick: () -> Unit) = Text(
    label,
    color = if (on) Palette.Bg else Palette.Text,
    fontSize = Type.Meta,
    maxLines = 1,
    modifier = Modifier
        .clip(Radii.Chip)
        .background(if (on) Palette.Accent else Palette.SurfaceHi)
        .clickable(role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp),
)

/**
 * 設定頁與對話面板上那一列「模型／思考強度」的摘要，點了開面板。
 */
@Composable
fun ModelSummaryRow(modelText: String, effortText: String, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth()
        .clip(Radii.Field)
        .clickable(role = Role.Button, onClick = onClick)
        .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f)) {
        Text("模型", color = Palette.TextDim, fontSize = Type.Tiny)
        Text(modelText, color = Palette.Text, fontSize = Type.Meta, fontWeight = FontWeight.Medium)
        Text(
            "思考強度 $effortText", color = Palette.TextDim, fontSize = Type.Tiny,
            fontFamily = FontFamily.Default, modifier = Modifier.padding(top = 2.dp),
        )
    }
    Icon(Icons.Filled.ChevronRight, null, tint = Palette.TextFaint, modifier = Modifier.size(20.dp))
}

/** 值 → 顯示名。清單裡找得到就用官方顯示名，找不到（舊資料的完整 id）就去掉前綴。 */
fun modelDisplay(value: String, infos: List<ModelInfo>): String {
    if (value.isBlank()) return ""
    val hit = infos.firstOrNull { it.value == value || it.resolved == value }
    return hit?.name ?: value.removePrefix("claude-")
}
