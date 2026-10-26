package com.turing.thumbcard.data.i18n

/**
 * 译名服务契约。
 *
 * 后端接口就绪后，实现本接口并在 [NameResolver] 里挂上即可，UI 无需改动。
 *
 * ============ 给后端的建议接口（与 PRD 第 12 章一致）============
 *
 * 方案 A（推荐，批量）：随记录列表一起下发
 *   GET /api/record/list?...&lang=ja
 *   → 每条记录新增字段 name_localized（该语言名）与 name_localized_lang（实际语言）
 *   优点：一次请求搞定，列表页无额外往返；缺点：切换语言要重拉列表。
 *
 * 方案 B（按需）：独立翻译接口
 *   POST /api/name/translate
 *   body: { "lang":"ja", "items":[{"id":44,"source":"立白洗洁精"}, ...] }
 *   resp: { "code":200, "data":{ "44":{"text":"..."} , "45":{"text":null} } }
 *   优点：不重拉列表，只在切换语言时补一次；缺点：多一次往返。
 *
 * 两种方案 App 都已兼容：[NameResolver] 优先读本地缓存，缓存没有再走本接口。
 */
interface NameTranslator {

    /**
     * 把中文原文翻成目标语言。
     *
     * @param sourceName 中文原名（识物接口给的 name_chinese）
     */
    suspend fun translate(sourceName: String, lang: NameLanguage): TranslateResult

    /**
     * 批量翻译（列表页一次拉一屏）。
     *
     * @param items (cardId, 中文原名) 列表
     * @return cardId → 结果；未出现在 map 里的视为未知，不写缓存
     */
    suspend fun translateBatch(
        items: List<Pair<Long, String>>,
        lang: NameLanguage
    ): Map<Long, TranslateResult>
}

/** 翻译结果四态，UI 据此决定「显示译名 / 回退中文 / 给重试 / 静默」 */
sealed class TranslateResult {
    /** 成功拿到译名 */
    data class Ok(val text: String) : TranslateResult()

    /** 服务端明确表示该词在该语言下没有译名（不再重试） */
    object NotFound : TranslateResult()

    /** 翻译服务尚未接入 —— 当前版本的常态，UI 显示「开发中」，不报错 */
    object NotReady : TranslateResult()

    /** 网络/服务异常，可重试 */
    data class Failed(val message: String) : TranslateResult()
}

/**
 * 当前生效的实现：**翻译服务未接入**。
 *
 * 恒返回 [TranslateResult.NotReady]，App 展示中文原词并标注「该语言翻译服务接入中」。
 * 这样做的目的：功能入口、交互、缓存、回退链路全部到位且可验证，
 * 等接口来了只替换这一个 object，不需要再动 UI。
 *
 * ⚠️ 这里**故意不做任何客户端机翻**（不内置词表、不调第三方）。
 *    假译名比没有译名更糟：用户会以为翻译是对的。
 */
object PendingNameTranslator : NameTranslator {

    override suspend fun translate(sourceName: String, lang: NameLanguage): TranslateResult =
        TranslateResult.NotReady

    override suspend fun translateBatch(
        items: List<Pair<Long, String>>,
        lang: NameLanguage
    ): Map<Long, TranslateResult> = items.associate { (id, _) ->
        id to (TranslateResult.NotReady as TranslateResult)
    }
}
