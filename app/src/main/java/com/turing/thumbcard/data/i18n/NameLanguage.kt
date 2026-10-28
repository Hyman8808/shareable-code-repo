package com.turing.thumbcard.data.i18n

/**
 * 物品名称可切换的语言。
 *
 * 产品定义（2026-10-20，2026-10-25 收敛，2026-10-27 补阿拉伯语）：
 *  - 单词卡默认展示「中文名（主）+ 英文名（副）」
 *  - 其中**中文名这一栏**可切换到下列任意语言
 *  - 英文名固定保留为副名，不参与切换
 *
 * ⚠️ 2026-10-25 由 17 项收敛为 9 项。
 *    原因：多语言接口尚未真正上线，17 项里有 13 项只能显示「翻译服务开发中」，
 *    等于给用户摆了 13 个坏入口。现在只保留**能真正出结果**的：
 *    简中 / 繁中（本机转换）/ 仅英文（原样）/ 日韩西法德俄（服务端按 lang 下发 name_localized）。
 *    后续云端补齐语言后，在这里加一行枚举即可，UI 与其他逻辑都不用动。
 *
 * ⚠️ 2026-10-27 追加 [AR]。依据：
 *    - 云端 `name_localized` 的 ar 通道已在 V2.1 正式版接口白名单内；
 *    - TTS 侧 `ar-SA` 经 cosyvoice-v2 跨语言音色实测可返回有效 mp3（12:33 抽样 6/6 有 url）。
 *    与日语等同属「服务端下发」类：切过去之后要等下一次同步才出译名，
 *    拿不到译名时**回退显示中文原词，绝不伪造**（见 [NameResolver]）。
 *
 * @param code 与服务端约定的语言代码（BCP-47；繁体统一用 zh-Hant，不用 zh-TW/zh-HK）
 * @param label 中文展示名（给中文界面的用户看）
 * @param labelEn 英文展示名（给**非中文界面**看，见 [labelFor]）
 * @param native 该语言的本地写法（列表主标题、设置页当前值——**只能用它**，
 *               因为 [label] 是写死的中文，在英文/阿语界面里会串味）
 *
 * ⚠️ 为什么要有 [labelEn]（2026-10-27 加）：
 *    [label] 是硬编码中文，不看界面语言。加了阿拉伯语之后问题变严重——
 *    阿语界面下卡片详情会显示「العربية   阿拉伯语」，Toast 里也会冒出
 *    「لا يوجد اسم «阿拉伯语» لهذه الكلمة بعد」这种半阿半中的句子。
 *    阿语用户 100% 看不懂中文，这已经不是「串味」而是「不可读」。
 *    因此**所有面向用户的 label 都必须走 [labelFor]**，不要直接读 [label]。
 */
enum class NameLanguage(
    val code: String,
    val label: String,
    val labelEn: String,
    val native: String
) {
    // 放在列表第一位：它不是「第 8 种语言」，而是一个模式开关——
    // 选了它，名称只留英文，所有中文相关内容（中文名、拼音、汉语词典、中文释义、直译条）全部隐藏。
    NONE("none", "无（仅英文）", "None (English only)", "English only"),
    ZH("zh", "中文（简体）", "Chinese (Simplified)", "简体中文"),
    // 繁体与简体**同属中文**，不是「翻译目标语言」：名称由 [HantConverter] 在本机转换，
    // 不经过服务端，因此选它不会出现「待同步」状态。
    ZH_TW("zh-Hant", "中文（繁体）", "Chinese (Traditional)", "繁體中文"),
    JA("ja", "日语", "Japanese", "日本語"),
    KO("ko", "韩语", "Korean", "한국어"),
    ES("es", "西班牙语", "Spanish", "Español"),
    FR("fr", "法语", "French", "Français"),
    DE("de", "德语", "German", "Deutsch"),
    RU("ru", "俄语", "Russian", "Русский"),

    /**
     * 阿拉伯语。排在最后而不是紧跟中文：语言列表按「中文系 → 东亚 → 欧洲 → 其他」排，
     * 阿语插在中间会让用户以为它是欧洲语言。
     *
     * 🔴 [native] 必须是阿语原文「العربية」：阿语是 RTL，这条文案在列表里会按阿语的
     *    书写方向渲染，用中文写「阿拉伯语」会让阿语用户找不到自己的语言。
     */
    AR("ar", "阿拉伯语", "Arabic", "العربية");

    /**
     * 按**界面语言**取展示名。
     *
     * 中文界面给中文名（中文用户看得懂），其他界面一律给英文名——
     * 英文至少是国际通用，中文对阿语/英语用户是完全不可读的方块。
     *
     * @param uiTag 当前界面语言 tag，直接传 `UiLanguage.storedTag(ctx)`
     */
    fun labelFor(uiTag: String): String =
        if (uiTag.startsWith("zh", ignoreCase = true)) label else labelEn

    /** 是否为中文。中文是识物接口的原始语言，不需要翻译 */
    val isSource: Boolean get() = this == ZH

    /** 繁体中文：本机简繁转换，不查译名缓存、不调翻译接口，但**仍算中文** */
    val isHant: Boolean get() = this == ZH_TW

    /**
     * 是否展示「中文内容」（拼音、汉语词典、中文发音、直译条）。
     *
     * 与 [isSource] 的区别：繁体也属于这一类——繁体用户照样要拼音和中文释义，
     * 只是字形换成繁体。用 isSource 判定会把繁体用户的中文释义整块吞掉。
     */
    val isChinese: Boolean get() = this == ZH || this == ZH_TW

    /** 是否为「无（仅英文）」模式：界面只出现英文，一切中文内容退场 */
    val isNone: Boolean get() = this == NONE

    companion object {
        /** 默认语言：中文（与改造前行为完全一致） */
        val DEFAULT: NameLanguage = ZH

        /**
         * 代码 → 枚举。
         *
         * 兼容旧值：v2.8.0 及更早把繁体存成 `zh-TW`，存量用户升级后不能丢设置，
         * 因此 `zh-TW` / `zh-HK` 一律归到 [ZH_TW]。新写入统一用 `zh-Hant`。
         */
        fun fromCode(code: String?): NameLanguage = when {
            code == null -> DEFAULT
            else -> entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
                ?: if (code.equals("zh-TW", true) || code.equals("zh-HK", true)) ZH_TW
                else DEFAULT
        }
    }
}
