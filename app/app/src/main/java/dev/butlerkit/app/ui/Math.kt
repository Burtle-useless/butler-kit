package dev.butlerkit.app.ui

/**
 * 數學式的解析。渲染在 [MathView]（Math 的 Compose 那半邊）。
 *
 * 為什麼不引 LaTeX 函式庫：聊天裡會出現的式子多半是「上下標、分數、根號、
 * 希臘字母、向量帽」這幾種。為此拉一個完整的 TeX 排版
 * 引擎進來要多兩三 MB 的字型、而且渲染出來的字體與整個 App 的配色字級對不上。
 * 自己畫換來的是「跟內文同一套字」與「深淺主題自動跟著換」。
 *
 * 代價講清楚：多行對齊（align）、矩陣、積分上下限的精細排版這裡**做不出來**，
 * 遇到不認得的指令一律原樣印出來，不會整段消失——看到 `\begin{matrix}` 就知道
 * 是踩到邊界了，而不是對著一塊空白猜。
 *
 * 語法沿用 LaTeX 的子集，因為模型本來就會寫那個：
 *
 *     $...$      行內式，混在句子裡
 *     $$...$$    獨立式，自己佔一行、可以橫捲
 *
 * 這份檔案只有純函式，沒有 Compose 的東西——單元測試才跑得起來。
 */

internal sealed interface MathNode {
    /** 一個原子：變數、數字、運算子。[italic] 決定要不要用數學斜體。 */
    data class Sym(val text: String, val italic: Boolean = false) : MathNode

    /** 一串水平排列的東西。 */
    data class Row(val items: List<MathNode>) : MathNode

    /** 上下標。兩個都可能是 null，但不會同時是。 */
    data class Script(val base: MathNode, val sub: MathNode?, val sup: MathNode?) : MathNode

    data class Frac(val num: MathNode, val den: MathNode) : MathNode

    data class Sqrt(val body: MathNode) : MathNode

    /** 頭上戴東西：向量箭頭、hat、bar、dot。 */
    data class Accent(val base: MathNode, val kind: AccentKind) : MathNode

    /** `\text{}` 裡的東西：正體、不轉斜體，字型跟內文一致。 */
    data class Plain(val text: String) : MathNode
}

internal enum class AccentKind { Vec, Hat, Bar, Dot, Tilde }

/**
 * 認得的指令。值是要畫出來的字元。
 *
 * 只收「一個字元就畫得出來」的：希臘字母、關係與運算符號、箭頭。需要排版的
 * （frac、sqrt、上下標）在解析器裡各自處理，不在這張表。
 */
internal val MATH_SYMBOLS: Map<String, String> = mapOf(
    // 希臘字母（小寫）
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
    "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
    "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
    "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ",
    "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ",
    "psi" to "ψ", "omega" to "ω",
    // 希臘字母（大寫）
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
    "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ",
    "Psi" to "Ψ", "Omega" to "Ω",
    // 運算與關係
    "times" to "×", "div" to "÷", "cdot" to "·", "pm" to "±", "mp" to "∓",
    "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥",
    "neq" to "≠", "ne" to "≠", "approx" to "≈", "sim" to "∼",
    "equiv" to "≡", "propto" to "∝", "ll" to "≪", "gg" to "≫",
    // 箭頭
    "to" to "→", "rightarrow" to "→", "Rightarrow" to "⇒",
    "leftarrow" to "←", "Leftarrow" to "⇐", "leftrightarrow" to "↔",
    // 大運算子與微積分
    "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮",
    "partial" to "∂", "nabla" to "∇", "infty" to "∞",
    // 集合與邏輯
    "in" to "∈", "notin" to "∉", "subset" to "⊂", "cup" to "∪", "cap" to "∩",
    "forall" to "∀", "exists" to "∃", "emptyset" to "∅",
    // 雜項
    "degree" to "°", "circ" to "∘", "angle" to "∠", "perp" to "⊥",
    "parallel" to "∥", "therefore" to "∴", "because" to "∵",
    "ldots" to "…", "cdots" to "⋯", "prime" to "′",
    "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ",
)

/** 這些指令後面要吃一個 `{}`，做的是排版不是替換。 */
private val ACCENTS = mapOf(
    "vec" to AccentKind.Vec, "hat" to AccentKind.Hat,
    "bar" to AccentKind.Bar, "overline" to AccentKind.Bar,
    "dot" to AccentKind.Dot, "tilde" to AccentKind.Tilde,
)

/** 函式名要用正體：`\sin x` 的 sin 不是三個相乘的變數。 */
private val FUNCTIONS = setOf(
    "sin", "cos", "tan", "cot", "sec", "csc",
    "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
    "log", "ln", "lg", "exp", "lim", "max", "min", "det", "gcd", "mod",
)

/** 這些字元前後要留一點氣。連在一起的 `a=b` 讀起來像一個字。 */
internal val MATH_SPACED = setOf('=', '+', '<', '>', '≤', '≥', '≠', '≈', '±', '→', '∈')

private class Parser(private val src: String) {
    private var i = 0

    fun parse(): MathNode = row { false }

    /** 一直讀到 [stop] 說停為止。 */
    private inline fun row(stop: (Char) -> Boolean): MathNode {
        val items = mutableListOf<MathNode>()
        while (i < src.length && !stop(src[i])) {
            val atom = atom() ?: break
            items += scripts(atom)
        }
        return if (items.size == 1) items[0] else MathNode.Row(items)
    }

    /** 把緊跟在後面的 `^` 與 `_` 吸收掉。兩個都有時不管誰先寫都疊在同一個底上。 */
    private fun scripts(base: MathNode): MathNode {
        var sub: MathNode? = null
        var sup: MathNode? = null
        while (i < src.length && (src[i] == '^' || src[i] == '_')) {
            val isSup = src[i] == '^'
            i++
            val arg = group() ?: break
            if (isSup) sup = arg else sub = arg
        }
        return if (sub == null && sup == null) base else MathNode.Script(base, sub, sup)
    }

    /**
     * 上下標的引數：`{...}` 是一整組，否則只吃一個字元。
     *
     * 「只吃一個」是 TeX 的規則也是實際需要：`x^2y` 的 y 不在指數上，
     * 寫錯成整串都上去的話 `10^-9 N` 會變成「10 的 -9N 次方」。
     */
    private fun group(): MathNode? {
        if (i >= src.length) return null
        if (src[i] == '{') {
            i++
            val inner = row { it == '}' }
            if (i < src.length && src[i] == '}') i++
            return inner
        }
        return atom()
    }

    private fun atom(): MathNode? {
        if (i >= src.length) return null
        val c = src[i]
        return when {
            c == '\\' -> command()
            c == '{' -> group()
            c == '}' -> null            // 交給呼叫端處理，這裡不吞
            c == '^' || c == '_' -> null
            c.isWhitespace() -> { i++; atom() }   // 空白由排版決定，不照抄
            else -> { i++; MathNode.Sym(c.toString(), italic = c.isLetter()) }
        }
    }

    private fun command(): MathNode {
        i++                                   // 跳過反斜線
        if (i >= src.length) return MathNode.Sym("\\")
        // 跳脫的標點：`\{` `\%` `\$` 這種
        if (!src[i].isLetter()) {
            val ch = src[i]
            i++
            return MathNode.Sym(if (ch == ' ') " " else ch.toString())
        }
        val start = i
        while (i < src.length && src[i].isLetter()) i++
        val name = src.substring(start, i)

        ACCENTS[name]?.let { kind ->
            return MathNode.Accent(group() ?: MathNode.Sym(""), kind)
        }
        return when (name) {
            "frac", "dfrac", "tfrac" -> {
                val num = group() ?: MathNode.Sym("")
                val den = group() ?: MathNode.Sym("")
                MathNode.Frac(num, den)
            }
            "sqrt" -> MathNode.Sqrt(group() ?: MathNode.Sym(""))
            "text", "mathrm", "operatorname" -> MathNode.Plain(rawGroup())
            // 這幾個只是間距，畫成一個空格就好
            "quad", "qquad", "," , ";", "!" -> MathNode.Sym(" ")
            "left", "right" -> atom() ?: MathNode.Sym("")   // 括號大小這裡不調
            in FUNCTIONS -> MathNode.Plain(name)
            else -> MATH_SYMBOLS[name]?.let { MathNode.Sym(it) }
                // 不認得就原樣印回去。整段消失才是最難查的——畫面上留著
                // `\begin` 至少看得出是這裡沒支援
                ?: MathNode.Plain("\\$name")
        }
    }

    /** `\text{}` 的內容原樣取出，不解析。 */
    private fun rawGroup(): String {
        if (i >= src.length || src[i] != '{') return ""
        i++
        val sb = StringBuilder()
        var depth = 1
        while (i < src.length) {
            val c = src[i]
            if (c == '{') depth++
            if (c == '}') { depth--; if (depth == 0) { i++; break } }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}

internal fun parseMath(src: String): MathNode = Parser(src).parse()

/**
 * 一段內文裡的數學式位置。[display] 是 `$$...$$`（獨立一行），否則是行內。
 *
 * 錢字號的難處在於它也是錢：「$5 美金」不是公式。所以認的是**成對**的錢字號，
 * 而且開頭那個後面不能接空白、結尾那個前面也不能是空白——這樣「$5 和 $10」
 * 中間那段雖然成對，卻會因為 `5 和 ` 的樣子過不了下面的 looksLikeMath 而被放過。
 */
internal data class MathSpan(val range: IntRange, val body: String, val display: Boolean)

/** 這段東西像不像數學式。純中文或帶空白的金額不算。 */
private fun looksLikeMath(s: String): Boolean {
    if (s.isBlank()) return false
    if (s.any { it.code in 0x4E00..0x9FFF }) return s.contains('\\')  // 有中文就要有指令
    return s.any { it in "\\^_+-=*/<>()[]" } || s.any { it.isLetter() }
}

internal fun findMath(src: String): List<MathSpan> {
    val out = mutableListOf<MathSpan>()
    var i = 0
    while (i < src.length) {
        if (src[i] != '$') { i++; continue }
        // 反斜線跳脫的錢字號不是公式邊界
        if (i > 0 && src[i - 1] == '\\') { i++; continue }
        val display = i + 1 < src.length && src[i + 1] == '$'
        val open = if (display) i + 2 else i + 1
        if (open >= src.length) break
        val close = if (display) src.indexOf("$$", open) else findSingleClose(src, open)
        if (close < 0) { i++; continue }
        val body = src.substring(open, close)
        val end = if (display) close + 2 else close + 1
        if (looksLikeMath(body) && (display || body.length <= 200)) {
            out += MathSpan(i until end, body, display)
            i = end
        } else {
            i++
        }
    }
    return out
}

/** 行內式的收尾錢字號：跳過跳脫的，而且不跨行——公式不會跨段落。 */
private fun findSingleClose(src: String, from: Int): Int {
    var j = from
    while (j < src.length) {
        val c = src[j]
        if (c == '\n') return -1
        if (c == '$' && src[j - 1] != '\\') return j
        j++
    }
    return -1
}
