package dev.butlerkit.app.voice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.butlerkit.app.data.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 對話裡的一句話。原文留著是刻意的——譯得不對時對方指著原文還能重講一次。 */
data class TalkLine(
    /** true＝這句是他講的，false＝對面講的 */
    val fromMine: Boolean,
    val original: String,
    val translated: String,
    /**
     * 這句話**當時**的來源與目標語言。一定要存下來，不能顯示時再從 [TalkState] 現算：
     * 中翻英講了幾句之後把對面改成日文，那幾句舊的標籤就會一起變成日文，
     * 內容還是英文。（實機上就是這樣：標「中文」的氣泡裡放著英文譯文。）
     */
    val fromLang: TalkLang,
    val toLang: TalkLang,
    /**
     * 這句是線上翻的還是離線退路翻的。存下來是為了在氣泡上標出來：
     * 兩條路的品質差距很明顯（離線那條會把「我日文不好」翻成「我的日本人不好」），
     * 不標的話只會覺得這個 App 時準時不準。
     */
    val online: Boolean,
)

data class TalkState(
    val mine: TalkLang = TalkLang.DEFAULT_MINE,
    val theirs: TalkLang = TalkLang.DEFAULT_THEIRS,
    val models: Models = Models.Missing,
    val lines: List<TalkLine> = emptyList(),
    /** 正在聽誰講；null＝沒在聽 */
    val listeningMine: Boolean? = null,
    /** 還沒定稿的辨識片段 */
    val partial: String = "",
    val error: String? = null,
    val speechAvailable: Boolean = true,
)

/**
 * 面對面翻譯的狀態機：聽 → 翻 → 唸 → 記一行。
 *
 * **刻意不做「自動接話」**（講完自動開始聽對方）。那功能要先解決三件事才不會壞：
 * 朗讀的聲音會被麥克風收回去形成迴圈、環境噪音會誤觸發、兩人同時講時沒有仲裁。
 * 三件都做對才換來省一次點擊，而按鈕本身在面對面的場合反而是有用的信號——
 * 對方看得懂你按下去代表「現在換你講」。要加的話從 [Speaker] 補朗讀完成回呼開始。
 */
class TalkViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val speech = SpeechInput(app)
    private val translator = TalkTranslator(app)
    private val speaker = Speaker(app)

    private val _state = MutableStateFlow(TalkState())
    val state: StateFlow<TalkState> = _state.asStateFlow()

    /** 正在跑的語言包準備工作。換語言時要先取消它，見 [prepare]。 */
    private var prepareJob: Job? = null

    init {
        val mine = prefs.talkMine.toLangOr(TalkLang.DEFAULT_MINE)
        val theirs = prefs.talkTheirs.toLangOr(TalkLang.DEFAULT_THEIRS)
        _state.update {
            it.copy(mine = mine, theirs = theirs, speechAvailable = speech.available())
        }
        collectSpeech()
        collectModels()
        prepare(mine, theirs)
    }

    /** 開始聽。[fromMine] 決定用哪個語言辨識、以及翻往哪一邊。 */
    fun listen(fromMine: Boolean) {
        val s = _state.value
        if (s.listeningMine != null) return  // 已經在聽了，重複點擊忽略
        speaker.stop()                        // 還在唸的話先閉嘴，否則會被麥克風收回去
        _state.update { it.copy(listeningMine = fromMine, partial = "", error = null) }
        speech.start(if (fromMine) s.mine else s.theirs)
    }

    /** 使用者鬆手。真正的結果會從 [collectSpeech] 那條路回來。 */
    fun stopListening() {
        speech.stop()
    }

    fun setLang(forMine: Boolean, lang: TalkLang) {
        val s = _state.value
        val mine = if (forMine) lang else s.mine
        val theirs = if (forMine) s.theirs else lang
        // 兩邊同語言沒有意義。但**不能默默忽略**——原本直接 return，使用者點了
        // 選單、選單關掉、語言沒變、畫面上什麼都沒說，看起來就是這個選項壞了。
        if (mine == theirs) {
            _state.update { it.copy(error = "兩邊不能選同一種語言") }
            return
        }
        prefs.talkMine = mine.name
        prefs.talkTheirs = theirs.name
        _state.update { it.copy(mine = mine, theirs = theirs) }
        prepare(mine, theirs)
    }

    fun clear() {
        _state.update { it.copy(lines = emptyList(), partial = "", error = null) }
    }

    /** 重新唸某一句（對方沒聽清楚時用）。用當時的語言，不是現在選的那個。 */
    fun replay(line: TalkLine) {
        speaker.say(line.translated, line.toLang)
    }

    fun retryDownload() {
        val s = _state.value
        prepare(s.mine, s.theirs)
    }

    private fun prepare(mine: TalkLang, theirs: TalkLang) {
        // 先取消上一次還沒做完的準備。在語言選單上連點幾下會疊出好幾個 prepare，
        // 它們寫的是同一條 models flow——最後蓋上去的不保證是最新選的那組語言，
        // 畫面顯示「可以用了」，實際準備好的卻是中途某一組，翻出來的東西不對。
        prepareJob?.cancel()
        prepareJob = viewModelScope.launch { translator.prepare(mine, theirs) }
    }

    private fun collectModels() {
        viewModelScope.launch {
            translator.models.collect { m -> _state.update { it.copy(models = m) } }
        }
    }

    private fun collectSpeech() {
        viewModelScope.launch {
            speech.state.collect { heard ->
                when (heard) {
                    is Heard.Listening ->
                        _state.update { it.copy(partial = heard.partial) }

                    is Heard.Final -> {
                        // listeningMine 在這裡就要先讀下來：翻譯是 suspend 的，
                        // 等它回來時狀態可能已經被下一次點擊改掉了
                        val fromMine = _state.value.listeningMine ?: true
                        _state.update { it.copy(listeningMine = null, partial = "") }
                        commit(heard.text, fromMine)
                    }

                    is Heard.Failed ->
                        _state.update {
                            it.copy(listeningMine = null, partial = "", error = heard.why)
                        }

                    Heard.Idle ->
                        _state.update { it.copy(listeningMine = null, partial = "") }
                }
            }
        }
    }

    private fun commit(text: String, fromMine: Boolean) {
        viewModelScope.launch {
            val s = _state.value
            val from = if (fromMine) s.mine else s.theirs
            val to = if (fromMine) s.theirs else s.mine
            val r = translator.translate(text, from)
            _state.update {
                it.copy(
                    lines = it.lines +
                        TalkLine(fromMine, text, r.text, from, to, r.online),
                )
            }
            // 唸的是譯文，講給對面聽
            speaker.say(r.text, to)
        }
    }

    override fun onCleared() {
        speech.release()
        speaker.release()
        translator.close()
    }

    private fun String.toLangOr(fallback: TalkLang): TalkLang =
        TalkLang.entries.firstOrNull { it.name == this } ?: fallback
}
