package dev.butlerkit.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log

/**
 * 把譯文唸出來，包一層 [TextToSpeech]。
 *
 * 面對面對話裡這一步不是裝飾——對方不會盯著你的手機螢幕讀字，
 * 尤其譯進中文時 ML Kit 只給簡體，聽比看可靠。
 *
 * 兩個要處理的實務問題：
 *
 * 1. **init 是異步的**。`TextToSpeech(context) { }` 的 callback 要幾百毫秒才來，
 *    在那之前 `speak()` 一律靜默失敗。這裡把 init 之前的最後一句存進 [pending]，
 *    好了之後補唸——第一句話最容易撞上這個空窗。
 * 2. **語言可能沒裝**。`setLanguage` 回 LANG_MISSING_DATA 時直接放棄這一句，
 *    不要 fallback 到別的語言（用日文腔唸英文比不唸更糟）。
 */
class Speaker(context: Context) {

    private var ready = false
    private var pending: Pair<String, TalkLang>? = null

    private val tts = TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (!ready) {
            Log.w(TAG, "TTS 初始化失敗 status=$status")
            return@TextToSpeech
        }
        pending?.let { (text, lang) -> say(text, lang) }
        pending = null
    }

    /** 唸一句。新的一句會蓋掉還在唸的舊句子（對話節奏比唸完整段重要）。 */
    fun say(text: String, lang: TalkLang) {
        if (text.isBlank()) return
        if (!ready) {
            pending = text to lang
            return
        }
        val result = tts.setLanguage(lang.locale)
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.w(TAG, "TTS 缺 ${lang.label} 的語音資料，跳過不唸")
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun release() {
        tts.stop()
        tts.shutdown()
        ready = false
    }

    private companion object {
        const val TAG = "butler-tts"
        const val UTTERANCE_ID = "talk"
    }
}
