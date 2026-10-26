package com.turing.thumbcard.devtool

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.turing.thumbcard.R
import com.turing.thumbcard.ble.BleConstants
import com.turing.thumbcard.ble.BleWiFiClient
import com.turing.thumbcard.ble.model.ConfigStaResult
import com.turing.thumbcard.ble.model.DeviceInfoResult
import com.turing.thumbcard.ble.model.ScannedDevice
import com.turing.thumbcard.ble.model.WifiAp
import com.turing.thumbcard.ble.model.WifiScanResult
import com.turing.thumbcard.databinding.DevFragmentNetBinding
import com.turing.thumbcard.util.CurrentWifi

/**
 * 配网面板。
 *
 * 只做联调需要的四件事：
 *  1. 扫到广播里的设备（带广播版本号，用来判断固件是不是 V1.2）
 *  2. 连上并按协议顺序完成 密钥协商 → 读设备信息 → 扫 WiFi → 下发
 *  3. 每一步与每一帧都写进 [DevLog]，报文页可以直接看
 *  4. 失败时把固件返回的错误码原样显示，不做好坏判断
 *
 * 刻意不做的事：不绑定、不写数据库、不扣额度、不做多设备。
 */
class NetFragment : Fragment(), BleWiFiClient.ScanListener, BleWiFiClient.ProvisionListener {

    private var _b: DevFragmentNetBinding? = null
    private val b get() = _b!!

    private val devices = LinkedHashMap<String, ScannedDevice>()
    private val aps = ArrayList<WifiAp>()
    private lateinit var devAdapter: DevRowAdapter
    private lateinit var apAdapter: DevRowAdapter

    private var scanning = false
    private var connectedDevice: ScannedDevice? = null
    private var infoRequested = false
    private var wifiAsked = false
    private var configuring = false

    private val ble get() = (requireActivity() as DevToolActivity).bleClient

    /** 设备不响应 0x0D 时的兜底：4 秒后直接进扫描，不等蓝牙栈超时 */
    private val infoFallback = Runnable {
        if (!wifiAsked && connectedDevice != null) {
            DevLog.step("4s 内没有设备信息响应，跳过读取直接扫描 WiFi")
            requestWifiScan(manual = false)
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it } || result.isEmpty()
        if (granted) {
            startScan()
        } else {
            setStatus("蓝牙权限被拒绝，无法扫描")
            DevLog.error("蓝牙权限被拒绝：请在系统设置里授予「附近的设备」权限")
        }
    }

    /**
     * 读「当前 WiFi 名称」的权限回调。
     *
     * 无论用户给不给都继续弹输入框：给了就预填、没给就留空手打。
     * 这里不做「拒绝就提示失败」——那会让本来还能手输的流程也走不下去。
     */
    private val ssidPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        DevLog.step(if (granted) "已获得读取 WiFi 名称的权限" else "未授权，SSID 需手动填写")
        showManualSsidDialog()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = DevFragmentNetBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        devAdapter = DevRowAdapter { row ->
            (row.tag as? ScannedDevice)?.let { connect(it) }
        }
        apAdapter = DevRowAdapter { row ->
            (row.tag as? WifiAp)?.let { askPassword(it) }
        }

        b.rvDevices.layoutManager = LinearLayoutManager(requireContext())
        b.rvDevices.adapter = devAdapter
        b.rvAps.layoutManager = LinearLayoutManager(requireContext())
        b.rvAps.adapter = apAdapter

        b.btnScan.setOnClickListener {
            if (scanning) stopScan("点按钮停止") else ensurePermission()
        }
        b.btnDisconnect.setOnClickListener { disconnect() }
        b.btnRescanWifi.setOnClickListener { requestWifiScan(manual = true) }
        b.btnManualSsid.setOnClickListener { askManualSsid() }

        if (connectedDevice == null) setStatus("点右上「扫描设备」开始")

        // 记一笔本机当前 WiFi：手输 SSID 会拿它当默认值。
        // 分开报「未授权」和「未连接」，因为这两种情况的处理方式完全不同
        // （前者去系统设置给权限，后者先让手机连上 2.4G）。
        val link = CurrentWifi.link(requireContext())
        DevLog.step(
            when {
                link != null -> "本机 WiFi：${link.ssid}" +
                    "（${link.freqMhz}MHz，${if (link.is5G) "5G" else "2.4G"}）"
                !CurrentWifi.hasPermission(requireContext()) -> "本机 WiFi：未授权，读不到名称"
                else -> "本机 WiFi：未连接或系统未返回名称"
            }
        )
        renderWifiTitle()
    }

    override fun onDestroyView() {
        b.root.removeCallbacks(infoFallback)
        _b = null
        super.onDestroyView()
    }

    // ==================== 权限 ====================

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun ensurePermission() {
        val missing = neededPermissions().filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScan() else permLauncher.launch(missing.toTypedArray())
    }

    // ==================== 扫描设备 ====================

    private fun startScan() {
        devices.clear()
        devAdapter.submit(emptyList())
        scanning = true
        b.btnScan.text = getString(R.string.dev_stop_scan)
        setStatus(getString(R.string.dev_scanning))
        DevLog.step("开始扫描广播（只认 ORG-ID 0x070C 或名字 AIPicCamera 开头）")
        ble.startScan(this)
    }

    private fun stopScan(reason: String) {
        DevLog.step("停止扫描：$reason")
        ble.stopScan()
        scanning = false
        b.btnScan.text = getString(R.string.dev_scan)
        if (devices.isEmpty()) setStatus(getString(R.string.dev_no_device)) else setStatus("已停止扫描")
    }

    override fun onDeviceFound(device: ScannedDevice) {
        devices[device.mac] = device
        val rows = devices.values
            .sortedByDescending { it.rssi }
            .map { d ->
                DevRow(
                    title = d.displayName,
                    sub = buildString {
                        append(d.mac)
                        append("  ·  广播版本 0x").append(Integer.toHexString(d.version).uppercase())
                        if (!d.supportsWifiListV2) append("（老固件，列表无加密类型）")
                    },
                    trail = "${d.rssi} dBm",
                    active = connectedDevice?.mac == d.mac,
                    tag = d
                )
            }
        devAdapter.submit(rows)
        if (rows.isNotEmpty()) setStatus("发现 ${rows.size} 台，点一行连接")
    }

    override fun onScanFailed(reason: String) {
        scanning = false
        b.btnScan.text = getString(R.string.dev_scan)
        setStatus("扫描失败：$reason")
        DevLog.error("扫描失败：$reason")
    }

    // ==================== 连接与配网流程 ====================

    private fun connect(device: ScannedDevice) {
        if (scanning) stopScan("开始连接")
        connectedDevice = device
        infoRequested = false
        wifiAsked = false
        configuring = false
        aps.clear()
        apAdapter.submit(emptyList())
        b.btnRescanWifi.visibility = View.GONE
        b.tvDeviceInfo.visibility = View.GONE
        b.btnDisconnect.visibility = View.VISIBLE
        setStatus("连接 ${device.displayName} …")
        DevLog.step("连接 ${device.mac}（广播版本 0x${Integer.toHexString(device.version).uppercase()}）")
        ble.connect(device.mac, this)
    }

    private fun disconnect() {
        b.root.removeCallbacks(infoFallback)
        ble.disconnect()
        connectedDevice = null
        scanning = false
        b.btnScan.text = getString(R.string.dev_scan)
        b.btnDisconnect.visibility = View.GONE
        b.btnRescanWifi.visibility = View.GONE
        setStatus("已断开")
        DevLog.step("主动断开连接")
    }

    override fun onConnecting() {
        setStatus("连接中…")
    }

    override fun onConnected() {
        setStatus("已连接，等待服务发现…")
        DevLog.step("GATT 已连接")
    }

    override fun onServiceFound() {
        DevLog.step("服务发现完成，开始密钥协商（0x0F/0x8F）")
        ble.negotiateSecretKey()
    }

    override fun onKeyNegotiated() {
        setStatus("密钥协商完成，读取设备信息…")
        DevLog.step("密钥协商完成，查询设备信息（0x0D/0x8D）")
        if (!infoRequested) {
            infoRequested = true
            // 结果统一走 onDeviceInfo / onError，这里不再重复处理
            ble.queryDeviceInfo { }
            b.root.postDelayed(infoFallback, 4000L)
        }
    }

    override fun onDeviceInfo(info: DeviceInfoResult) {
        b.root.removeCallbacks(infoFallback)
        renderDeviceInfo(info)
        if (!wifiAsked) requestWifiScan(manual = false)
    }

    private fun renderDeviceInfo(info: DeviceInfoResult) {
        b.tvDeviceInfo.visibility = View.VISIBLE
        b.tvDeviceInfo.text = buildString {
            append("apikey      : ").append(maskKey(info.apikey)).append('\n')
            append("deviceId    : ").append(info.deviceId ?: "未返回").append('\n')
            append("固件版本    : ").append(info.firmwareVersionText)
            if (info.firmwareVersion != null) {
                append("  (0x").append(Integer.toHexString(info.firmwareVersion).uppercase()).append(')')
            }
            append('\n')
            append("ORG-ID      : ")
            append(info.orgId?.let { "0x" + Integer.toHexString(it).uppercase().padStart(4, '0') } ?: "未返回")
            append("     型号 : ").append(info.deviceType?.toString() ?: "未返回")
        }
        DevLog.step(
            "设备信息：deviceId=${info.deviceId ?: "-"} 固件=${info.firmwareVersionText} " +
                "ORG-ID=${info.orgId?.let { "0x" + Integer.toHexString(it).uppercase().padStart(4, '0') } ?: "-"}"
        )
    }

    private fun requestWifiScan(manual: Boolean) {
        if (connectedDevice == null) return
        if (configuring) return
        if (wifiAsked && !manual) return
        wifiAsked = true
        b.root.removeCallbacks(infoFallback)
        b.tvWifiTitle.text = "设备扫到的 WiFi（扫描中…）"
        setStatus("让设备扫描 2.4G 热点…")
        DevLog.step(if (manual) "重新请求扫描 WiFi（0x0E）" else "请求扫描 WiFi（0x0E）")
        val sent = ble.startWifiScan { result -> onWifiList(result) }
        if (!sent) {
            setStatus("扫描指令发送失败（连接可能已断开）")
            DevLog.error("startWifiScan 返回 false，指令未发出")
        }
    }

    private fun onWifiList(result: WifiScanResult) {
        aps.clear()
        aps.addAll(result.aps)
        apAdapter.submit(aps.map { ap -> toRow(ap) })
        renderWifiTitle()
        b.btnRescanWifi.visibility = View.VISIBLE
        if (aps.isEmpty()) {
            setStatus("没有热点列表：固件可能未实现 0x0E 扫描，可点「手输 SSID」直接下发")
            DevLog.error("扫描结果为空")
        } else {
            setStatus("扫到 ${aps.size} 个热点，点一个下发密码")
        }
    }

    private fun renderWifiTitle() {
        b.tvWifiTitle.text = when {
            aps.isEmpty() -> "设备扫到的 WiFi"
            else -> "设备扫到的 WiFi（${aps.size} 个，已按信号降序）"
        }
    }

    private fun toRow(ap: WifiAp): DevRow {
        val lock = if (ap.isOpen) "开放" else "加密 ${authText(ap.auth)}"
        return DevRow(
            title = ap.ssid,
            sub = lock,
            trail = "${ap.rssi} dBm",
            tag = ap
        )
    }

    private fun authText(auth: Int): String = when (auth) {
        WifiAp.Auth.OPEN -> "OPEN"
        WifiAp.Auth.WEP -> "WEP"
        WifiAp.Auth.WPA_WPA2 -> "WPA/WPA2"
        WifiAp.Auth.WPA2 -> "WPA2"
        WifiAp.Auth.WPA3 -> "WPA3"
        WifiAp.Auth.WPA2_WPA3 -> "WPA2/WPA3"
        else -> "未知"
    }

    override fun onWifiScanBusy() {
        setStatus("设备正在扫描上一轮，稍等再试")
        DevLog.step("设备回报 scan_busy（0x8E 0x81=0x08），本轮跳过")
    }

    private fun askPassword(ap: WifiAp) {
        if (ap.isOpen) {
            configure(ap.ssid, "")
            return
        }
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "WiFi 密码"
            setPadding(30, 30, 30, 30)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(ap.ssid)
            .setView(input)
            .setNegativeButton(R.string.dev_cancel, null)
            .setPositiveButton(R.string.dev_send_wifi) { _, _ ->
                configure(ap.ssid, input.text.toString())
            }
            .show()
    }

    /**
     * 手输 SSID + 密码。
     *
     * 固件早期普遍没有实现「设备扫 WiFi 回列表」（0x0E/0x88），
     * 没有这个入口客户就配不上网。联调工具必须留这条路。
     */
    private fun askManualSsid() {
        if (connectedDevice == null) {
            setStatus("先连上设备，再手输 SSID")
            return
        }
        // 读「手机当前连着的 WiFi」需要附近设备/定位权限。没给也照常弹窗，
        // 只是输入框留空 —— 不能因为读不到默认值就把配网流程拦住。
        val perm = CurrentWifi.requiredPermission()
        if (perm != null && !CurrentWifi.hasPermission(requireContext())) {
            DevLog.step("申请读取当前 WiFi 名称的权限（$perm）")
            ssidPermLauncher.launch(perm)
            return
        }
        showManualSsidDialog()
    }

    private fun showManualSsidDialog() {
        val ctx = context ?: return
        val link = CurrentWifi.link(ctx)
        val preset = link?.ssid

        val ssidInput = EditText(ctx).apply {
            hint = "WiFi 名称（SSID）"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            if (!preset.isNullOrBlank()) {
                setText(preset)
                setSelection(preset.length)
            }
        }
        val pwdInput = EditText(ctx).apply {
            hint = "密码（开放网络留空）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val tip = android.widget.TextView(ctx).apply {
            text = when {
                preset.isNullOrBlank() ->
                    "未读到当前 WiFi，请手动填写（必须是 2.4G）"
                // 手机连的是 5G 时，预填值很可能是「一定配不上」的那个：
                // 相机只支持 2.4G。这句话不能省，否则用户会照填、等到超时。
                link.is5G ->
                    "已填入：$preset（手机当前是 5G ${link.freqMhz}MHz）\n" +
                        "相机只支持 2.4G。若这个名称同时有 2.4G 就不用改；" +
                        "若是 5G 专用名，请改成 2.4G 的名称"
                else ->
                    "已填入当前连接的 WiFi：$preset\n不是这个就点名称改掉（必须是 2.4G）"
            }
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
            addView(tip)
            addView(ssidInput)
            addView(pwdInput)
        }
        if (!preset.isNullOrBlank()) {
            // 预填后把光标放到密码框：用户要改名称就点一下，不改直接输密码
            pwdInput.requestFocus()
        }
        AlertDialog.Builder(ctx)
            .setTitle("输入 WiFi")
            .setView(box)
            .setNegativeButton(R.string.dev_cancel, null)
            .setPositiveButton(R.string.dev_send_wifi) { _, _ ->
                val ssid = ssidInput.text.toString().trim()
                if (ssid.isBlank()) {
                    setStatus("SSID 不能为空")
                } else {
                    configure(ssid, pwdInput.text.toString())
                }
            }
            .show()
    }

    private fun configure(ssid: String, password: String) {
        if (connectedDevice == null) return
        configuring = true
        b.tvWifiTitle.text = "正在下发：$ssid"
        setStatus("下发配网：$ssid（等设备联网，最长 50 秒）")
        DevLog.step("下发配网 SSID=$ssid password=${if (password.isEmpty()) "(空)" else "******"}")
        ble.configureSta(ssid, password, null) { }
    }

    override fun onWifiConfigured(result: ConfigStaResult) {
        configuring = false
        renderWifiTitle()
        if (result.isSuccess) {
            setStatus("配网成功  IP=${result.ipAddress ?: "-"}  MAC=${result.mac ?: "-"}")
            DevLog.step("配网成功：IP=${result.ipAddress ?: "-"} MAC=${result.mac ?: "-"}")
            Toast.makeText(requireContext(), "配网成功", Toast.LENGTH_LONG).show()
        } else {
            val why = when (result.errorId) {
                1 -> "WiFi 不可见（SSID 错或路由器隐藏）"
                2 -> "密码错误"
                3 -> "已连上但取 IP 失败（DHCP）"
                else -> "未知错误"
            }
            setStatus("配网失败  错误码=${result.errorId}（$why）")
            DevLog.error("配网失败：errorId=${result.errorId} $why")
        }
    }

    override fun onError(code: Int, message: String) {
        if (code == BleConstants.STATUS_QUERY_INFO_UNSUPPORTED) {
            // 固件没实现 0x0D，属预期；直接进扫描，不要报错
            DevLog.step("设备不支持 0x0D 查询（$message），跳过凭据读取")
            b.root.removeCallbacks(infoFallback)
            if (!wifiAsked) requestWifiScan(manual = false)
            return
        }
        setStatus("错误 $code：$message")
        DevLog.error("错误 $code：$message")
    }

    override fun onDisconnected() {
        setStatus("连接已断开")
        DevLog.step("设备断开连接")
        connectedDevice = null
        b.btnDisconnect.visibility = View.GONE
    }

    override fun onTraffic(direction: String, hex: String) {
        DevLog.frame(direction, hex)
    }

    // ==================== 小工具 ====================

    private fun setStatus(text: String) {
        _b?.tvStatus?.text = text
    }

    private fun maskKey(key: String?): String {
        if (key.isNullOrBlank()) return "未返回"
        return if (key.length <= 8) key else key.take(6) + "…" + key.takeLast(4)
    }
}
