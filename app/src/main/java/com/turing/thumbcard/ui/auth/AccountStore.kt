package com.turing.thumbcard.ui.auth

import android.content.Context

/**
 * 账号信息（本地占位）。
 *
 * ⚠️ 当前服务端未就绪，本类只负责「是否已登录」的本地状态，
 * 不产生任何真实账号、不上传任何用户信息。
 * 后续接入微信/支付宝/手机号登录时，由服务端返回 token 后替换本类实现。
 */
data class AccountInfo(
    val displayName: String,
    /** 登录渠道展示名：微信登录 / 支付宝登录 / 手机号 xxx */
    val channel: String,
    val token: String? = null
)

object AccountStore {

    private const val PREF = "account_local"
    private const val KEY_NAME = "name"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_TOKEN = "token"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun current(ctx: Context): AccountInfo? = runCatching {
        val p = prefs(ctx)
        val name = p.getString(KEY_NAME, null) ?: return null
        AccountInfo(
            displayName = name,
            channel = p.getString(KEY_CHANNEL, "") ?: "",
            token = p.getString(KEY_TOKEN, null)
        )
    }.getOrNull()

    /** 仅供后续真实登录使用；当前 UI 不会调用（避免假登录） */
    fun save(ctx: Context, info: AccountInfo) {
        runCatching {
            prefs(ctx).edit()
                .putString(KEY_NAME, info.displayName)
                .putString(KEY_CHANNEL, info.channel)
                .putString(KEY_TOKEN, info.token)
                .apply()
        }
    }

    fun clear(ctx: Context) {
        runCatching { prefs(ctx).edit().clear().apply() }
    }
}
