package com.turing.thumbcard.ui.provision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.turing.thumbcard.R
import com.turing.thumbcard.ble.BleWiFiClient
import com.turing.thumbcard.ble.model.ScannedDevice
import com.turing.thumbcard.databinding.ActivityScanDeviceBinding
import com.turing.thumbcard.databinding.ItemDeviceBinding

/**
 * 设备扫描页。
 *
 * 按 ORG-ID（联盛德 0x070C）过滤，只展示本厂设备。
 */
class ScanDeviceActivity : AppCompatActivity(), BleWiFiClient.ScanListener {

    companion object {
        const val EXTRA_DEVICE_MAC = "extra_device_mac"
        const val EXTRA_DEVICE_NAME = "extra_device_name"

        /** 重新扫描的最小间隔（Android 限制 30 秒内最多 5 次扫描） */
        private const val SCAN_THROTTLE_MS = 3000L
    }

    private lateinit var binding: ActivityScanDeviceBinding
    private lateinit var bleClient: BleWiFiClient
    private val adapter = DeviceAdapter { device -> onDeviceClicked(device) }
    private val found = LinkedHashMap<String, ScannedDevice>()
    private var lastScanAt = 0L
    private var stopScanRunnable: Runnable? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it } || result.isEmpty()
        if (granted) startScan() else {
            Toast.makeText(this, com.turing.thumbcard.R.string.scan_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bleClient = BleWiFiClient(this)

        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = adapter
        binding.btnScanBack.setOnClickListener { finish() }
        binding.btnRescan.setOnClickListener { startScan() }

        requestPermissionsIfNeeded()
    }

    override fun onDestroy() {
        stopScanRunnable?.let { binding.root.removeCallbacks(it) }
        bleClient.stopScan()
        super.onDestroy()
    }

    // ==================== 权限 ====================

    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun requestPermissionsIfNeeded() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScan() else permissionLauncher.launch(missing.toTypedArray())
    }

    // ==================== 扫描 ====================

    private fun startScan() {
        // 3 秒节流：Android 对「30 秒内启动扫描次数」有硬限制，
        // 连点刷新到第 6~7 次时 startScan 会被系统直接拒绝，表现为「突然扫不到设备」。
        val now = System.currentTimeMillis()
        if (now - lastScanAt < SCAN_THROTTLE_MS) {
            return
        }
        lastScanAt = now

        found.clear()
        adapter.submit(emptyList())
        binding.pbScan.visibility = android.view.View.VISIBLE
        binding.tvScanEmpty.visibility = android.view.View.GONE
        bleClient.startScan(this)

        // 12 秒后自动停止并提示
        stopScanRunnable?.let { binding.root.removeCallbacks(it) }
        val r = Runnable {
            bleClient.stopScan()
            binding.pbScan.visibility = android.view.View.GONE
            if (found.isEmpty()) binding.tvScanEmpty.visibility = android.view.View.VISIBLE
        }
        stopScanRunnable = r
        binding.root.postDelayed(r, 12_000)
    }

    override fun onDeviceFound(device: ScannedDevice) {
        found[device.mac] = device
        adapter.submit(found.values.sortedByDescending { it.rssi })
        binding.tvScanEmpty.visibility = android.view.View.GONE
    }

    override fun onScanFailed(reason: String) {
        binding.pbScan.visibility = android.view.View.GONE
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
    }

    private fun onDeviceClicked(device: ScannedDevice) {
        bleClient.stopScan()
        startActivity(
            Intent(this, WifiConfigActivity::class.java)
                .putExtra(EXTRA_DEVICE_MAC, device.mac)
                .putExtra(EXTRA_DEVICE_NAME, device.displayName)
        )
    }

    // ==================== 列表 ====================

    private class DeviceAdapter(
        private val onClick: (ScannedDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.VH>() {

        private val items = ArrayList<ScannedDevice>()

        fun submit(list: Collection<ScannedDevice>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        class VH(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val d = items[position]
            holder.binding.tvDeviceName.text = d.displayName
            holder.binding.tvDeviceMac.text =
                holder.binding.root.context.getString(
                    R.string.scan_signal_fmt,
                    d.mac, d.rssi, (d.version shr 4), (d.version and 0x0F)
                )
            holder.binding.root.setOnClickListener { onClick(d) }
        }

        override fun getItemCount(): Int = items.size
    }
}
