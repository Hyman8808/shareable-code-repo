package com.turing.thumbcard.data.repo

import android.content.Context
import android.content.SharedPreferences
import com.turing.thumbcard.util.CredentialStore

/**
 * 设备号覆盖表。
 *
 * 用于「自动识别的 deviceId 不正确」时的人工修正：
 *  - 保存 广播 MAC → 修正后的 deviceId 的映射
 *  - 记录 一份「自动识别值」快照，便于一键恢复
 *
 * ⚠️ 不做加密：deviceId 不是敏感凭据（apikey 才是，已由 [CredentialStore] 加密）。
 */
object DeviceOverrideStore {

    private const val PREF = "device_override"
    private const val KEY_MAP = "map_"          // 前缀 + 归一化MAC -> deviceId
    private const val KEY_AUTO = "auto_last"    // 最近一次自动识别的 deviceId

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 归一化 MAC 作为 key（无分隔符小写） */
    private fun norm(mac: String?): String =
        DeviceIdResolver.normalizeMac(mac) ?: "_default"

    /** 保存修正值 */
    fun save(ctx: Context, deviceId: String, mac: String? = null) {
        runCatching {
            prefs(ctx).edit().putString(KEY_MAP + norm(mac), deviceId).apply()
        }
    }

    /** 读取修正值；无则返回 null */
    fun get(ctx: Context, mac: String? = null): String? =
        runCatching { prefs(ctx).getString(KEY_MAP + norm(mac), null) }.getOrNull()

    /**
     * 解析最终生效的 deviceId。
     *
     * 优先级：人工修正值 > 固件上报值 > 广播 MAC 推导值
     */
    fun resolve(
        ctx: Context,
        broadcastMac: String?,
        firmwareDeviceId: String? = null
    ): String {
        get(ctx, broadcastMac)?.takeIf { it.isNotBlank() }?.let { return it }
        firmwareDeviceId?.takeIf { it.isNotBlank() }?.let { return it.lowercase() }
        return DeviceIdResolver.normalizeMac(broadcastMac) ?: ""
    }

    /** 记录自动识别值（供恢复用） */
    fun rememberAuto(ctx: Context, deviceId: String) {
        runCatching { prefs(ctx).edit().putString(KEY_AUTO, deviceId).apply() }
    }

    /** 最近一次自动识别值 */
    fun autoDetected(ctx: Context): String? =
        runCatching { prefs(ctx).getString(KEY_AUTO, null) }.getOrNull()

    /** 清除修正（恢复自动识别） */
    fun clear(ctx: Context, mac: String? = null) {
        runCatching {
            if (mac == null) {
                // 清空全部映射
                prefs(ctx).edit().clear().apply()
            } else {
                prefs(ctx).edit().remove(KEY_MAP + norm(mac)).apply()
            }
        }
    }
}
