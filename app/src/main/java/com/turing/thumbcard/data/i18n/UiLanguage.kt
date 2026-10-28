package com.turing.thumbcard.data.i18n

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.turing.thumbcard.R

/**
 * App **界面语言**（10 档，对应 `values/`、`values-en/`、`values-ja/` … 各份 strings.xml），默认简体中文。
 *
 * ⚠️ 与 [NameLanguage]（物品名称语言）是**两件互不相干的事**，不要混：
 *  - 本类改的是 App 自己的界面文案（`values/`、`values-zh-rTW/`、`values-en/` … 的 strings.xml）；
 *  - [NameLanguage] 改的是「相机拍到的物品在 App 里显示成哪国语言」。
 *  一个用户完全可以「界面用阿语 + 物品名用英语」。
 *
 * 实现方式：
 *  - 用 AndroidX 官方 [AppCompatDelegate.setApplicationLocales]（AppCompat 1.6+），
 *    **不自己写 attachBaseContext 包 Context**——后者要处理 Activity/Service/Receiver
 *    各种入口，一处漏了就出现「半中半英」。
 *  - 语言 tag 存自己的 SharedPreferences，**进程启动时显式应用一次**。
 *    不依赖 `autoStoreLocales`：那个机制的读取时机在各版本 AppCompat 上不完全一致，
 *    显式存 + 显式应用最稳（也便于「默认中文」这条需求落地）。
 *
 * 🔴 阿拉伯语是 **RTL** 语言：切换后 Android 会按 locale 自动把整个布局镜像
 *    （`android:supportsRtl="true"` 已在 Manifest 打开）。因此：
 *    ① 布局里**禁止**出现 layout_marginLeft / layout_toRightOf 这类物理方向属性，一律用 Start/End；
 *    ② 方向性图标（返回箭头、右箭头）必须声明 `android:autoMirrored="true"`，否则阿语下箭头会指反；
 *    ③ strings.xml 里自带的 `‹` `›` `→` 这类符号不会自动镜像，必须在 values-ar 里手写成 RTL 方向。
 *
 * 切换后 AppCompat 会自动重建 Activity，界面即时生效，无需手动重启。
 */
object UiLanguage {

    /** 简体中文（默认） */
    const val ZH = "zh-CN"

    /** 繁体中文（台湾用字，对应 `values-zh-rTW/`；港澳设备会按 Hant 字集落到同一份资源） */
    const val TW = "zh-TW"

    /** 英文 */
    const val EN = "en"

    /** 日语 */
    const val JA = "ja"

    /** 韩语 */
    const val KO = "ko"

    /** 西班牙语 */
    const val ES = "es"

    /** 法语 */
    const val FR = "fr"

    /** 德语 */
    const val DE = "de"

    /** 俄语 */
    const val RU = "ru"

    /** 阿拉伯语（RTL，对应 `values-ar/`） */
    const val AR = "ar"

    /**
     * 语言选择项。
     *
     * 🔴 [name] 是**该语言自己的写法**（中文（简体）/ 日本語 / Русский / العربية），
     *    不能用 strings 资源：那样切到阿语后「日本語」也会变成阿语，用户反而认不出自己那项。
     *    [subRes] 才是跟随当前界面语言的说明文字。
     */
    data class Option(val tag: String, val name: String, @StringRes val subRes: Int)

    /**
     * 全部 10 档，顺序：中文系 → 英文 → 东亚 → 欧洲 → 阿拉伯（RTL 置尾）。
     *
     * ⚠️ 只在这一处维护顺序：设置弹窗按本列表渲染，新增语言只改这里 + `normalize` + 资源目录。
     */
    val ALL: List<Option> = listOf(
        Option(ZH, "中文（简体）", R.string.ui_lang_row_zh_sub),
        Option(TW, "中文（繁體）", R.string.ui_lang_row_tw_sub),
        Option(EN, "English", R.string.ui_lang_row_en_sub),
        Option(JA, "日本語", R.string.ui_lang_row_ja_sub),
        Option(KO, "한국어", R.string.ui_lang_row_ko_sub),
        Option(ES, "Español", R.string.ui_lang_row_es_sub),
        Option(FR, "Français", R.string.ui_lang_row_fr_sub),
        Option(DE, "Deutsch", R.string.ui_lang_row_de_sub),
        Option(RU, "Русский", R.string.ui_lang_row_ru_sub),
        Option(AR, "العربية", R.string.ui_lang_row_ar_sub),
    )

    private const val SP = "thumbcard_ui"
    private const val KEY_TAG = "ui_lang_tag"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** 当前界面语言 tag；未设置过 = 中文 */
    fun storedTag(ctx: Context): String =
        normalize(runCatching { prefs(ctx).getString(KEY_TAG, ZH) }.getOrNull())

    fun isEnglish(ctx: Context): Boolean = storedTag(ctx).startsWith("en", ignoreCase = true)

    /** 当前是否为繁体中文界面 */
    fun isTraditional(ctx: Context): Boolean =
        storedTag(ctx).startsWith("zh-TW", ignoreCase = true)

    /** 当前是否为阿拉伯语界面（RTL） */
    fun isArabic(ctx: Context): Boolean = storedTag(ctx).startsWith("ar", ignoreCase = true)

    /** 语言在设置页显示的名字（用各自语言写自己，不翻译） */
    fun displayName(ctx: Context): String = displayNameOf(storedTag(ctx))

    /**
     * 语言 tag → 设置页显示名。界面已切到英文时，「中文」仍显示「中文」——语言名不翻译。
     *
     * 🔴 阿拉伯语返回的是**阿语原文**「العربية」而不是「阿拉伯语」：
     *    这一列的值要能让不认识中文的人认出自己的语言，写中文等于没写。
     */
    fun displayNameOf(tag: String): String {
        val t = normalize(tag)
        return ALL.firstOrNull { it.tag == t }?.name ?: ALL[0].name
    }

    /**
     * 切换界面语言。
     *
     * 会重建当前 Activity（AppCompat 行为），调用方不要再手动 recreate。
     */
    fun set(ctx: Context, tag: String) {
        val safe = normalize(tag)
        runCatching { prefs(ctx).edit().putString(KEY_TAG, safe).apply() }
        runCatching {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(safe))
        }
    }

    /**
     * 把任意 tag 收敛到 10 档之一（防止脏数据让界面变回「半中半英」）。
     *
     * 系统语言常带地区后缀（ja-JP / ko-KR / es-419 / fr-CA / de-AT / ru-RU），一律按前缀归并；
     * 繁体三种写法（zh-TW / zh-HK / zh-Hant）落到同一份 `values-zh-rTW`。
     *
     * ⚠️ 新增语言时必须在这里加分支：漏了会被 `else -> ZH` 静默吞成中文，
     *    表现为「设置里显示阿语、界面还是中文」，而且不报任何错。
     */
    fun normalize(tag: String?): String = when {
        tag.isNullOrBlank() -> ZH
        tag.startsWith("en", ignoreCase = true) -> EN
        tag.startsWith("zh-TW", ignoreCase = true) ||
            tag.startsWith("zh-HK", ignoreCase = true) ||
            tag.startsWith("zh-Hant", ignoreCase = true) -> TW
        tag.startsWith("ja", ignoreCase = true) -> JA
        tag.startsWith("ko", ignoreCase = true) -> KO
        tag.startsWith("es", ignoreCase = true) -> ES
        tag.startsWith("fr", ignoreCase = true) -> FR
        tag.startsWith("de", ignoreCase = true) -> DE
        tag.startsWith("ru", ignoreCase = true) -> RU
        tag.startsWith("ar", ignoreCase = true) -> AR
        else -> ZH
    }

    /**
     * 进程启动时应用一次已保存的语言。
     *
     * 必须在任何 Activity 创建之前调用（[com.turing.thumbcard.ThumbCardApp.onCreate]）。
     */
    fun applyStored(ctx: Context) {
        val tag = storedTag(ctx)
        runCatching {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }
}
