package com.turing.thumbcard.data.ai

import android.content.Context
import com.turing.thumbcard.BuildConfig

/**
 * AI 出图额度账本。
 *
 * 两种来源、一个扣减顺序：
 *  1. **免费额度**（[freeQuota] 张，两种风格共用）—— 先用它，用户不用注册就能试
 *  2. **购买的张数** —— 免费额度用完才动它
 *
 * ⚠️ 当前是**纯本地记账**（SharedPreferences），只适合调试与内测：
 *    清数据 / 重装即归零，也没法跨设备同步，更拦不住越狱式刷额度。
 *    正式上线必须改成「服务端下单 → 服务端扣减 → App 只读余额」，
 *    否则用户卸载重装就能无限白嫖，等于把成本敞口全开。
 *    本类的公开方法签名刻意与「服务端版本」对齐（[refresh]/[consume]/[addCredits]），
 *    将来把实现换成 HTTP 即可，界面层不用改。
 *
 * ⚠️ 扣减发生在**生成成功之后**，不是点击时：
 *    生成失败（网络/审核/超时）不扣次数，否则用户会对不上账。
 */
object AiQuotaStore {

    private const val PREF = "thumbcard_ai"

    private const val KEY_FREE_USED = "free_used"
    private const val KEY_PURCHASED = "purchased"
    private const val KEY_SPENT = "spent"
    private const val KEY_TOTAL_GENERATED = "total_generated"

    /** 免费额度（张），两种风格共用一个池子 */
    val freeQuota: Int get() = BuildConfig.AI_FREE_QUOTA

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun readInt(ctx: Context, key: String): Int =
        runCatching { prefs(ctx).getInt(key, 0) }.getOrDefault(0)

    private fun writeInt(ctx: Context, key: String, value: Int) {
        runCatching { prefs(ctx).edit().putInt(key, value.coerceAtLeast(0)).apply() }
    }

    // ==================== 余额 ====================

    /** 已用掉的免费张数 */
    fun freeUsed(ctx: Context): Int = readInt(ctx, KEY_FREE_USED).coerceIn(0, freeQuota)

    /** 剩余免费张数 */
    fun freeRemaining(ctx: Context): Int = (freeQuota - freeUsed(ctx)).coerceAtLeast(0)

    /** 已购买总张数 */
    fun purchased(ctx: Context): Int = readInt(ctx, KEY_PURCHASED)

    /** 已花掉的购买张数 */
    fun spent(ctx: Context): Int = readInt(ctx, KEY_SPENT)

    /** 剩余购买张数 */
    fun creditsRemaining(ctx: Context): Int = (purchased(ctx) - spent(ctx)).coerceAtLeast(0)

    /** 总共还能生成几张（免费余量 + 购买余量） */
    fun remaining(ctx: Context): Int = freeRemaining(ctx) + creditsRemaining(ctx)

    /** 累计成功生成张数（「我的」页展示，也算个使用记录） */
    fun totalGenerated(ctx: Context): Int = readInt(ctx, KEY_TOTAL_GENERATED)

    fun canGenerate(ctx: Context): Boolean = remaining(ctx) > 0

    // ==================== 变动 ====================

    /**
     * 记一次成功生成。免费池优先消耗。
     *
     * @return 本次扣的是哪种额度；false 表示两种都没了（理论上调用前已用 [canGenerate] 挡过）
     */
    fun consume(ctx: Context): Boolean {
        val freeLeft = freeRemaining(ctx)
        if (freeLeft > 0) {
            writeInt(ctx, KEY_FREE_USED, freeUsed(ctx) + 1)
        } else if (creditsRemaining(ctx) > 0) {
            writeInt(ctx, KEY_SPENT, spent(ctx) + 1)
        } else {
            return false
        }
        writeInt(ctx, KEY_TOTAL_GENERATED, totalGenerated(ctx) + 1)
        return true
    }

    /** 购买成功加张数 */
    fun addCredits(ctx: Context, count: Int) {
        if (count <= 0) return
        writeInt(ctx, KEY_PURCHASED, purchased(ctx) + count)
    }

    /**
     * 刷新余额。
     *
     * 本地实现是空操作；接服务端后这里去拿真实余额。
     * 保留这个方法是让界面层习惯「余额需要主动刷新」这件事。
     */
    @Suppress("UNUSED_PARAMETER")
    fun refresh(ctx: Context) {
        // no-op（本地账本）；接服务端后在这里拉真实余额
    }

    /** 清空账本 —— 仅 VIP 调试版可见 */
    fun resetAll(ctx: Context) {
        runCatching { prefs(ctx).edit().clear().apply() }
    }
}
