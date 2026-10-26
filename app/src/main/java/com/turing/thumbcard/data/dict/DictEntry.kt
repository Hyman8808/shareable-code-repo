package com.turing.thumbcard.data.dict

/**
 * 词典查询结果的统一视图模型。
 *
 * 图灵词典返回的类型非常多（汉字/成语/汉词/英文单词/英文短语/人名/古诗…），
 * 字段命名各不相同（英文单词用 phoneticUs，汉词用 polyphone[].pinyin）。
 * 这里**归一化成前端唯一需要消费的结构**，UI 层不必关心 dict 类型差异。
 */
data class DictEntry(
    /** 命中的词典类型：en_word / cn_word / cn_char / cn_idiom / en_phrase / others */
    val dict: String,
    /** 词条本身 */
    val word: String,
    /** 音标（英文）：美音优先，无则英音。已去掉首尾斜杠，展示时由 [phoneticDisplay] 补齐 */
    val phonetic: String? = null,
    /** 美式音标原始值（词典只给了英音时为 null）。学习用途要把美/英分开显示，不能只留合并值 */
    val phoneticUs: String? = null,
    /** 英式音标（可选，用于并排展示） */
    val phoneticUk: String? = null,
    /** 拼音（中文） */
    val pinyin: String? = null,
    /** 词性 + 释义；中文的 pos 为 null */
    val meanings: List<Meaning> = emptyList(),
    /** 例句 */
    val examples: List<Example> = emptyList(),
    /** 同义词 */
    val synonyms: List<String> = emptyList(),
    /** 反义词 */
    val antonyms: List<String> = emptyList(),
    /** 常用短语 / 词汇搭配 */
    val phrases: List<Example> = emptyList(),
    /** 词形变化（过去式、复数等），仅英文单词有 */
    val forms: List<Pair<String, String>> = emptyList(),

    // ---------- 以下仅汉字 / 汉词有 ----------
    /** 部首，如「木」 */
    val radical: String? = null,
    /** 结构，如「左右结构」 */
    val structure: String? = null,
    /** 笔画数 */
    val strokes: String? = null,
    /** 组词，如「杯子/酒杯/奖杯」 */
    val combineWords: List<String> = emptyList(),
    /** 中文音频（汉字/词语发音） */
    val audioUrl: String? = null,

    /** 美式发音 mp3（en_word 的 phoneticUsAudio） */
    val audioUs: String? = null,
    /** 英式发音 mp3（en_word 的 phoneticEnAudio） */
    val audioUk: String? = null,

    /** 是否有可展示内容 */
    val isEmpty: Boolean = false
) {
    data class Meaning(val pos: String?, val meaning: String)
    data class Example(val en: String?, val cn: String?)

    /** 带斜杠的音标展示，如 `/ˈkɑːtn/`；无值返回 null */
    val phoneticDisplay: String?
        get() = fmt(phonetic)

    /** 美式音标展示；词典没给美音（只有英音）返回 null，**不要**拿英音顶替 */
    val phoneticUsDisplay: String?
        get() = fmt(phoneticUs)

    /**
     * 英式音标展示。
     *
     * **不做「与美音相同就省略」的去重**：英美同音时两行都写出来，
     * 用户看到的是「两种口音一致」；省掉一行反而像「英音丢了」。
     */
    val phoneticUkDisplay: String?
        get() = fmt(phoneticUk)

    private fun fmt(raw: String?): String? {
        val p = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (p.startsWith("/") || p.startsWith("[")) return p
        return "/$p/"
    }
}
