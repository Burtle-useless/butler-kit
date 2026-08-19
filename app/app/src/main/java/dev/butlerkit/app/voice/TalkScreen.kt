package dev.butlerkit.app.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.butlerkit.app.ui.IconBtn
import dev.butlerkit.app.ui.Palette
import dev.butlerkit.app.ui.Radii
import dev.butlerkit.app.ui.Space
import dev.butlerkit.app.ui.Type

/**
 * 面對面翻譯。
 *
 * 兩個人**看同一個方向**，不做上下對翻。原因是收音孔在機身底部：畫面上下分邊的話，
 * 對面那個人得對著手機頂端講，離收音孔最遠又被機身擋著，辨識率會掉。
 * 改成同一面之後，誰要講就把手機底端朝著誰，換人講就把機身轉過去。
 *
 * 上半只放最新一句、字大到隔一張桌子看得見——正在聽的那個人只需要這一句。
 * 下半是完整可捲動的紀錄，事後想回頭確認「他剛剛說多少錢」的一定是他自己。
 * 兩顆麥克風按鈕並排在最底下：離嘴巴近、離拇指也近。
 */
@Composable
fun TalkScreen(micGranted: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val vm: TalkViewModel = viewModel()
    val s by vm.state.collectAsState()
    val listState = rememberLazyListState()

    // 新句子進來就貼到底；用 animateScrollToItem 會在連續對話時一直播動畫
    LaunchedEffect(s.lines.size) {
        if (s.lines.size > 1) listState.scrollToItem(s.lines.size - 2)
    }

    Column(
        Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Divider(s, onSwap = { forMine, lang -> vm.setLang(forMine, lang) }, onClose = onClose,
            onClear = vm::clear)

        Column(Modifier.fillMaxSize().padding(Space.Screen)) {
            // 先接出來：s 是 delegated property，直接寫 s.error 沒辦法 smart cast
            val err = s.error
            when {
                !micGranted -> Hint("沒有麥克風權限，這個畫面不能用。到系統設定裡開給它。")
                !s.speechAvailable -> Hint("這支手機找不到語音辨識引擎。")
                s.models is Models.Downloading -> Downloading()
                // 兩則都只是告知，不擋操作——線上翻譯用不到語言包
                s.models is Models.Failed -> Hint(
                    "離線語言包沒裝好，沒網路時會翻不出來。現在照樣可以講　點這裡重試",
                    onClick = { vm.retryDownload() },
                )
                // 辨識失敗九成是語音包沒裝，所以整行可點，直接帶去下載
                err != null -> Hint(err, onClick = { openVoiceInputSettings(ctx) })
            }

            Latest(s, Modifier.weight(1f), onReplay = vm::replay)

            // 之前說過的。最後一句已經在上面放大顯示了，這裡不再重複一次。
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(s.lines.dropLast(1)) { line -> LineRow(line, vm::replay) }
            }

            if (s.listeningMine != null && s.partial.isNotBlank()) {
                Text(
                    s.partial,
                    color = Palette.Accent, fontSize = Type.Body, lineHeight = Type.BodyLine,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MicButton(
                    lang = s.theirs,
                    listening = s.listeningMine == false,
                    // 一次只能有一個人在講：另一邊正在聽的時候整顆變灰
                    // **刻意不看語言包狀態**：翻譯主力是線上的 [GoogleTranslate]，語言包
                    // 只是沒網路時的退路。原本這裡要求 Models.Ready，於是語言包在下載的
                    // 那幾分鐘兩顆按鈕全灰——實機上看起來就是「叫我下載、又不讓我用」。
                    enabled = micGranted &&
                        (s.listeningMine == null || s.listeningMine == false),
                    onClick = {
                        if (s.listeningMine == false) vm.stopListening()
                        else vm.listen(fromMine = false)
                    },
                    modifier = Modifier.weight(1f),
                )
                MicButton(
                    lang = s.mine,
                    listening = s.listeningMine == true,
                    enabled = micGranted &&
                        (s.listeningMine == null || s.listeningMine == true),
                    onClick = {
                        if (s.listeningMine == true) vm.stopListening()
                        else vm.listen(fromMine = true)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 最新一句的譯文，大字。給剛剛在聽的那個人看，所以不分是誰講的。
 * 點一下重播，譯得歪的時候比重講一次快。
 */
@Composable
private fun Latest(s: TalkState, modifier: Modifier, onReplay: (TalkLine) -> Unit) {
    val latest = s.lines.lastOrNull()
    Column(
        modifier.fillMaxWidth()
            .then(if (latest != null) Modifier.clickable { onReplay(latest) } else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            // 空狀態這行對面也要看得懂，所以附上他那個語言的同一句（見 [TalkLang.hint]）
            latest?.translated
                ?: ("按下面的按鈕開始講" +
                    if (s.theirs.hint.isBlank()) "" else "　${s.theirs.hint}"),
            color = if (latest == null) Palette.TextFaint else Palette.Text,
            fontSize = if (latest == null) Type.Body else 30.sp,
            lineHeight = if (latest == null) Type.BodyLine else 40.sp,
            textAlign = TextAlign.Center,
        )
        if (latest != null) {
            Text(
                latest.original,
                color = Palette.TextDim, fontSize = Type.Meta, lineHeight = Type.MetaLine,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            // 這句最大最顯眼，是拿給對面看的那一句，所以品質有疑慮時要當場說
            if (!latest.online) {
                Text(
                    "離線翻譯，可能不準",
                    color = Palette.Warn, fontSize = Type.Tiny,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * 一句話。譯文大、原文小。
 *
 * 原文一定留著：譯得歪的時候，指著原文重講一次比反覆講同一句有效。
 */
@Composable
private fun LineRow(line: TalkLine, onReplay: (TalkLine) -> Unit) {
    val mine = line.fromMine
    Column(
        Modifier.fillMaxWidth()
            .background(
                if (mine) Palette.UserBubble else Palette.BotBubble, Radii.Bubble,
            )
            .clickable { onReplay(line) }
            .padding(12.dp),
    ) {
        // 標明方向而不只是說話者的語言：只寫「中文」的話，配著下面那行英文譯文
        // 看起來像在說「這句英文是中文」。
        // 離線那條路要標出來，它的品質明顯較差，不標會以為是 App 時準時不準。
        Text(
            "${line.fromLang.label} → ${line.toLang.label}" +
                if (line.online) "" else "　離線翻譯",
            color = if (line.online) Palette.TextFaint else Palette.Warn,
            fontSize = Type.Tiny,
        )
        Text(
            line.translated,
            color = Palette.Text, fontSize = 22.sp, lineHeight = 30.sp,
        )
        Text(
            line.original,
            color = Palette.TextDim, fontSize = Type.Meta, lineHeight = Type.MetaLine,
        )
    }
}

/**
 * 兩邊共用的大按鈕。整塊都可按——面對面時沒人想瞄準一個小圓圈。
 *
 * 主行寫中文語言名（按的人多半是手機主人），副行寫該語言自己的寫法，
 * 對面那個人才認得出哪顆是自己的。
 */
@Composable
private fun MicButton(
    lang: TalkLang,
    listening: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = when {
        listening -> Palette.AccentSoft
        enabled -> Palette.SurfaceHi
        else -> Palette.Surface
    }
    Column(
        modifier
            .background(bg, Radii.Card)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (listening) "聽著…" else "◉  ${lang.label}",
            color = when {
                listening -> Palette.Accent
                enabled -> Palette.Text
                else -> Palette.TextFaint
            },
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            // 一律留一行，不然按下去時整排按鈕高度會跳
            when {
                listening -> "講完再點一下"
                lang.native != lang.label -> lang.native
                else -> " "
            },
            color = Palette.TextFaint, fontSize = Type.Tiny,
            textAlign = TextAlign.Center,
        )
    }
}

/** 中間那條：兩邊的語言選擇、清空、關閉。 */
@Composable
private fun Divider(
    s: TalkState,
    onSwap: (forMine: Boolean, TalkLang) -> Unit,
    onClose: () -> Unit,
    onClear: () -> Unit,
) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(Palette.Surface).padding(
            horizontal = Space.Screen, vertical = 8.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LangPicker(s.theirs) { onSwap(false, it) }
        Text("  ⇄  ", color = Palette.TextFaint, fontSize = Type.Body)
        LangPicker(s.mine) { onSwap(true, it) }
        Spacer(Modifier.weight(1f))
        // 離線辨識包的下載入口。放常駐而不是只在出錯時給：新增一個語言之後
        // 第一件該做的事就是去下載它，不必先撞一次失敗才知道。
        Text(
            "語音包", color = Palette.TextDim, fontSize = Type.Meta,
            modifier = Modifier.clickable { openVoiceInputSettings(ctx) }.padding(8.dp),
        )
        Text(
            "清空", color = Palette.TextDim, fontSize = Type.Meta,
            modifier = Modifier.clickable { onClear() }.padding(8.dp),
        )
        IconBtn(Icons.Filled.Close, "關掉翻譯", onClick = onClose)
    }
}

@Composable
private fun LangPicker(current: TalkLang, onPick: (TalkLang) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            current.label,
            color = Palette.Accent, fontSize = Type.Body,
            modifier = Modifier.clickable { open = true }.padding(vertical = 4.dp),
        )
        // 選單裡才附原文名：頂端那條放不下「印尼文　Bahasa Indonesia」這種長度
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TalkLang.entries.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(lang.display) },
                    onClick = {
                        open = false
                        onPick(lang)
                    },
                )
            }
        }
    }
}

@Composable
private fun Downloading() {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.height(16.dp), color = Palette.Accent, strokeWidth = 2.dp)
        Text(
            "  背景下載離線語言包（約 30MB）　不用等它，現在就能講",
            color = Palette.TextDim, fontSize = Type.Meta,
        )
    }
}

@Composable
private fun Hint(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text,
        color = Palette.Warn, fontSize = Type.Meta, lineHeight = Type.MetaLine,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(bottom = 10.dp),
    )
}
