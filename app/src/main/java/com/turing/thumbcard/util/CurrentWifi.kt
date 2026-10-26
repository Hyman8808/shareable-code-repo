package com.turing.thumbcard.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 读「这台手机当前连着的 WiFi 名称」，给配网页做默认值。
 *
 * ## 解决什么
 * 配网时让用户手打 SSID 是最容易放弃的一步：名称里有中文、空格、大小写，
 * 打错一个字就是「密码错误」或「找不到热点」，而真正的原因只是名字打错了。
 * 绝大多数场景下，相机要连的就是手机此刻正连着的那个 2.4G 网络 ——
 * 直接把它填进输入框，用户只需确认或改一下。
 *
 * ## 为什么「读不到」是常态，必须留空而不是报错
 * Android 10 起，`getConnectionInfo().ssid` 返回真实名称要求：
 *  - 有位置权限（API ≤ 32）或「附近设备」权限（API 33+）；
 *  - 并且系统**定位开关是打开的**。
 *
 * 任何一条不满足，系统就返回 `<unknown ssid>`。这不是异常，是正常返回。
 * 此时唯一正确的做法是输入框留空、由用户自己填 ——
 * 弹「读取失败」只会把配网流程堵在原地。
 *
 * ## 返回值清洗
 * 系统给的是带双引号的 `"MyWiFi"`，未连接时给 `<unknown ssid>` 或空串。
 */
object CurrentWifi {

    private const val TAG = "CurrentWifi"

    private const val API_TIRAMISU = 33
    private const val API_Q = 29

    /** 4000MHz 以上按 5G 处理（2.4G 在 2400~2500，5G 在 4900~5900） */
    private const val FREQ_5G_FLOOR = 4000

    /**
     * 手机当前这个连接的信息。
     *
     * @param ssid    清洗后的名称
     * @param freqMhz 频率，读不到时为 0
     */
    data class Link(val ssid: String, val freqMhz: Int) {
        /**
         * 是否 5G。
         *
         * 为什么要判断：相机只支持 2.4G。如果手机正连着一个 5G 专用的 SSID，
         * 把它预填进配网框等于把一个**必然失败**的值放在最显眼的位置 ——
         * 用户点一下发送，等 50 秒超时，最后看到的是「密码错误」这类误导性结论。
         */
        val is5G: Boolean get() = freqMhz > FREQ_5G_FLOOR
    }

    /**
     * 读 SSID 需要的运行时权限。
     *
     * - API 33+：`NEARBY_WIFI_DEVICES`（定位权限在 33 起已不足以读 SSID）
     * - API 29–32：`ACCESS_FINE_LOCATION`
     * - API 28 及以下：无需运行时权限
     */
    fun requiredPermission(): String? = when {
        Build.VERSION.SDK_INT >= API_TIRAMISU -> Manifest.permission.NEARBY_WIFI_DEVICES
        Build.VERSION.SDK_INT >= API_Q -> Manifest.permission.ACCESS_FINE_LOCATION
        else -> null
    }

    fun hasPermission(ctx: Context): Boolean {
        val p = requiredPermission() ?: return true
        return ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 当前连接的信息（名称 + 频段）。
     *
     * @return 未连接 / 无权限 / 定位关闭 → null
     */
    fun link(ctx: Context): Link? {
        if (!hasPermission(ctx)) {
            Log.d(TAG, "skip: no permission")
            return null
        }
        return try {
            val wm = ctx.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo ?: return null
            val s = info.ssid.orEmpty().trim().removeSurrounding("\"")
            // <unknown ssid> / <unknown_ssid> 都表示系统不肯给
            if (s.isEmpty() || s.startsWith("<")) return null
            val freq = runCatching { info.frequency }.getOrDefault(0)
            Link(s, freq)
        } catch (e: Exception) {
            Log.w(TAG, "read link failed", e)
            null
        }
    }

    /**
     * 当前连接中的 WiFi 名称。
     *
     * @return 清洗后的 SSID；未连接 / 无权限 / 定位关闭 → null
     */
    fun ssid(ctx: Context): String? = link(ctx)?.ssid

    /**
     * 给输入框用的「预填值」。
     *
     * 与 [ssid] 的区别只有一个：把「读不到」包装成 null 由调用方决定怎么提示。
     * 单独留这个方法是为了让调用点读起来是「要一个默认值」，而不是「要一个 WiFi 名」。
     */
    fun defaultSsid(ctx: Context): String? = ssid(ctx)
}
