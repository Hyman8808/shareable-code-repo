package com.turing.thumbcard.data.i18n

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 名称展示的唯一入口：**列表、网格、详情页都从这里取名称**，不要各自拼。
 *
 * 取名优先级：
 *  1. 目标语言 = 中文 → 直接用识物返回的 `name_chinese`（原始结果，永不翻译）
 *  2. 目标语言 = 其他 → 读 [NameStore] 的译名缓存
 *     - 命中译名 → 用译名
 *     - 命中「无译名」→ 回退中文原词，并标注
 *     - 未缓存 → 回退中文原词，并标注「翻译服务接入中」
 *
 * ★ 回退时 `pending = true`，界面**必须**把当前显示的是中文这件事标出来。
 *   用户选了日语却看到中文名、且没有任何提示，会判定为功能坏了。
 */
object NameResolver {

    private const val TAG = "NameResolver"

    /** 当前生效的译名服务。接口就绪后换掉这一行即可 */
    @Volatile
    var translator: NameTranslator = PendingNameTranslator

    /**
     * 一次展示所需的全部名称信息。
     *
     * @param primary   主名称（**当前语言**的名称）
     * @param secondary 副名称（英文名，固定不变）
     * @param source    中文原词（主名称不是中文时，作为参考展示）
     * @param lang      当前语言
     * @param translated 主名称是否为目标语言的真译名
     * @param pending   翻译服务还没接入（区别于「已接入但该词无译名」）
     */
    data class Display(
        val primary: String,
        val secondary: String?,
        val source: String?,
        val lang: NameLanguage,
        val translated: Boolean,
        val pending: Boolean
    ) {
        /** 主名称下方是否要再挂一条「中文原文」 */
        val showSource: Boolean get() = !lang.isChinese && !source.isNullOrBlank() &&
            source != primary
    }

    /**
     * 同步请求该带什么语言参数。
     *
     * - 中文（简/繁）→ 不带：中文是服务端默认结果，繁体在本机转换，
     *   传上去只会让云端白翻一遍（繁体的字形由 [HantConverter] 负责）
     * - 「无（仅英文）」→ 不带：英文本来就在记录里
     * - 其余 → 带该语言代码，云端按它下发 `name_localized` / `tts_url_localized`
     *
     * 2026-10-25 起不再等 [translator] 就绪：多语言改成「云端按 `lang` 下发」，
     * App 侧不做翻译。云端返回的 `name_localized` 由 RecordRepository 写进译名缓存，
     * 列表与详情读缓存即可——链路里没有客户端补译这一步了。
     */
    fun requestLangParam(context: Context): String? {
        val lang = LanguagePrefs.current(context)
        return lang.code.takeIf { !lang.isChinese && !lang.isNone }
    }

    fun resolve(
        context: Context,
        cardId: Long,
        nameChinese: String?,
        nameEnglish: String?,
        lang: NameLanguage
    ): Display {
        val cn = nameChinese?.trim()?.takeIf { it.isNotBlank() }

        // 「无（仅英文）」：主名称 = 英文名，副名与中文原文一律不出。
        // translated = true 让各处把它当「最终结果」渲染（颜色、不挂「待翻译」标签）。
        if (lang.isNone) {
            val en = nameEnglish?.trim()?.takeIf { it.isNotBlank() }
            return Display(
                primary = en ?: cn ?: "-",
                secondary = null,
                source = null,
                lang = lang,
                translated = true,
                pending = false
            )
        }

        // 中文：原始结果，不查缓存不翻译
        if (lang.isSource) {
            return Display(
                primary = cn ?: nameEnglish?.trim().orEmpty().ifBlank { "-" },
                secondary = nameEnglish?.takeIf { it.isNotBlank() },
                source = null,
                lang = lang,
                translated = false,
                pending = false
            )
        }

        // 繁体中文：名称在本机由简体转繁体（同一门语言，不算翻译）
        // - 不查译名缓存：缓存里存的是「翻译结果」，繁体不需要
        // - translated = true：这是最终结果，界面不该挂「待翻译」标签
        // - source = null：繁体用户不需要再看一遍简体原文
        if (lang.isHant) {
            return Display(
                primary = HantConverter.convert(cn)
                    ?: nameEnglish?.trim().orEmpty().ifBlank { "-" },
                secondary = nameEnglish?.takeIf { it.isNotBlank() },
                source = null,
                lang = lang,
                translated = true,
                pending = false
            )
        }

        // 非中文：查译名缓存
        when (val hit = NameStore.read(context, cardId, lang, cn)) {
            is NameStore.Lookup.Hit -> {
                val t = hit.text?.trim()?.takeIf { it.isNotBlank() }
                if (t != null) {
                    return Display(
                        primary = t,
                        secondary = nameEnglish?.takeIf { it.isNotBlank() },
                        source = cn,
                        lang = lang,
                        translated = true,
                        pending = false
                    )
                }
                // 服务端明确说这个词在该语言下没有译名
                return Display(
                    primary = cn ?: "-",
                    secondary = nameEnglish?.takeIf { it.isNotBlank() },
                    source = cn,
                    lang = lang,
                    translated = false,
                    pending = false
                )
            }

            NameStore.Lookup.Miss -> {
                // 未缓存：发起一次补译（服务未接入时立即返回 NotReady）
                requestAsync(context, cardId, cn, lang)
                return Display(
                    primary = cn ?: "-",
                    secondary = nameEnglish?.takeIf { it.isNotBlank() },
                    source = cn,
                    lang = lang,
                    translated = false,
                    pending = translator is PendingNameTranslator
                )
            }
        }
    }

    /**
     * 后台补译一个词并把结果写缓存。
     *
     * 不阻塞渲染：本次先显示中文原词，下次进入该页（或收到语言变更通知后的重绘）
     * 就能读到译名。当前实现是 Noop，不会产生任何网络请求。
     */
    private fun requestAsync(
        context: Context,
        cardId: Long,
        source: String?,
        lang: NameLanguage
    ) {
        if (source.isNullOrBlank() || lang.isChinese || lang.isNone) return
        if (translator is PendingNameTranslator) return

        ioScope.launch {
            val r = runCatching { translator.translate(source, lang) }.getOrNull()
            when (r) {
                is TranslateResult.Ok -> NameStore.write(context, cardId, lang, source, r.text)
                TranslateResult.NotFound -> NameStore.write(context, cardId, lang, source, null)
                else -> Log.d(TAG, "translate pending/failed for card $cardId")
            }
        }
    }

    /** 补译专用后台作用域：与应用进程同生命周期，单线程足够（一屏最多 10 个词） */
    private val ioScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("name-i18n")
    )
}
