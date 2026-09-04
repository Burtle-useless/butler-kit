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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
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
 * 表格畫成等寬欄的簡表（見 [TableBlock]）；圖片不支援。
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
// 括號裡的 `https://` 是選配的——助理自己就常寫成 [看這裡](demo.example.com/tree/)。
private val LINK = Regex("""\[([^\]\n]+)\]\(((?:https?://)?[^\s)]+\.[^\s)]+)\)""")

// 裸網址**必須用白名單**（RFC 3986 的合法字元），不能寫成「非空白就吃」——
// 中文句子沒有空白，`[^\s]+` 會把「https://x.com。點進去看」整句都吞進網址。
// 代價是含中文的網址（IDN、中文路徑）認不出來，那比每個網址後面都黏著半句話好。
private val BARE = Regex("""https?://[A-Za-z0-9\-._~:/?#\[\]@!&'()*+,;=%]+""")

// 沒帶 https:// 的裸網址。助理報網址時十次有九次寫成 `demo.example.com/tree/`，
// 上面那條抓不到，使用者看到的就是一行不能點的字（2026-08-25 回報）。
//
// 這條**只認白名單裡的頂級網域**，不能寫成通用的 `x.y`：對話裡滿地都是
// `main.py`、`chat.js`、`app.css`、`版本 1.2.3`，長得跟網域一模一樣。
// 白名單也因此刻意排除了會跟副檔名撞的 `sh`／`ai`／`cc`／`rs`／`pl`。
private const val TLD =
    "com|net|org|edu|gov|io|dev|app|co|me|tw|jp|uk|de|tv|xyz|page|site|info|blog"

private val NOSCHEME = Regex(
    // 前面不能黏著字母數字或 @：`youteng330@gmail.com` 的 gmail.com 不是連結
    """(?<![A-Za-z0-9@._/\-])""" +
        """(?:[A-Za-z0-9\-]+\.)+(?:$TLD)(?::\d{2,5})?""" +
        """(?:/[A-Za-z0-9\-._~:/?#\[\]@!&'()*+,;=%]*)?""" +
        // 後面也不能再接字母：否則 `foo.community` 會被切出一個 `foo.com`
        """(?![A-Za-z0-9\-])""",
)

/** 點下去要開的網址。無 scheme 的補上 https——瀏覽器不吃 `demo.example.com/tree/`。 */
private fun href(u: String): String =
    if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"

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

/**
 * 行內樣式：**粗體**、`行內程式碼`、[標題](網址) 與裸網址。（internal 是為了單元測試看得到）
 *
 * [c] 是要套的色票。這是純函式不是 composable，讀不到 [Palette]，所以由
 * 呼叫端把當前主題帶進來；單元測試只看文字與連結範圍，用預設的淺色就好。
 */
internal fun inline(src: String, c: Colors = LightColors): AnnotatedString = buildAnnotatedString {
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
            } +
            NOSCHEME.findAll(src).mapNotNull {
                val u = trimUrl(it.value)
                // 剝完句讀只剩一個點以下的不是網址（`圖.tw` 那種殘骸）
                if (!u.contains('.')) null
                else Hit(it.range.first until it.range.first + u.length, u, Kind.Link, u)
            }
        ).sortedBy { it.range.first }

    var cursor = 0
    for (h in hits) {
        if (h.range.first < cursor) continue     // 與前一個重疊，跳過
        append(src.substring(cursor, h.range.first))
        when (h.kind) {
            // 凹面底＋主文字色的等寬字。**不要用強調色**——強調色留給「要注意」
            // 的東西，而一段回覆裡的檔名與指令名沒有一個是警示，整段染色
            // 看起來像滿版錯誤
            Kind.Code -> withStyle(SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = Type.Mono,
                color = c.text,
                background = c.surfaceHi,
            )) { append(h.text) }
            // 粗體裡面再解析一次。**不這樣做的話粗體會把連結吃掉**：助理報網址時
            // 會順手加粗（`**demo.example.com/room/**`），粗體那段的起點在網址
            // 之前，排序後先被套用、cursor 整段跳過，裡面的網址就再也輪不到。
            // 2026-08-25 使用者第二次回報「又不能按超連結了」就是這個。
            //
            // 遞迴不會停不下來：傳進去的是剝掉星號的內容，同一段不會再匹配 BOLD。
            // 只有粗體這樣做，程式碼區塊維持整段原文——`demo.example.com` 寫在
            // 反引號裡指的是設定值不是要點的東西。
            Kind.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(inline(h.text, c))
            }
            // LinkAnnotation 讓 Text 自己處理點擊（Compose 1.7 起內建），
            // 不必自己接 UriHandler 也不必把 Text 換成 ClickableText
            Kind.Link -> withLink(LinkAnnotation.Url(
                href(h.url),
                TextLinkStyles(SpanStyle(
                    color = c.accent,
                    textDecoration = TextDecoration.Underline,
                )),
            )) { append(h.text) }
        }
        cursor = h.range.last + 1
    }
    append(src.substring(cursor))
}

private val ORDERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")

/**
 * 列表與引用的正規化（標題另外走 Heading 樣式，不在這裡剝）。
 *
 * 先前只換 `- `，`1.`／`2.` 的有序清單、縮排的巢狀清單、`>` 引用全部原樣印出來
 * （2026-09-02 審查：助理的回覆大量用 `1.` 清單）。這裡不做真正的清單版面，
 * 只把符號整理成一眼看得懂的樣子：縮排用全形空白保留層次，有序的保留數字。
 */
private fun tidy(text: String): String = text.lines().joinToString("\n") { line ->
    ORDERED.find(line)?.let { m ->
        val (indent, n, rest) = m.destructured
        "${"　".repeat(indent.length / 2)}$n. $rest"
    } ?: BULLET.find(line)?.let { m ->
        val (indent, rest) = m.destructured
        "${"　".repeat(indent.length / 2)}・$rest"
    } ?: if (line.startsWith("> ")) "│ " + line.removePrefix("> ") else line
}

/** 把一段內文再切成「標題行」「表格」與「段落」，各自有各自的畫法。 */
private sealed interface Chunk {
    data class Heading(val text: String) : Chunk
    data class Para(val text: String) : Chunk
    data class Table(val rows: List<List<String>>) : Chunk
}

private val TABLE_SEP = Regex("""^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

private fun tableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

private fun splitHeadings(body: String): List<Chunk> {
    val out = mutableListOf<Chunk>()
    val para = StringBuilder()
    val table = mutableListOf<List<String>>()
    fun flush() {
        if (para.isNotBlank()) out += Chunk.Para(para.toString().trim('\n'))
        para.clear()
    }
    fun flushTable() {
        if (table.isNotEmpty()) out += Chunk.Table(table.toList())
        table.clear()
    }
    for (line in body.lines()) {
        val t = line.trim()
        if (t.startsWith("|") && t.length > 1) {
            // 表格：連續的 | 開頭行。分隔列（|---|---|）只是標記，不畫
            if (table.isEmpty()) flush()
            if (!TABLE_SEP.matches(t)) table += tableRow(t)
            continue
        }
        flushTable()
        if (line.startsWith("#")) {
            flush()
            out += Chunk.Heading(line.trimStart('#', ' '))
        } else {
            para.append(line).append('\n')
        }
    }
    flushTable()
    flush()
    return out
}

/**
 * 表格。欄寬平均分、表頭加粗、列與列之間一條淡線——手機寬度放不下真正的
 * 表格排版，但至少每格對得齊、讀得出「哪一欄是什麼」。欄數多的話整張可以橫捲。
 */
@Composable
private fun TableBlock(rows: List<List<String>>) {
    val cols = rows.maxOf { it.size }
    val c = LocalPalette.current
    Column(
        Modifier.fillMaxWidth()
            .background(Palette.Surface, Radii.Card)
            .padding(vertical = 4.dp),
    ) {
        rows.forEachIndexed { r, row ->
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                for (col in 0 until cols) {
                    Text(
                        inline(row.getOrNull(col).orEmpty(), c),
                        color = if (r == 0) Palette.Text else Palette.TextDim,
                        fontSize = Type.Meta,
                        lineHeight = Type.MetaLine,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f).padding(end = 6.dp),
                    )
                }
            }
            if (r < rows.lastIndex) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        .background(Palette.SurfaceHi).padding(top = 1.dp),
                )
            }
        }
    }
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
                .clip(Radii.Chip)
                .background(Palette.SurfaceHi)
                .clickable(role = Role.Button) {
                    clip.setText(AnnotatedString(code))
                    copied = true
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * Markdown 正文。標題與內文的層次只用粗細與字級分，不換字族——換了字型的話
 * 這裡是要動的地方之一：標題想走襯線就在 [Chunk.Heading] 那支 Text 上指定，
 * 長段中文的內文建議留無襯線（見 Theme.kt 的 [Fonts]）。
 */
@Composable
fun MarkdownText(src: String, modifier: Modifier = Modifier) {
    val blocks = remember(src) { parseBlocks(src) }
    val c = LocalPalette.current
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
                            inline(tidy(chunk.text), c),
                            color = Palette.Text,
                            fontSize = Type.Body,
                            lineHeight = Type.BodyLine,
                            fontFamily = FontFamily.SansSerif,
                        )
                        is Chunk.Table -> TableBlock(chunk.rows)
                    }
                }
                is Block.Code -> CodeBlock(b.text, b.lang)
            }
        }
    }
}
