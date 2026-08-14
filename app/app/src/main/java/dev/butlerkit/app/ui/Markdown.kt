package dev.butlerkit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 極簡 Markdown 渲染。
 *
 * 模型回覆一定帶 Markdown，Compose 的 Text 不認得它，畫面上就會出現一堆
 * 星號和反引號——那是「看起來很廉價」最直接的來源。
 *
 * 這裡刻意不引第三方 Markdown 函式庫：手機聊天訊息只會用到粗體、行內程式碼、
 * 程式碼區塊、列表和超連結這幾種，為此拉一個完整的 CommonMark 實作進來不划算。
 * 表格與圖片目前不支援；真的需要時再換。
 *
 * 註：**程式碼區塊裡的網址仍然點不了**（那是刻意的，區塊內容要原樣可複製）。
 * 要給人點的網址別包在反引號或 ``` 裡面。
 */

private sealed interface Block {
    data class Body(val text: String) : Block
    data class Code(val text: String, val lang: String) : Block
}

private val FENCE = Regex("""^```(\w*)\s*$""")

private fun parseBlocks(src: String): List<Block> {
    val out = mutableListOf<Block>()
    val body = StringBuilder()
    val code = StringBuilder()
    var inCode = false
    var lang = ""

    fun flushBody() {
        if (body.isNotBlank()) out += Block.Body(body.toString().trim('\n'))
        body.clear()
    }

    for (line in src.lines()) {
        val fence = FENCE.find(line.trim())
        if (fence != null) {
            if (inCode) {
                out += Block.Code(code.toString().trimEnd('\n'), lang)
                code.clear()
                inCode = false
            } else {
                flushBody()
                lang = fence.groupValues[1]
                inCode = true
            }
            continue
        }
        if (inCode) code.append(line).append('\n') else body.append(line).append('\n')
    }
    // 收尾：串流中途可能還沒等到收尾的 ```，把已有的先當程式碼顯示
    if (inCode && code.isNotBlank()) out += Block.Code(code.toString().trimEnd('\n'), lang)
    flushBody()
    return out
}

private val BOLD = Regex("""\*\*(.+?)\*\*""")
private val CODE = Regex("""`([^`\n]+)`""")

// 兩種連結寫法都要收：模型有時寫 [標題](網址)，有時直接貼裸網址。
private val LINK = Regex("""\[([^\]\n]+)\]\((https?://[^\s)]+)\)""")

// 裸網址**必須用白名單**（RFC 3986 的合法字元），不能寫成「非空白就吃」——
// 中文句子沒有空白，`[^\s]+` 會把「https://x.com。點進去看」整句都吞進網址。
// 代價是含中文的網址（IDN、中文路徑）認不出來，那比每個網址後面都黏著半句話好。
private val BARE = Regex("""https?://[A-Za-z0-9\-._~:/?#\[\]@!&'()*+,;=%]+""")

// 白名單裡有幾個字元同時是句讀：句末的 `.`、`,`、`)` 不屬於網址。
// 全形標點已被上面的白名單擋掉，這裡只處理半形。
// 收尾的 `)` 一起剝：括號結尾的網址（維基百科那種）比句末網址少得多。
private val TAIL = ",;:!.')]".toSet()

private fun trimUrl(raw: String): String {
    var end = raw.length
    while (end > 0 && raw[end - 1] in TAIL) end--
    return raw.substring(0, end)
}

private enum class Kind { Bold, Code, Link }

/** 行內樣式：**粗體**、`行內程式碼`、[標題](網址) 與裸網址。（internal 是為了單元測試看得到） */
internal fun inline(src: String): AnnotatedString = buildAnnotatedString {
    // 先把各種語法的位置一起找出來再依序套用，避免巢狀時互相吃掉對方的標記。
    // [標題](網址) 的起點在裡面那串網址之前，排序後先被套用、cursor 直接跳過整段，
    // 所以同一個網址不會被 BARE 再匹配一次——靠的是下面那行 range.first < cursor。
    data class Hit(val range: IntRange, val text: String, val kind: Kind, val url: String = "")

    val hits = (
        BOLD.findAll(src).map { Hit(it.range, it.groupValues[1], Kind.Bold) } +
            CODE.findAll(src).map { Hit(it.range, it.groupValues[1], Kind.Code) } +
            LINK.findAll(src).map {
                Hit(it.range, it.groupValues[1], Kind.Link, it.groupValues[2])
            } +
            BARE.findAll(src).mapNotNull {
                val u = trimUrl(it.value)
                if (u.length <= "https://".length) null
                else Hit(it.range.first until it.range.first + u.length, u, Kind.Link, u)
            }
        ).sortedBy { it.range.first }

    var cursor = 0
    for (h in hits) {
        if (h.range.first < cursor) continue     // 與前一個重疊，跳過
        append(src.substring(cursor, h.range.first))
        when (h.kind) {
            Kind.Code -> withStyle(SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = Type.Mono,
                color = Palette.Accent,
            )) { append(h.text) }
            Kind.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(h.text) }
            // LinkAnnotation 讓 Text 自己處理點擊（Compose 1.7 起內建），
            // 不必自己接 UriHandler 也不必把 Text 換成 ClickableText
            Kind.Link -> withLink(LinkAnnotation.Url(
                h.url,
                TextLinkStyles(SpanStyle(
                    color = Palette.Accent,
                    textDecoration = TextDecoration.Underline,
                )),
            )) { append(h.text) }
        }
        cursor = h.range.last + 1
    }
    append(src.substring(cursor))
}

/** 列表符號正規化（標題另外走 Heading 樣式，不在這裡剝）。 */
private fun tidy(text: String): String = text.lines().joinToString("\n") { line ->
    if (line.trimStart().startsWith("- ")) line.replaceFirst("- ", "・") else line
}

/** 把一段內文再切成「標題行」與「段落」，標題才有機會放大加粗。 */
private sealed interface Chunk {
    data class Heading(val text: String) : Chunk
    data class Para(val text: String) : Chunk
}

private fun splitHeadings(body: String): List<Chunk> {
    val out = mutableListOf<Chunk>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += Chunk.Para(para.toString().trim('\n'))
        para.clear()
    }
    for (line in body.lines()) {
        if (line.startsWith("#")) {
            flush()
            out += Chunk.Heading(line.trimStart('#', ' '))
        } else {
            para.append(line).append('\n')
        }
    }
    flush()
    return out
}

/**
 * 程式碼區塊。右上角一顆複製鈕——手機上要手動選取一段等寬文字幾乎是不可能的任務，
 * 而模型給的程式碼與指令十之八九是要拿去用的。
 */
@Composable
private fun CodeBlock(code: String, lang: String) {
    val clip = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(1600); copied = false }
    }
    Box(
        Modifier.fillMaxWidth()
            .background(Palette.Surface, Radii.Chip)
            .padding(12.dp),
    ) {
        Column {
            if (lang.isNotBlank()) {
                Text(lang, color = Palette.TextFaint, fontSize = Type.Tiny)
            }
            Text(
                code,
                color = Palette.Text,
                fontSize = Type.Mono,
                fontFamily = FontFamily.Monospace,
                lineHeight = Type.MetaLine,
                modifier = Modifier.padding(end = 44.dp)
                    .horizontalScroll(rememberScrollState()),
            )
        }
        Text(
            if (copied) "已複製" else "複製",
            color = if (copied) Palette.Ok else Palette.TextDim,
            fontSize = Type.Tiny,
            modifier = Modifier.align(Alignment.TopEnd)
                .background(Palette.SurfaceHi, Radii.Chip)
                .clickable {
                    clip.setText(AnnotatedString(code))
                    copied = true
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun MarkdownText(src: String, modifier: Modifier = Modifier) {
    val blocks = remember(src) { parseBlocks(src) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Body -> splitHeadings(b.text).forEach { chunk ->
                    when (chunk) {
                        is Chunk.Heading -> Text(
                            chunk.text,
                            color = Palette.Text,
                            fontSize = Type.Title,
                            fontWeight = FontWeight.Bold,
                            lineHeight = Type.BodyLine,
                        )
                        is Chunk.Para -> Text(
                            inline(tidy(chunk.text)),
                            color = Palette.Text,
                            fontSize = Type.Body,
                            lineHeight = Type.BodyLine,
                        )
                    }
                }
                is Block.Code -> CodeBlock(b.text, b.lang)
            }
        }
    }
}
