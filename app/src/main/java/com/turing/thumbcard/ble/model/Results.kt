package com.turing.thumbcard.ble.model

/**
 * 查询设备信息（0x8D）的解析结果。
 *
 * 字段来源：《固件端协议扩展规格书 V1.0》第 4 章。
 */
data class DeviceInfoResult(
    /** 平台 Accesskey，32 位十六进制。null 表示设备未烧录凭据 */
    val apikey: String? = null,
    /** 设备唯一标识，≤18 位字母数字。null 表示设备未烧录凭据 */
    val deviceId: String? = null,
    /** 组织 ID，联盛德为 0x070C */
    val orgId: Int? = null,
    /** 设备型号编码 */
    val deviceType: Int? = null,
    /** 固件版本，高 4 位主版本 + 低 4 位次版本 */
    val firmwareVersion: Int? = null,
    /** 既有 WiFi 配置项（SSID 等） */
    val wifiItems: Map<Int, String> = emptyMap()
) {
    /** 是否具备绑定所需凭据 */
    val hasCredential: Boolean
        get() = !apikey.isNullOrBlank() && !deviceId.isNullOrBlank()

    /** 固件版本字符串，如 "1.2" */
    val firmwareVersionText: String
        get() = firmwareVersion?.let { "${(it shr 4) and 0x0F}.${it and 0x0F}" } ?: "未知"

    /** deviceId 格式校验：字母/数字，1~18 位 */
    val isDeviceIdValid: Boolean
        get() = deviceId?.matches(Regex("^[A-Za-z0-9]{1,18}$")) == true

    /** apikey 格式校验：32 位十六进制 */
    val isApiKeyValid: Boolean
        get() = apikey?.matches(Regex("^[0-9a-fA-F]{32}$")) == true
}

/**
 * 配网（0x8A）结果。
 */
data class ConfigStaResult(
    val errorId: Int,
    val ipAddress: String? = null,
    val mac: String? = null
) {
    val isSuccess: Boolean get() = errorId == 0
}

/**
 * 广播扫描到的设备。
 */
data class ScannedDevice(
    /** BLE MAC 地址 */
    val mac: String,
    /** 广播中的设备名 */
    val name: String?,
    /** 组织 ID */
    val orgId: Int,
    /** 型号 */
    val type: Int,
    /** 协议版本 */
    val version: Int,
    /** RSSI，用于排序 */
    val rssi: Int = 0
) {
    /** 展示名：优先广播名，否则用 MAC 后 6 位 */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: "拇指相机 ${mac.replace(":", "").takeLast(6).uppercase()}"

    /**
     * 协议是否支持 V1.2 列表格式（TLV `0x88` 行 / `0x89` 汇总）。
     *
     * 广播包 Version 字段 >= 0x12 才算支持。老固件（0x11 及以下）只能按
     * V1.0 的 `0x86`（`SSID,RSSI`）解析，此时列表没有加密类型。
     * 名字兜底通道（广播里没有 ORG-ID）version 为 0，一律按老格式试。
     *
     * 注意：`0x87` 已被 `PUB_ENC_KEY`（密钥协商）占用，列表相关 TLV 从 `0x88` 起。
     */
    val supportsWifiListV2: Boolean get() = version >= 0x12
}

/**
 * 设备扫描到的一个 WiFi 热点。
 */
data class WifiAp(
    /** 热点名，UTF-8，最长 32 字节。允许含逗号、竖线、中文（协议里放在行末） */
    val ssid: String,
    /** 信号强度 dBm，-30 强 ~ -100 弱 */
    val rssi: Int,
    /** 加密方式，见 [Auth] */
    val auth: Int = Auth.UNKNOWN
) {
    /** 是否开放网络（选了不用输密码） */
    val isOpen: Boolean get() = auth == Auth.OPEN

    /** 信号格数 0~4，用于 UI 画强度条 */
    val signalBars: Int
        get() = when {
            rssi >= -55 -> 4
            rssi >= -67 -> 3
            rssi >= -78 -> 2
            rssi >= -85 -> 1
            else -> 0
        }

    /** 加密方式取值，与配网协议 V1.2 §4.5.3 AUTH 字段一一对应 */
    object Auth {
        const val OPEN = 0
        const val WEP = 1
        const val WPA_WPA2 = 2
        const val WPA2 = 3
        const val WPA3 = 4
        const val WPA2_WPA3 = 5
        const val UNKNOWN = 9
    }
}

/**
 * 一次 WiFi 扫描的结果。
 */
data class WifiScanResult(
    /** 热点列表，已按 RSSI 降序、同 SSID 去重 */
    val aps: List<WifiAp>,
    /** 是否需要输密码（老固件没有加密类型，一律要输） */
    val authUnknown: Boolean = false,
    /**
     * 设备声明的条数（`0x89` 的 `total`）。
     *
     * -1 表示没收到 `0x89`（老固件或链路中断）。
     * 🔴 **它和 [aps].size 不等是正常现象**：协议 §4.5.4 要求固件做「同名合并」，
     * 固件没做时同一 SSID 会有多条，App 侧合并后条数自然变少。
     * 界面必须把这个差值讲出来，否则用户只会认为「App 丢了两条」。
     */
    val total: Int = -1,
    /** 因同名而被合并掉的条数（= 收到行数 - 去重后条数） */
    val duplicates: Int = 0,
    /** 实际收到的 `0x88` 行数（未去重） */
    val received: Int = 0
) {
    val isEmpty: Boolean get() = aps.isEmpty()

    /** 是否收齐（`total` 已知且收到行数不小于它） */
    val complete: Boolean get() = total < 0 || received >= total
}
