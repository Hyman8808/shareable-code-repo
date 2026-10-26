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
}
