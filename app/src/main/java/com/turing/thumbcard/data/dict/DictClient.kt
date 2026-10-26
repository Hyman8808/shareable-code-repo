package com.turing.thumbcard.data.dict

import android.util.Log
import com.turing.thumbcard.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 查词结果。
 *
 * **必须区分「没查到」和「查失败」**：
 *  - 没查到（服务端 answering，但该词没收录）→ UI 直接隐藏该区块，不给用户看错误
 *  - 网络/超时失败 → UI 保留重试入口（这是可恢复的）
 */
sealed class DictResult {
    data class Found(val entry: DictEntry) : DictResult()
    object NotFound : DictResult()
    data class Failed(val reason: String) : DictResult()
}

/**
 * 图灵在线词典客户端（TuringOS-Retrieval-V2.0）。
 *
 * 与拍照记录接口不同，词典接口是 **WebSocket 长连接**（`ws://ws-api.turingapi.com/api/v2`）。
 *
 * 协议为「一连接一次请求」，服务端依次回：
 *   `210 参数初始化成功` → `220 参数上传完成` → `200 success`(含 nlpResponse) → done=true
 * 因此必须等 done=true 才关闭连接，不能收到第一帧就走。
 *
 * ⚠️ data 字段用**明文**发送（实测服务端不接受文档描述的 AES 密文，见 [DictRequest]），
 *    因此本模块**不依赖 Secret**。
 *
 * ⚠️ 兼容性：全部异常内部吞掉并返回 null，绝不抛给 UI（与项目既有容错约定一致）。
 */
object DictClient {

    private const val TAG = "DictClient"
    private const val TIMEOUT_SEC = 8L

    private val http = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    /**
     * 查询一个词。
     *
     * @param content 待查词（英文名优先，查不到再传中文名）
     * @return 归一化后的词条；失败或无结果返回 null
     */
    suspend fun lookup(
        content: String,
        dicts: List<String>? = null
    ): DictEntry? = (query(content, dicts) as? DictResult.Found)?.entry

    /**
     * 查询一个词，并**区分**「没收录」与「查失败」。
     * UI 据此决定是隐藏区块还是给重试入口。
     *
     * **先查本地缓存**：查过的词不再连云端（详见 [DictCache]）。
     * 只有网络失败不写缓存，保证下次仍可重试。
     */
    suspend fun query(
        content: String,
        dicts: List<String>? = null
    ): DictResult {
        if (content.isBlank()) return DictResult.NotFound
        val key = DictCache.keyOf(content, dicts)

        // 1) 命中本地缓存 → 直接用
        val cached = withContext(Dispatchers.IO) { DictCache.read(key) }
        if (cached != null) {
            Log.d(TAG, "cache hit: [$content] hit=${cached.entry != null}")
            return if (cached.entry != null) DictResult.Found(cached.entry) else DictResult.NotFound
        }

        // 2) 未命中 → 走网络
        val res = queryRemote(content, dicts)

        // 3) 查到 / 明确未收录都落盘；网络失败不落盘，以便重试
        when (res) {
            is DictResult.Found -> withContext(Dispatchers.IO) { DictCache.write(key, res.entry) }
            is DictResult.NotFound -> withContext(Dispatchers.IO) { DictCache.write(key, null) }
            is DictResult.Failed -> Unit
        }
        return res
    }

    private suspend fun queryRemote(
        content: String,
        dicts: List<String>?
    ): DictResult = suspendCoroutine { cont ->
        if (content.isBlank()) {
            cont.resume(DictResult.NotFound); return@suspendCoroutine
        }
        val latch = CountDownLatch(1)
        var result: DictEntry? = null
        var error: String? = null
        var finished = false

        val body = runCatching {
            if (dicts == null) DictRequest.build(content.trim())
            else DictRequest.build(content.trim(), dicts)
        }.getOrElse {
            Log.e(TAG, "build request failed", it)
            cont.resume(DictResult.Failed("请求构造失败"))
            return@suspendCoroutine
        }

        val request = Request.Builder()
            .url(BuildConfig.DICT_WS_URL)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(body)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    val code = json.optInt("code", -1)
                    val done = json.optBoolean("done", true)

                    if (result == null && code == 200) {
                        result = parse(json)
                    } else if (code != 200 && code != 210 && code != 220) {
                        Log.w(TAG, "dict code=$code msg=${json.optString("message")}")
                        error = "词典服务返回 $code"
                    }

                    if (done) {
                        finished = true
                        latch.countDown()
                        webSocket.close(1000, "done")
                    }
                }.onFailure {
                    Log.w(TAG, "parse failed", it)
                    if (!finished) { finished = true; latch.countDown() }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: ${t.javaClass.simpleName} ${t.message}")
                error = "网络异常"
                if (!finished) { finished = true; latch.countDown() }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!finished) { finished = true; latch.countDown() }
            }
        }

        val ws = http.newWebSocket(request, listener)

        // 在 IO 线程等待，避免阻塞主线程
        Thread {
            val ok = runCatching { latch.await(TIMEOUT_SEC, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!ok) {
                Log.w(TAG, "dict timeout for [$content]")
                runCatching { ws.cancel() }
                error = "查询超时"
            }
            cont.resume(
                when {
                    result != null -> DictResult.Found(result!!)
                    error != null -> DictResult.Failed(error!!)
                    else -> DictResult.NotFound
                }
            )
        }.apply { isDaemon = true }.start()
    }

    /**
     * 逐字取拼音（**只取拼音，不拼释义**）。
     *
     * 用途：像「立白洗洁精」这种词，词典整体未收录（返回 others），拿不到词级拼音，
     * 但每个汉字都能查到读音。此时按字序并发查询，拼接成 `lì bái xǐ jié jīng`。
     *
     * ⚠️ 早前版本写成 `take(4)`，5 字以上的词**最后一个字的拼音会丢**（真机反馈的 bug），
     *    现改为默认取前 8 字且并发查询，既完整又不必串行等待。
     */
    suspend fun pinyinByCharacters(word: String, maxChars: Int = 8): String? = coroutineScope {
        val chars = word.filter { it.code in 0x4E00..0x9FFF }.take(maxChars)
        if (chars.isEmpty()) return@coroutineScope null

        val parts = chars.map { c ->
            async(Dispatchers.IO) {
                val r = query(c.toString(), listOf("cn_char"))
                (r as? DictResult.Found)?.entry?.pinyin
            }
        }.awaitAll()

        parts.mapNotNull { it?.trim()?.takeIf { p -> p.isNotBlank() } }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ")
    }

    // ==================== 解析 ====================

    private fun parse(root: JSONObject): DictEntry? {
        val params = root.optJSONObject("nlpResponse")
            ?.optJSONObject("intent")
            ?.optJSONObject("parameters")
            ?: return null
        val result = params.optJSONObject("result") ?: return null
        val queried = params.optString("content")

        val dict = result.optString("dict")
        if (dict.isBlank()) return null

        val infoEl = result.opt("info")

        // 未命中标准词典：服务端返回 others，只带中文翻译（如「纸箱」）
        if (dict == "others") {
            val o = infoEl as? JSONObject ?: return null
            val w = o.optString("word").ifBlank { queried }
            val tr = o.optString("translation").takeIf { it.isNotBlank() }
            if (tr == null) return null
            return DictEntry(
                dict = "others",
                word = w,
                meanings = listOf(DictEntry.Meaning("翻译", tr))
            )
        }

        // 图灵自研词典的 info 包了一层 { dict, info: [...] }，标准词典直接是对象
        val info = when (infoEl) {
            is JSONObject -> infoEl.optJSONObject("info") ?: infoEl
            else -> infoEl as? JSONObject ?: return null
        }
        // 自研词典 info 内是数组
        if (infoEl is JSONObject && infoEl.opt("info") is JSONArray) {
            val arr = infoEl.optJSONArray("info") ?: return null
            if (arr.length() == 0) return null
            return parseArrayItem(dict, arr.optJSONObject(0) ?: return null)
        }

        return when (dict) {
            "en_word" -> parseEnWord(info)
            "cn_word" -> parseCnWord(info)
            "cn_char" -> parseCnChar(info)
            "cn_idiom" -> parseCnIdiom(info)
            "en_phrase" -> parseEnPhrase(info)
            else -> null
        }
    }

    private fun parseEnWord(info: JSONObject): DictEntry? {
        val word = info.optString("word")
        if (word.isBlank()) return null

        val meanings = ArrayList<DictEntry.Meaning>()
        info.optJSONArray("explanations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pos = o.optString("pos").takeIf { it.isNotBlank() }
                val m = o.optString("meaning")
                if (m.isNotBlank()) meanings += DictEntry.Meaning(pos, m)
            }
        }

        val examples = ArrayList<DictEntry.Example>()
        info.optJSONArray("examples")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 3)) {
                val o = arr.optJSONObject(i) ?: continue
                examples += DictEntry.Example(
                    o.optString("en").takeIf { it.isNotBlank() },
                    o.optString("cn").takeIf { it.isNotBlank() }
                )
            }
        }

        val phrases = ArrayList<DictEntry.Example>()
        info.optJSONArray("vocabularyGroup")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 5)) {
                val o = arr.optJSONObject(i) ?: continue
                phrases += DictEntry.Example(
                    o.optString("en").takeIf { it.isNotBlank() },
                    o.optString("cn").takeIf { it.isNotBlank() }
                )
            }
        }

        val forms = ArrayList<Pair<String, String>>()
        fun addForm(label: String, key: String) {
            val v = info.optString(key)
            if (v.isNotBlank()) forms += label to v
        }
        addForm("复数", "nounPlurals")
        addForm("第三人称单数", "thirdSingular")
        addForm("过去式", "preterit")
        addForm("过去分词", "pastParticiple")
        addForm("现在分词", "presentParticiple")
        addForm("比较级", "comparativeDegree")
        addForm("最高级", "superlativeDegree")

        return DictEntry(
            dict = "en_word",
            word = word,
            phonetic = info.optString("phoneticUs").ifBlank { info.optString("phoneticEn") }
                .takeIf { it.isNotBlank() },
            // 美/英分开存：合并值只够「显示一行」，而学习场景必须两行都在
            phoneticUs = info.optString("phoneticUs").takeIf { it.isNotBlank() },
            phoneticUk = info.optString("phoneticEn").takeIf { it.isNotBlank() },
            audioUrl = info.optString("wordAudio").takeIf { it.isNotBlank() },
            // 美音 / 英音各有独立 mp3，详情页「音标 + 喇叭」用它试听
            audioUs = info.optString("phoneticUsAudio").takeIf { it.isNotBlank() },
            audioUk = info.optString("phoneticEnAudio").takeIf { it.isNotBlank() },
            meanings = meanings,
            examples = examples,
            synonyms = enArrayToStrings(info.optJSONArray("synonyms")),
            antonyms = enArrayToStrings(info.optJSONArray("antonyms")),
            phrases = phrases,
            forms = forms,
            isEmpty = meanings.isEmpty()
        )
    }

    private fun parseCnWord(info: JSONObject): DictEntry? {
        val word = info.optString("word")
        if (word.isBlank()) return null
        val poly = info.optJSONArray("polyphone")?.optJSONObject(0)

        val meanings = ArrayList<DictEntry.Meaning>()
        poly?.optJSONArray("explanations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotBlank()) meanings += DictEntry.Meaning(null, s)
            }
        }

        val examples = ArrayList<DictEntry.Example>()
        poly?.optJSONArray("examples")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 3)) {
                val o = arr.optJSONObject(i) ?: continue
                examples += DictEntry.Example(
                    o.optString("en").takeIf { it.isNotBlank() },
                    o.optString("cn").takeIf { it.isNotBlank() }
                )
            }
        }

        return DictEntry(
            dict = "cn_word",
            word = word,
            pinyin = poly?.optString("pinyin")?.takeIf { it.isNotBlank() },
            meanings = meanings,
            examples = examples,
            synonyms = strArray(poly?.optJSONArray("synonyms")),
            antonyms = strArray(poly?.optJSONArray("antonyms")),
            audioUrl = poly?.optString("audio")?.takeIf { it.isNotBlank() }
                ?: info.optString("wordAudio").takeIf { it.isNotBlank() },
            isEmpty = meanings.isEmpty() && examples.isEmpty()
        )
    }

    private fun parseCnChar(info: JSONObject): DictEntry? {
        val word = info.optString("word")
        if (word.isBlank()) return null
        val poly = info.optJSONArray("polyphone")?.optJSONObject(0)

        val meanings = ArrayList<DictEntry.Meaning>()
        poly?.optJSONArray("explanations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotBlank()) meanings += DictEntry.Meaning(null, s)
            }
        }

        // 组词（combineWords: [{type:start|end, word:"杯子"}]）
        val combine = ArrayList<String>()
        poly?.optJSONArray("combineWords")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 8)) {
                val o = arr.optJSONObject(i) ?: continue
                val w = o.optString("word")
                if (w.isNotBlank()) combine += w
            }
        }

        return DictEntry(
            dict = "cn_char",
            word = word,
            pinyin = poly?.optString("pinyin")?.takeIf { it.isNotBlank() },
            meanings = meanings,
            synonyms = strArray(info.optJSONArray("synonyms")),
            antonyms = strArray(info.optJSONArray("antonyms")),
            radical = info.optString("radicalsText").ifBlank { info.optString("radicals") }
                .takeIf { it.isNotBlank() },
            structure = info.optString("structure").takeIf { it.isNotBlank() },
            strokes = info.optString("strokesNumText").ifBlank { info.optString("strokesNum") }
                .takeIf { it.isNotBlank() },
            combineWords = combine,
            audioUrl = poly?.optString("audio")?.takeIf { it.isNotBlank() }
                ?: info.optString("wordAudio").takeIf { it.isNotBlank() },
            isEmpty = meanings.isEmpty()
        )
    }

    private fun parseCnIdiom(info: JSONObject): DictEntry? {
        val word = info.optString("word")
        if (word.isBlank()) return null
        val meanings = ArrayList<DictEntry.Meaning>()
        info.optJSONArray("explanations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotBlank()) meanings += DictEntry.Meaning(null, s.trim())
            }
        }
        info.optString("derivation").takeIf { it.isNotBlank() }?.let {
            meanings += DictEntry.Meaning("出处", it)
        }

        val examples = ArrayList<DictEntry.Example>()
        info.optJSONArray("examples")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 2)) {
                val o = arr.optJSONObject(i) ?: continue
                examples += DictEntry.Example(
                    o.optString("en").takeIf { it.isNotBlank() },
                    o.optString("cn").takeIf { it.isNotBlank() }
                )
            }
        }

        return DictEntry(
            dict = "cn_idiom",
            word = word,
            pinyin = info.optString("pinyin").takeIf { it.isNotBlank() },
            meanings = meanings,
            examples = examples,
            synonyms = strArray(info.optJSONArray("synonyms")),
            antonyms = strArray(info.optJSONArray("antonyms")),
            isEmpty = meanings.isEmpty()
        )
    }

    private fun parseEnPhrase(info: JSONObject): DictEntry? {
        val word = info.optString("phrase")
        if (word.isBlank()) return null
        val meanings = ArrayList<DictEntry.Meaning>()
        info.optJSONArray("explanations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotBlank()) meanings += DictEntry.Meaning(null, s)
            }
        }
        return DictEntry(dict = "en_phrase", word = word, meanings = meanings, isEmpty = meanings.isEmpty())
    }

    /** 图灵自研词典（dictType=turingown）返回结构：[{dict, info:[{...}]}] */
    private fun parseArrayItem(dict: String, item: JSONObject): DictEntry? {
        return when (dict) {
            "cn_char" -> DictEntry(
                dict = dict,
                word = item.optString("hzName"),
                pinyin = item.optString("pinyin").takeIf { it.isNotBlank() },
                meanings = listOfNotNull(
                    item.optString("jieShi").takeIf { it.isNotBlank() }
                        ?.let { DictEntry.Meaning(null, it) }
                ),
                isEmpty = item.optString("jieShi").isBlank()
            )
            "cn_word" -> DictEntry(
                dict = dict,
                word = item.optString("ciYu"),
                pinyin = item.optString("pinYin").takeIf { it.isNotBlank() },
                meanings = listOfNotNull(
                    item.optString("jieShi").takeIf { it.isNotBlank() }
                        ?.let { DictEntry.Meaning(null, it) }
                ),
                isEmpty = item.optString("jieShi").isBlank()
            )
            else -> null
        }
    }

    // ==================== 小工具 ====================

    private fun strArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until minOf(arr.length(), 8)) {
            val s = arr.optString(i)
            if (s.isNotBlank()) out += s
        }
        return out
    }

    private fun enArrayToStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until minOf(arr.length(), 8)) {
            val o = arr.optJSONObject(i) ?: continue
            val en = o.optString("en")
            val cn = o.optString("cn")
            when {
                en.isNotBlank() && cn.isNotBlank() -> out += "$en（$cn）"
                en.isNotBlank() -> out += en
                cn.isNotBlank() -> out += cn
            }
        }
        return out
    }
}
