package dev.butlerkit.app.voice

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 聽的過程要回報給畫面的四種狀態。 */
sealed interface Heard {
    /** 沒在聽 */
    data object Idle : Heard

    /** 正在聽；[partial] 是還沒定稿的片段，可能會被後面的結果改寫 */
    data class Listening(val partial: String) : Heard

    /** 這一句聽完了 */
    data class Final(val text: String) : Heard

    /** [why] 已經是給人看的中文說明 */
    data class Failed(val why: String) : Heard
}

/**
 * 麥克風轉文字，包一層 [SpeechRecognizer]。
 *
 * 三件踩得到的事，都在這裡處理掉：
 *
 * 1. **必須在主執行緒建立與呼叫**。`SpeechRecognizer` 沒有自己的 handler，
 *    在背景執行緒 `createSpeechRecognizer` 會直接拋 RuntimeException。
 * 2. **離線引擎不一定裝了，而且各家回的錯誤碼不一樣**。
 *    [RecognizerIntent.EXTRA_PREFER_OFFLINE] 只是「偏好」，該語言的離線包沒下載時，
 *    有的 ROM 回 ERROR_LANGUAGE_UNAVAILABLE，有的回 ERROR_SERVER_DISCONNECTED、
 *    ERROR_NETWORK，甚至 ERROR_CLIENT。所以重試條件不是列舉錯誤碼，而是看時機：
 *    **只要這一輪還沒收到任何聲音就失敗，就當成引擎起不來，不帶旗標重試一次**。
 *    （列舉碼的版本會讓使用者一按按鈕就看到紅字，人還沒開口。）
 * 3. **「沒聽到聲音」不是錯誤**。ERROR_NO_MATCH 與 ERROR_SPEECH_TIMEOUT 在使用者
 *    按了按鈕又沒開口時每次都會來，跳錯誤提示只會讓畫面一直閃紅字，當成 Idle 收掉。
 * 4. **換一輪不能立刻重來**。[destroy] 之後 Google 的辨識服務要走完自己的 onDestroy，
 *    這段期間送進去的 `startListening` 會被立刻回一個 ERROR_SERVER_DISCONNECTED(11)，
 *    但那個請求其實**有被受理**——實機上的症狀是畫面跳紅字說「連不上網」，麥克風卻
 *    還在聽，而且過幾秒真的辨識出來了。所以重試一律走 [relisten] 隔一段時間再起。
 *
 * 生命週期由呼叫端負責：畫面收掉時一定要 [release]，不然那條 audio session 會留著。
 */
class SpeechInput(private val context: Context) {

    private val _state = MutableStateFlow<Heard>(Heard.Idle)
    val state: StateFlow<Heard> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    /** 目前這一輪聽的語言，重試時要用同一個 */
    private var lang: TalkLang? = null

    private val handler: Handler = Handler(Looper.getMainLooper())

    /** 這一輪是否已經降級成「允許連線辨識」，避免無限重試 */
    private var retriedOnline = false

    /** 這一輪是否已經因為服務端把連線切掉而重試過，避免無限重試 */
    private var retriedDisconnected = false

    /** 正在等 [relisten] 的空窗期。這期間 [recognizer] 已經沒用了 */
    private var pendingRetry = false

    /**
     * 已知沒有離線辨識包的語言，同一次 App 生命週期內不再白試那一輪。
     *
     * 不只是省時間：離線那一輪失敗會觸發辨識服務重啟，緊接著的連線那一輪就會撞上
     * 上面第 4 點那個假斷線。少撞一次就少一次出錯機會。
     */
    private val noOfflinePack: MutableSet<TalkLang> = mutableSetOf()

    /**
     * 這一輪有沒有真的收到聲音（引擎說開始說話，或已經吐出片段）。
     * 用來分辨「引擎起不來」與「人講了但辨識不出來」——兩者的處理方式相反。
     */
    private var heardSpeech = false

    /** 手機上到底有沒有語音辨識引擎；沒有的話整個功能免談 */
    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * 開始聽一句話。同一時間只允許一輪，重複呼叫會先取消前一輪。
     * 必須在主執行緒呼叫。
     */
    fun start(lang: TalkLang) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SpeechInput.start 必須在主執行緒呼叫"
        }
        if (!available()) {
            _state.value = Heard.Failed("這支手機沒有語音辨識引擎")
            return
        }
        this.lang = lang
        retriedOnline = false
        retriedDisconnected = false
        // 已經知道這個語言沒有離線包就直接走連線，別再白撞一輪
        val tryOffline = lang !in noOfflinePack
        if (!tryOffline) retriedOnline = true
        listen(lang, offlineOnly = tryOffline)
    }

    /**
     * 停止聽，但把已經收到的音處理完（`stopListening` 而非 `cancel`）——
     * 使用者鬆手時最後半個字通常還在緩衝區裡。
     */
    fun stop() {
        // 還在等重試的空窗裡鬆手＝根本還沒開始講，取消重試直接收掉。
        // 這時 recognizer 已經 destroy 過了，stopListening 不會有任何效果。
        if (pendingRetry) {
            pendingRetry = false
            handler.removeCallbacksAndMessages(null)
            _state.value = Heard.Idle
            return
        }
        recognizer?.stopListening()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        pendingRetry = false
        recognizer?.destroy()
        recognizer = null
        _state.value = Heard.Idle
    }

    /**
     * 隔一小段時間再起一輪連線辨識。見類別說明第 4 點——立刻重來會收到假的斷線錯誤。
     * 空窗期間狀態維持在 [Heard.Listening]，畫面不要閃。
     */
    private fun relisten(lang: TalkLang) {
        pendingRetry = true
        handler.postDelayed({
            pendingRetry = false
            listen(lang, offlineOnly = false)
        }, RETRY_DELAY_MS)
    }

    private fun listen(lang: TalkLang, offlineOnly: Boolean) {
        heardSpeech = false
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(Listener(offlineOnly))
        _state.value = Heard.Listening("")
        r.startListening(intentFor(lang, offlineOnly))
    }

    private fun intentFor(lang: TalkLang, offlineOnly: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.speechTag)
            // 沿路把片段吐回來，畫面才能邊講邊出字
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (offlineOnly) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

    private inner class Listener(private val offlineOnly: Boolean) : RecognitionListener {

        override fun onPartialResults(bundle: android.os.Bundle?) {
            val text = bundle.firstResult() ?: return
            if (text.isNotBlank()) {
                heardSpeech = true
                _state.value = Heard.Listening(text)
            }
        }

        override fun onResults(bundle: android.os.Bundle?) {
            val text = bundle.firstResult()?.trim().orEmpty()
            _state.value = if (text.isEmpty()) Heard.Idle else Heard.Final(text)
        }

        override fun onError(code: Int) {
            // 人還沒開口就掛掉＝引擎起不來，不是講得不清楚。離線模式一律降級重試一次。
            // 這裡刻意不看錯誤碼：離線包沒裝時各家 ROM 回的碼不一樣，列舉一定會漏。
            if (offlineOnly && !heardSpeech && !retriedOnline) {
                Log.w(TAG, "離線辨識起不來 code=$code，改用連線重試")
                retriedOnline = true
                lang?.let {
                    noOfflinePack += it
                    relisten(it)
                }
                return
            }
            // 連線那一輪還沒收到一點聲音就被切掉，幾乎都是上一輪的辨識服務還在關、
            // 把這個請求一起帶走了（見類別說明第 4 點）。等它關完再試一次，不要跳紅字。
            if (!offlineOnly && !heardSpeech && !retriedDisconnected &&
                (code == ERROR_SERVER_DISCONNECTED || code == SpeechRecognizer.ERROR_CLIENT)
            ) {
                Log.w(TAG, "連線辨識被服務端切掉 code=$code，等一下重試")
                retriedDisconnected = true
                lang?.let { relisten(it) }
                return
            }
            when (code) {
                // 使用者按了按鈕但沒開口，這是常態不是錯誤
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> _state.value = Heard.Idle

                else -> {
                    Log.w(TAG, "辨識失敗 code=$code offline=$offlineOnly heard=$heardSpeech")
                    _state.value = Heard.Failed(explain(code))
                }
            }
        }

        override fun onReadyForSpeech(params: android.os.Bundle?) = Unit

        override fun onBeginningOfSpeech() {
            heardSpeech = true
        }

        override fun onRmsChanged(rms: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(type: Int, params: android.os.Bundle?) = Unit
    }

    private fun android.os.Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    /** 錯誤訊息一律指名是哪個語言出問題——只寫「辨識失敗」的話沒人知道要去下載什麼。 */
    private fun explain(code: Int): String {
        val name = lang?.label ?: "這個語言"
        // 「點這行」指的是畫面上這條訊息本身可點，會直接開語音設定
        val download = "點這行去下載 $name 的語音包（Google 語音 → 離線語音辨識）"
        return when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "錄音出問題，可能有別的 App 佔著麥克風"
            SpeechRecognizer.ERROR_CLIENT -> "辨識服務被中斷，再按一次"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "沒有麥克風權限"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "辨識服務忙碌中，等一下再按"
            SpeechRecognizer.ERROR_SERVER -> "辨識伺服器回錯，再按一次"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            -> "手機的語音辨識不支援$name。$download。"
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            -> "$name 的離線辨識包沒裝，連線辨識又連不上網。$download。"
            // 11 是「辨識服務把連線切掉」，不是沒網路。寫成沒網路會害人去查 Wi-Fi，
            // 而真正的原因通常是服務剛被重啟（重試兩次還是這個碼才會走到這裡）。
            ERROR_SERVER_DISCONNECTED -> "辨識服務中斷了，再按一次"
            ERROR_TOO_MANY_REQUESTS -> "辨識服務被叫太多次了，等幾秒再按"
            ERROR_CANNOT_CHECK_SUPPORT -> "手機沒回報支不支援$name，再按一次試試"
            else ->
                if (heardSpeech) "辨識失敗（code $code）"
                // 連聲音都還沒收到就掛，最常見的原因就是辨識包沒裝
                else "還沒收到聲音就失敗了（code $code）。$name 的辨識包可能沒裝，$download。"
        }
    }

    private companion object {
        const val TAG = "butler-speech"

        /**
         * 重試前等多久。250ms 是實測值：辨識服務關掉舊實例大約要 15ms，留一個數量級的
         * 餘裕；而人按下按鈕到開口通常超過半秒，這段延遲聽不出來。
         */
        const val RETRY_DELAY_MS = 250L

        // API 33 才加進 SpeechRecognizer 的錯誤碼。minSdk 26 直接引常數會被 lint 擋，
        // 而這些值只是 int 常數、舊版收不到也不會壞，所以寫死數字。
        const val ERROR_SERVER_DISCONNECTED = 11
        const val ERROR_TOO_MANY_REQUESTS = 15
        const val ERROR_CANNOT_CHECK_SUPPORT = 14
    }
}
