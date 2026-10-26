package com.turing.thumbcard.data.i18n

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * App **界面语言**（简体中文 / 繁体中文 / English），默认简体中文。
 *
 * ⚠️ 与 [NameLanguage]（物品名称语言）是**两件互不相干的事**，不要混：
 *  - 本类改的是 App 自己的界面文案（`values/`、`values-zh-rTW/`、`values-en/` 的 strings.xml）；
 *  - [NameLanguage] 改的是「相机拍到的物品在 App 里显示成哪国语言」。
 *  一个用户完全可以「界面用繁体 + 物品名用日语」。
 *
 * 实现方式：
 *  - 用 AndroidX 官方 [AppCompatDelegate.setApplicationLocales]（AppCompat 1.6+），
 *    **不自己写 attachBaseContext 包 Context**——后者要处理 Activity/Service/Receiver
 *    各种入口，一处漏了就出现「半中半英」。
 *  - 语言 tag 存自己的 SharedPreferences，**进程启动时显式应用一次**。
 *    不依赖 `autoStoreLocales`：那个机制的读取时机在各版本 AppCompat 上不完全一致，
 *    显式存 + 显式应用最稳（也便于「默认中文」这条需求落地）。
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

    /** 语言在设置页显示的名字（用各自语言写自己，不翻译） */
    fun displayName(ctx: Context): String = displayNameOf(storedTag(ctx))

    /** 语言 tag → 设置页显示名。界面已切到英文时，「中文」仍显示「中文」——语言名不翻译 */
    fun displayNameOf(tag: String): String = when {
        tag.startsWith("en", ignoreCase = true) -> "English"
        tag.startsWith("zh-TW", ignoreCase = true) -> "繁體中文"
        else -> "中文"
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

    /** 把任意 tag 收敛到三档之一（防止脏数据让界面变回「半中半英」） */
    fun normalize(tag: String?): String = when {
        tag.isNullOrBlank() -> ZH
        tag.startsWith("en", ignoreCase = true) -> EN
        tag.startsWith("zh-TW", ignoreCase = true) ||
            tag.startsWith("zh-HK", ignoreCase = true) -> TW
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
