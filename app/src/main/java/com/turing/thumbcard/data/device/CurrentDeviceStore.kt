package com.turing.thumbcard.data.device

import android.content.Context
import com.turing.thumbcard.data.db.BoundDeviceEntity

/**
 * 当前设备（多设备时「所有 App 页面看哪台」的唯一真源）。
 *
 * 为什么需要它：
 *  - 旧实现里首页与收藏页各自写 `devices.first()`，于是「在设备页切到第二台」时，
 *    只有点进去的那个页面换了设备，首页和收藏仍然是第一台 —— 用户看到的是
 *    「切了但没完全切」，不同页面显示不同相机的数据，比不支持多设备更难理解。
 *  - 现在**只有一处**决定当前设备，其余页面全部读这里。
 *
 * 三条设计约束：
 *  1. 存的是 **deviceId**，不存下标 —— 解绑一台之后下标会整体错位。
 *  2. 存的 id 已经不在绑定列表里（被解绑 / 重装）→ 自动退回最近绑定的一台，
 *     绝不返回 null 让上层去猜。
 *  3. [ALL] 是**伪设备**，只允许「全部单词记录」页使用。
 *     首页的成就统计、收藏的收藏列表都不能用它：跨设备合并的收藏列表
 *     会让「取消收藏」落到另一台设备的同一张卡上，语义不清。
 */
object CurrentDeviceStore {

    private const val PREF = "thumbcard_device"
    private const val KEY_CURRENT = "current_device_id"

    /** 伪设备：全部设备。**只在「全部单词记录」页当筛选条件用，不写进绑定表** */
    const val ALL = "__all__"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun currentId(context: Context): String? =
        runCatching { prefs(context).getString(KEY_CURRENT, null) }.getOrNull()

    fun setCurrent(context: Context, deviceId: String?) {
        runCatching {
            prefs(context).edit().apply {
                if (deviceId.isNullOrBlank()) remove(KEY_CURRENT) else putString(KEY_CURRENT, deviceId)
            }.apply()
        }
    }

    fun isAll(deviceId: String?): Boolean = deviceId == ALL

    /** 解绑后清掉指针，避免下次启动拿着一台已经不存在的设备去同步 */
    fun clearIfMatches(context: Context, deviceId: String) {
        if (currentId(context) == deviceId) setCurrent(context, null)
    }

    /**
     * 取「有效」当前设备。
     *
     * @param devices [com.turing.thumbcard.data.repo.BindRepository.listBound] 的结果，
     *                已按 bindTime 倒序 —— 所以 `first()` 就是最近绑定的一台。
     */
    fun resolve(context: Context, devices: List<BoundDeviceEntity>): BoundDeviceEntity? {
        if (devices.isEmpty()) return null
        val stored = currentId(context)
        devices.firstOrNull { it.deviceId == stored }?.let { return it }

        // 没选过 / 选的那台已解绑 → 落回最近绑定的一台，并把指针写正
        val fallback = devices.first()
        setCurrent(context, fallback.deviceId)
        return fallback
    }

    /** 给 UI 用的设备显示名：用户改的名字优先，否则「拇指相机 XXXX」（MAC 后 4 位） */
    fun displayName(device: BoundDeviceEntity, fallback: String): String {
        val name = device.deviceName?.trim()
        if (!name.isNullOrBlank()) return name
        val tail = device.deviceId.takeLast(4).uppercase()
        return "$fallback $tail"
    }
}
