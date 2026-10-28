package com.turing.thumbcard.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.turing.thumbcard.ble.model.ConfigStaResult
import com.turing.thumbcard.ble.model.DeviceInfoResult
import com.turing.thumbcard.ble.model.ScannedDevice
import com.turing.thumbcard.ble.model.WifiAp
import com.turing.thumbcard.ble.model.WifiScanResult
import com.turing.thumbcard.crypto.RsaUtils
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * BleWiFi 配网客户端。
 *
 * 本实现**严格对齐联盛德官方 Android SDK `BleWiFiClient`** 的时序语义，
 * 这是设备固件所期望的行为。任何"看起来更合理"的改动都可能导致通信失败。
 *
 * ⚠️ 三条不可动的时序事实（来自官方 SDK 源码 `BleWiFiClient.java`）：
 *
 *  1. **Sequence 是「分片级」序号，每个分片自增**。
 *     官方 `__negotiateSecretKey()`：
 *     ```
 *     for (MessagePacket p : messageEncode.getMessagePacketList()) {
 *         int seq = mSendSequence.getAndIncrement();   // 每片 seq++
 *         p.buildPacket(seq, mNeedAck);
 *         gattWrite(p.packageInfo);
 *     }
 *     ```
 *     官方 `handleCharacteristicData()`：
 *     ```
 *     if (mRecvSequence.getAndIncrement() != packet.seq) { return; }  // 每片 recvSeq++
 *     ```
 *     （曾误改为"整条消息共用一个 seq"，会导致设备全部丢弃 → 协商超时。已回退。）
 *
 *  2. **发送下一片前必须等 `onCharacteristicWrite` 回调**。
 *     官方 `gattWrite()` 在 `mWriteLock` 上 `wait(1000)`，由
 *     `onCharacteristicWrite` 唤醒；超时未回调即判失败。
 *     不能"写完就 postDelayed 发下一片" —— 会超出 BLE 栈的发送队列深度而丢包。
 *
 *  3. **`parsePacket` 以 `length <= 5` 判为非法**（官方原样）。
 *     即 5 字节的"空 PAYLOAD 帧"是不接受的。
 *
 * 线程模型：所有对外回调投递到主线程；阻塞式写入在单线程池中串行执行。
 */
class BleWiFiClient(private val context: Context) {

    companion object {
        private const val TAG = "BleWiFiClient"
        private const val TRAFFIC_TAG = "BleTraffic"
        private const val TRANSPORT_LE = 2
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** 等待 onCharacteristicWrite 回调的最长时间（官方 mWriteLock.wait(1000)） */
        private const val WRITE_CALLBACK_TIMEOUT_MS = 1000L

        /** 单片的写入尝试次数（含首次）。链路抖动/GATT busy 时重发比让用户重连便宜得多 */
        private const val WRITE_MAX_ATTEMPT = 3

        /** 两次写尝试之间的间隔，给 ATT 队列收尾留时间 */
        private const val WRITE_RETRY_GAP_MS = 120L

        /** 设备没发完列表时，最多自动重发几轮 `0x0E`（协议 §4.5.7 允许 2 次） */
        private const val SCAN_MAX_RETRY = 2
    }

    // ==================== 回调接口 ====================

    interface ScanListener {
        fun onDeviceFound(device: ScannedDevice)
        fun onScanFailed(reason: String)
    }

    interface ProvisionListener {
        fun onConnecting()
        fun onConnected()
        fun onServiceFound()
        fun onKeyNegotiated()
        fun onDeviceInfo(info: DeviceInfoResult)
        fun onWifiConfigured(result: ConfigStaResult)
        fun onError(code: Int, message: String)
        fun onDisconnected()

        /**
         * 设备回报「扫描进行中」（`0x8E{0x81:0x08}`）。
         *
         * **这不是错误**：用户在设备上一轮扫描没结束时又点了刷新就会走到这里。
         * 上层应静默等待，不要弹错误提示 —— 弹了就是「一闪而过的报错」体验事故。
         */
        fun onWifiScanBusy() {}
        /**
         * 设备本轮没在超时内把列表发完，App 自动重发 `0x0E`（协议 §4.5.7 允许 2 次）。
         *
         * 上层应把计时器重新武装、提示「正在重试」，**不要**当成失败弹错误：
         * 实测设备扫一轮 2.4G 偶发要 7~9s，一次超时就报失败会误判为固件坏了。
         */
        fun onWifiScanRetry(attempt: Int, received: Int, total: Int) {}
        /** 原始收发报文（十六进制），用于问题诊断 */
        fun onTraffic(direction: String, hex: String) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 发送线程池（单线程串行）。
     *
     * 官方 SDK 把 `__negotiateSecretKey` / `__configureSta` 提交到
     * `Executors.newSingleThreadExecutor()`，在**后台线程**上阻塞等待写回调。
     * 若在主线程做同样的等待，会冻住 UI 且 `onCharacteristicWrite` 的
     * 主线程投递可能被阻塞 → 死锁。因此这里必须保持同样的线程模型。
     */
    private val writeExecutor = Executors.newSingleThreadExecutor()

    // ==================== 状态 ====================

    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    /** 发送序号：**每个分片自增**（官方 mSendSequence） */
    private val sendSequence = AtomicInteger(0)

    /** 接收序号：**每个分片自增**（官方 mRecvSequence） */
    private val recvSequence = AtomicInteger(0)

    /** 写完成信号：官方 mWriteLock + mCharacterChanged */
    private val writeLock = Object()
    private var writeCallbackLatch: CountDownLatch? = null

    private var encryptEnabled = false
    private var aesKey: ByteArray? = null
    private var keyHolder: RsaUtils.KeyHolder? = null

    /** 多帧接收缓冲 */
    private var recvBuffer: MessageEncode? = null

    private var connected = false

    private var scanListener: ScanListener? = null
    private var provisionListener: ProvisionListener? = null

    private var deviceInfoCallback: ((DeviceInfoResult) -> Unit)? = null
    private var configStaCallback: ((ConfigStaResult) -> Unit)? = null

    private var timeoutRunnable: Runnable? = null
    private val seenMacs = HashSet<String>()

    /**
     * 本次连接是否已经触发过「服务就绪」。
     *
     * 🔴 必须去重：CCCD 写回调在部分 ROM 上会丢，代码里有 1.5s 兜底；
     * 若兜底先触发、真实回调随后又到，就会**重复发起密钥协商**，
     * 第二次还会把收发 Sequence 归零 → 设备看到重复 seq 直接断链。
     */
    private var serviceDispatched = false

    /**
     * 断连触发的「写中止」标记。
     *
     * 用于区分「写回调真的来了」与「disconnect 把等待放行了」——
     * 后者若被当成成功，会在已断开的链路上继续发后续分片。
     */
    @Volatile
    private var writeAborted = false

    // ==================== 扫描 ====================

    @SuppressLint("MissingPermission")
    fun startScan(listener: ScanListener) {
        scanListener = listener
        seenMacs.clear()
        adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            post { listener.onScanFailed("蓝牙未开启，请先打开蓝牙") }
            return
        }
        val scanner = ad.bluetoothLeScanner
        if (scanner == null) {
            post { listener.onScanFailed("蓝牙扫描不可用") }
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // 部分 ROM 对 manufacturerData 过滤器支持不一致，因此这里不加过滤，
        // 改为在回调中按 ORG-ID 二次过滤（parseAdvertisement），行为更可靠。
        try {
            // 🔴 先停再启：重复 startScan 会拿到 SCAN_FAILED_ALREADY_STARTED，
            //    而且 Android 普遍有「30 秒内最多 5 次扫描」的限制，
            //    连点刷新到第 6~7 次时 startScan 直接被系统拒绝（error 6）。
            stopScan()
            scanner.startScan(emptyList<ScanFilter>(), settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "startScan failed", e)
            post { listener.onScanFailed("启动扫描失败：${e.message}") }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan failed", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = parseAdvertisement(result) ?: return
            if (!seenMacs.add(device.mac)) return
            post { scanListener?.onDeviceFound(device) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            // 6 = SCAN_FAILED_SCANNING_TOO_FREQUENTLY（30 秒内启了太多次扫描）。
            // 现场「连到第 7 次就异常」里有这一条的成分，必须给出可执行的提示，
            // 而不是丢一句「扫描失败，错误码 6」让用户猜。
            val hint = when (errorCode) {
                1 -> "扫描已经启动，无需重复点击"
                2 -> "系统未分配扫描资源，请关闭蓝牙再打开后重试"
                3 -> "系统内部错误，请重试"
                4 -> "本机不支持低功耗扫描"
                5 -> "硬件资源不足，请关闭其他蓝牙应用"
                6 -> "扫描过于频繁，请等 10 秒再试"
                else -> "扫描失败，错误码 $errorCode"
            }
            Log.w(TAG, "onScanFailed code=$errorCode")
            post { scanListener?.onScanFailed(hint) }
        }
    }

    /**
     * 解析广播包，提取 ORG-ID / Type / Version。
     *
     * 广播结构：0xFF | ORG-ID(2B 小端) | Type(1B) | Version(1B) | Others | 0x09 | Name
     *
     * 两条识别通道（V1.2 起）：
     *  1. **ORG-ID = 0x070C**（主通道，联盛德官方格式，能拿到 Type/Version）
     *  2. **设备名以 `AIPicCamera` 开头**（兜底，用于固件还没改 ORG-ID 字段、
     *     或产线试产板沿用原厂 demo 广播的场景）。
     *
     * 为什么必须有第 2 条：现场样机的广播包未必已经按 V1.2 填好，若只认 ORG-ID，
     * 一次广播字段不对就整台设备扫不到，联调无从下手。名字通道至少能连上，
     * 后续靠 `0x8D` 查设备信息再补全凭据。
     */
    private fun parseAdvertisement(result: ScanResult): ScannedDevice? {
        val record = result.scanRecord?.bytes ?: return null
        val devName = runCatching { result.device.name }.getOrNull()
            ?: result.scanRecord?.deviceName
        var fallback: ScannedDevice? = null
        var pos = 0
        try {
            while (pos + 1 < record.size) {
                val length = record[pos].toInt() and 0xFF
                if (length == 0) break
                val fieldType = record[pos + 1].toInt() and 0xFF
                val dataStart = pos + 2
                val dataLen = length - 1
                if (dataStart + dataLen > record.size) break

                if (fieldType == 0xFF && dataLen >= 4) {
                    val orgId = (record[dataStart].toInt() and 0xFF) or
                            ((record[dataStart + 1].toInt() and 0xFF) shl 8)
                    if (orgId == BleConstants.ORG_ID_WINNERMICRO) {
                        return ScannedDevice(
                            mac = result.device.address,
                            name = devName,
                            orgId = orgId,
                            type = record[dataStart + 2].toInt() and 0xFF,
                            version = record[dataStart + 3].toInt() and 0xFF,
                            rssi = result.rssi
                        )
                    }
                }
                pos += length + 1
            }
        } catch (e: Exception) {
            Log.w(TAG, "parse advertisement failed", e)
        }

        // 兜底：名字通道。orgId 填 0 表示「未从广播包识别」，不参与协议版本判断
        if (devName?.startsWith(BleConstants.NAME_PREFIX, ignoreCase = true) == true) {
            fallback = ScannedDevice(
                mac = result.device.address,
                name = devName,
                orgId = 0,
                type = 0,
                version = 0,
                rssi = result.rssi
            )
        }
        return fallback
    }

    // ==================== 连接 ====================

    @SuppressLint("MissingPermission")
    fun connect(mac: String, listener: ProvisionListener) {
        provisionListener = listener

        // 🔴 连之前先把上一条 GATT 关掉。
        //    Android 的 GATT client 是有限资源，远程断连（设备自己掉线）时如果不 close，
        //    每次重试都会留下一个僵尸 client；而 W800 只接受 1 条链路，
        //    反复「连→配网失败→再连」到第 6~7 次就会出现「连上了但不响应／随即被踢」。
        closeGattQuietly()

        serviceDispatched = false
        writeAborted = false
        connected = false
        resetSequences()

        adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device: BluetoothDevice? = try {
            adapter?.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (device == null) {
            post { listener.onError(BleConstants.STATUS_INVALID_PARAMS, "无效的设备地址") }
            return
        }
        post { listener.onConnecting() }
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    /** 断开并释放当前 GATT（幂等，异常吞掉） */
    @SuppressLint("MissingPermission")
    private fun closeGattQuietly() {
        val old = gatt
        gatt = null
        writeChar = null
        connected = false
        if (old == null) return
        try {
            old.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "gatt disconnect failed", e)
        }
        try {
            old.close()
        } catch (e: Exception) {
            Log.w(TAG, "gatt close failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        cancelTimeout()
        // 先置中止标记再放行等待线程：让它按「失败」返回，而不是把断连当成写成功
        writeAborted = true
        releaseWriteWait()
        closeGattQuietly()
        recvBuffer = null
        scanOrder.clear()
        scanTotal = -1
        wifiScanCallback = null
        cancelScanTimeout()
    }

    val isConnected: Boolean get() = connected

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(
                TAG,
                "onConnectionStateChange status=$status newState=$newState addr=${g.device.address}"
            )
            if (status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                connected = true
                // 官方行为：连接成功后提升连接优先级，降低分片发送间隔/丢包
                runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                post { provisionListener?.onConnected() }
                g.discoverServices()
            } else {
                // 🔴 远程断连 / 连接失败都必须走这里并 close()。
                //    只置 connected=false 不 close 会漏 GATT client：
                //    控制器层面链路还挂着，第 7 次连接时 W800 已经没有空位或已 deinit，
                //    表现就是「连上了、也发现服务了，之后一帧都不回，随后被踢」。
                val wasConnected = connected
                connected = false
                writeAborted = true
                cancelTimeout()
                cancelScanTimeout()
                // 先放行等写回调的线程（它会读到 writeAborted，按失败返回）
                releaseWriteWait()
                if (gatt === g) {
                    gatt = null
                    writeChar = null
                    recvBuffer = null
                }
                try {
                    g.close()
                } catch (e: Exception) {
                    Log.w(TAG, "gatt close failed", e)
                }
                Log.d(TAG, "gatt closed, status=$status")
                post {
                    provisionListener?.onDisconnected()
                    // 正在等扫描结果时断链：立刻把已收到的部分交出去，不要让界面干等到超时
                    if (wifiScanCallback != null) {
                        finishScan(
                            BleConstants.STATUS_NOT_CONNECTED,
                            if (wasConnected) "设备连接已断开" else "连接设备失败"
                        )
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                post { provisionListener?.onError(BleConstants.STATUS_INVALID_PARAMS, "服务发现失败") }
                return
            }
            val service = g.getService(UUID.fromString(BleConstants.UUID_SERVICE_WIFI))
            val char = service?.getCharacteristic(
                UUID.fromString(BleConstants.UUID_CHARACTERISTIC_WRITE)
            )
            if (service == null || char == null) {
                post {
                    provisionListener?.onError(
                        BleConstants.STATUS_INVALID_PARAMS,
                        "设备未提供配网服务，请确认固件版本"
                    )
                }
                return
            }
            // 官方：writeChar 与 notifyChar 是同一个特征；仅需 setCharacteristicNotification(true)
            writeChar = char
            g.setCharacteristicNotification(char, true)

            if (serviceDispatched) {
                Log.w(TAG, "services discovered twice, ignored")
                return
            }

            // 官方 Demo 未显式写 CCCD，仅依赖 setCharacteristicNotification。
            // 但部分 Android 版本必须写 CCCD 才会真正开启——
            // 🔴 值必须按特征自身声明的属性选：只支持 NOTIFY 的特征写 ENABLE_INDICATION 会回
            //    status=133，ATT 层进入脏状态，紧接着的第一片 write 就再也收不到回调
            //    （现场表现：「公钥发送失败（分片写超时）」）。
            val desc = char.getDescriptor(CCCD_UUID)
            if (desc == null) {
                dispatchServiceFound()
                return
            }
            val props = char.properties
            val useIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            val value = if (useIndicate) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            Log.d(TAG, "cccd props=0x${Integer.toHexString(props)} indicate=$useIndicate")

            // 写 CCCD 是"锦上添花"：回调丢了也要能继续，但不能在 ATT 操作未完成时就发数据
            cccdFallback = Runnable {
                Log.w(TAG, "CCCD write callback timeout, continue anyway")
                dispatchServiceFound()
            }.also { mainHandler.postDelayed(it, 2500) }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(desc, value)
                } else {
                    @Suppress("DEPRECATION")
                    desc.value = value
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(desc)
                }
            } catch (e: Exception) {
                Log.w(TAG, "writeDescriptor failed, continue", e)
                dispatchServiceFound()
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "onDescriptorWrite status=$status uuid=${descriptor.uuid}")
            if (descriptor.uuid != CCCD_UUID) return
            // status!=SUCCESS 也要放行：设备侧通常已 setCharacteristicNotification 生效，
            // 只是 CCCD 值不合它口味。真正的判据是后面的写回调有没有来。
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "cccd write status=$status, continue anyway")
            }
            dispatchServiceFound()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicWrite status=$status")
            lastWriteStatus = status
            // 官方：唤醒 gattWrite 的等待，让下一分片得以发送
            synchronized(writeLock) {
                writeCallbackLatch?.countDown()
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            characteristic.value?.let { handleIncoming(it) }
        }
    }

    /** CCCD 写回调兜底（部分设备/ROM 不回调） */
    private var cccdFallback: Runnable? = null

    /** 最后一次写回调的 status（0 = GATT_SUCCESS） */
    @Volatile
    private var lastWriteStatus = 0

    private fun resetSequences() {
        sendSequence.set(0)
        recvSequence.set(0)
        recvBuffer = null
        encryptEnabled = false
        aesKey = null
    }

    /**
     * 「服务就绪」只派发一次。
     *
     * 两个触发源：CCCD 写回调（正常）与 2.5s 兜底（回调丢失）。
     * 若不去重，兜底先到、真实回调随后又到，会重复发起密钥协商并把 Sequence 归零，
     * 设备侧看到同一 seq 的重复帧 → 判为异常 → 直接断链。
     */
    private fun dispatchServiceFound() {
        cccdFallback?.let { mainHandler.removeCallbacks(it) }
        cccdFallback = null
        if (serviceDispatched) {
            Log.w(TAG, "service already dispatched, duplicate ignored")
            return
        }
        serviceDispatched = true
        resetSequences()
        post { provisionListener?.onServiceFound() }
    }

    // ==================== 密钥协商 ====================

    fun negotiateSecretKey() {
        // 🔴 密钥对在**后台线程**生成：RSA-1024 在中低端机上要 100~500ms，
        //    放在主线程会冻界面，更要命的是它会推迟「服务就绪 → 首片发出」的时间，
        //    设备此时若已掉线，就表现为「公钥发送失败」。
        writeExecutor.execute {
            if (!connected) {
                post { provisionListener?.onError(BleConstants.STATUS_NOT_CONNECTED, "设备未连接") }
                return@execute
            }
            val holder = runCatching { RsaUtils.generateKeyPair() }.getOrNull()
            if (holder == null) {
                post { provisionListener?.onError(BleConstants.STATUS_NEGOTIATE_SECRET_KEY, "生成密钥对失败") }
                return@execute
            }
            keyHolder = holder
            val encode = MessageEncode()
            encode.putBytesValue(BleConstants.TLV_TYPE_PUB_KEY, holder.publicKeyEncoded())
            encode.encode(BleConstants.PACKET_CMD_KEY_EXCHANGE.toByte(), false, null)

            val ok = writePacketsBlocking(encode)
            if (!ok) {
                post {
                    provisionListener?.onError(
                        BleConstants.STATUS_GATT_WRITE,
                        "公钥发送失败（分片写超时）"
                    )
                }
                return@execute
            }
            // 官方 negotiateSecretKey 启的是 5 秒定时器
            startTimeout(BleConstants.TIMEOUT_NEGOTIATE, "密钥交换超时")
        }
    }

    // ==================== 查询设备信息 ====================

    /**
     * 查询设备信息，读取 apikey / deviceId。
     *
     * 依赖固件实现 TLV 0x0A / 0x0B。固件未升级时不会收到有效字段，
     * 结果中 hasCredential 为 false，由上层用预置凭据兜底。
     *
     * 注意：官方 `MessageEncode.serialize800()` 在**无 TLV 时直接返回**，
     * 因此空请求不会产生任何分片。这里放一个无副作用的内存占位 TLV
     * （0x00，长度 0），确保请求帧能真正发出。
     */
    fun queryDeviceInfo(callback: (DeviceInfoResult) -> Unit) {
        deviceInfoCallback = callback
        val encode = MessageEncode()
        encode.putBytesValue(BleConstants.TLV_TYPE_PLACEHOLDER, ByteArray(0))
        encode.encode(BleConstants.PACKET_CMD_QUERY_CONFIG.toByte(), encryptEnabled, aesKey)
        writeExecutor.execute {
            val ok = writePacketsBlocking(encode)
            if (!ok) {
                post {
                    provisionListener?.onError(
                        BleConstants.STATUS_GATT_WRITE,
                        "查询指令发送失败（分片写超时）"
                    )
                }
                return@execute
            }
            startTimeoutFor(
                BleConstants.TIMEOUT_QUERY_INFO,
                "查询设备信息超时",
                BleConstants.STATUS_QUERY_INFO_UNSUPPORTED
            )
        }
    }

    // ==================== 扫描 WiFi（V1.2） ====================

    /** 扫描结果回调 */
    private var wifiScanCallback: ((WifiScanResult) -> Unit)? = null

    /**
     * 已收到的 AP，**以 `0x88` 的「序号」为键**（1 基）。
     *
     * 🔴 不能用 SSID 做键：同一 SSID 可能有多个 BSSID（Mesh / 双频 / 固件未做同名合并），
     * 用 SSID 做键会把它们吞掉 —— 现场「设备上报 10 个、App 只显示 8 个」就是这么来的。
     * 用序号做键还有个好处：自动重试时设备重发同一序号，天然幂等覆盖。
     */
    private val scanOrder = LinkedHashMap<Int, WifiAp>()

    /** 旧格式 0x86 的行序号（负数区间，与 0x88 的 1 基序号不冲突） */
    private var legacySeq = 0

    /** 设备声明的条数（0x89 的 total），-1 表示还没收到 */
    private var scanTotal = -1

    /** 老固件（无 AUTH 字段）标记：列表一律要求输密码 */
    private var scanAuthUnknown = false

    /** 本轮已自动重发的次数 */
    private var scanRetries = 0

    /** 列表收集超时 */
    private var scanTimeoutRunnable: Runnable? = null

    /**
     * 请求设备扫描 WiFi 并收集列表（配网协议 V1.2 §4.5）。
     *
     * 交互模型是 **pull**：App 发 `0x0E`，设备扫 2.4G 后把结果按
     * `0x88`（`序号|RSSI|AUTH|SSID`）逐条推上来，最后用 `0x89`（total/done）收尾。
     *
     * 与 `0x0D`/`0x0E` 一样，PAYLOAD 为空 —— 必须放零长占位 TLV，
     * 否则官方 `serialize800()` 不产生任何分片，指令根本发不出去。
     *
     * @param callback 扫完（或超时）时回调一次，列表已按 RSSI 降序
     * @return false 表示指令未能发出（连接已断等），此时仍会通过 onError 通知上层
     */
    fun startWifiScan(
        timeoutMs: Long = BleConstants.TIMEOUT_WIFI_SCAN * 1000L,
        callback: (WifiScanResult) -> Unit
    ): Boolean {
        // 重扫必须清空上一轮，否则「刷新」会把旧热点和新热点混在一起
        scanOrder.clear()
        scanTotal = -1
        scanAuthUnknown = false
        scanRetries = 0
        legacySeq = 0
        cancelScanTimeout()
        wifiScanCallback = callback
        return sendScanRequest(timeoutMs)
    }

    /**
     * 发一条 `0x0E`（首发与自动重试共用）。
     *
     * 重试**不重连、不重置 Sequence** —— 它只是让设备再扫一遍，链路一直没断。
     */
    private fun sendScanRequest(timeoutMs: Long = BleConstants.TIMEOUT_WIFI_SCAN * 1000L): Boolean {
        if (!connected) {
            post { finishScan(BleConstants.STATUS_NOT_CONNECTED, "设备未连接") }
            return false
        }
        val encode = MessageEncode()
        encode.putBytesValue(BleConstants.TLV_TYPE_PLACEHOLDER, ByteArray(0))
        encode.encode(
            BleConstants.PACKET_CMD_WIFI_SCAN.toByte(),
            encryptEnabled,
            aesKey
        )

        writeExecutor.execute {
            val sent = writePacketsBlocking(encode)
            if (!sent) {
                post {
                    finishScan(
                        error = BleConstants.STATUS_GATT_WRITE,
                        message = "扫描指令发送失败（分片写超时）"
                    )
                }
                return@execute
            }
            startScanTimeout(timeoutMs)
        }
        return true
    }

    /**
     * 取消等待中的扫描（页面退出时调用，避免回调打到已销毁的界面）。
     */
    fun cancelWifiScan() {
        cancelScanTimeout()
        wifiScanCallback = null
        scanOrder.clear()
        scanTotal = -1
    }

    private fun startScanTimeout(timeoutMs: Long) {
        cancelScanTimeout()
        val r = Runnable { onScanRoundTimeout() }
        scanTimeoutRunnable = r
        mainHandler.postDelayed(r, timeoutMs)
    }

    private fun cancelScanTimeout() {
        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanTimeoutRunnable = null
    }

    /**
     * 一轮等待超时：没发完就**自动重发 `0x0E`**（协议 §4.5.7 允许 2 次）。
     *
     * 为什么不直接判失败：实测 W800 扫一轮 2.4G 偶发要 7~9s，
     * 一次 15s 超时就弹「失败」会让用户以为固件坏了，而重发一次成功率很高。
     */
    private fun onScanRoundTimeout() {
        if (wifiScanCallback == null) return
        val received = scanOrder.size
        if (scanRetries < SCAN_MAX_RETRY && connected) {
            scanRetries++
            Log.w(
                TAG,
                "wifi scan round timeout, retry $scanRetries (received=$received total=$scanTotal)"
            )
            post { provisionListener?.onWifiScanRetry(scanRetries, received, scanTotal) }
            sendScanRequest()
            return
        }
        finishScan(BleConstants.STATUS_TIMEOUT, "获取 WiFi 列表超时")
    }

    /**
     * 收尾：排序 + 回调，且**只回调一次**。
     *
     * @param error 0 表示正常；非 0 时列表可能为空或不全
     */
    private fun finishScan(error: Int, message: String?) {
        cancelScanTimeout()
        val cb = wifiScanCallback ?: return
        wifiScanCallback = null

        if (error != 0 && error != BleConstants.STATUS_TIMEOUT) {
            post { provisionListener?.onError(error, message ?: "扫描失败") }
            return
        }
        val all = scanOrder.values.toList()
        // 展示层按 SSID 合并（保留最强信号），并把「合并掉几条」如实报给界面。
        // 协议 §4.5.4 要求固件做同名合并；固件没做时，这里是唯一的安全网。
        val dedup = LinkedHashMap<String, WifiAp>()
        all.forEach { ap ->
            val old = dedup[ap.ssid]
            if (old == null || ap.rssi > old.rssi) dedup[ap.ssid] = ap
        }
        val list = dedup.values.sortedByDescending { it.rssi }
        val result = WifiScanResult(
            aps = list,
            authUnknown = scanAuthUnknown,
            total = scanTotal,
            duplicates = (all.size - list.size).coerceAtLeast(0),
            received = all.size
        )
        Log.d(TAG, "wifi scan done: received=${all.size} unique=${list.size} total=$scanTotal")
        post { cb(result) }
    }

    /**
     * 处理 `0x8E`。
     *
     * 🔴 入参是**列表**不是 Map：固件会把多条 `0x88` 塞进同一条消息，
     * 用 Map 收只会剩最后一条（这是「少两个热点」的第二个成因）。
     */
    private fun onWifiScan(tlvs: List<MessageEncode.Tlv>) {
        // ① 错误/状态分支（0x81 只可能有一条）
        val err = tlvs.firstOrNull { it.type == BleConstants.TLV_RESP_TYPE_ERROR_ID }
            ?.value?.firstOrNull()?.toInt()?.and(0xFF)
        if (err != null) {
            when (err) {
                BleConstants.WIFI_SCAN_OK -> Unit // 只是确认，继续等列表
                BleConstants.WIFI_SCAN_BUSY -> {
                    // 不是错误：设备正在扫，等它这一轮结束即可（协议 §4.9.1）
                    post { provisionListener?.onWifiScanBusy() }
                    return
                }
                BleConstants.WIFI_SCAN_FAILED -> {
                    finishScan(BleConstants.STATUS_WIFI_SCAN, "WiFi 扫描失败")
                    return
                }
                BleConstants.WIFI_SCAN_NO_AP -> {
                    // 扫描成功但没有 2.4G 热点 —— 与「扫描失败」必须区分
                    cancelScanTimeout()
                    val cb = wifiScanCallback
                    wifiScanCallback = null
                    post { cb?.invoke(WifiScanResult(emptyList(), total = 0)) }
                    return
                }
                else -> {
                    finishScan(BleConstants.STATUS_WIFI_SCAN, "扫描失败（设备错误码 $err）")
                    return
                }
            }
        }

        // ② 新格式（V1.2）+ ③ 旧格式（V1.0）逐条处理
        tlvs.forEach { tlv ->
            when (tlv.type) {
                BleConstants.TLV_RESP_TYPE_WIFI_LIST_V2 -> {
                    parseApLine(tlv.value)?.let { (seq, ap) ->
                        // 序号缺失/非法时给一个不会撞车的负键
                        val key = if (seq > 0) seq else -(1000 + scanOrder.size)
                        scanOrder[key] = ap
                    }
                }
                BleConstants.TLV_RESP_TYPE_WIFI_SCAN_RESULT -> parseLegacyLines(tlv.value)
            }
        }

        // ④ 结束标志
        val meta = tlvs.firstOrNull { it.type == BleConstants.TLV_RESP_TYPE_WIFI_LIST_META }
            ?.value ?: return
        if (meta.size < 2) return
        scanTotal = meta[0].toInt() and 0xFF
        val done = (meta[1].toInt() and 0xFF) == 1
        if (!done) return

        // 收齐 = 收到的行数 >= total。少发了就再要一轮，别拿半张列表糊弄用户
        if (scanOrder.size < scanTotal && scanRetries < SCAN_MAX_RETRY && connected) {
            scanRetries++
            Log.w(TAG, "list incomplete ${scanOrder.size}/$scanTotal, retry $scanRetries")
            post { provisionListener?.onWifiScanRetry(scanRetries, scanOrder.size, scanTotal) }
            sendScanRequest()
            return
        }
        finishScan(0, null)
    }

    /**
     * 解析一行 `序号|RSSI|AUTH|SSID`。
     *
     * SSID 在最后一段，因此它含逗号、竖线都不会切错 —— 这是协议刻意的排布。
     *
     * @return `序号 to AP`；序号解析失败时返回 0（调用方会换占位键）
     */
    private fun parseApLine(raw: ByteArray): Pair<Int, WifiAp>? {
        val line = raw.toString(Charsets.UTF_8).trim()
        if (line.isEmpty()) return null
        val p1 = line.indexOf('|')
        if (p1 <= 0) return null
        val p2 = line.indexOf('|', p1 + 1)
        if (p2 < 0) return null
        val p3 = line.indexOf('|', p2 + 1)
        if (p3 < 0) return null

        val seq = line.substring(0, p1).trim().toIntOrNull() ?: 0
        val rssi = line.substring(p1 + 1, p2).trim().toIntOrNull() ?: return null
        // AUTH 是单字符数字。固件若用 %c 传了数值而非数字字符会解析成 UNKNOWN，
        // 此时按最保守处理（要求输密码），而不是把整条记录丢掉。
        val auth = line.substring(p2 + 1, p3).trim().toIntOrNull() ?: WifiAp.Auth.UNKNOWN
        val ssid = line.substring(p3 + 1)
        if (ssid.isBlank()) return null
        return seq to WifiAp(ssid, rssi, auth)
    }

    /** 旧格式：`SSID,RSSI\r\n` 多行。SSID 可能含逗号，因此从**最后一个逗号**右切。 */
    private fun parseLegacyLines(raw: ByteArray) {
        scanAuthUnknown = true
        raw.toString(Charsets.UTF_8)
            .split("\r\n", "\n")
            .forEach { line ->
                val s = line.trim()
                if (s.isEmpty()) return@forEach
                val i = s.lastIndexOf(',')
                if (i <= 0) return@forEach
                val rssi = s.substring(i + 1).trim().toIntOrNull() ?: return@forEach
                val ssid = s.substring(0, i)
                if (ssid.isBlank()) return@forEach
                legacySeq++
                scanOrder[-legacySeq] = WifiAp(ssid, rssi, WifiAp.Auth.UNKNOWN)
            }
    }

    // ==================== 配置 WiFi ====================

    fun configureSta(
        ssid: String,
        password: String?,
        bssid: String?,
        callback: (ConfigStaResult) -> Unit
    ): Boolean {
        if (ssid.isBlank()) {
            provisionListener?.onError(BleConstants.STATUS_INVALID_PARAMS, "SSID 不能为空")
            return false
        }
        if (ssid.toByteArray(Charsets.UTF_8).size > BleConstants.MAX_LENGTH_SSID) {
            provisionListener?.onError(BleConstants.STATUS_INVALID_PARAMS, "SSID 过长")
            return false
        }
        if (!password.isNullOrEmpty() && password.toByteArray(Charsets.UTF_8).size > BleConstants.MAX_LENGTH_PWD) {
            provisionListener?.onError(BleConstants.STATUS_INVALID_PARAMS, "密码过长")
            return false
        }

        configStaCallback = callback
        val encode = MessageEncode()
        encode.putStringValue(BleConstants.TLV_TYPE_AP_SSID, ssid)
        if (!password.isNullOrEmpty()) {
            encode.putStringValue(BleConstants.TLV_TYPE_AP_PASSWORD, password)
        }
        if (!bssid.isNullOrEmpty() && bssid.replace(":", "").length == 12) {
            encode.putBytesValue(
                BleConstants.TLV_TYPE_AP_BSSID,
                hexToBytes(bssid.replace(":", ""))
            )
        }
        encode.encode(BleConstants.PACKET_CMD_CONFIG_STA.toByte(), encryptEnabled, aesKey)
        writeExecutor.execute {
            val ok = writePacketsBlocking(encode)
            if (!ok) {
                post {
                    provisionListener?.onError(
                        BleConstants.STATUS_GATT_WRITE,
                        "配网指令发送失败（分片写超时）"
                    )
                }
                return@execute
            }
            // 官方 configureSta 启的是 50 秒定时器
            startTimeout(BleConstants.TIMEOUT_CONFIG_STA, "配网超时")
        }
        return true
    }

    /** 可选：向设备回写绑定结果（需固件支持 0x11 命令） */
    fun sendBindResult(status: Int) {
        val encode = MessageEncode()
        encode.putByteValue(BleConstants.TLV_TYPE_BIND_STATUS, status)
        encode.encode(BleConstants.PACKET_CMD_BIND_RESULT.toByte(), encryptEnabled, aesKey)
        writeExecutor.execute { writePacketsBlocking(encode) }
    }

    /**
     * 下发设备配置：物品语言 + 界面语言 + 云端保留天数（需固件支持 0x12）。
     *
     * ## 为什么这三个参数走同一条命令
     *
     * 它们都是「同一台通用硬件在某个市场的表现」，天生一组：
     * 日本市场的设备 = 物品日语 + 界面日语 + 该市场的存储策略。
     * 分三条命令发会出现「语言改了但存储没改」的半配置状态，排查起来很难。
     *
     * ## 回调语义（重要）
     *
     * 回调 `true` 只代表**帧已全部写出**，不代表设备已接受：
     * 固件尚未实现 0x12 时设备不会回 0x92，而这条链路没有任何理由因此失败 ——
     * 配网本身是成功的。所以上层拿到 `true` 就清 `pending` 并提示
     * 「已下发，固件升级后生效」；拿到 `false` 才说明链路断了，保留 `pending`。
     *
     * @param onResult 在**主线程**回调；null 表示不关心结果
     */
    fun sendDeviceConfig(
        itemLang: String,
        uiLang: String,
        cloudDays: Int,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        val g = gatt
        if (g == null || !connected) {
            Log.w(TAG, "sendDeviceConfig skipped: not connected")
            post { onResult?.invoke(false) }
            return
        }
        val encode = MessageEncode()
        encode.putStringValue(BleConstants.TLV_TYPE_ITEM_LANG, itemLang)
        encode.putStringValue(BleConstants.TLV_TYPE_UI_LANG, uiLang)
        // ⚠️ 保留天数用 **2 字节小端**：365 = 0x016D，单字节会被截断成 109 天，
        //    而 109 天既不是任何套餐也不是默认值，排查起来极难。
        encode.putBytesValue(
            BleConstants.TLV_TYPE_CLOUD_STORE_DAYS,
            byteArrayOf(
                (cloudDays and 0xFF).toByte(),
                ((cloudDays shr 8) and 0xFF).toByte()
            )
        )
        encode.encode(BleConstants.PACKET_CMD_DEVICE_CONFIG.toByte(), encryptEnabled, aesKey)
        writeExecutor.execute {
            val ok = writePacketsBlocking(encode)
            Log.d(TAG, "device config item=$itemLang ui=$uiLang days=$cloudDays sent=$ok")
            post { onResult?.invoke(ok) }
        }
    }

    // ==================== 发送 ====================

    /**
     * 逐片阻塞式发送整条消息。
     *
     * **严格对齐官方 `__configureSta` / `__negotiateSecretKey` 的循环**：
     * 每个分片单独取一个自增的 seq，写完必须等到 `onCharacteristicWrite` 回调
     * 才能发下一片；任一分片失败即整条消息失败。
     *
     * @return 全部成功返回 true
     */
    private fun writePacketsBlocking(encode: MessageEncode): Boolean {
        val packets = encode.getMessagePacketList()
        if (packets.isEmpty()) {
            Log.w(TAG, "no packet to send (empty TLV?)")
            return false
        }
        val char = writeChar ?: return false

        for (packet in packets) {
            // 🔴 每片发之前都要确认链路还在：
            //    设备中途掉线时如果继续往死链路上写，会连累下一次连接（ATT 队列里塞满无效请求）。
            if (!connected || writeAborted) {
                Log.w(TAG, "link gone, abort remaining ${packets.size - packet.no} packet(s)")
                return false
            }
            val g = gatt ?: return false
            val seq = sendSequence.getAndIncrement()
            packet.buildPacket(seq, false)
            if (!gattWrite(g, char, packet.packageInfo)) {
                Log.e(TAG, "gattWrite failed at no=${packet.no} seq=$seq")
                return false
            }
        }
        return true
    }

    /**
     * 写一片并等待 `onCharacteristicWrite` 回调（官方 `gattWrite` + 现场加固）。
     *
     * 加固点（对应客户现场「公钥发送失败（分片写超时）」）：
     *  1. `writeCharacteristic` 立即返回非 SUCCESS（最常见是 `GATT_WRITE_REQUEST_BUSY`，
     *     即上一条 ATT 操作还没收尾）时**重试**，而不是直接判整条消息失败；
     *  2. 回调 1s 未到也重试 —— 链路抖动时重发一片的代价远小于让用户重连；
     *  3. 回调 status 非 SUCCESS（如 133 GATT_ERROR）按失败处理，不误判为成功；
     *  4. 断连会 `countDown` 放行等待线程，这里用 [writeAborted] 识别并返回失败。
     */
    @SuppressLint("MissingPermission")
    private fun gattWrite(
        g: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        data: ByteArray
    ): Boolean {
        var attempt = 0
        while (attempt < WRITE_MAX_ATTEMPT) {
            attempt++
            if (!connected || writeAborted) {
                Log.w(TAG, "gattWrite aborted (connected=$connected)")
                return false
            }
            emitTraffic(if (attempt == 1) ">>>" else ">>>R$attempt", data)

            val latch = CountDownLatch(1)
            lastWriteStatus = 0
            synchronized(writeLock) { writeCallbackLatch = latch }

            val submitted: Boolean
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+ 的新重载返回 BluetoothStatusCodes 状态码（Int），
                // 而非旧版的 Boolean。SUCCESS == 0。
                val status = g.writeCharacteristic(
                    char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                submitted = status == android.bluetooth.BluetoothStatusCodes.SUCCESS
                if (!submitted) {
                    Log.w(TAG, "writeCharacteristic rejected, code=$status (attempt $attempt)")
                }
            } else {
                @Suppress("DEPRECATION")
                char.value = data
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                submitted = g.writeCharacteristic(char)
                if (!submitted) {
                    Log.w(TAG, "writeCharacteristic returned false (attempt $attempt)")
                }
            }

            if (!submitted) {
                synchronized(writeLock) { writeCallbackLatch = null }
                if (attempt < WRITE_MAX_ATTEMPT) {
                    // 退避递增：GATT busy 往往是上一条 ATT 操作（CCCD 写）还没收尾，
                    // 固定间隔重试容易连续撞同一堵墙
                    sleepQuietly(WRITE_RETRY_GAP_MS * attempt)
                    continue
                }
                return false
            }

            val got = try {
                latch.await(WRITE_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            val status = lastWriteStatus
            synchronized(writeLock) { writeCallbackLatch = null }

            if (writeAborted) {
                Log.w(TAG, "gattWrite aborted while waiting callback")
                return false
            }
            if (got && status == BluetoothGatt.GATT_SUCCESS) return true

            Log.w(
                TAG,
                "write attempt $attempt failed (callback=$got status=$status, " +
                    "${WRITE_CALLBACK_TIMEOUT_MS}ms)"
            )
            if (attempt < WRITE_MAX_ATTEMPT) sleepQuietly(WRITE_RETRY_GAP_MS * attempt)
        }
        return false
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun releaseWriteWait() {
        synchronized(writeLock) {
            writeCallbackLatch?.countDown()
            writeCallbackLatch = null
        }
    }

    // ==================== 接收 ====================

    private fun handleIncoming(data: ByteArray) {
        emitTraffic("<<<", data)

        val packet = MessagePacket.parsePacket(data)
        if (packet == null) {
            Log.w(TAG, "invalid packet (crc/short), dropped, len=${data.size}")
            return
        }

        // ⚠️ 官方语义：**每个分片都推进一次 recvSequence**，并逐片比对。
        if (recvSequence.getAndIncrement() != (packet.seq.toInt() and 0xFF)) {
            Log.w(
                TAG,
                "recv seq mismatch: got=${packet.seq.toInt() and 0xFF} " +
                        "expect=${recvSequence.get() - 1}"
            )
            return
        }

        val cmd = packet.cmd.toInt() and 0xFF
        if (cmd == BleConstants.PACKET_RESP_CMD_ACK) {
            return
        }

        // 设备要求 Ack 时回一帧 0x10（PAYLOAD = 收到的 Sequence）。
        // 老版本 App 从不回，导致多分片响应（如 0x8D、0x8E 列表）只能靠设备超时重发；
        // V1.2 起列表帧数变多，这条必须补上。
        if ((packet.flag.toInt() and BleConstants.PACKET_FLAG_ACK_MASK) != 0) {
            sendAck(packet.seq.toInt() and 0xFF)
        }

        if (recvBuffer == null) recvBuffer = MessageEncode()
        recvBuffer!!.addMessagePacket(packet)

        // 官方：flag & LAST_PACKET_MASK == 0 表示这是最后一片，可以解码
        if ((packet.flag.toInt() and BleConstants.PACKET_FLAG_LAST_PACKET_MASK) != 0) return

        // 保留重复行的解码结果：0x8E 一条消息里可能带多条 0x88
        val tlvList = recvBuffer!!.decodeAll(aesKey)
        recvBuffer = null
        if (tlvList == null) {
            Log.w(TAG, "decode tlv failed")
            return
        }

        when (cmd) {
            BleConstants.PACKET_RESP_CMD_WIFI_SCAN -> onWifiScan(tlvList)
            else -> {
                // 其余响应按「类型唯一」使用，转成 Map 即可
                val tlvs = HashMap<Int, ByteArray>(tlvList.size)
                tlvList.forEach { tlvs[it.type] = it.value }
                when (cmd) {
                    BleConstants.PACKET_RESP_CMD_KEY_EXCHANGE -> onKeyExchange(tlvs)
                    BleConstants.PACKET_RESP_CMD_QUERY_CONFIG -> onDeviceInfo(tlvs)
                    BleConstants.PACKET_RESP_CMD_CONFIG_STA -> onConfigSta(tlvs)
                }
            }
        }
    }

    /**
     * 回复 Ack（`0x10`）。
     *
     * 按协议：`Flag` 与 `NO` 固定 `0x00`，PAYLOAD 为 1 字节「收到的 Sequence」，
     * 且是**裸字节不是 TLV** —— 所以走 [MessageEncode.encodeRaw]。
     */
    private fun sendAck(seq: Int) {
        if (gatt == null || writeChar == null) return
        val encode = MessageEncode()
        encode.encodeRaw(
            BleConstants.PACKET_CMD_ACK.toByte(),
            byteArrayOf((seq and 0xFF).toByte()),
            false,
            null
        )
        writeExecutor.execute { writePacketsBlocking(encode) }
    }

    private fun onKeyExchange(tlvs: Map<Int, ByteArray>) {
        cancelTimeout()
        val encKey = tlvs[BleConstants.TLV_RESP_TYPE_PUB_ENC_KEY]
        val holder = keyHolder
        if (encKey == null || holder == null) {
            post {
                provisionListener?.onError(
                    BleConstants.STATUS_NEGOTIATE_SECRET_KEY,
                    "密钥交换失败：设备未返回加密密钥"
                )
            }
            return
        }
        val aes = RsaUtils.decryptSessionKey(holder, encKey)
        if (aes == null) {
            post {
                provisionListener?.onError(
                    BleConstants.STATUS_NEGOTIATE_SECRET_KEY,
                    "密钥解密失败（RSA/NONE/PKCS1 不匹配？）"
                )
            }
            return
        }
        aesKey = aes
        encryptEnabled = true
        Log.d(TAG, "aes key len=${aes.size}")
        post { provisionListener?.onKeyNegotiated() }
    }

    private fun onDeviceInfo(tlvs: Map<Int, ByteArray>) {
        cancelTimeout()
        val info = DeviceInfoResult(
            apikey = tlvs[BleConstants.TLV_RESP_TYPE_APIKEY]?.toString(Charsets.UTF_8)?.trim(),
            deviceId = tlvs[BleConstants.TLV_RESP_TYPE_DEVICE_ID]?.toString(Charsets.UTF_8)?.trim(),
            orgId = tlvs[BleConstants.TLV_RESP_TYPE_ORG_ID]?.let {
                if (it.size >= 2) (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) else null
            },
            deviceType = tlvs[BleConstants.TLV_RESP_TYPE_DEVICE_TYPE]
                ?.firstOrNull()?.toInt()?.and(0xFF),
            firmwareVersion = tlvs[BleConstants.TLV_RESP_TYPE_FIRMWARE_VER]
                ?.firstOrNull()?.toInt()?.and(0xFF)
        )
        post {
            provisionListener?.onDeviceInfo(info)
            deviceInfoCallback?.invoke(info)
        }
    }

    private fun onConfigSta(tlvs: Map<Int, ByteArray>) {
        cancelTimeout()
        val errorId = tlvs[BleConstants.TLV_RESP_TYPE_ERROR_ID]
            ?.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        val result = ConfigStaResult(
            errorId = errorId,
            ipAddress = tlvs[BleConstants.TLV_RESP_TYPE_IP_ADDR]
                ?.takeIf { it.size == 4 }
                ?.joinToString(".") { (it.toInt() and 0xFF).toString() },
            mac = tlvs[BleConstants.TLV_RESP_TYPE_MAC]
                ?.takeIf { it.size == 6 }
                ?.joinToString(":") { String.format("%02X", it.toInt() and 0xFF) }
        )
        post {
            provisionListener?.onWifiConfigured(result)
            configStaCallback?.invoke(result)
        }
    }

    // ==================== 超时 ====================

    private fun startTimeout(seconds: Int, reason: String) {
        startTimeoutFor(seconds, reason, BleConstants.STATUS_TIMEOUT)
    }

    /**
     * 启动超时定时器。
     *
     * @param code 超时时回报的错误码，便于上层区分"真错误"与"预期的不支持"
     */
    private fun startTimeoutFor(seconds: Int, reason: String, code: Int) {
        cancelTimeout()
        val r = Runnable {
            post { provisionListener?.onError(code, "$reason（${seconds}s）") }
        }
        timeoutRunnable = r
        mainHandler.postDelayed(r, seconds * 1000L)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    // ==================== 工具 ====================

    private fun post(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun emitTraffic(direction: String, data: ByteArray) {
        val hex = hexString(data)
        Log.d(TRAFFIC_TAG, "$direction $hex")
        post { provisionListener?.onTraffic(direction, hex) }
    }

    private fun hexString(data: ByteArray): String {
        val sb = StringBuilder(data.size * 3)
        data.forEachIndexed { i, b ->
            if (i > 0) sb.append(' ')
            sb.append(String.format("%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            out[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
        }
        return out
    }
}
