package dev.butlerkit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全 App 共用的版面元件。
 *
 * 這些原本各自長在 AgendaScreen 與 ToolsScreen 裡，兩邊的卡片內縮、圓角、字級
 * 都差一點點——那些「差一點點」加起來就是整個 App 看起來沒設計過的原因。
 * 尺寸與顏色一律從 [Palette] / [Type] / [Radii] / [Space] 取，不要在呼叫端寫死。
 */

/** 頁面大標。一頁只有一個，[trailing] 放右上角的日期或設定鈕。 */
@Composable
internal fun PageTitle(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth()
            .padding(start = Space.Screen, end = Space.Screen, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            title,
            color = Palette.Text, fontSize = Type.Display,
            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/** 區塊標題。[hint] 是右邊的計數膠囊，沒東西時傳 null 就不佔位置。 */
@Composable
internal fun SectionHead(text: String, hint: String? = null, tint: Color = Palette.Accent) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text, color = Palette.Text, fontSize = Type.Head,
            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
        )
        if (hint != null) {
            Text(
                hint, color = tint, fontSize = Type.Meta, fontWeight = FontWeight.Medium,
                modifier = Modifier.background(tint.soft(), Radii.Chip)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** 卡片。[pad] 只有網格類內容（月曆、星期列）才需要調小。 */
@Composable
internal fun Card(pad: Dp = Space.Inner, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Palette.Surface, Radii.Card)
            // 深色底上陰影幾乎看不見，一圈細邊框才是卡片的分界線
            .border(1.dp, Palette.Line, Radii.Card)
            .padding(pad),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * 帶色條的一列：清單項共用。
 *
 * 左邊那條色條是拉開層次的主要手段——深色底上卡片與背景的明度差本來就小，
 * 一條實色直邊比再加一階灰有效得多，而且它同時擔了「這是哪一頁」的顏色。
 * [dimmed] 是已完成／已關掉的狀態，整列連色條一起壓成灰。
 *
 * [leadWidth] 固定是為了讓同一頁的每一列對齊：時間欄長短不一的話右邊會參差不齊。
 * 但也不能給太窄——26sp 的「07:20」實寬約 80dp，不夠時不會報錯，而是折成兩行。
 */
@Composable
internal fun StripeRow(
    tint: Color,
    dimmed: Boolean = false,
    leadWidth: Dp = 70.dp,
    lead: @Composable ColumnScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            // 一定要先 clip 再 background：色條是獨立的 Box，沒有裁切的話它會直接
            // 畫過卡片的圓角，左邊上下各凸出一塊直角
            .clip(Radii.Card)
            .background(Palette.Surface)
            .border(1.dp, Palette.Line, Radii.Card)
            // 色條要跟整列一樣高，列高又是由文字撐出來的，所以要 IntrinsicSize
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(4.dp).fillMaxHeight()
                .background(if (dimmed) Palette.TextFaint else tint),
        )
        // leadWidth 0 ＝ 這一頁沒有前導欄。連 Column 都不放，否則它的左右內縮
        // 還在，色條跟文字之間會多出一段對不上其他頁的空白
        if (leadWidth > 0.dp) {
            Column(
                // 前導欄寬度要跟著系統字級走。使用者把字調到 130% 時，
                // 26sp 的「07:20」在原本的固定寬度裡會被截成「07:2…」——
                // 給不夠寬不會報錯，只會默默截掉
                Modifier.width(leadWidth * LocalDensity.current.fontScale)
                    .padding(start = 12.dp, end = 8.dp),
                content = lead,
            )
        }
        Column(
            Modifier.weight(1f).padding(
                start = if (leadWidth > 0.dp) 0.dp else 16.dp,
                top = 16.dp, bottom = 16.dp,
            ),
            content = content,
        )
        actions()
    }
}

/**
 * 收合的新增表單。收起時是一顆虛線框的「＋」按鈕，展開後才是輸入欄。
 * 展開狀態不記憶：送出或收起就回到按鈕，下次進來畫面是乾淨的。
 */
@Composable
internal fun AddPanel(
    label: String,
    tint: Color,
    canSubmit: Boolean,
    submitText: String,
    onSubmit: () -> Unit,
    fields: @Composable ColumnScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    if (!open) {
        Row(
            Modifier.fillMaxWidth()
                // clip 要在 border 之前，否則水波紋是整塊方形，蓋過圓角
                .clip(Radii.Card)
                .border(1.dp, Palette.Line, Radii.Card)
                .clickable { open = true }
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, null, tint = tint, modifier = Modifier.size(18.dp))
            Text(
                label, color = tint, fontSize = Type.Body,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        return
    }
    Column(
        Modifier.fillMaxWidth()
            .background(Palette.Surface, Radii.Card)
            .border(1.dp, tint.soft(), Radii.Card)
            .padding(Space.Inner),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label, color = Palette.Text, fontSize = Type.Title,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            )
            IconBtn(Icons.Filled.Close, "收起") { open = false }
        }
        fields()
        ActionButton(submitText, canSubmit, tint, onSubmit)
    }
}

@Composable
internal fun Field(
    value: String,
    hint: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth()
            .background(Palette.SurfaceHi, Radii.Field)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        textStyle = TextStyle(fontSize = Type.Body, color = Palette.Text),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        cursorBrush = SolidColor(Palette.Accent),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(hint, color = Palette.TextFaint, fontSize = Type.Body)
                }
                inner()
            }
        },
    )
}

@Composable
internal fun PickerChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, color = Palette.Text, fontSize = Type.Body,
        modifier = modifier
            .clip(Radii.Field)
            .background(Palette.SurfaceHi)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    )
}

/** [tint] 是所在分頁的重點色；沒傳就用介面主色。 */
@Composable
internal fun ActionButton(
    text: String,
    enabled: Boolean,
    tint: Color = Palette.Accent,
    onClick: () -> Unit,
) {
    Text(
        text,
        color = if (enabled) Palette.Bg else Palette.TextFaint,
        fontSize = Type.Body,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
            .clip(Radii.Field)
            .background(if (enabled) tint else Palette.SurfaceHi)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
    )
}

/**
 * 圖示按鈕。
 *
 * 一定要用向量圖示，不要拿「✕」「✓」這種字元充數：字元圖示的粗細與大小跟著
 * 系統字型跑，換一支手機就變另一個樣子，而且讀螢幕軟體會把它唸成標點符號。
 * [desc] 是給讀螢幕軟體用的，不會顯示出來，但每顆都要給。
 *
 * 48dp 是 Android 的最小可觸控尺寸（iOS 是 44pt，取大的那個）；圖示本身只有
 * 20dp，外圈那圈空白是可以點的範圍。clip 是為了讓點下去的水波紋是圓的。
 */
@Composable
internal fun IconBtn(
    icon: ImageVector,
    desc: String,
    tint: Color = Palette.TextDim,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(48.dp)
            .clip(Radii.Chip)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(20.dp)) }
}

@Composable
internal fun Empty(text: String) {
    Column {
        HorizontalDivider(color = Palette.Line, thickness = 0.6.dp)
        // 空清單是唯一有空間讓桌寵出場的地方——正在用的頁面塞它只會擋路
        Column(
            Modifier.fillMaxWidth().heightIn(min = 150.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PetFace(PetMood.Idle, 54.dp)
            Text(
                text, color = Palette.TextFaint, fontSize = Type.Body,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
