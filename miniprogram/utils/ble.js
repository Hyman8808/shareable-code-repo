// BLE 配网封装 —— 微信小程序版
// 与 Android App 的差异全部集中在这里，改动前先看注释里的「坑」

const { buildFrame, parseFrame, slice14 } = require('./packet')

const SERVICE_UUID = '00001824-0000-1000-8000-00805F9B34FB'
const CHAR_UUID = '00002ABC-0000-1000-8000-00805F9B34FB'  // 写与通知是同一个特征
const DEVICE_NAME_PREFIX = 'AIPicCamera'

// iOS 上连续写特征值会失败，两次写之间必须留间隔；
// 官方文档未写，但社区一致结论是 20ms 起，实测 30ms 更稳。
const WRITE_GAP_MS = 30

function sleep(ms) { return new Promise(r => setTimeout(r, ms)) }

function ab2u8(ab) { return new Uint8Array(ab) }
function u8ToAb(u8) { return u8.buffer.slice(u8.byteOffset, u8.byteOffset + u8.byteLength) }
function toHex(u8) {
  return Array.from(u8).map(b => b.toString(16).padStart(2, '0')).join(' ')
}

class BleClient {
  constructor() {
    this.deviceId = ''
    this.serviceId = SERVICE_UUID
    this.charId = CHAR_UUID
    this.connected = false
    this.seq = 0           // 发送 Sequence
    this.recvSeq = 0       // 接收 Sequence（逐片比对）
    // 收包缓冲：按 no 排序后拼接
    this.rxChunks = []
    this.listeners = {}
    this._notifyBound = false
  }

  // ---------- 1. 初始化 ----------
  openAdapter() {
    return new Promise((resolve, reject) => {
      wx.openBluetoothAdapter({
        mode: 'central',
        success: resolve,
        fail: (e) => reject(new Error('蓝牙不可用：' + (e.errMsg || e.errCode))),
      })
    })
  }

  closeAdapter() {
    return new Promise((resolve) => {
      wx.closeBluetoothAdapter({ complete: resolve })
    })
  }

  // ---------- 2. 搜索设备 ----------
  /**
   * @param {(list: Array<{deviceId:string,name:string,RSSI:number,adv:Uint8Array}>)=>void} onFound
   */
  startScan(onFound) {
    return new Promise((resolve, reject) => {
      wx.onBluetoothDeviceFound((res) => {
        const list = (res.devices || [])
          .filter(d => (d.name || '').indexOf(DEVICE_NAME_PREFIX) === 0 || (d.localName || '').indexOf(DEVICE_NAME_PREFIX) === 0)
          .map(d => ({
            deviceId: d.deviceId,
            name: d.name || d.localName,
            RSSI: d.RSSI,
            adv: d.advertisData ? ab2u8(d.advertisData) : new Uint8Array(0),
          }))
        if (list.length) onFound(list)
      })
      wx.startBluetoothDevicesDiscovery({
        // 只找我们自己的设备，降低功耗与干扰
        services: [SERVICE_UUID],
        allowDuplicatesKey: true,
        success: resolve,
        fail: (e) => reject(new Error('搜索失败：' + (e.errMsg || e.errCode))),
      })
    })
  }

  stopScan() {
    return new Promise((resolve) => {
      wx.stopBluetoothDevicesDiscovery({ complete: resolve })
    })
  }

  // ---------- 3. 连接 ----------
  async connect(deviceId) {
    await this.stopScan()
    await new Promise((resolve, reject) => {
      wx.createBLEConnection({
        deviceId,
        timeout: 10000,
        success: resolve,
        fail: (e) => reject(new Error('连接失败：' + (e.errMsg || e.errCode))),
      })
    })
    this.deviceId = deviceId
    this.connected = true

    // 🔴 连上后必须先拿服务/特征，否则写不进去
    await this._discover()

    // 🔴 开通知：小程序这个 API 内部会替我们写 CCCD
    //    （Android 上是 setCharacteristicNotification(true)，小程序没有 descriptor 接口，
    //     只能靠它；如果设备只支持 INDICATION 而 properties 没声明 notify，这里会失败）
    await new Promise((resolve, reject) => {
      wx.notifyBLECharacteristicValueChange({
        deviceId,
        serviceId: this.serviceId,
        characteristicId: this.charId,
        state: true,
        success: resolve,
        fail: (e) => reject(new Error('开通知失败：' + (e.errMsg || e.errCode))),
      })
    })

    if (!this._notifyBound) {
      wx.onBLECharacteristicValueChange((res) => {
        if (res.characteristicId.toUpperCase() !== this.charId.toUpperCase()) return
        this._onFrame(ab2u8(res.value))
      })
      this._notifyBound = true
    }

    // 🔴 Sequence 每次连接从 0 开始（设备侧也重置）；不归零会丢第一帧
    this.seq = 0
    this.recvSeq = 0
    this.rxChunks = []
  }

  async _discover() {
    const services = await new Promise((resolve, reject) => {
      wx.getBLEDeviceServices({
        deviceId: this.deviceId,
        success: (r) => resolve(r.services || []),
        fail: (e) => reject(new Error('取服务失败：' + (e.errMsg || e.errCode))),
      })
    })
    const target = services.find(s => s.uuid.toUpperCase() === SERVICE_UUID.toUpperCase())
    if (!target) throw new Error('未找到配网服务 1824')
    this.serviceId = target.uuid

    const chars = await new Promise((resolve, reject) => {
      wx.getBLEDeviceCharacteristics({
        deviceId: this.deviceId,
        serviceId: this.serviceId,
        success: (r) => resolve(r.characteristics || []),
        fail: (e) => reject(new Error('取特征失败：' + (e.errMsg || e.errCode))),
      })
    })
    const c = chars.find(x => x.uuid.toUpperCase() === CHAR_UUID.toUpperCase())
    if (!c) throw new Error('未找到配网特征 2ABC')
    this.charId = c.uuid
    this.charProps = c.properties
    return chars
  }

  async disconnect() {
    if (!this.deviceId) return
    await new Promise((resolve) => {
      wx.closeBLEConnection({ deviceId: this.deviceId, complete: resolve })
    })
    this.connected = false
    this.deviceId = ''
    this.rxChunks = []
    this.recvSeq = 0
  }

  // ---------- 4. 发送 ----------
  /** 单次写一片（≤20 字节） */
  _writeOnce(u8) {
    return new Promise((resolve, reject) => {
      wx.writeBLECharacteristicValue({
        deviceId: this.deviceId,
        serviceId: this.serviceId,
        characteristicId: this.charId,
        value: u8ToAb(u8),
        success: resolve,
        fail: (e) => reject(new Error('写失败：' + (e.errMsg || e.errCode))),
      })
    })
  }

  /**
   * 发送一条完整消息（自动切 14 字节分片）。
   * 🔴 必须「发一片等一片」：不等回调连发会撑爆 ATT 队列，
   *    表现为设备只收到前一两片、后续静默丢失。
   * 🔴 分片序号 no 是 **0 基**（App 用 forEachIndexed 的 index），不是 1 基。
   * 🔴 发送方向不要求 Ack（App 里 buildPacket(seq, false) 恒为 false），
   *    Ack 只出现在设备→App 方向。
   */
  async send(cmd, payload, { ack = false, encrypt = false } = {}) {
    if (!this.connected) throw new Error('未连接设备')
    const chunks = slice14(payload)
    for (let i = 0; i < chunks.length; i++) {
      const isLast = i === chunks.length - 1
      const frame = buildFrame(cmd, this.seq, i, chunks[i], {
        ack,
        encrypt,
        hasMore: !isLast,   // 🔴 非末片置「还有后续」，否则设备按单帧处理
      })
      this.seq = (this.seq + 1) & 0xff
      await this._writeOnce(frame)
      await sleep(WRITE_GAP_MS)
    }
  }

  // ---------- 5. 收包 ----------
  /**
   * 收帧主逻辑，严格对齐 App 的 handleIncoming：
   * ① CRC 不对 → 丢
   * ② seq 必须连续，不连续 → 丢（设备会重发）
   * ③ cmd=0x90 的 Ack → 忽略
   * ④ 设备要求 Ack（flag&0x40）→ 立即回一帧 0x10
   * ⑤ 累积分片；flag&0x20 置位表示「还有后续」，继续等
   * ⑥ 未置位 → 这是最后一片，合并后回调
   */
  _onFrame(u8) {
    const p = parseFrame(u8)
    if (!p) {
      this.emit('log', '收到非法帧（CRC 或长度），已丢弃')
      return
    }

    // ② seq 连续校验：每片都推进
    if ((this.recvSeq & 0xff) !== p.seq) {
      this.emit('log', `seq 不连续 got=${p.seq} expect=${this.recvSeq & 0xff}，丢弃`)
      return
    }
    this.recvSeq = (this.recvSeq + 1) & 0xff

    // ③ Ack 响应本身不当数据
    if (p.cmd === 0x90) return

    // ④ 设备要求回执：不回的话多分片响应会卡住直到设备超时重发
    if (p.ackRequired) this._sendAck(p.seq)

    this.rxChunks.push(p)

    // ⑤ 还有后续帧
    if (p.hasMore) return

    // ⑥ 最后一片：合并
    const merged = this._takeChunks()
    this.emit('message', p.cmd, merged.payload, merged.encrypted)
  }

  /** 回 0x10 Ack：PAYLOAD 是 1 字节裸 Sequence，不是 TLV */
  _sendAck(seq) {
    const frame = buildFrame(0x10, this.seq, 0, Uint8Array.from([seq & 0xff]), { encrypt: false })
    this.seq = (this.seq + 1) & 0xff
    // 不 await，避免阻塞收帧回调
    this._writeOnce(frame).catch(e => this.emit('log', 'Ack 发送失败：' + e.message))
  }

  _takeChunks() {
    const sorted = this.rxChunks.slice().sort((a, b) => a.no - b.no)
    let total = 0
    sorted.forEach(c => { total += c.payload.length })
    const out = new Uint8Array(total)
    let off = 0
    sorted.forEach(c => { out.set(c.payload, off); off += c.payload.length })
    const encrypted = sorted.length ? sorted[0].encrypted : false
    this.rxChunks = []
    return { payload: out, encrypted }
  }

  /** 上层用：超时后强制取走已收到的分片 */
  takeBuffered() { return this._takeChunks() }

  // ---------- 事件 ----------
  emit(type, ...args) {
    const fn = this.listeners[type]
    if (fn) fn(...args)
  }

  on(type, fn) { this.listeners[type] = fn; return this }

  // ---------- 6. 调试 ----------
  log(label, u8) {
    console.log(`[BLE] ${label}: ${toHex(u8)}`)
  }
}

module.exports = { BleClient, SERVICE_UUID, CHAR_UUID, DEVICE_NAME_PREFIX, toHex }
