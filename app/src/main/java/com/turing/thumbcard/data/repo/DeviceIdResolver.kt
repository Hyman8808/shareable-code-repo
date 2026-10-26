package com.turing.thumbcard.data.repo

import android.util.Log
import com.turing.thumbcard.BuildConfig

/**
 * deviceId 解析与自动匹配。
 *
 * ── 背景（实测结论，2026-10-17）────────────────────────────
 * 设备号（云端 deviceId）= W800 的**真实 MAC** 去冒号小写。
 * 已用云端激活清单（33 台）交叉验证：28 台真实 MAC 全为 `c8586a63xxxx`，
 * 与厂商 OUI `C8:58:6A` = 北京联盛德微电子 完全吻合。
 *
 * ⚠️ 但 **BLE 广播地址 ≠ 真实 MAC**：
 *     设备在广播时通常做 +1 偏移（避开 Android 对「与真实 MAC 相同地址」
 *     的过滤），实测读到 `C8:58:6A:63:82:23`，而真实 MAC 是 `C8:58:6A:63:82:22`，
 *     **末位差 1** —— 导致查云端记录永远为空。
 *
 * ── 解决方案 ─────────────────────────────────────────────
 * 1. 首选：固件在 `0x8D` 响应中回 TLV `0x0B` DEVICE_ID（协议已定义，最可靠）
 * 2. 兜底：以广播 MAC 为基准生成候选列表（-2/-1/原值/+1），
 *    逐个查云端激活/记录，取命中者。
 *
 * ⚠️ 候选择序说明：官方固件沿用 **ESP32/ESP-IDF 的 base MAC** 风格，
 *    常见为「WiFi STA = base、BT = base+1」（例如 `c8586a630000` 旁的
 *    `c8586a630010`、`c8586a630020` 同属一个基址段），因此 **-1 优先**。
 */
object DeviceIdResolver {

    private const val TAG = "DeviceIdResolver"

    /** 由 BLE 广播 MAC 生成的候选项及其偏移量（用于日志展示） */
    data class Candidate(val deviceId: String, val offset: Int, val source: String)

    /**
     * 生成候选 deviceId 列表（按优先级排序）。
     *
     * @param broadcastMac BLE 扫描到的 MAC，如 `C8:58:6A:63:82:23`
     * @param firmwareDeviceId 固件通过 TLV 0x0B 上报的 deviceId（若已实现）
     */
    fun candidates(
        broadcastMac: String,
        firmwareDeviceId: String? = null
    ): List<Candidate> {
        val list = ArrayList<Candidate>()

        // 1) 固件上报值 —— 权威，永远排第一
        val fw = firmwareDeviceId?.trim()?.lowercase()
        if (!fw.isNullOrBlank() && fw.matches(Regex("^[a-z0-9]{1,18}$"))) {
            list += Candidate(fw, 0, "固件上报")
        }

        // 2) 广播 MAC 原值
        val base = normalizeMac(broadcastMac) ?: return list
        list += Candidate(base, 0, "广播MAC")

        // 3) 数值偏移（仅对 12 位纯 hex 有意义的 MAC 型）
        if (base.length == 12 && base.matches(Regex("^[0-9a-f]{12}$"))) {
            val v = base.toLongOrNull(16)
            if (v != null) {
                // -1 优先：ESP-IDF 风格 BT=base+1，则真实值 = 广播值-1
                for (off in listOf(-1, 1, -2)) {
                    val nv = v + off
                    if (nv < 0) continue
                    val s = java.lang.String.format("%012x", nv)
                    if (list.none { it.deviceId == s }) {
                        list += Candidate(s, off, "广播MAC${if (off > 0) "+" else ""}$off")
                    }
                }
            }
        }
        return list
    }

    /** 去掉 MAC 中的分隔符并转小写；非法返回 null */
    fun normalizeMac(mac: String?): String? {
        val s = mac?.replace(":", "")?.replace("-", "")?.trim()?.lowercase()
        return s?.takeIf { it.isNotEmpty() }
    }

    /**
     * 逐个候选查询云端，返回第一个「存在」的 deviceId。
     *
     * ⚠️ 云端接口**不校验 deviceId 归属**，任意不存在的 deviceId 也返回 200 + 空列表，
     *    因此无法直接判断「设备真实存在但没拍过」与「deviceId 写错了」。
     *
     * 判定策略（保守，避免误改）：
     *  - 优先取 **cloudTotal > 0** 的候选（确定命中）→ 高置信
     *  - 若全部为 0，则退回 **列表第一个候选**（不做猜测性改写）→ 低置信
     *
     * @return Pair(最终采用的 deviceId, 命中的候选)
     */
    suspend fun resolveByCloud(
        repo: RecordRepository,
        cands: List<Candidate>
    ): Pair<String, Candidate?> {
        if (cands.isEmpty()) return "" to null

        var firstSuccess: Candidate? = null
        for (c in cands) {
            val r = runCatching { repo.sync(c.deviceId) }.getOrNull()
            Log.d(TAG, "probe ${c.deviceId} (${c.source}) -> $r")
            if (r is SyncResult.Success) {
                if (firstSuccess == null) firstSuccess = c
                if (r.cloudTotal > 0) return c.deviceId to c
            }
        }
        // 全部为空：不做猜测性改写，保留原值（避免把用户设备号改错）
        return (cands.firstOrNull()?.deviceId ?: "") to null
    }

    /** 生成候选清单的可读描述（用于日志） */
    fun describe(cands: List<Candidate>): String =
        cands.joinToString("、") { "${it.deviceId}(${it.source})" }

    /** 内置预置 apikey（出厂烧录，整批公用） */
    val presetApiKey: String get() = BuildConfig.DEFAULT_API_KEY
}
