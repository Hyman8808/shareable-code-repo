package com.turing.thumbcard.ble

/**
 * BleWiFi 协议常量。
 *
 * 来源：联盛德官方 Android SDK + 《固件端协议扩展规格书 V1.0》。
 * 请勿随意修改既有值，帧层与设备固件强耦合。
 */
object BleConstants {

    // ==================== GATT ====================
    /** Service UUID：BleWiFi 配置服务 */
    const val UUID_SERVICE_WIFI = "00001824-0000-1000-8000-00805f9b34fb"

    /** 读写使用同一特征 */
    const val UUID_CHARACTERISTIC_WRITE = "00002abc-0000-1000-8000-00805f9b34fb"

    /** 描述符 UUID（用于开启 indicate） */
    const val UUID_CHARACTERISTIC_READ = "00002902-0000-1000-8000-00805f9b34fb"

    /** 联盛德公司 ORG-ID，出现在广播包 0xFF 制造商数据中 */
    const val ORG_ID_WINNERMICRO = 0x070C

    /**
     * 设备名统一前缀（协议 V1.2 §3.1）。
     *
     * 完整格式：`AIPicCamera` + 4 位大写 hex 编号（MAC 低 2 字节），如 `AIPicCamera3F2A`。
     * 扫描时作为 ORG-ID 之外的兜底识别通道（固件广播字段还没改好时仍能连上）。
     */
    const val NAME_PREFIX = "AIPicCamera"

    // ==================== 帧标志位 ====================
    const val PACKET_FLAG_ENCRYPT_MASK = 0x80
    const val PACKET_FLAG_ACK_MASK = 0x40
    const val PACKET_FLAG_LAST_PACKET_MASK = 0x20

    // ==================== 命令码（App -> 设备） ====================
    const val PACKET_CMD_CONFIG_STA = 0x0A
    const val PACKET_CMD_CONFIG_AP = 0x0B
    const val PACKET_CMD_CONFIG_APSTA = 0x0C
    const val PACKET_CMD_QUERY_CONFIG = 0x0D
    const val PACKET_CMD_WIFI_SCAN = 0x0E
    const val PACKET_CMD_KEY_EXCHANGE = 0x0F
    const val PACKET_CMD_ACK = 0x10
    /** 新增：绑定结果回执（可选） */
    const val PACKET_CMD_BIND_RESULT = 0x11

    /**
     * 新增：设备配置下发（语言 + 云端保留天数）。
     *
     * 硬件与固件**不区分国家**，同一块板子发往任何市场；
     * 具体国家的表现（物品语言、设备界面语言、云端可回看天数）
     * 全部由 App 通过这条命令写入，设备再按该配置向云端拉取。
     * 对应技术方案 V3.0 第 5 章「全球化语言架构」。
     */
    const val PACKET_CMD_DEVICE_CONFIG = 0x12

    // ==================== 命令码（设备 -> App） ====================
    const val PACKET_RESP_CMD_CONFIG_STA = 0x8A
    const val PACKET_RESP_CMD_CONFIG_AP = 0x8B
    const val PACKET_RESP_CMD_CONFIG_APSTA = 0x8C
    const val PACKET_RESP_CMD_QUERY_CONFIG = 0x8D
    const val PACKET_RESP_CMD_WIFI_SCAN = 0x8E
    const val PACKET_RESP_CMD_KEY_EXCHANGE = 0x8F
    const val PACKET_RESP_CMD_ACK = 0x90
    const val PACKET_RESP_CMD_BIND_RESULT = 0x91
    /** 新增：设备配置回执（固件未实现时不回，App 按「已下发」处理） */
    const val PACKET_RESP_CMD_DEVICE_CONFIG = 0x92

    // ==================== TLV：App -> 设备 ====================
    const val TLV_TYPE_AP_SSID = 0x01
    const val TLV_TYPE_AP_PASSWORD = 0x02
    const val TLV_TYPE_AP_BSSID = 0x03
    const val TLV_TYPE_SOFT_AP_SSID = 0x04
    const val TLV_TYPE_SOFT_AP_PASSWORD = 0x05
    const val TLV_TYPE_SOFT_AP_MAX_CN = 0x06
    const val TLV_TYPE_SOFT_AP_AUTH_MODE = 0x07
    const val TLV_TYPE_SOFT_AP_CHANNEL = 0x08
    const val TLV_TYPE_PUB_KEY = 0x09
    /** 新增：绑定状态 */
    const val TLV_TYPE_BIND_STATUS = 0x0F

    // ---- 设备配置（0x12 命令携带，见技术方案 V3.0 §5.4）----
    /**
     * 物品语言（BCP-47 短码 ASCII，如 `ja` / `zh` / `zh-Hant` / `en`）。
     *
     * 设备把它连同 deviceId 一起上报云端，云端据此下发
     * 「英文名称+英文 TTS（默认）」+「该语言名称+TTS」。
     */
    const val TLV_TYPE_ITEM_LANG = 0x10

    /** 设备界面语言：提示语、开机动画字幕、按键提示。与物品语言默认一致。 */
    const val TLV_TYPE_UI_LANG = 0x11

    /** 云端记录保留天数：7（默认免费）/ 30 / 365（订阅） */
    const val TLV_TYPE_CLOUD_STORE_DAYS = 0x12

    /**
     * 零长占位 TLV。
     *
     * 官方 `MessageEncode.serialize800()` 在 TLV 集合为空时**直接 return**，
     * 不产生任何分片 —— 这会让「无参数的查询指令」（如 0x0D）根本发不出去。
     * 放一个长度为 0 的 0x00 占位符即可让帧正常生成，设备侧按未知 TLV 忽略。
     */
    const val TLV_TYPE_PLACEHOLDER = 0x00

    // ==================== TLV：设备 -> App ====================
    const val TLV_RESP_TYPE_ERROR_ID = 0x81
    const val TLV_RESP_TYPE_IP_ADDR = 0x82
    const val TLV_RESP_TYPE_MAC = 0x83
    const val TLV_RESP_TYPE_SOFTAP_IP_ADDR = 0x84
    const val TLV_RESP_TYPE_SOFTAP_MAC = 0x85
    const val TLV_RESP_TYPE_WIFI_SCAN_RESULT = 0x86
    const val TLV_RESP_TYPE_PUB_ENC_KEY = 0x87

    /**
     * V1.2 新增：WiFi 列表条目，UTF-8 文本一行：
     * `序号|RSSI|AUTH|SSID`
     *
     * SSID 刻意放在**最后**，因此允许含逗号、竖线、中文等任何字符
     * （802.11 允许 SSID 含可打印字符），解析时只需从左侧数两个 `|`。
     */
    const val TLV_RESP_TYPE_WIFI_LIST_V2 = 0x88

    /**
     * V1.2 新增：列表元信息，2 字节 `total(1B) + done(1B)`。
     *
     * `done=1` 表示本次列表发送完毕；App 用 `total` 校验是否收齐。
     */
    const val TLV_RESP_TYPE_WIFI_LIST_META = 0x89

    /** ★ 新增：APIKEY（32 字节 ASCII 十六进制） */
    const val TLV_RESP_TYPE_APIKEY = 0x0A
    /** ★ 新增：DEVICEID（≤18 字节 ASCII） */
    const val TLV_RESP_TYPE_DEVICE_ID = 0x0B
    /** ★ 新增：ORG_ID（2 字节小端 uint16） */
    const val TLV_RESP_TYPE_ORG_ID = 0x0C
    /** ★ 新增：DEVICE_TYPE（1 字节） */
    const val TLV_RESP_TYPE_DEVICE_TYPE = 0x0D
    /** ★ 新增：FIRMWARE_VER（1 字节，高 4 位主版本 + 低 4 位次版本） */
    const val TLV_RESP_TYPE_FIRMWARE_VER = 0x0E

    // ==================== 长度约束 ====================
    const val MAX_LENGTH_SSID = 32
    const val MAX_LENGTH_PWD = 64
    const val HEX_LENGTH_BSSID = 17
    /** 分片长度：W800 平台的 MTU 约束，不可调整 */
    const val PACKET_MAX_LENGTH_800 = 14

    // ==================== 绑定状态 ====================
    const val BIND_STATUS_SUCCESS = 0x00
    const val BIND_STATUS_FAILED = 0x01
    const val BIND_STATUS_CANCELLED = 0x02

    // ==================== 错误码（设备 -> App） ====================
    const val STATUS_SUCCESS = 0
    const val STATUS_INVALID_PARAMS = 1
    const val STATUS_PASSWORD = 2
    const val STATUS_DHCP_IP = 3
    const val STATUS_WIFI_SCAN = 4
    const val STATUS_NEGOTIATE_SECRET_KEY = 5
    const val STATUS_GATT_WRITE = 6

    // ==================== 错误码（App 内部） ====================
    const val STATUS_TIMEOUT = 7
    const val STATUS_BT_POWER_OFF = 8
    const val STATUS_LOCATION_DISABLE = 9
    const val STATUS_NOT_CONNECTED = 10

    /**
     * 查询设备信息超时（**非错误**）。
     *
     * 固件未实现 0x0D 扩展时的预期结果。上层识别此码后应直接
     * 回退到预置凭据继续配网，而不是中断流程。
     */
    const val STATUS_QUERY_INFO_UNSUPPORTED = 11

    /** 超时配置（秒） */
    const val TIMEOUT_CONNECT = 10
    const val TIMEOUT_NEGOTIATE = 10
    const val TIMEOUT_CONFIG_STA = 50

    /**
     * WiFi 列表收集超时（秒）。
     *
     * 设备扫一次 2.4G（遍历 13 信道）实测 2~4s，加上 10 条列表分片传输约 1s，
     * 15s 留足余量；超时后上层允许用户手动点「刷新」重试。
     */
    const val TIMEOUT_WIFI_SCAN = 15

    /**
     * `0x8E` 扫描响应里的 `0x81` 错误码（**设备侧错误 id，与 App 内部码不同命名空间**）。
     *
     * ⚠️ 不要用 `messageOf()` 解析这两个值 —— 那里 8/9 是「蓝牙未打开 / 定位未打开」。
     */
    const val WIFI_SCAN_OK = 0x00
    const val WIFI_SCAN_FAILED = 0x04

    /** V1.2 新增：扫描正在进行。**不是错误**，静默等待即可（见配网协议 V1.2 §4.9.1） */
    const val WIFI_SCAN_BUSY = 0x08

    /** V1.2 新增：扫描成功但附近没有可用的 2.4G 热点（见 §4.9.3） */
    const val WIFI_SCAN_NO_AP = 0x09

    /**
     * 查询设备信息（0x0D）超时。
     *
     * ⚠️ 刻意设短：实测固件未实现 0x0D 响应分支，正常情况**一定**超时。
     * 该超时不是错误，而是"设备不支持扩展"的判定信号 ——
     * 超时后立即回退到预置凭据配网，避免让用户白等。
     */
    const val TIMEOUT_QUERY_INFO = 3

    /**
     * 下发设备配置（0x12）后等待回执的时间。
     *
     * ⚠️ 与 0x0D 同理：固件尚未实现 0x12 时**一定**不回。
     * 因此该值只用于「帧写完了」之后的短暂观察，超时**不算失败**——
     * 上层据此把设置标记为「已下发，待固件支持」，绝不能拦着用户配网。
     */
    const val TIMEOUT_DEVICE_CONFIG = 3

    /** 连接重试次数 */
    const val CONNECT_RETRY = 5

    /**
     * 将错误码转成中文提示。
     */
    fun messageOf(code: Int): String = when (code) {
        STATUS_SUCCESS -> "成功"
        STATUS_INVALID_PARAMS -> "参数错误"
        STATUS_PASSWORD -> "WiFi 密码错误"
        STATUS_DHCP_IP -> "获取 IP 地址失败"
        STATUS_WIFI_SCAN -> "WiFi 扫描失败"
        STATUS_NEGOTIATE_SECRET_KEY -> "密钥交换失败"
        STATUS_GATT_WRITE -> "发送数据失败"
        STATUS_TIMEOUT -> "操作超时"
        STATUS_BT_POWER_OFF -> "蓝牙未打开"
        STATUS_LOCATION_DISABLE -> "定位服务未打开"
        STATUS_NOT_CONNECTED -> "设备未连接"
        STATUS_QUERY_INFO_UNSUPPORTED -> "设备未响应查询指令（固件未实现扩展）"
        else -> "未知错误($code)"
    }
}
