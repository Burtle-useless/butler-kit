package dev.butlerkit.app.voice

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 語言包的狀態。面對面講話前一定要先到 [Ready]，否則第一句會卡在下載上。 */
sealed interface Models {
    data object Missing : Models
    data object Downloading : Models
    data object Ready : Models
    data class Failed(val why: String) : Models
}

/** 翻譯結果。[online] 讓畫面能標明這句是誰翻的——準確度時好時壞而不說明會讓人困惑。 */
data class Translated(val text: String, val online: Boolean)

/**
 * 雙向翻譯：線上走 [GoogleTranslate]，不通才退回 ML Kit 的離線模型。
 *
 * 順序是刻意的。ML Kit 在非英文對非英文上會出歧義錯（「我日文不好」→
 * 「私の日本人は良くありません」），理由寫在 [GoogleTranslate] 裡。所以它的角色
 * 從「主要引擎」降成「沒網路時的退路」——譯得差但總比一句都翻不出來好。
 *
 * 語言包還是照舊預先下載：需要它的時機正是沒網路的時候，那時再下載就來不及了。
 *
 * 對話要兩個方向都能走，所以持有兩個 [Translator]（ML Kit 的 client 是單向的，
 * 一個 client 只認一組 source→target）。語言包本身是**全域共用**的，
 * 兩個方向合起來只需要下載兩個語言的模型，各約 30MB。
 *
 * **刻意不加 `requireWifi()`**：官方範例都寫它，但這個功能的使用場合就是在外面，
 * 強制 Wi-Fi 等於在最需要的時候永遠下載不了。改成畫面上明講耗用流量，讓他自己決定。
 *
 * [translate] 線上約 300ms、離線幾十毫秒，兩者都不需要進度回報。
 *
 * **[ChineseVariants] 只夾在離線那條路上**：ML Kit 只認簡體，而畫面要繁體、
 * 語音辨識出來的又是繁體。線上那條送 `zh-TW` 就直接拿到繁體，不必轉。
 */
class TalkTranslator(private val context: Context) {

    private val _models = MutableStateFlow<Models>(Models.Missing)
    val models: StateFlow<Models> = _models.asStateFlow()

    private var forward: Translator? = null   // mine → theirs
    private var backward: Translator? = null  // theirs → mine
    private var pair: Pair<TalkLang, TalkLang>? = null

    /**
     * 準備好這組語言的兩個方向，語言包沒下載就順便下載。
     * 換語言時再呼叫一次即可，同一組語言重複呼叫會直接返回。
     */
    suspend fun prepare(mine: TalkLang, theirs: TalkLang) {
        if (pair == mine to theirs && _models.value is Models.Ready) return
        close()
        pair = mine to theirs
        _models.value = Models.Downloading

        // 簡繁字典跟語言包無關，這裡順便載：兩邊都不是中文時它只是白花 200ms
        // 在 IO 執行緒上，比在第一句話中間才發現要載好。
        if (mine == TalkLang.ZH || theirs == TalkLang.ZH) {
            ChineseVariants.load(context)
        }

        val f = Translation.getClient(options(mine, theirs))
        val b = Translation.getClient(options(theirs, mine))
        forward = f
        backward = b

        val conditions = DownloadConditions.Builder().build()
        _models.value = try {
            f.downloadModelIfNeeded(conditions).await()
            b.downloadModelIfNeeded(conditions).await()
            Models.Ready
        } catch (e: Exception) {
            Log.w(TAG, "語言包下載失敗", e)
            Models.Failed(e.message ?: "語言包下載失敗")
        }
    }

    /**
     * 翻一句。[from] 必須是 [prepare] 給過的兩個語言之一，否則回原文
     * ——面對面對話時吐原文比吐空字串或拋錯有用得多。
     */
    suspend fun translate(text: String, from: TalkLang): Translated {
        val (mine, theirs) = pair ?: return Translated(text, false)
        val to = when (from) {
            mine -> theirs
            theirs -> mine
            else -> return Translated(text, false)
        }
        // 線上優先。離線模型錯得太明顯，只留著當沒網路時的退路
        GoogleTranslate.translate(text, from, to)?.let { return Translated(it, true) }
        return Translated(offline(text, from, to), false)
    }

    /** ML Kit 那條離線退路。它也翻不出來就回原文。 */
    private suspend fun offline(text: String, from: TalkLang, to: TalkLang): String {
        val client = (if (from == pair?.first) forward else backward) ?: return text
        // 再確認一次字典就緒：prepare 那邊是背景載的，第一句話有可能比它先到
        if (from == TalkLang.ZH || to == TalkLang.ZH) ChineseVariants.load(context)
        // 送進去的中文先轉簡體，ML Kit 的中文模型只吃這個
        val source = if (from == TalkLang.ZH) ChineseVariants.toSimplified(text) else text
        return try {
            val out = client.translate(source).await().orEmpty().ifBlank { text }
            if (to == TalkLang.ZH) ChineseVariants.toTraditional(out) else out
        } catch (e: Exception) {
            Log.w(TAG, "離線翻譯也失敗", e)
            text
        }
    }

    fun close() {
        forward?.close()
        backward?.close()
        forward = null
        backward = null
        pair = null
        _models.value = Models.Missing
    }

    private fun options(from: TalkLang, to: TalkLang): TranslatorOptions =
        TranslatorOptions.Builder()
            .setSourceLanguage(from.mlkit)
            .setTargetLanguage(to.mlkit)
            .build()

    private companion object {
        const val TAG = "butler-translate"
    }
}

/**
 * 把 GMS 的 [Task] 接成 coroutine。
 *
 * 刻意不引入 `kotlinx-coroutines-play-services` 只為了一個 `await()`：
 * 那個套件會連帶拉進整組 play-services 相依，而這裡要的就只有這五行。
 */
internal suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
