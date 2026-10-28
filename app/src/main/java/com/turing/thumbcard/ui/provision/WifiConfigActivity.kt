package com.turing.thumbcard.ui.provision

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.turing.thumbcard.BuildConfig
import com.turing.thumbcard.R
import com.turing.thumbcard.ble.BleConstants
import com.turing.thumbcard.ble.BleWiFiClient
import com.turing.thumbcard.ble.model.ConfigStaResult
import com.turing.thumbcard.ble.model.DeviceInfoResult
import com.turing.thumbcard.ble.model.WifiAp
import com.turing.thumbcard.ble.model.WifiScanResult
import com.turing.thumbcard.data.device.DeviceLangStore
import com.turing.thumbcard.data.repo.BindRepository
import com.turing.thumbcard.data.repo.BindResult
import com.turing.thumbcard.databinding.ActivityWifiConfigBinding
import com.turing.thumbcard.databinding.DialogWifiManualBinding
import com.turing.thumbcard.databinding.DialogWifiPasswordBinding
import com.turing.thumbcard.ui.MainActivity
import com.turing.thumbcard.ui.card.CardListActivity
import com.turing.thumbcard.util.CurrentWifi
import kotlinx.coroutines.launch

/**
 * WiFi 配网 + 绑定页（配网协议 V1.2 · 列表式）。
 *
 * 流程（与 V1.1 及以前最大的区别：**用户不再手输 SSID**）：
 * ```
 * 进页 → 连接设备 → 密钥协商 → 查询设备信息（读 apikey/deviceId）
 *      → 请求设备扫描 WiFi（0x0E）→ 收 0x88 列表 + 0x89 结束
 *      → 用户点选热点（开放网络直接下发，加密网络弹密码框）
 *      → 下发 0x0A → 0x8A 成功 → 本地绑定 → 下发设备配置（0x12）→ 首页
 * ```
 *
 * 三点设计取舍：
 *  1. **进页即连**，不再等用户填表。列表是从设备来的，不连就拿不到，
 *     所以"先填再连"在新模型下没有意义。
 *  2. **刷新有 3s 节流**。图灵线上曾出现"刷新连点导致设备扫描线程踩踏"（#2349），
 *     这里主动挡一层，避免把问题带给固件。
 *  3. **超时也展示已收到的部分列表**。设备扫 10 个热点时若中途超时，
 *     用户看到 6 个也比看到空页面强。
 */
class WifiConfigActivity : AppCompatActivity(), BleWiFiClient.ProvisionListener {

    private lateinit var binding: ActivityWifiConfigBinding
    private lateinit var bleClient: BleWiFiClient
    private lateinit var bindRepo: BindRepository
    private lateinit var apAdapter: WifiApAdapter

    private var deviceMac: String = ""
    private var deviceName: String = ""

    /** 从设备读取到的凭据（查询设备信息阶段获得） */
    private var infoResult: DeviceInfoResult? = null

    private var infoHandled = false
    private var configHandled = false
    private var plaintextFallbackTried = false

    /**
     * 是否已经为「公钥发送失败」换过一条干净链路重试。
     *
     * 现场反馈「提示公钥发送失败，重启 App 也不行，反复重启多次后才正常」——
     * 这是 ATT 队列被上一条未收尾的操作堵住的典型表现（GATT 层面脏状态），
     * **只有重建 GATT 才能清掉**。所以这里自动断开重连一次，
     * 免去用户手动杀进程。只重试一次，避免死循环。
     */
    private var gattRetried = false

    /** 是否正在等设备回列表 */
    private var scanning = false

    /** 正在下发 0x0A / 等 0x8A，期间禁止再点列表 */
    private var configBusy = false

    /** 上次发起扫描的时间，用于 3s 节流 */
    private var lastScanAt = 0L

    private var pendingSsid: String = ""
    private var pendingPwd: String = ""
    private var pendingBssid: String? = null

    /** 读 WiFi 名称的权限只主动要一次，避免「点一下弹一次」 */
    private var ssidPermAsked = false

    /**
     * 读 SSID 的权限回调。
     *
     * 给不给都继续弹输入框：给了就预填当前网络，没给就留空手打。
     * 这里不做「拒绝就报错」——那等于把一个本来还能用的兜底入口关掉。
     */
    private val ssidPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { showManualDialog() }

    private val log = StringBuilder()

    /** 原始报文记录（用于无 adb 环境下定位问题） */
    private val traffic = StringBuilder()
    private var trafficPending = 0

    private var pwdDialog: AlertDialog? = null
    private var manualDialog: AlertDialog? = null

    /** 慢扫描提示/手动入口的延时任务 */
    private var slowScanHint: Runnable? = null

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

        apAdapter = WifiApAdapter { onApPicked(it) }
        binding.rvAp.layoutManager = LinearLayoutManager(this)
        binding.rvAp.adapter = apAdapter

        binding.btnRefresh.setOnClickListener { requestScan(manual = true) }
        binding.btnManualSsid.setOnClickListener { showManualDialog() }
        binding.btnCopyLog.setOnClickListener { copyLogToClipboard() }

        showListState(getString(R.string.wifi_scan_loading))
        appendLog("开始连接设备 $deviceMac")
        bleClient.connect(deviceMac, this)
    }

    override fun onDestroy() {
        cancelSlowScanHint()
        pwdDialog?.dismiss()
        pwdDialog = null
        manualDialog?.dismiss()
        manualDialog = null
        bleClient.cancelWifiScan()
        bleClient.disconnect()
        super.onDestroy()
    }

    // ==================== 回调 ====================

    override fun onConnecting() {
        appendLog("连接中…")
    }

    override fun onConnected() {
        appendLog("设备已连接")
    }

    override fun onServiceFound() {
        if (plaintextFallbackTried) {
            // 明文降级重连：Sequence 已归零，跳过密钥协商
            appendLog("服务发现完成，明文模式（跳过密钥协商）")
            proceedToScan()
        } else {
            appendLog("服务发现完成，开始密钥协商")
            bleClient.negotiateSecretKey()
        }
    }

    override fun onKeyNegotiated() {
        appendLog("密钥协商成功（加密通道已建立）")
        // 固件未实现 0x0D 响应分支时会静默丢弃 → 3s 后按预期超时继续
        appendLog("尝试读取设备凭据（3s 内无响应则用预置凭据）…")
        bleClient.queryDeviceInfo { }
    }

    override fun onDeviceInfo(info: DeviceInfoResult) {
        infoResult = info
        appendLog("收到设备信息响应")
        proceedToScan()
    }

    /**
     * 凭据就绪后进入扫描阶段。
     *
     * 凭据来源优先级：
     *  1. 设备返回值（固件实现 TLV 0x0A/0x0B 时）
     *  2. 预置 apikey + MAC 推导 deviceId（当前阶段的实际路径）
     */
    private fun proceedToScan() {
        if (infoHandled) return
        infoHandled = true

        val deviceInfo = infoResult
        infoResult = if (deviceInfo != null && deviceInfo.hasCredential) {
            appendLog("✓ 设备返回凭据（固件已支持扩展）")
            appendLog("  deviceId = ${deviceInfo.deviceId}")
            appendLog("  apikey   = ${maskApiKey(deviceInfo.apikey)}")
            appendLog("  固件版本 = ${deviceInfo.firmwareVersionText}")
            deviceInfo
        } else {
            appendLog("设备未返回凭据 → 使用预置凭据")
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

        // 凭据拿到后马上要列表：设备扫一次约 2~4s，越早发越省用户等待
        requestScan(manual = false)
    }

    /**
     * 查询超时（设备不支持 0x0D）—— **预期行为**，不是错误。
     */
    private fun onQueryInfoTimeout() {
        if (infoHandled) return
        appendLog("设备未响应查询指令（固件未实现 0x0D 扩展，属预期）")
        appendLog("→ 跳过凭据读取，使用预置凭据继续")
        infoResult = null
        proceedToScan()
    }

    // ==================== 扫描 ====================

    /**
     * 请求设备扫描 WiFi。
     *
     * @param manual 用户主动点刷新。此时要做 3s 节流并清空旧列表；
     *               自动触发（刚连上设备）不受节流限制。
     */
    private fun requestScan(manual: Boolean) {
        if (configBusy) return
        if (scanning) {
            // 上一轮还没回来：不重复下发，避免把设备的扫描线程打乱
            if (manual) toast(getString(R.string.wifi_scan_wait))
            return
        }
        if (manual) {
            val now = System.currentTimeMillis()
            if (now - lastScanAt < SCAN_THROTTLE_MS) {
                toast(getString(R.string.wifi_refresh_wait))
                return
            }
            apAdapter.submit(emptyList())
        }

        scanning = true
        lastScanAt = System.currentTimeMillis()
        showListState(getString(R.string.wifi_scan_loading))
        armSlowScanHint()
        appendLog(if (manual) "刷新：请求设备重新扫描 WiFi" else "请求设备扫描 WiFi")

        bleClient.startWifiScan { result -> onScanResult(result) }
    }

    private fun onScanResult(result: WifiScanResult) {
        scanning = false
        cancelSlowScanHint()
        if (result.isEmpty) {
            appendLog("设备未扫描到可用的 2.4G WiFi")
            showListState(getString(R.string.wifi_scan_empty), allowManual = true)
            return
        }
        // 把「设备报了几条 / 实际收到几条 / 合并了几条」全写出来：
        // 客户对账时最常问的就是「设备上报 10 个，App 为什么只显示 8 个」。
        appendLog(
            buildString {
                append("收到 ${result.aps.size} 个热点（已按信号排序）")
                if (result.total >= 0) {
                    append("；设备上报 ${result.total} 条")
                    if (result.received > result.aps.size) {
                        append("，其中 ${result.duplicates} 条同名已合并")
                    }
                    if (!result.complete) {
                        append("；⚠ 未收齐（${result.received}/${result.total}）")
                    }
                }
            }
        )
        hideListState()
        apAdapter.submit(result.aps)
        if (result.authUnknown) {
            appendLog("⚠ 设备固件为旧版本（无加密类型），选中后一律需要输密码")
        }
    }

    override fun onWifiScanBusy() {
        // 设备正在扫：静默等待，不弹错（协议 §4.9.1）
        appendLog("设备回复「扫描进行中」，等这一轮结束")
    }

    override fun onWifiScanRetry(attempt: Int, received: Int, total: Int) {
        // 自动重发 0x0E：不是错误，别弹提示，但要让用户看到「还在动」
        scanning = true
        appendLog(
            "本轮没发完（已收 $received" + (if (total >= 0) "/$total" else "") +
                " 条），自动重试第 $attempt 次…"
        )
        armSlowScanHint()
    }

    // ==================== 选网 + 配网 ====================

    private fun onApPicked(ap: WifiAp) {
        if (configBusy) return
        if (ap.isOpen) {
            appendLog("选择开放网络：${ap.ssid}")
            startProvision(ap.ssid, "")
        } else {
            showPasswordDialog(ap)
        }
    }

    private fun showPasswordDialog(ap: WifiAp) {
        val db = DialogWifiPasswordBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.wifi_pwd_dialog_title, ap.ssid))
            .setView(db.root)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.wifi_connect, null)
            .create()

        // 密码为空时不让关窗：默认 positive 会无条件 dismiss，这里手动接管
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pwd = db.etPwd.text.toString()
                if (pwd.isEmpty()) {
                    db.tvPwdError.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                dialog.dismiss()
                startProvision(ap.ssid, pwd)
            }
        }
        dialog.setOnDismissListener { if (pwdDialog === dialog) pwdDialog = null }
        pwdDialog = dialog
        dialog.show()
        db.etPwd.requestFocus()
    }

    private fun startProvision(ssid: String, pwd: String) {
        configBusy = true
        cancelSlowScanHint()
        pendingSsid = ssid
        pendingPwd = pwd
        appendLog("下发 WiFi：$ssid")
        showListState(getString(R.string.wifi_configuring))
        val ok = bleClient.configureSta(ssid, pwd, pendingBssid) { }
        if (!ok) {
            configBusy = false
            hideListState()
        }
    }

    override fun onWifiConfigured(result: ConfigStaResult) {
        if (configHandled) return
        configHandled = true

        if (!result.isSuccess) {
            configBusy = false
            appendLog("✗ 配网失败：${BleConstants.messageOf(result.errorId)}")
            toast(getString(R.string.common_sync_failed_fmt, BleConstants.messageOf(result.errorId)))
            hideListState()
            return
        }

        appendLog("✓ 配网成功  IP=${result.ipAddress ?: "未知"}")
        appendLog("设备上报 MAC：${result.mac ?: "（无）"}")

        // ---- 用设备自报 MAC 校正 deviceId ----
        // 广播 MAC 末位常 +1，0x8A 里的 MAC 才是设备真实 MAC
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
        showListState(getString(R.string.wifi_binding))

        lifecycleScope.launch {
            val info = infoResult
            if (info == null) {
                configBusy = false
                hideListState()
                return@launch
            }
            when (val r = bindRepo.bind(info, deviceName)) {
                is BindResult.Success -> {
                    appendLog("✓ 绑定完成：${r.deviceId}")
                    bleClient.sendBindResult(BleConstants.BIND_STATUS_SUCCESS)
                    applyDeviceConfig()
                    toast(getString(R.string.device_connected_toast))
                    binding.root.postDelayed({ goHome() }, 800)
                }
                BindResult.NoCredential -> {
                    configBusy = false
                    appendLog("✗ 设备无凭据")
                    hideListState()
                }
                is BindResult.InvalidDeviceId -> {
                    configBusy = false
                    appendLog("✗ deviceId 格式非法：${r.deviceId}")
                    hideListState()
                }
                BindResult.InvalidApiKey -> {
                    configBusy = false
                    appendLog("✗ apikey 格式非法")
                    hideListState()
                }
                is BindResult.StorageUnavailable -> {
                    // 容器环境（如卓易通）本地库不可用：凭据已读到，先以临时模式进入列表
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

    private fun goHome() {
        // 配网成功后回**首页**：首页才有学习报告和「今天拍的」
        startActivity(
            android.content.Intent(this, MainActivity::class.java)
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                .putExtra(MainActivity.EXTRA_SELECT_TAB, MainActivity.TAB_HOME)
        )
        finish()
    }

    /**
     * 把 App 侧选定的语言与存储策略写进设备（0x12）。
     *
     * 失败不阻断流程：帧写出成功即标记 applied（固件未实现时不回执，
     * 无法也无需区分"已接受"与"未实现"）。
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
        if (code == BleConstants.STATUS_QUERY_INFO_UNSUPPORTED) {
            onQueryInfoTimeout()
            return
        }

        appendLog("✗ $message")

        // ---- 分支 1：等列表期间设备掉线 ----
        // 现场「第 7 次扫描必断连」走的就是这条。要点是把话说清楚，
        // 否则用户只会看到一句「设备连接已断开」而不知道该重连还是换设备。
        if (code == BleConstants.STATUS_NOT_CONNECTED) {
            scanning = false
            configBusy = false
            cancelSlowScanHint()
            appendLog("→ 设备在等列表期间掉线。请返回重新扫描设备后再试")
            showListState(getString(R.string.wifi_scan_failed), allowManual = true)
            return
        }

        // ---- 分支 2：扫描失败 ----
        if (code == BleConstants.STATUS_WIFI_SCAN) {
            scanning = false
            cancelSlowScanHint()
            appendLog("→ 可点右上角刷新重试，或手动输入 WiFi")
            showListState(getString(R.string.wifi_scan_failed), allowManual = true)
            return
        }

        // ---- 分支 3：换一条干净链路重试（公钥/密钥阶段失败） ----
        // 「重启 App 也不行、反复重启多次才正常」说明脏状态在 GATT 客户端里，
        // 断开 + 重建连接能清掉；自动做掉，别让用户去杀进程。
        if ((code == BleConstants.STATUS_GATT_WRITE ||
                code == BleConstants.STATUS_NEGOTIATE_SECRET_KEY) &&
            !gattRetried && !infoHandled && !configBusy
        ) {
            gattRetried = true
            appendLog("→ 链路状态异常，断开后重建连接重试（第 2 次）…")
            bleClient.disconnect()
            binding.root.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    appendLog("开始连接设备 $deviceMac（第 2 次）")
                    bleClient.connect(deviceMac, this)
                }
            }, 600)
            return
        }

        // ---- 分支 4：明文降级 ----
        // 密钥协商失败/超时时，断开重连后跳过加密直接完成配网。
        if (!infoHandled && message.contains("密钥") && !plaintextFallbackTried) {
            plaintextFallbackTried = true
            appendLog("→ 切换明文模式，重新连接设备…")
            bleClient.disconnect()
            appendLog("开始连接设备 $deviceMac")
            bleClient.connect(deviceMac, this)
            return
        }

        scanning = false
        configBusy = false
        toast(message)
        hideListState()
        bleClient.disconnect()
    }

    override fun onDisconnected() {
        appendLog("设备连接已断开")
        scanning = false
        if (!configHandled) configBusy = false
    }

    /**
     * 原始报文回调。
     *
     * 设备端通常无法 adb，这里把每一帧十六进制记录下来，
     * 供用户一键复制回传作为定位证据。日志面板只展示摘要行，完整报文进 [traffic]。
     */
    override fun onTraffic(direction: String, hex: String) {
        traffic.append(direction).append(' ').append(hex).append('\n')
        trafficPending++
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
            appendLine("已选 SSID: $pendingSsid")
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

    private fun showListState(text: String, allowManual: Boolean = false) {
        binding.rvAp.visibility = View.GONE
        binding.tvListState.text = text
        binding.tvListState.visibility = View.VISIBLE
        // 手动入口只在「列表拿不到」时才给，避免盖住正常点选流程
        binding.btnManualSsid.visibility = if (allowManual) View.VISIBLE else View.GONE
    }

    private fun hideListState() {
        binding.tvListState.visibility = View.GONE
        binding.rvAp.visibility = View.VISIBLE
        binding.btnManualSsid.visibility = View.GONE
    }

    /**
     * 起一个「还没回列表」的兜底计时器。
     *
     * 为什么需要：未升级的样机固件只实现 V1.0 基线，收到 0x0E 扫描指令既不回列表
     * 也不报错，App 侧只能等超时。若这 8~10s 里页面只有一句「正在扫描」，
     * 用户会以为卡死。到点后把手动输入入口亮出来，配网不至于被固件版本锁死。
     */
    private fun armSlowScanHint() {
        slowScanHint?.let { binding.root.removeCallbacks(it) }
        val r = Runnable {
            if (scanning && !configBusy) {
                appendLog("等待列表超过 ${SLOW_SCAN_HINT_MS / 1000}s，亮出手动输入兜底")
                showListState(getString(R.string.wifi_scan_slow), allowManual = true)
            }
        }
        slowScanHint = r
        binding.root.postDelayed(r, SLOW_SCAN_HINT_MS)
    }

    private fun cancelSlowScanHint() {
        slowScanHint?.let { binding.root.removeCallbacks(it) }
        slowScanHint = null
    }

    /**
     * 手动输入 SSID + 密码（V1.1 的旧通道，作为兜底保留）。
     * 密码留空即按开放网络下发，交给固件去试。
     */
    private fun showManualDialog() {
        if (configBusy) return

        // 读「手机当前连着的 WiFi」需要附近设备/定位权限。缺就补要一次；
        // 只要没拿到就照常弹窗、输入框留空 —— 不让「读不到默认值」把兜底入口堵死。
        val perm = CurrentWifi.requiredPermission()
        if (perm != null && !CurrentWifi.hasPermission(this) && !ssidPermAsked) {
            ssidPermAsked = true
            ssidPermLauncher.launch(perm)
            return
        }

        val db = DialogWifiManualBinding.inflate(layoutInflater)
        // 优先填上一次填过的（重试场景，用户可能改过名）；没填过就填手机当前连的 WiFi。
        val link = CurrentWifi.link(this)
        val preset = pendingSsid.takeIf { it.isNotBlank() } ?: link?.ssid
        db.etSsid.setText(preset)
        if (!preset.isNullOrBlank()) {
            // 提示「这不是我想要的网络」时该改哪里，否则用户会以为字段是只读的
            appendLog("已预填当前 WiFi：$preset（可修改）")
        }
        // 手机连的是 5G 时，预填值很可能是「一定配不上」的那个：相机只支持 2.4G。
        // 用错误行显红提示，否则用户照填、等 50 秒超时，最后被误导成「密码错了」。
        if (link != null && link.is5G && !preset.isNullOrBlank()) {
            db.tvManualError.text =
                getString(R.string.wifi_manual_5g_warn, preset, link.freqMhz)
            db.tvManualError.visibility = View.VISIBLE
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.wifi_manual_title)
            .setView(db.root)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.wifi_connect, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ssid = db.etSsid.text.toString().trim()
                if (ssid.isEmpty()) {
                    db.tvManualError.setText(R.string.wifi_ssid_empty)
                    db.tvManualError.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                dialog.dismiss()
                appendLog("手动输入 SSID：$ssid")
                pendingBssid = null
                startProvision(ssid, db.etPwd.text.toString())
            }
        }
        dialog.setOnDismissListener { if (manualDialog === dialog) manualDialog = null }
        manualDialog = dialog
        dialog.show()
        db.etSsid.requestFocus()
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

    private companion object {
        /** 刷新节流：与协议 V1.2 §4.9.1 的「最小重启间隔 3s」对齐 */
        const val SCAN_THROTTLE_MS = 3000L

        /**
         * 多久没收到列表就亮出手动输入。
         *
         * 取 9s：新固件扫 10 个热点实测 2~9s（弱信号/信道多时偏慢），
         * 比旧值 6s 留了一倍余量 —— 6s 就到点会让用户以为已经失败，
         * 而实际上 App 还在自动重试（协议允许 2 次），提示与事实相反。
         */
        const val SLOW_SCAN_HINT_MS = 9000L
    }
}
