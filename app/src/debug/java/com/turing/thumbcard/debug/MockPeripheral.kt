package com.turing.thumbcard.debug

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.util.Log
import java.util.UUID

/**
 * 【仅 debug】把本机扮成一台拇指相机。
 *
 * 做两件事：
 *  1. **广播**：设备名 `AIPicCamera` + 4 位 hex，可选带 `0xFF` 制造商数据
 *     （`0C 07 | 01 | 12 | 编号 ASCII`），用来验证 App 的两条识别通道。
 *  2. **GATT server**：只应答连接与写请求，**不回任何业务数据**。
 *     这样 App 走到「请求扫描」后收不到响应，6s 后应亮出「手动输入 WiFi」——
 *     正是未升级固件的真实现场，可用来验兜底逻辑。
 *
 * 注意：会临时修改**本机蓝牙适配器名称**，停止时恢复原值。
 */
@SuppressLint("MissingPermission")
class MockPeripheral(private val ctx: Context) {

    private var advertiser: BluetoothLeAdvertiser? = null
    private var server: BluetoothGattServer? = null
    private var callback: AdvertiseCallback? = null
    private var originalName: String? = null
    var running = false
        private set

    fun start(withOrgId: Boolean) {
        stop()
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = mgr.adapter ?: return
        if (!adapter.isEnabled) {
            Log.w(TAG, "蓝牙未开启，无法模拟")
            return
        }

        // ---- 广播名：靠改 adapter.name + includeDeviceName 才能进 0x09 段 ----
        originalName = adapter.name
        runCatching { adapter.setName(FAKE_NAME) }
            .onFailure { Log.w(TAG, "改适配器名失败：${it.message}") }

        // ---- GATT server：只收不回 ----
        server = mgr.openGattServer(ctx, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                Log.i(TAG, "连接状态 newState=$newState ${device.address}")
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                // 故意不回应业务数据 —— 模拟「固件不认这条指令」
                Log.i(TAG, "收到写请求 ${value.size}B（按模拟器规则忽略）")
                if (responseNeeded) {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
        })

        val svc = BluetoothGattService(UUID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                UUID_WRITE,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                UUID_NOTIFY,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
        )
        val added = server?.addService(svc) ?: false
        Log.i(TAG, "GATT service 注册 ${if (added) "成功" else "失败"}")

        // ---- 广播 ----
        advertiser = adapter.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val builder = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
        if (withOrgId) {
            // 0xFF 段：ORG-ID 小端 0C 07 | Type 01 | Version 12 | 编号 ASCII "3F2A"
            builder.addManufacturerData(
                0x070C,
                byteArrayOf(0x01, 0x12, 0x33, 0x46, 0x32, 0x41)
            )
        }

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                running = true
                Log.i(TAG, "广播已启动 name=$FAKE_NAME orgId=$withOrgId")
            }

            override fun onStartFailure(errorCode: Int) {
                running = false
                Log.e(TAG, "广播启动失败 errorCode=$errorCode")
            }
        }
        callback = cb
        advertiser?.startAdvertising(settings, builder.build(), cb)
        Log.i(TAG, "startAdvertising 已调用（orgId=$withOrgId）")
    }

    fun stop() {
        callback?.let { runCatching { advertiser?.stopAdvertising(it) } }
        callback = null
        runCatching { server?.clearServices() }
        runCatching { server?.close() }
        server = null
        running = false
    }

    /** 恢复本机蓝牙名（Receiver 收到停止指令时调用） */
    fun restoreName() {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = mgr.adapter ?: return
        originalName?.let { runCatching { adapter.setName(it) } }
    }

    companion object {
        private const val TAG = "MockPeripheral"
        const val FAKE_NAME = "AIPicCamera3F2A"
        private val UUID_SERVICE = UUID.fromString("00001824-0000-1000-8000-00805f9b34fb")
        private val UUID_WRITE = UUID.fromString("00002abc-0000-1000-8000-00805f9b34fb")
        private val UUID_NOTIFY = UUID.fromString("00002abd-0000-1000-8000-00805f9b34fb")
    }
}
