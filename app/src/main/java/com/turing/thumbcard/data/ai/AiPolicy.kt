package com.turing.thumbcard.data.ai

import android.content.Context
import com.turing.thumbcard.BuildConfig
import com.turing.thumbcard.R
import com.turing.thumbcard.data.db.WordCardEntity
import com.turing.thumbcard.data.repo.BindRepository

/**
 * 额度策略：哪些设备**不限额**。
 *
 * 为什么需要它：AI 出图是**真金白银**（一张 2K 图约 0.2 元），
 * 所以默认必须卡额度；但自研机 / 客户演示机如果也卡，会出现两种难看场面：
 *   · 连续压测跑到一半弹出「买张数」；
 *   · 客户面前演示，第二张就说「额度已用完」。
 *
 * 白名单走 `BuildConfig.AI_UNLIMITED_DEVICES`（逗号分隔），改名/换机时改 build.gradle 一处即可。
 *
 * ⚠️ 比对的是**云端 deviceId**（整批清单里的那串，如 c8586a630000），
 *    不是 BLE 广播 MAC —— 广播 MAC 末位普遍 +1，用它比对永远匹配不上（v1.0 踩过）。
 *
 * ⚠️ 白名单是**编译期常量**，只适合内测/演示。
 *    正式商用必须搬到服务端（按账号/设备下发），否则任何人拆包就知道白名单值。
 */
object AiPolicy {

    /** 小写化后比较：deviceId 在云端是小写十六进制，但人工填白名单时容易敲成大写 */
    private val unlimitedDevices: List<String> by lazy {
        BuildConfig.AI_UNLIMITED_DEVICES
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
    }

    /** 这台设备是否不限额（null / 空串 = 未知设备 → 受额度限制） */
    fun isUnlimited(deviceId: String?): Boolean {
        val id = deviceId?.trim()?.lowercase().orEmpty()
        return id.isNotEmpty() && unlimitedDevices.contains(id)
    }

    /** 这张卡所属设备是否不限额 */
    fun isUnlimited(card: WordCardEntity?): Boolean = card != null && isUnlimited(card.deviceId)

    /**
     * 当前已绑定设备是否不限额。
     *
     * 用于「我的」页这类**没有具体卡片**的位置：额度是整机概念，
     * 多设备绑定下以第一台（主设备）为准，与首页/收藏页取数口径一致。
     */
    suspend fun currentUnlimited(ctx: Context): Boolean = runCatching {
        BindRepository(ctx.applicationContext).listBound().firstOrNull()?.deviceId
    }.getOrNull()?.let { isUnlimited(it) } ?: false

    /**
     * 额度文案（「我的」页 / AI 页共用）。
     *
     * @param free     剩余免费张数
     * @param credits  剩余购买张数
     * @param unlimited 是否不限额设备
     */
    fun quotaText(ctx: Context, free: Int, credits: Int, unlimited: Boolean): String {
        if (unlimited) return ctx.getString(R.string.ai_quota_unlimited)
        return when {
            free > 0 && credits > 0 -> ctx.getString(R.string.ai_quota_both, free, credits)
            free > 0 -> ctx.getString(R.string.ai_quota_free, free)
            credits > 0 -> ctx.getString(R.string.ai_quota_credits, credits)
            else -> ctx.getString(R.string.ai_quota_none)
        }
    }

    /** 短文案：列表/底栏这种只能放两三个字的地方 */
    fun quotaShort(ctx: Context, free: Int, credits: Int, unlimited: Boolean): String =
        if (unlimited) ctx.getString(R.string.ai_quota_unlimited_short)
        else ctx.getString(R.string.ai_quota_left_short, free + credits)
}
