package dev.butlerkit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.butlerkit.app.net.ModelInfo
import kotlinx.coroutines.launch

/** 面板是在改哪一層：單一對話的覆寫，還是帳號預設（沒覆寫的對話都吃它）。 */
enum class PickScope { Conv, Account }

/**
 * 模型與思考強度的選擇面板。對話頂欄的模型鈕（這條對話）與設定頁（帳號預設）共用。
 *
 * 一個模型一列（顯示名、一句說明、選中的打勾），各系列最新的那顆放外面，舊型號收進
 * 「更多模型」；思考強度依所選的模型給。**選了模型就收起來**——選模型是一次決定，
 * 不是在微調；思考強度可以連點，不收。
 *
 * 清單全部來自伺服器（官方 `/v1/models`），這裡不寫死任何型號——出新模型不必改 App。
 * 現在的 Claude Code 跑不動的模型照樣列出來但灰掉，點了指去工具頁更新（[onNeedUpdate]）。
 *
 * [selectedModel]／[selectedEffort] 是這一層自己設的值，空字串＝跟隨上一層；
 * [followModel]／[followEffort] 是跟隨的話會拿到什麼（對話：帳號預設；帳號：官方建議），
 * 跟隨那列要寫出來——只寫「跟隨預設」沒有用，人要知道那是什麼。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    infos: List<ModelInfo>,
    scope: PickScope,
    selectedModel: String,
    selectedEffort: String,
    followModel: String,
    followEffort: String,
    onPickModel: (String) -> Unit,
    onPickEffort: (String) -> Unit,
    onNeedUpdate: () -> Unit,
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val co = rememberCoroutineScope()
    // 選了就先套用（請求馬上出去），面板滑下去之後才拆掉，不要啪一下消失。
    // [after] 等面板收完才做——換頁要等，不然面板還在滑就被整頁換掉
    fun close(now: () -> Unit = {}, after: () -> Unit = {}) {
        now()
        co.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); after() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        // 清單長（展開更多模型）時面板會長到全螢幕高，沒這個標題會壓在狀態列的時鐘上
        modifier = Modifier.statusBarsPadding(),
        containerColor = Palette.Surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "模型", color = Palette.Text, fontSize = Type.Title,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                )
                // 改的是哪一層要看得出來：同一個面板在設定頁改的是所有對話的預設
                Text(
                    if (scope == PickScope.Conv) "這條對話" else "帳號預設",
                    color = Palette.TextDim, fontSize = Type.Tiny,
                )
            }

            val main = infos.filter { it.tier != "more" }
            val more = infos.filter { it.tier == "more" }
            val picked = selectedModel.takeIf { it.isNotBlank() }
                ?.let { v -> infos.firstOrNull { it.matches(v) } }
            // 選著的是舊型號就一開始展開，不然打勾的那列藏在摺疊裡
            var moreOpen by remember { mutableStateOf(picked?.tier == "more") }
            // 目前攤開說明的那顆（跑不動的模型點了是看說明，不是選它）
            var noticeFor by remember { mutableStateOf<String?>(null) }

            val followName = modelDisplay(followModel, infos)
            PickRow(
                title = if (scope == PickScope.Conv) "跟隨預設" else followLabel(followModel),
                subtitle = followName.takeIf { it.isNotBlank() }?.let { "目前是 $it" }.orEmpty(),
                selected = selectedModel.isBlank(),
            ) { close(now = { onPickModel("") }) }
            main.forEach { m ->
                ModelRow(m, selected = picked == m, notice = noticeFor == m.value,
                    onUnavailable = { noticeFor = if (noticeFor == m.value) null else m.value },
                    onNeedUpdate = { close(after = onNeedUpdate) },
                ) { close(now = { onPickModel(m.value) }) }
            }
            if (more.isNotEmpty()) {
                MoreRow(open = moreOpen, count = more.size) { moreOpen = !moreOpen }
                if (moreOpen) {
                    more.forEach { m ->
                        ModelRow(m, selected = picked == m, notice = noticeFor == m.value,
                            onUnavailable = { noticeFor = if (noticeFor == m.value) null else m.value },
                            onNeedUpdate = { close(after = onNeedUpdate) },
                        ) { close(now = { onPickModel(m.value) }) }
                    }
                }
            }

            // ── 思考強度：依「選著的、或跟隨會拿到的」那顆模型給 ──
            val basis = picked ?: infos.firstOrNull { it.matches(followModel) }
            Text(
                "思考強度", color = Palette.Text, fontSize = Type.Title,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
            )
            if (basis != null && !basis.supportsEffort) {
                // 沒得調的就直說，不擺一排按了沒用的按鈕
                Text(
                    "${basis.name} 不能調思考強度。",
                    color = Palette.TextFaint, fontSize = Type.Meta,
                )
            } else {
                val levels = basis?.efforts?.ifEmpty { null } ?: DEFAULT_EFFORTS
                val options = listOf("" to "預設") + levels.map { it to effortLabel(it) }
                // 先前設的等級這顆模型不支援時，沒有任何一顆是選中的——不假裝選中了什麼
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { (value, label) ->
                        EffortChip(label = label, on = selectedEffort == value) { onPickEffort(value) }
                    }
                }
                Text(
                    when {
                        selectedEffort.isNotBlank() -> EFFORT_HINT[selectedEffort].orEmpty()
                        followEffort.isNotBlank() ->
                            "跟隨預設，目前是「${effortLabel(followEffort)}」"
                        else -> "交給 Claude Code 自己決定"
                    },
                    color = Palette.TextDim, fontSize = Type.Tiny, lineHeight = Type.TinyLine,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            footer?.let {
                Spacer(Modifier.height(14.dp))
                it()
            }
        }
    }
}

/**
 * 一個模型。跑得動的就是普通的一列；現在的 Claude Code 跑不動的照樣列出來
 * （知道有這顆），但灰掉，點了是攤開說明與「去更新」，不是選它。
 */
@Composable
private fun ModelRow(
    m: ModelInfo,
    selected: Boolean,
    notice: Boolean,
    onUnavailable: () -> Unit,
    onNeedUpdate: () -> Unit,
    onPick: () -> Unit,
) {
    if (m.available) {
        PickRow(m.name, m.description, selected, onClick = onPick)
        return
    }
    Column {
        PickRow(
            title = m.name,
            subtitle = "要更新 Claude Code 才能用",
            selected = false,
            dim = true,
            onClick = onUnavailable,
        )
        if (notice) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    buildString {
                        append("官方要求 Claude Code ")
                        append(m.requiresCli.ifBlank { "新版" })
                        if (m.requiresCli.isNotBlank()) append(" 以上")
                        append("。更新在工具頁，按一下就好。")
                    },
                    color = Palette.TextDim, fontSize = Type.Tiny, lineHeight = Type.TinyLine,
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    "去更新",
                    modifier = Modifier,
                    fontSize = Type.Meta,
                    pad = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    onClick = onNeedUpdate,
                )
            }
        }
    }
}

@Composable
private fun PickRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    dim: Boolean = false,
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
            title, color = if (dim) Palette.TextFaint else Palette.Text, fontSize = Type.Meta,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle, color = if (dim) Palette.Warn else Palette.TextDim,
                fontSize = Type.Tiny, lineHeight = Type.TinyLine,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
    if (selected) {
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.Check, null, tint = Palette.Accent, modifier = Modifier.size(20.dp))
    }
}

/** 「更多模型」那一列：點了攤開／收起舊型號，旁邊寫有幾顆。 */
@Composable
private fun MoreRow(open: Boolean, count: Int, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth()
        .clip(Radii.Field)
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(
        "更多模型", color = Palette.Text, fontSize = Type.Meta,
        modifier = Modifier.weight(1f),
    )
    Text("$count", color = Palette.TextFaint, fontSize = Type.Tiny)
    Icon(
        if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
        if (open) "收起舊型號" else "展開舊型號",
        tint = Palette.TextFaint,
        modifier = Modifier.padding(start = 6.dp).size(20.dp),
    )
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
 * 面板底部「這條對話」的狀態：context 用了多少、工作目錄。模型鈕直接開這個面板，
 * 這兩項就跟著放在底下，不必另外開一個框才看得到。
 */
@Composable
fun ConvStatusFooter(cs: ConvStatus) = Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(
        "這條對話", color = Palette.Text, fontSize = Type.Title,
        fontWeight = FontWeight.Bold,
    )
    CtxBar(cs.ctxTokens, cs.ctxLimit)
    Column {
        Text("工作目錄", color = Palette.TextDim, fontSize = Type.Tiny)
        Text(
            cs.cwd.ifBlank { "（預設）" },
            color = Palette.Text, fontSize = Type.Tiny,
            fontFamily = FontFamily.Monospace, maxLines = 2,
        )
    }
}

/** context 用量長條。分母由伺服器給（隨模型變），不在前端寫死。 */
@Composable
private fun CtxBar(tokens: Int, limit: Int) = Column {
    val frac = if (limit > 0) (tokens.toFloat() / limit).coerceIn(0f, 1f) else 0f
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("context", color = Palette.TextDim, fontSize = Type.Tiny)
        Text(
            "%,d / %,d".format(tokens, limit),
            color = Palette.TextDim, fontSize = Type.Tiny,
            fontFamily = FontFamily.Monospace,
        )
    }
    Box(
        Modifier.fillMaxWidth().height(4.dp).padding(top = 1.dp)
            .background(Palette.SurfaceHi, Radii.Chip),
    ) {
        Box(
            Modifier.fillMaxWidth(frac).fillMaxHeight()
                // 逼近 0.85 就會觸發自動壓縮，先變色當預告
                .background(if (frac > 0.85f) Palette.Danger else Palette.Accent, Radii.Chip),
        )
    }
}

/**
 * 設定頁那一列「模型／思考強度」的摘要，點了開面板。
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

/** 伺服器沒給這顆模型的等級時用的完整清單（照強度排）。 */
private val DEFAULT_EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

private val EFFORT_LABEL = mapOf(
    "low" to "低", "medium" to "中", "high" to "高", "xhigh" to "超高", "max" to "最高",
)

private val EFFORT_HINT = mapOf(
    "low" to "最快，想得最少",
    "medium" to "速度與深度的平衡",
    "high" to "想得比較深，稍慢一點",
    "xhigh" to "更深入，適合難題",
    "max" to "想到最透，最慢也最耗額度",
)

/**
 * 帳號預設沒設時那一列叫什麼：內建預設是官方別名 `default` 就是「官方建議」，
 * 部署者用 DEFAULT_MODEL 指定了別的就是「內建預設」。
 */
fun followLabel(builtinModel: String): String =
    if (builtinModel.isBlank() || builtinModel == "default") "官方建議" else "內建預設"

/** 思考等級的中文名。官方哪天多一級、這裡還沒有對應的就原樣顯示。 */
fun effortLabel(value: String): String = EFFORT_LABEL[value] ?: value

/**
 * 這顆模型認不認得這個值。存下來的值可能是任何一種寫法：清單的值（`claude-opus-5-5[1m]`）、
 * 官方 id、CLI 別名（`opus[1m]`／`default`，伺服器放在 aliases），或只寫系列名
 * （`opus`＝該系列最新那顆，跟 CLI 的解析一致）。
 */
fun ModelInfo.matches(v: String): Boolean {
    if (v.isBlank()) return false
    val base = v.substringBefore('[')
    return v == value || v == resolved || v in aliases ||
        (resolved.isNotBlank() && base == resolved.substringBefore('[')) ||
        (tier == "main" && family.isNotBlank() && base == family)
}

/** 值 → 顯示名。清單裡找得到就用官方顯示名，找不到（清單還沒到）就去掉前綴。 */
fun modelDisplay(value: String, infos: List<ModelInfo>): String {
    if (value.isBlank()) return ""
    return infos.firstOrNull { it.matches(value) }?.name ?: value.removePrefix("claude-")
}
