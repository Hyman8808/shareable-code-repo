package com.turing.thumbcard.data.dict

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 短语拆解后的单个词。
 *
 * 一个物品的英文名常常是**多词短语**（tea tray / pencil case / remote control），
 * 只查整串是查不到词义的，用户也就学不会「这个产品在英文里是怎么拼出来的」。
 * 这里为短语里的每个词各查一次英文词典，UI 逐词展示。
 */
data class PhraseWord(
    /** 词序，从 1 开始 */
    val order: Int,
    /** 词本身，原样（保留 T-shirt 之类的大小写与连字符） */
    val text: String,
    /** 美式音标，已带斜杠。词典只给英音时为 null —— **不要**用英音顶替 */
    val phoneticUs: String? = null,
    /** 英式音标，已带斜杠；与美音相同时为 null（省一行，也不让用户以为看错了） */
    val phoneticUk: String? = null,
    /** 美式发音 mp3 */
    val audioUs: String? = null,
    /** 英式发音 mp3 */
    val audioUk: String? = null,
    /** 词性，如 n / v / adj；词典没给就为 null */
    val pos: String? = null,
    /** 第一条释义，词砖上显示这一条 */
    val meaning: String? = null,
    /** 全部释义（含词性前缀），点开词砖看完整版 */
    val allMeanings: List<String> = emptyList(),
    /** 例句（已拼成「英文\n中文」） */
    val examples: List<String> = emptyList(),
    /** 词典是否收录；false 时词砖显示「暂未收录」，但词本身仍然展示 */
    val found: Boolean = false
) {
    /** 主音标：美音优先，仅用于标题这类单行位置；词砖里两个都要显示 */
    val phoneticPrimary: String?
        get() = phoneticUs ?: phoneticUk

    /** 该词是否任何音标都没有 */
    val hasPhonetic: Boolean
        get() = !phoneticUs.isNullOrBlank() || !phoneticUk.isNullOrBlank()

    /**
     * 取「核心义」，用于拼底部的直译行。
     *
     * 词典释义常带一串近义（`茶；茶叶；茶树`），直译行只要第一个义项，
     * 且限长，否则一行拼不下。
     */
    val coreMeaning: String?
        get() {
            val raw = meaning?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val first = raw.split('；', ';', '，', ',', '（', '(')
                .firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return first.takeIf { it.length <= 8 }
        }
}

/**
 * 把英文名拆成单个词并逐词查词典。
 *
 * 三条规则：
 *  1. **冠词照常出砖**（a / an / the）—— 只从「直译条」里剔除，不在拆解里删
 *  2. **保留连字符词**（T-shirt）与数字开头的（3D / 4K），查不到就显示「暂未收录」
 *  3. **有几个词就出几块砖**（[split] 绝不截断、绝不去重）；只有「查词典」有并发上限
 *     [MAX_LOOKUP] = 8，超出的词照样出砖，只是标「暂未收录」
 *
 * 只拆**英文名**。中文不需要拆：汉语词典本身就能查到整词，逐字拼释义已被证明「没有可读性」。
 *
 * 🔴 2026-10 修 bug：旧实现是「白名单式」拆词 —— token 必须整体匹配
 *    `[A-Za-z][A-Za-z\-']*` 才留下，否则**静默丢弃**，而界面上「N 个词」的徽章用的是
 *    丢弃**之前**的数量。于是用户会遇到「明明三个英文词，拆解卡里只有两块砖」。
 *    实测会踩的输入（都真实存在于识物结果里）：
 *      · `a potted plant`          → `a` 被当冠词丢掉（产品上可以理解，但必须与徽章口径一致）
 *      · `3D glasses`              → `3D` 首字符是数字，整词丢（注释写着「保留」，代码却在丢）
 *      · `green\u00A0plant`        → Java 的 `\s` **不匹配** NBSP(U+00A0)，两个词被粘成一坨后一起丢
 *      · `T-shirt’s`               → 弯引号 ’ 不在白名单里，整词丢
 *      · `potted plant (small)`    → 括号能 trim 掉，但带斜杠/竖线/顿号的 token 会整词丢
 *    现在改成「黑名单式」：**只要 token 里还有英文字母就出砖**，只在最后截断并记日志。
 *
 * 🔴 2026-10 二次修（用户实测仍只有 2 块砖）：
 *    · 去掉了 `distinctBy { lowercase() }` —— `plant / Plant` 是两块砖，序号能区分，
 *      去重等于**替用户丢词**；
 *    · 去掉 `take(MAX_WORDS)` —— 上限只约束「并发查词典」，不约束「出砖」。
 *      原来 `tea set for 4 people` 这类 5 词以上的名字会被截断，徽章和砖一起变少，
 *      用户以为功能坏了。
 */
object PhraseBreaker {

    private const val TAG = "PhraseBreaker"

    /**
     * 并发查词典的上限（**不是**出砖上限）。
     *
     * 每多查一个词就多一条 WebSocket 连接，8 条已经到体验边界；再多只影响
     * 「有没有释义」，不影响「词出不出现在页面上」。
     */
    private const val MAX_LOOKUP = 8

    /** 半角空白 + NBSP(00A0) / FIGURE SPACE(2007) / NARROW NBSP(202F)：识物结果里都出现过 */
    private val SPACES = Regex("[\\s\\u00A0\\u2007\\u202F]+")

    /** 词首尾容易粘的标点（云端识物回来的名字常带括号、斜杠、顿号） */
    private val TRIM_CHARS = charArrayOf(
        ',', '.', ';', ':', '!', '?', '"', '\'', '’', '(', ')', '[', ']',
        '/', '\\', '|', '·', '、', '“', '”'
    )

    /** 能不能拿去查词典：至少含一个英文字母（3D / 4K 含字母，照查） */
    private fun lookupable(token: String) = token.any { it in 'a'..'z' || it in 'A'..'Z' }

    /**
     * 只拆词，不查词典。UI 用它先判断要不要显示拆解区。
     *
     * 🔴 **砖数必须等于本函数的返回长度**：这里返回几个词，界面上就必须有几块砖。
     *    任何「悄悄少一个」的过滤（去重、截断、白名单）都会变成用户的可见 bug。
     */
    fun split(name: String?): List<String> {
        val raw = name?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val all = SPACES.split(raw)
            .map { it.trim(*TRIM_CHARS) }
            // 弯引号统一成直引号：`plant’s` / `T-shirt’s` 词典只认直引号
            .map { it.replace('’', '\'') }
            // 只剔除「连一个英文字母都没有」的碎片（纯标点、纯数字、纯中文）：
            // 这类 token 查英文字典无意义，中文名另有汉语词典卡负责。
            .filter { it.isNotEmpty() && lookupable(it) }

        Log.d(TAG, "split name=\"$raw\" -> $all (n=${all.size})")
        return all
    }

    /**
     * 逐词查询，**保证返回列表与入参等长、同序**。
     *
     * 并发查（每个词一条独立 WebSocket），因为接口「一连接一次请求」，
     * 串行查 3 个词要等 3 个往返，用户会明显感到卡。
     * 单个词失败**不影响**其它词：失败的词标 [PhraseWord.found] = false。
     *
     * 超 [MAX_LOOKUP] 的词不查（省连接），但仍然原样返回一块「暂未收录」的砖 ——
     * 页面上永远是「有几个词就有几块砖」。
     */
    suspend fun lookup(words: List<String>): List<PhraseWord> = coroutineScope {
        val head = words.take(MAX_LOOKUP).mapIndexed { index, word ->
            async(Dispatchers.IO) { toPhraseWord(index + 1, word) }
        }.awaitAll()

        if (words.size > MAX_LOOKUP) {
            Log.w(TAG, "lookup capped at $MAX_LOOKUP, rest rendered as missing: ${words.drop(MAX_LOOKUP)}")
        }
        val tail = words.drop(MAX_LOOKUP).mapIndexed { i, word ->
            PhraseWord(MAX_LOOKUP + i + 1, word)
        }
        head + tail
    }

    private suspend fun toPhraseWord(order: Int, text: String): PhraseWord {
        val result = runCatching {
            DictClient.query(text, listOf("en_word"))
        }.getOrNull() as? DictResult.Found
        val entry = result?.entry ?: return PhraseWord(order, text)

        // dict=others 是「未收录、只给机器翻译」：可以当释义用，但它的 pos 固定是「翻译」，不能显示
        val isMachineTranslation = entry.dict == "others"

        val all = entry.meanings.mapNotNull { m ->
            m.meaning.trim().takeIf { it.isNotBlank() }?.let { meaning ->
                val pos = m.pos?.trim()?.takeIf { it.isNotBlank() && !isMachineTranslation }
                if (pos == null) meaning else "$pos. $meaning"
            }
        }

        val first = entry.meanings.firstOrNull()
        val pos = first?.pos?.trim()?.takeIf { it.isNotBlank() && !isMachineTranslation }

        return PhraseWord(
            order = order,
            text = text,
            // 美/英各自独立取：旧版只留合并值，用户在词砖上永远看不到「美」，
            // 而美音恰恰是学发音时最常对照的那一条
            phoneticUs = entry.phoneticUsDisplay,
            phoneticUk = entry.phoneticUkDisplay,
            audioUs = entry.audioUs?.takeIf { it.isNotBlank() },
            audioUk = entry.audioUk?.takeIf { it.isNotBlank() },
            pos = pos,
            meaning = first?.meaning?.trim()?.takeIf { it.isNotBlank() },
            allMeanings = all,
            examples = entry.examples.take(2).mapNotNull { ex ->
                val en = ex.en?.trim()?.takeIf { it.isNotBlank() }
                val cn = ex.cn?.trim()?.takeIf { it.isNotBlank() }
                when {
                    en != null && cn != null -> "$en\n$cn"
                    en != null -> en
                    cn != null -> cn
                    else -> null
                }
            },
            found = all.isNotEmpty()
        )
    }
}
