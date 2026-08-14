package dev.butlerkit.app.voice

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/**
 * 面對面翻譯用得到的語言，以及三套識別碼的對應。
 *
 * 三個 API 各用自己的一套代碼，彼此不通用，這是這個功能最容易靜默出錯的地方：
 *   - SpeechRecognizer 吃 BCP-47（`zh-TW`、`en-US`、`ja-JP`）
 *   - ML Kit 翻譯吃 ISO 639-1（`zh`、`en`、`ja`），常數在 [TranslateLanguage]
 *   - TextToSpeech 吃 [Locale]
 * 拿錯一套不會拋錯，只會辨識不到聲音或翻出空字串，所以對應表集中在這裡一份。
 *
 * **ML Kit 的中文只有簡體**：`TranslateLanguage.CHINESE` 是簡體中文，官方沒有繁體
 * 選項。譯進中文時先接 [ChineseVariants] 轉成繁體再顯示。
 *
 * 名字有兩個：[label] 是中文，給操作手機的人看——選單上寫「한국어」「ไทย」的話
 * 根本分不出哪個是哪個。[native] 是該語言自己的寫法，給對面那個人看。
 */
enum class TalkLang(
    /** 中文名字，選單與按鈕的主要標示 */
    val label: String,
    /** 該語言自己的名字，給看不懂中文的人辨認 */
    val native: String,
    /** SpeechRecognizer 的 BCP-47 標籤 */
    val speechTag: String,
    /** ML Kit 的語言代碼，取自 [TranslateLanguage] */
    val mlkit: String,
    /** TextToSpeech 用的 Locale */
    val locale: Locale,
) {
    ZH("中文", "中文", "zh-TW", TranslateLanguage.CHINESE, Locale.TRADITIONAL_CHINESE),
    EN("英文", "English", "en-US", TranslateLanguage.ENGLISH, Locale.US),
    JA("日文", "日本語", "ja-JP", TranslateLanguage.JAPANESE, Locale.JAPAN),
    KO("韓文", "한국어", "ko-KR", TranslateLanguage.KOREAN, Locale.KOREA),
    ES("西班牙文", "Español", "es-ES", TranslateLanguage.SPANISH, Locale("es", "ES")),
    FR("法文", "Français", "fr-FR", TranslateLanguage.FRENCH, Locale.FRANCE),
    DE("德文", "Deutsch", "de-DE", TranslateLanguage.GERMAN, Locale.GERMANY),
    VI("越南文", "Tiếng Việt", "vi-VN", TranslateLanguage.VIETNAMESE, Locale("vi", "VN")),
    TH("泰文", "ไทย", "th-TH", TranslateLanguage.THAI, Locale("th", "TH")),
    ID("印尼文", "Bahasa Indonesia", "id-ID", TranslateLanguage.INDONESIAN, Locale("in", "ID")),
    ;

    /** 選單／按鈕上的完整標示。中文自己不用再附一次原文。 */
    val display: String get() = if (native == label) label else "$label　$native"

    /**
     * [GoogleTranslate] 用的代碼。除了中文，其餘與 ML Kit 的 ISO 639-1 相同。
     *
     * 中文一定要送 `zh-TW` 而不是 `zh`：送 `zh` 拿回來的是簡體，送 `zh-TW` 它會自己
     * 多走一跳轉繁體，那條路上就不必接 [ChineseVariants] 了。
     */
    val gTag: String get() = if (this == ZH) "zh-TW" else mlkit

    /**
     * 空狀態那行提示的譯文，給對面那個人看。
     *
     * 寫死在這裡而不去跑翻譯：這句話要在**還沒有任何譯文**的時候就顯示，那時線上
     * 可能還沒通、語言包也可能還在下載。原本畫面上這行英文是寫死的，對面講日文時
     * 那句英文對他毫無意義。中文回空字串——畫面上那行本來就是中文，不必重複。
     */
    val hint: String get() = when (this) {
        ZH -> ""
        EN -> "Tap a button and speak"
        JA -> "ボタンを押して話してください"
        KO -> "버튼을 누르고 말하세요"
        ES -> "Pulsa un botón y habla"
        FR -> "Appuyez sur un bouton et parlez"
        DE -> "Taste drücken und sprechen"
        VI -> "Nhấn một nút và nói"
        TH -> "แตะปุ่มแล้วพูด"
        ID -> "Tekan tombol lalu bicara"
    }

    companion object {
        /** 預設組合：他講中文、對方講英文 */
        val DEFAULT_MINE: TalkLang = ZH
        val DEFAULT_THEIRS: TalkLang = EN
    }
}
