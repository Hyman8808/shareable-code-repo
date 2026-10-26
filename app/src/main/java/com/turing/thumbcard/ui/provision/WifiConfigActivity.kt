package com.turing.thumbcard.ui.provision

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.turing.thumbcard.BuildConfig
import com.turing.thumbcard.R
import com.turing.thumbcard.ble.BleConstants
import com.turing.thumbcard.ble.BleWiFiClient
import com.turing.thumbcard.ble.model.ConfigStaResult
import com.turing.thumbcard.ble.model.DeviceInfoResult
import com.turing.thumbcard.data.device.DeviceLangStore
import com.turing.thumbcard.data.repo.BindRepository
import com.turing.thumbcard.data.repo.BindResult
import com.turing.thumbcard.databinding.ActivityWifiConfigBinding
import com.turing.thumbcard.ui.MainActivity
import com.turing.thumbcard.ui.card.CardListActivity
import kotlinx.coroutines.launch

/**
 * WiFi 配网 + 绑定页。
 *
 * 流程：
 *  连接设备 → 密钥协商 → 查询设备信息（读取 apikey/deviceId）
 *  → 下发 WiFi → 确认配网成功 → 建立本地绑定 → 进入单词卡
 */
class WifiConfigActivity : AppCompatActivity(), BleWiFiClient.ProvisionListener {

    private lateinit var binding: ActivityWifiConfigBinding
    private lateinit var bleClient: BleWiFiClient
    private lateinit var bindRepo: BindRepository

    private var deviceMac: String = ""
    private var deviceName: String = ""

    /** 从设备读取到的凭据（查询设备信息阶段获得） */
    private var infoResult: DeviceInfoResult? = null

    private var infoHandled = false
    private var configHandled = false
    private var plaintextFallbackTried = false

    private val log = StringBuilder()

    /** 原始报文记录（用于无 adb 环境下定位问题） */
    private val traffic = StringBuilder()
    private var trafficPending = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWifiConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceMac = intent.getStringExtra(ScanDeviceActivity.EXTRA_DEVICE_MAC) ?: run {
            finish(); return
        }
        deviceName = intent.getStringExtra(ScanDeviceActivity.EXTRA_DEVICE_NAME)
            ?: getString(R.string.device_default_name)

        bleClient = BleWiFiClient(this)
        bindRepo = BindRepository(this)

        binding.tvWifiTitle.setText(R.string.wifi_title)
        binding.tvWifiHint.text = getString(R.string.wifi_device_fmt, deviceName)
        binding.btnStart.setOnClickListener { startProvision() }
        binding.btnCopyLog.setOnClickListener { copyLogToClipboard() }
    }

    override fun onDestroy() {
        bleClient.disconnect()
        super.onDestroy()
    }

    // ==================== 流程编排 ====================

    private fun startProvision() {
        val ssid = binding.etSsid.text.toString().trim()
        val pwd = binding.etPassword.text.toString()
        if (ssid.isEmpty()) {
            toast(getString(R.string.wifi_ssid_empty))
            return
        }
        setUiBusy(true)
        appendLog("开始连接设备 $deviceMac")
        // 保存待下发的 WiFi 信息，连接成功后在 onServiceFound 中继续
        pendingSsid = ssid
        pendingPwd = pwd
        bleClient.connect(deviceMac, this)
    }

    private var pendingSsid: String = ""
    private var pendingPwd: String = ""
    private var pendingBssid: String? = null

    // ==================== 回调 ====================

    override fun onConnecting() {
        appendLog("连接中…")
    }

    override fun onConnected() {
        appendLog("设备已连接")
    }

    override fun onServiceFound() {
        if (plaintextFallbackTried) {
            // 明文降级重连：Sequence 已归零，跳过密钥协商，直接明文配网
            appendLog("服务发现完成，明文模式（跳过密钥协商）")
            proceedToConfig()
        } else {
            appendLog("服务发现完成，开始密钥协商")
            bleClient.negotiateSecretKey()
        }
    }

    override fun onKeyNegotiated() {
        appendLog("密钥协商成功（加密通道已建立）")
        // ⚠️ 实测结论（v1.0.4 真机日志）：
        // 固件未实现 0x0D（查询设备信息）的响应分支，发出后设备静默丢弃 → 必然超时。
        // 因此默认**不再等待设备回凭据**，直接用预置凭据 + MAC 推导 deviceId 走配网；
        // 若设备确实支持（固件已升级），查询会在 3 秒内返回，届时用设备值覆盖。
        appendLog("尝试读取设备凭据（3s 内无响应则用预置凭据）…")
        bleClient.queryDeviceInfo { }
    }

    /**
     * 统一进入配网步骤。
     *
     * 凭据来源优先级：
     *  1. 设备返回值（固件实现 TLV 0x0A/0x0B 时）
     *  2. 预置 apikey + MAC 推导 deviceId（当前阶段的实际路径）
     */
    private fun proceedToConfig() {
        if (infoHandled) return
        infoHandled = true

        val deviceInfo = infoResult
        val finalInfo = if (deviceInfo != null && deviceInfo.hasCredential) {
            appendLog("✓ 设备返回凭据（固件已支持扩展）")
            appendLog("  deviceId = ${deviceInfo.deviceId}")
            appendLog("  apikey   = ${maskApiKey(deviceInfo.apikey)}")
            appendLog("  固件版本 = ${deviceInfo.firmwareVersionText}")
            deviceInfo
        } else {
            // ---- 当前阶段的实际路径 ----
            // 固件尚未实现 TLV 0x0A/0x0B，改用预置测试凭据：
            //  - apikey：内置测试密钥（整批设备公用，出厂烧录）
            //  - deviceId：由设备 MAC 推导（实测确认 = MAC 去冒号小写）
            appendLog("设备未返回凭据 → 使用预置凭据")
            // ⚠️ 实测：BLE 广播 MAC 与设备真实 MAC 可能相差 1（广播常做 +1 偏移），
            //    因此这里优先取人工修正值，其次才用广播 MAC 推导。
            //    详见 DeviceIdResolver 注释。
            val derived = deviceMac.replace(":", "").lowercase()
            val resolved = com.turing.thumbcard.data.repo.DeviceOverrideStore
                .resolve(this, deviceMac, null)
            val fb = DeviceInfoResult(
                apikey = BuildConfig.DEFAULT_API_KEY,
                deviceId = resolved.ifBlank { derived },
                orgId = deviceInfo?.orgId,
                deviceType = deviceInfo?.deviceType,
                firmwareVersion = deviceInfo?.firmwareVersion
            )
            com.turing.thumbcard.data.repo.DeviceOverrideStore.rememberAuto(this, derived)
            appendLog("  deviceId = ${fb.deviceId}（由 MAC 推导${if (resolved != derived) "，已按人工修正" else ""}）")
            appendLog("  apikey   = ${maskApiKey(fb.apikey)}（预置测试值）")
            fb
        }

        infoResult = finalInfo
        appendLog("下发 WiFi：$pendingSsid")

        val ok = bleClient.configureSta(pendingSsid, pendingPwd, pendingBssid) { }
        if (!ok) setUiBusy(false)
    }

    override fun onDeviceInfo(info: DeviceInfoResult) {
        // 设备真的回了凭据：记录下来，进入配网
        infoResult = info
        appendLog("收到设备信息响应")
        proceedToConfig()
    }

    /**
     * 查询超时（设备不支持 0x0D）。
     *
     * 这是**预期行为**，不应当作错误中断流程 —— 直接用预置凭据继续配网。
     */
    private fun onQueryInfoTimeout() {
        if (infoHandled) return
        appendLog("设备未响应查询指令（固件未实现 0x0D 扩展，属预期）")
        appendLog("→ 跳过凭据读取，使用预置凭据继续配网")
        infoResult = null
        proceedToConfig()
    }

    override fun onWifiConfigured(result: ConfigStaResult) {
        if (configHandled) return
        configHandled = true

        if (!result.isSuccess) {
            appendLog("✗ 配网失败：${BleConstants.messageOf(result.errorId)}")
            toast(getString(R.string.common_sync_failed_fmt, BleConstants.messageOf(result.errorId)))
            setUiBusy(false)
            return
        }

        appendLog("✓ 配网成功  IP=${result.ipAddress ?: "未知"}")
        appendLog("设备上报 MAC：${result.mac ?: "（无）"}")

        // ---- 用设备自报 MAC 校正 deviceId ----
        // 官方小程序同款逻辑：0x8A 响应里的 MAC TLV 是设备**真实 MAC**，
        // 而 BLE 扫描到的地址是广播偏移地址（常 +1）。以设备自报为准。
        // 见 DeviceIdResolver 注释与《激活清单核对与deviceId问题定位.md》。
        if (!result.mac.isNullOrBlank()) {
            val reportedId = com.turing.thumbcard.data.repo.DeviceIdResolver
                .normalizeMac(result.mac)
            val info = infoResult
            if (info != null && !reportedId.isNullOrBlank() && info.deviceId != reportedId) {
                appendLog("⚠ deviceId 校正：${info.deviceId} → $reportedId（设备自报 MAC）")
                infoResult = info.copy(deviceId = reportedId)
            }
        }

        appendLog("建立绑定关系…")

        // 条件 1、2 已满足（凭据读取成功 + 格式校验），条件 3（联网成功）刚确认
        lifecycleScope.launch {
            val info = infoResult
            if (info == null) {
                setUiBusy(false)
                return@launch
            }
            when (val r = bindRepo.bind(info, deviceName)) {
                is BindResult.Success -> {
                    appendLog("✓ 绑定完成：${r.deviceId}")
                    // 可选：通知设备绑定结果（需固件支持 0x11）
                    bleClient.sendBindResult(BleConstants.BIND_STATUS_SUCCESS)

                    // 下发设备配置：物品语言 + 界面语言 + 云端保留天数（需固件支持 0x12）。
                    // 这是「一块通用硬件适配全球市场」的关键一步：固件不区分国家，
                    // 语言与存储策略由 App 在配网时写入。固件未实现时设备不回 0x92，
                    // 但帧已写出即视为完成 —— 不能让"设备还没升级固件"影响配网成功。
                    applyDeviceConfig()
                    toast(getString(R.string.device_connected_toast))
                    binding.root.postDelayed({
                        // ⚠️ 配网成功后回**首页**，不是单词卡列表：
                        //    首页才有学习报告和「今天拍的」，是新用户配完网最该看到的东西。
                        //    CLEAR_TOP + SINGLE_TOP：MainActivity 已在栈里就复用它（onNewIntent 切 Tab），
                        //    避免再堆一个新首页出来。
                        startActivity(
                            android.content.Intent(this@WifiConfigActivity, MainActivity::class.java)
                                .addFlags(
                                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                                )
                                .putExtra(MainActivity.EXTRA_SELECT_TAB, MainActivity.TAB_HOME)
                        )
                        finish()
                    }, 800)
                }
                BindResult.NoCredential -> {
                    appendLog("✗ 设备无凭据")
                    setUiBusy(false)
                }
                is BindResult.InvalidDeviceId -> {
                    appendLog("✗ deviceId 格式非法：${r.deviceId}")
                    setUiBusy(false)
                }
                BindResult.InvalidApiKey -> {
                    appendLog("✗ apikey 格式非法")
                    setUiBusy(false)
                }
                is BindResult.StorageUnavailable -> {
                    // 容器环境（如卓易通）本地库不可用：凭据已读到，先以临时模式进入列表
                    // ⚠️ 这里仍进 CardListActivity：没有持久化绑定记录，首页会判成「未绑定」
                    //    又让用户配一次网，反而更像失败。临时模式本来就是异常兜底。
                    appendLog("⚠ 本地存储受限，无法持久化绑定：${r.detail}")
                    appendLog("以临时模式进入单词卡列表…")
                    bleClient.sendBindResult(BleConstants.BIND_STATUS_SUCCESS)
                    toast(getString(R.string.provision_temp_mode_toast))
                    binding.root.postDelayed({
                        startActivity(
                            android.content.Intent(this@WifiConfigActivity, CardListActivity::class.java)
                                .putExtra(CardListActivity.EXTRA_DEVICE_ID, info.deviceId ?: "")
                                .putExtra(CardListActivity.EXTRA_TEMP_MODE, true)
                                .putExtra(CardListActivity.EXTRA_API_KEY_OVERRIDE, info.apikey ?: "")
                        )
                        finish()
                    }, 800)
                }
            }
        }
    }

    /**
     * 把 App 侧选定的语言与存储策略写进设备。
     *
     * 失败不阻断流程：帧写出成功即标记 applied（固件未实现 0x12 时不回执，
     * 我们无法也无需区分"设备已接受"与"设备未实现"）；只有写入失败才保留
     * `pending`，等下一次连上设备自动重发。
     */
    private fun applyDeviceConfig() {
        val dl = DeviceLangStore.current(this)
        bleClient.sendDeviceConfig(dl.itemLang, dl.uiLang, dl.cloudDays) { ok ->
            if (ok) {
                DeviceLangStore.markApplied(applicationContext)
                appendLog(
                    "✓ 已下发设备语言：${DeviceLangStore.displayNameOf(dl.itemLang)}" +
                        "（界面同语言，云端保留 ${dl.cloudDays} 天）"
                )
            } else {
                appendLog("⚠ 设备语言下发失败，下次连接设备时自动重试")
            }
        }
    }

    override fun onError(code: Int, message: String) {
        // ---- 分支 1：查询设备信息超时 —— 预期行为，不是错误 ----
        // 实测固件未实现 0x0D 扩展，设备不响应。直接用预置凭据继续配网。
        if (code == BleConstants.STATUS_QUERY_INFO_UNSUPPORTED) {
            onQueryInfoTimeout()
            return
        }

        appendLog("✗ $message")

        // ---- 分支 2：明文降级 ----
        // 密钥协商失败/超时时，断开重连后跳过加密直接完成配网。
        // 协议允许明文传输（3.2 节）。必须重连以复位 Sequence 状态。
        if (!infoHandled && message.contains("密钥") && !plaintextFallbackTried) {
            plaintextFallbackTried = true
            appendLog("→ 切换明文模式，重新连接设备…")
            bleClient.disconnect()
            appendLog("开始连接设备 $deviceMac")
            bleClient.connect(deviceMac, this)
            return
        }

        toast(message)
        setUiBusy(false)
        bleClient.disconnect()
    }

    override fun onDisconnected() {
        appendLog("设备连接已断开")
        if (!infoHandled || !configHandled) setUiBusy(false)
    }

    /**
     * 原始报文回调。
     *
     * 由于设备端（手机）可能无法使用 adb，这里把每一帧的十六进制
     * 记录下来，供用户一键复制回传，作为定位配网问题的直接证据。
     *
     * 为避免刷屏，日志面板只展示帧的**摘要行**，完整报文进 [traffic]。
     */
    override fun onTraffic(direction: String, hex: String) {
        traffic.append(direction).append(' ').append(hex).append('\n')
        trafficPending++
        // 每 4 帧在 UI 日志里提示一次，避免刷屏
        if (trafficPending % 4 == 1) {
            val brief = if (hex.length > 24) hex.take(24) + "…" else hex
            appendLog("  $direction $brief")
        }
        binding.btnCopyLog.visibility = View.VISIBLE
    }

    private fun copyLogToClipboard() {
        val report = buildString {
            appendLine("═══ 单词卡相机 · 配网调试日志 ═══")
            appendLine("设备 MAC: $deviceMac")
            appendLine("设备名称: $deviceName")
            appendLine("待配 SSID: $pendingSsid")
            appendLine()
            appendLine("─── 流程日志 ───")
            appendLine(log.toString().trimEnd())
            appendLine()
            appendLine("─── 原始报文（App: >>> 发送 / <<< 接收）───")
            if (traffic.isEmpty()) {
                appendLine("(无报文记录)")
            } else {
                append(traffic.toString().trimEnd())
            }
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.wifi_log_clip_label), report)
            )
        toast(getString(R.string.common_copied))
    }

    // ==================== UI ====================

    private fun setUiBusy(busy: Boolean) {
        binding.btnStart.isEnabled = !busy
        binding.btnStart.text = if (busy) getString(com.turing.thumbcard.R.string.wifi_connecting)
        else getString(com.turing.thumbcard.R.string.wifi_start)
        binding.svLog.visibility = if (busy || log.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun appendLog(line: String) {
        log.append(line).append('\n')
        binding.tvLog.text = log.toString()
        binding.svLog.visibility = View.VISIBLE
        binding.svLog.post { binding.svLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** 日志中脱敏显示 apikey，仅保留首尾各 4 位 */
    private fun maskApiKey(key: String?): String {
        if (key == null) return "(空)"
        if (key.length <= 8) return "****"
        return "${key.take(4)}****${key.takeLast(4)}"
    }
}
