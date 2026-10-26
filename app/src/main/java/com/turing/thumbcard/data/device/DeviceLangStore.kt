package com.turing.thumbcard.data.device

import android.content.Context
import android.util.Log
import com.turing.thumbcard.data.i18n.LanguagePrefs
import com.turing.thumbcard.data.i18n.NameLanguage

/**
 * 「设备侧语言 + 云端保留天数」的本地存储。
 *
 * ## 为什么设备需要 App 告诉它语言
 *
 * 硬件与固件**不区分市场**：同一块板子发日本、发德国都是同一版固件。
 * 国家的差异不烧在固件里，而是由 App 在配网时写入（BLE `0x12` + 三个 TLV），
 * 设备再带着这组参数向云端取数据。这样：
 *  - 固件只有一条分支（少一套版本 = 少一类客诉）
 *  - 同一台设备换个国家卖，App 改一次设置即可，不用返厂重烧
 *  - 新增语言不需要动固件（除非该语言要求新的字形/字体，见技术方案 V3.0 §5.3）
 *
 * ## 与「物品名称语言」的关系
 *
 * 产品要求「设备界面语言跟随物品语言」：用户在 App 选了物品语言 = 日语，
 * 设备提示语、开机字幕、按键提示也跟着变日文。因此默认一次设置同时写两个 TLV。
 *
 * 之所以仍分开存两字段：后续可能出现「物品读日语、界面用英语」的组合
 * （例如成人自用、只练听力），届时只需 UI 暴露第二个选择，协议与存储不用改。
 *
 * ## pending 语义
 *
 * 设置时设备通常不在连接状态（平时不连 BLE）。因此写入后标记 `pending = true`，
 * 等下一次配网 / 连上设备时自动补发，成功后清标记。
 * UI 上必须能看出「还没生效」，否则用户会以为设置失败。
 */
object DeviceLangStore {

    private const val TAG = "DeviceLangStore"
    private const val PREF = "thumbcard_device"
    private const val KEY_ITEM = "item_lang"
    private const val KEY_UI = "ui_lang"
    private const val KEY_PENDING = "pending"
    private const val KEY_APPLIED_AT = "applied_at"
    private const val KEY_CLOUD_DAYS = "cloud_days"

    /** 默认云端保留天数（免费档） */
    const val DEFAULT_CLOUD_DAYS = 7

    /** 订阅档位（天）—— 与商务口径一致，见技术方案 V3.0 §6.3 */
    val CLOUD_STORE_TIERS = intArrayOf(7, 30, 365)

    data class DeviceLang(
        val itemLang: String,
        val uiLang: String,
        val pending: Boolean,
        val appliedAt: Long,
        val cloudDays: Int
    )

    @Volatile
    private var cached: DeviceLang? = null

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * 当前配置。
     *
     * 首次读取时以「物品名称语言」为默认值 —— 用户在外面选过日语，
     * 配网时设备就该是日语，不需要再设一遍。
     */
    fun current(ctx: Context): DeviceLang {
        cached?.let { return it }
        val fallback = defaultFromNameLang(ctx)
        val v = runCatching {
            val p = prefs(ctx)
            DeviceLang(
                itemLang = p.getString(KEY_ITEM, null) ?: fallback,
                uiLang = p.getString(KEY_UI, null) ?: fallback,
                pending = p.getBoolean(KEY_PENDING, false),
                appliedAt = p.getLong(KEY_APPLIED_AT, 0L),
                cloudDays = p.getInt(KEY_CLOUD_DAYS, DEFAULT_CLOUD_DAYS)
                    .takeIf { it in CLOUD_STORE_TIERS } ?: DEFAULT_CLOUD_DAYS
            )
        }.getOrElse {
            Log.w(TAG, "read failed: ${it.javaClass.simpleName}")
            DeviceLang(fallback, fallback, false, 0L, DEFAULT_CLOUD_DAYS)
        }
        cached = v
        return v
    }

    /**
     * 设置物品语言；界面语言默认跟随。
     *
     * @param followUi true = 界面语言同步改成同一语言（默认行为）
     */
    fun setItemLang(ctx: Context, code: String, followUi: Boolean = true): DeviceLang {
        val cur = current(ctx)
        val next = cur.copy(
            itemLang = code,
            uiLang = if (followUi) code else cur.uiLang,
            pending = true
        )
        persist(ctx, next)
        Log.d(TAG, "set item=$code ui=${next.uiLang} pending=true")
        return next
    }

    /** 单独设置界面语言（后续能力，当前 UI 未暴露） */
    fun setUiLang(ctx: Context, code: String): DeviceLang {
        val next = current(ctx).copy(uiLang = code, pending = true)
        persist(ctx, next)
        return next
    }

    /** 设置云端保留天数（订阅生效后由服务端回写本地） */
    fun setCloudDays(ctx: Context, days: Int): DeviceLang {
        val safe = if (days in CLOUD_STORE_TIERS) days else DEFAULT_CLOUD_DAYS
        val next = current(ctx).copy(cloudDays = safe, pending = true)
        persist(ctx, next)
        return next
    }

    /** 下发成功（或帧已写出）后调用，清掉 pending */
    fun markApplied(ctx: Context): DeviceLang {
        val next = current(ctx).copy(pending = false, appliedAt = System.currentTimeMillis())
        persist(ctx, next)
        Log.d(TAG, "marked applied at ${next.appliedAt}")
        return next
    }

    private fun persist(ctx: Context, v: DeviceLang) {
        cached = v
        runCatching {
            prefs(ctx).edit()
                .putString(KEY_ITEM, v.itemLang)
                .putString(KEY_UI, v.uiLang)
                .putBoolean(KEY_PENDING, v.pending)
                .putLong(KEY_APPLIED_AT, v.appliedAt)
                .putInt(KEY_CLOUD_DAYS, v.cloudDays)
                .apply()
        }.onFailure { Log.w(TAG, "write failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 从物品名称语言取默认设备语言。
     *
     * `NONE`（仅英文模式）不是真实语言，下发时归到 `en`：
     * 设备端拿 `none` 会找不到对应的提示语资源。
     */
    private fun defaultFromNameLang(ctx: Context): String {
        val code = runCatching { LanguagePrefs.current(ctx).code }.getOrDefault("en")
        return if (code == NameLanguage.NONE.code) "en" else code
    }

    /**
     * 给 UI 用的显示名 —— **只返回本地写法**（简体中文 / 繁體中文 / 日本語 / Español…）。
     *
     * ⚠️ 不要拼「本地名 + 中文名」：设置页值列只有 150dp，
     *    「Español  西班牙语」会被截成「Español  西班牙…」，语言越长越难看。
     *    本地写法在任何界面语言下都成立，中文对照留给选择页的副标题。
     *
     * ⚠️ 也不能直接用 `NameLanguage.fromCode()` 的 label：它是写死的中文，
     *    在英文界面里会串味。
     */
    fun displayNameOf(code: String): String {
        if (code.equals("en", ignoreCase = true)) return "English"
        val lang = NameLanguage.entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: return code
        return lang.native
    }
}
