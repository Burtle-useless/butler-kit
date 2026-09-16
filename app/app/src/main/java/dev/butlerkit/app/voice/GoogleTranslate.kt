package dev.butlerkit.app.voice

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * 線上翻譯，打 Google 翻譯網頁版在用的那支端點。
 *
 * 為什麼不只用 ML Kit：ML Kit 的非英文對非英文是走英文樞紐，歧義在中間那層被固化。
 * 實機上「我日文不好」被翻成「私の日本人は良くありません」——中文的「日文」翻成
 * 英文 `Japanese`，再翻日文時挑了「日本人」而不是「日本語」。這不是字典能補的。
 * 同一句走這裡出來是「私の日本語は上手ではありません」。
 *
 * 兩個要知道的事實：
 *  - **這不是公開 API**，是 gtx client。Google 改了就會壞，所以呼叫端一定要有退路
 *    （[TalkTranslator] 失敗就退回 ML Kit），不能讓翻譯功能整個依賴它。
 *  - 它**也**走英文樞紐（回傳裡看得到 `zh_en` 接 `en_ja` 兩跳），只是模型好得多。
 *
 * 中文用 `zh-TW` 送，它自己會多走一跳 `zh_zh-hant` 回繁體，所以這條路**不接**
 * [ChineseVariants]。那份字典只服務 ML Kit 那條離線退路。
 */
object GoogleTranslate {

    /**
     * 翻一句，失敗回 null 讓呼叫端去走退路。
     *
     * 逾時刻意壓到 4 秒：面對面講話等不了更久，寧可退回 ML Kit 拿一個差一點但立刻
     * 到手的結果。正常回應大約 300ms。
     */
    suspend fun translate(text: String, from: TalkLang, to: TalkLang): String? =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext null
            try {
                val url = ENDPOINT.toHttpUrl().newBuilder()
                    .addQueryParameter("client", "gtx")
                    .addQueryParameter("sl", from.gTag)
                    .addQueryParameter("tl", to.gTag)
                    .addQueryParameter("dt", "t")
                    .addQueryParameter("q", text)
                    .build()
                // 不帶 User-Agent 會被擋，OkHttp 的預設值不夠像瀏覽器
                val req = Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .build()
                client.newCall(req).execute().use { res ->
                    val body = res.body?.string()
                    if (!res.isSuccessful || body.isNullOrBlank()) {
                        Log.w(TAG, "端點回 ${res.code}")
                        return@withContext null
                    }
                    parse(body)
                }
            } catch (e: Exception) {
                // 沒網路是最常見的原因，不需要驚動使用者，安靜退回離線那條路
                Log.w(TAG, "線上翻譯不通", e)
                null
            }
        }

    /**
     * 從回傳裡撈譯文。
     *
     * 格式是巢狀且不具名的：`[[["譯文","原文",…],["第二段","…",…]],null,"zh-TW",…]`。
     * 長句子會被切成好幾段，要全部串起來——只取第一段的話後半句會不見。
     */
    private fun parse(body: String): String? {
        val segments = JSONArray(body).optJSONArray(0) ?: return null
        val out = StringBuilder()
        for (i in 0 until segments.length()) {
            segments.optJSONArray(i)?.optString(0)?.let(out::append)
        }
        return out.toString().ifBlank { null }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0"
    private const val TAG = "butler-gtx"
}
