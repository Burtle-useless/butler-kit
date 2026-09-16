package dev.butlerkit.app.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 簡繁轉換，字典來自 OpenCC（Apache-2.0），放在 assets/opencc/。
 *
 * 為什麼需要它——ML Kit 的中文只有一種，而且是簡體：
 * [com.google.mlkit.nl.translate.TranslateLanguage.CHINESE] 沒有繁體對應項，
 * 所以這一層在翻譯的兩端各補一刀：
 *  - **譯出**：ML Kit 吐簡體 → [toTraditional] 轉台灣繁體，畫面上才不會是簡體字。
 *  - **譯入**：SpeechRecognizer 用 `zh-TW` 聽出來的是繁體，但 ML Kit 的中文模型
 *    是簡體語料訓練的，直接餵繁體會掉品質 → [toSimplified] 先轉回簡體再送翻譯。
 *
 * 不用 opencc 的 Android 移植是因為那些都帶 native lib，而 APK 已經為了 ML Kit
 * 漲到 27MB 了；這裡只需要查表加最大正向匹配，純 Kotlin 三十行就夠。
 *
 * 沒有分詞器。OpenCC 官方鏈會先用 mmseg 斷詞再查表，這裡直接對整句做最大正向匹配
 * ——對「一句話」這種長度，兩者結果幾乎一樣，而詞組表本身就是在處理歧義字
 * （「头发」→「頭髮」而不是「頭發」）。
 */
object ChineseVariants {

    /**
     * 每一段是一次完整的掃描，順序照 OpenCC 的 s2twp.json：
     * 先簡→繁通用、再套台灣異體字（着→著）、最後換台灣用詞（軟件→軟體）。
     * 同一段內的字典，先列的優先——詞組表要壓在單字表前面，否則永遠匹配不到詞。
     */
    private val TO_TRADITIONAL = listOf(
        listOf("STPhrases.txt", "STCharacters.txt"),
        listOf("TWVariantsPhrases.txt", "TWVariants.txt"),
        listOf("TWPhrases.txt"),
    )

    /**
     * 反向。台灣用詞那一段不能省——只轉字形的話「印表機」會變成「印表机」，
     * ML Kit 認不出那是 printer；換成「打印机」才翻得對。
     */
    private val TO_SIMPLIFIED = listOf(
        listOf("TWPhrasesRev.txt"),
        listOf("TSPhrases.txt", "TSCharacters.txt"),
    )

    private class Stage(val table: Map<String, String>, val maxKey: Int)

    @Volatile private var toTrad: List<Stage>? = null
    @Volatile private var toSimp: List<Stage>? = null
    private val gate = Mutex()

    /**
     * 載入字典。約 1.2MB 文字要解析成六萬條 entry，一定要離開主執行緒，所以是 suspend。
     *
     * 已載入就直接返回，而且**要在每次翻譯前都呼叫一次**——換語言跟第一句話可能
     * 幾乎同時發生，靠 [Mutex] 讓後到的那個等前面載完，而不是各解析一遍字典。
     */
    suspend fun load(context: Context) {
        if (toTrad != null) return
        gate.withLock {
            if (toTrad != null) return
            withContext(Dispatchers.IO) {
                try {
                    val trad = TO_TRADITIONAL.map { stage(context, it) }
                    val simp = TO_SIMPLIFIED.map { stage(context, it) }
                    toSimp = simp
                    toTrad = trad  // 最後才設：它是「全部就緒」的旗標
                } catch (e: Exception) {
                    // 載不起來就讓 convert 原封不動回傳：簡體字看得懂，崩掉看不懂。
                    Log.w(TAG, "簡繁字典載入失敗", e)
                }
            }
        }
    }

    /** 簡體（含大陸用詞）→ 台灣繁體。字典還沒載好就原樣回傳。 */
    fun toTraditional(text: String): String = run(text, toTrad)

    /** 繁體 → 簡體字形。 */
    fun toSimplified(text: String): String = run(text, toSimp)

    private fun run(text: String, stages: List<Stage>?): String {
        if (text.isEmpty()) return text
        var out = text
        stages?.forEach { out = apply(out, it) }
        return out
    }

    private fun apply(text: String, stage: Stage): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            // 最大正向匹配：從最長的可能詞往下試，先中的優先
            var n = minOf(stage.maxKey, text.length - i)
            var matched = false
            while (n >= 1) {
                val hit = stage.table[text.substring(i, i + n)]
                if (hit != null) {
                    out.append(hit)
                    i += n
                    matched = true
                    break
                }
                n--
            }
            if (!matched) {
                // 沒對應就照抄。用 codePoint 步進，避免把 emoji 這種
                // 兩個 char 的字元切成兩半。
                val cp = text.codePointAt(i)
                out.appendCodePoint(cp)
                i += Character.charCount(cp)
            }
        }
        return out.toString()
    }

    private fun stage(context: Context, files: List<String>): Stage {
        val table = HashMap<String, String>(1 shl 16)
        var maxKey = 1
        files.forEach { name ->
            context.assets.open("$ASSET_DIR/$name").bufferedReader().forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEachLine
                val key = line.substring(0, tab)
                // 一個 key 可能有多個候選值（空格分隔），取第一個
                val value = line.substring(tab + 1).trimStart().substringBefore(' ')
                if (value.isEmpty()) return@forEachLine
                // 同一段內先載入的字典優先，所以已存在的 key 不覆蓋
                if (table.putIfAbsent(key, value) == null && key.length > maxKey) {
                    maxKey = key.length
                }
            }
        }
        return Stage(table, maxKey)
    }

    private const val ASSET_DIR = "opencc"
    private const val TAG = "butler-hanzi"
}
