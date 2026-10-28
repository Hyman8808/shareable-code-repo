// 配网流程编排（BLE）—— 页面只管展示，协议细节都收在这里
//
// 时序（与 App 一致）：
//   搜设备 → 连接 → 开通知
//   → ① 查设备信息 0x0D / 0x8D（拿 apikey + deviceId，本版唯一必做）
//   → ② 扫热点 0x0E / 0x8E（0x88 逐条 + 0x89 结束）
//   → ③ 配网 0x0A / 0x8A（SSID + 密码）
//   → ④ 绑定回传 0x11 本版跳过（协议要求必须加密，明文下设备解不开）
//
// 🔴 本版走**明文**（flag 不带 0x80）。App 里本来就有明文降级路径，
//    若实测设备不认明文，再补 AES-ECB + RSA 协商（约 +3~5 天）。

const { BleClient } = require('./ble')
const { CMD, RESP, TlvBuilder, decodeTlv, tlvFirst, utf8ToStr } = require('./packet')
const fmt = require('./fmt')

// 这几个 TLV 只在配网响应里出现，packet.js 的 TLV 表里没有，就近定义
const T_ERROR = 0x81 // 错误码：0x8A / 0x8E / 0x91 通用
const T_IP = 0x82
const T_MAC = 0x83
const T_AP_LINE = 0x88 // WiFi_LIST_V2，每行一条
const T_AP_META = 0x89 // total(1B) + done(1B)
const T_BIND_STATUS = 0x0f

const CONFIG_ERR = {
  0x00: '',
  0x01: '参数错误（SSID 或密码格式不对）',
  0x02: 'WiFi 密码错误',
  0x03: '设备连上热点但获取 IP 失败，检查路由器 DHCP',
  0x04: '设备扫描 WiFi 失败',
  0x08: '扫描进行中',
  0x09: '设备没扫到可用 WiFi',
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

function str(u8) {
  if (!u8) return ''
  let s = utf8ToStr(u8)
  // 去掉固件可能带的 \0 与首尾空白
  s = s.replace(/\u0000+$/g, '').replace(/^\s+|\s+$/g, '')
  return s
}

/**
 * 解析 0x88 一行：`序号|RSSI|AUTH|SSID`
 * 🔴 SSID 在第 **3** 个竖线之后（旧文档写第 2 个，是错的），
 *    这样 SSID 里带竖线、逗号都不用转义。
 */
function parseApLine(text) {
  if (!text) return null
  const s = text.replace(/[\r\n]+$/g, '')
  const i1 = s.indexOf('|')
  if (i1 < 0) return null
  const i2 = s.indexOf('|', i1 + 1)
  if (i2 < 0) return null
  const i3 = s.indexOf('|', i2 + 1)
  if (i3 < 0) return null
  const seq = parseInt(s.slice(0, i1), 10)
  const rssi = parseInt(s.slice(i1 + 1, i2), 10)
  const auth = parseInt(s.slice(i2 + 1, i3), 10)
  const ssid = s.slice(i3 + 1)
  if (!ssid) return null
  return {
    seq: isFinite(seq) ? seq : 0,
    rssi: isFinite(rssi) ? rssi : -99,
    auth: isFinite(auth) ? auth : 9,
    ssid,
    level: fmt.rssiLevel(rssi),
    authText: fmt.authLabel(auth),
    needPwd: fmt.needPassword(auth),
  }
}

class Provisioner {
  constructor(logger) {
    this.ble = new BleClient()
    this.log = logger || function () {}
    this.info = null
    this._waiters = []
    this._scan = null
    this._onScanProgress = null

    this.ble.on('message', (cmd, payload) => this._onMessage(cmd, payload))
    this.ble.on('log', (m) => this.log(m))
  }

  // ---------- 事件分发 ----------
  _onMessage(cmd, payload) {
    if (cmd === RESP.WIFI_SCAN) {
      this._onScanFrame(payload)
      return
    }
    const w = this._waiters.find((x) => x.cmd === cmd)
    if (!w) return
    clearTimeout(w.timer)
    this._waiters = this._waiters.filter((x) => x !== w)
    w.resolve(payload)
  }

  _wait(cmd, timeoutMs, hint) {
    return new Promise((resolve, reject) => {
      const w = { cmd, resolve }
      w.timer = setTimeout(() => {
        this._waiters = this._waiters.filter((x) => x !== w)
        reject(new Error((hint || '等待设备响应') + '超时'))
      }, timeoutMs)
      this._waiters.push(w)
    })
  }

  _clearWaiters() {
    this._waiters.forEach((w) => clearTimeout(w.timer))
    this._waiters = []
  }

  // ---------- 0. 蓝牙与扫描 ----------
  async openBluetooth() {
    try {
      await this.ble.openAdapter()
    } catch (e) {
      const msg = (e && e.message) || ''
      if (msg.indexOf('10001') >= 0) throw new Error('蓝牙未打开，请到系统设置里打开蓝牙后重试')
      if (msg.indexOf('10002') >= 0) throw new Error('没有找到这台手机的蓝牙，请检查系统蓝牙')
      if (msg.indexOf('10003') >= 0) throw new Error('蓝牙连接超时，请重试')
      throw new Error('蓝牙不可用：' + msg)
    }
  }

  /**
   * 扫描设备。回调会多次触发，每次带全量列表（已按 RSSI 降序）
   * @param {(list:Array)=>void} onFound
   */
  async startScan(onFound) {
    const seen = {}
    await this.ble.startScan((list) => {
      let changed = false
      list.forEach((d) => {
        const old = seen[d.deviceId]
        if (!old) {
          seen[d.deviceId] = d
          changed = true
        } else if (Math.abs((old.RSSI || 0) - (d.RSSI || 0)) >= 5) {
          seen[d.deviceId] = d // RSSI 变化够大才刷新，避免列表抖动
          changed = true
        }
      })
      if (!changed) return
      const arr = Object.keys(seen)
        .map((k) => seen[k])
        .sort((a, b) => (b.RSSI || -99) - (a.RSSI || -99))
      onFound(arr)
    })
  }

  async stopScan() {
    await this.ble.stopScan()
  }

  // ---------- 1. 连接 + 读设备信息 ----------
  async connect(deviceId) {
    await this.ble.connect(deviceId)
  }

  /** 0x0D 空参 → 0x8D 五个 TLV。失败自动重发一次（协议允许） */
  async readDeviceInfo() {
    let lastErr = null
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const p = this._wait(RESP.QUERY_CONFIG, 8000, '读取设备信息')
        // 🔴 无参请求必须有零长占位 TLV，否则固件不产生任何分片，指令发不出去
        await this.ble.send(CMD.QUERY_CONFIG, new TlvBuilder().putPlaceholder().build(), { encrypt: false })
        const payload = await p
        const info = this._parseDeviceInfo(payload)
        this.info = info
        return info
      } catch (e) {
        lastErr = e
        this.log('读设备信息失败：' + e.message + (attempt === 0 ? '，重试一次' : ''))
        if (attempt === 0) await sleep(600)
      }
    }
    throw lastErr || new Error('读取设备信息失败')
  }

  _parseDeviceInfo(payload) {
    const tlvs = decodeTlv(payload || new Uint8Array(0))
    const info = {
      apikey: str(tlvFirst(tlvs, 0x0a)),
      deviceId: str(tlvFirst(tlvs, 0x0b)),
      orgId: fmt.hexShort(tlvFirst(tlvs, 0x0c)),
      deviceType: fmt.hexShort(tlvFirst(tlvs, 0x0d)),
      fw: str(tlvFirst(tlvs, 0x0e)),
      bindStatus: tlvFirst(tlvs, T_BIND_STATUS) ? tlvFirst(tlvs, T_BIND_STATUS)[0] : null,
      tlvCount: tlvs.length,
    }
    if (!info.apikey || !info.deviceId) {
      throw new Error('设备没上报 apikey / deviceId，这台设备固件太旧，需要升级固件后再配网')
    }
    return info
  }

  // ---------- 2. 扫 WiFi ----------
  /**
   * @param {(list:Array, meta:object)=>void} onProgress 每收到一帧就回调，用于边收边显示
   */
  async scanWifi(onProgress) {
    this._onScanProgress = onProgress || null
    let last = null
    // 协议 §4.5.7 允许最多重发 2 次
    for (let attempt = 0; attempt < 3; attempt++) {
      last = await this._scanWifiOnce(6000)
      if (last.ok) return last
      if (last.fatal) throw new Error(last.fatal)
      if (attempt < 2) {
        this.log('WiFi 列表不完整（' + last.reason + '），重发 0x0E（第 ' + (attempt + 2) + ' 次）')
        await sleep(800)
      }
    }
    // 3 次都不完整：如果已经有部分结果就先用着，否则报错
    if (last && last.list && last.list.length) return last
    throw new Error('设备没有返回 WiFi 列表，可以改为手动输入 WiFi 名称')
  }

  _scanWifiOnce(timeoutMs) {
    return new Promise((resolve) => {
      const state = { lines: [], meta: null, err: null, finished: false, resolve }
      this._scan = state
      state.timer = setTimeout(() => this._finishScan(state, '超时'), timeoutMs)

      this.ble
        .send(CMD.WIFI_SCAN, new TlvBuilder().putPlaceholder().build(), { encrypt: false })
        .catch((e) => this._finishScan(state, '发送失败：' + e.message))
    })
  }

  _finishScan(state, reason) {
    if (!state || state.finished) return
    state.finished = true
    clearTimeout(state.timer)
    if (this._scan === state) this._scan = null
    const total = state.meta ? state.meta.total : state.lines.length
    const list = state.lines.slice().sort((a, b) => a.seq - b.seq)
    const ok = !!state.meta && state.meta.done && state.lines.length >= total
    state.resolve({
      ok,
      reason,
      list,
      total,
      err: state.err,
      fatal: state.err === 0x04 ? CONFIG_ERR[0x04] : '',
    })
  }

  _onScanFrame(payload) {
    const state = this._scan
    if (!state) return
    const tlvs = decodeTlv(payload || new Uint8Array(0))
    let meta = null
    tlvs.forEach((t) => {
      if (t.type === T_AP_LINE) {
        const line = parseApLine(str(t.value))
        if (line) state.lines.push(line)
      } else if (t.type === T_AP_META) {
        if (t.value.length >= 2) {
          meta = { total: t.value[0], done: t.value[1] === 0x01 }
          state.meta = meta
        }
      } else if (t.type === T_ERROR) {
        state.err = t.value[0]
      }
    })

    if (this._onScanProgress) {
      this._onScanProgress(
        state.lines.slice().sort((a, b) => a.seq - b.seq),
        state.meta,
        state.err
      )
    }
    // 收齐 = done 且行数 == total
    if (meta && meta.done && state.lines.length >= meta.total) {
      this._finishScan(state, 'ok')
    }
  }

  // ---------- 3. 配网 ----------
  /**
   * @param {string} ssid
   * @param {string} password 开放网络传空字符串（或干脆不传）
   * @param {number} timeoutMs 设备联网最长等待
   */
  async provision(ssid, password, timeoutMs) {
    if (!ssid) throw new Error('请先选一个 WiFi')
    const tb = new TlvBuilder().putString(0x01, ssid) // AP_SSID
    if (password) tb.putString(0x02, password) // AP_PASSWORD
    const p = this._wait(RESP.CONFIG_STA, timeoutMs || 45000, '设备联网')
    await this.ble.send(CMD.CONFIG_STA, tb.build(), { encrypt: false })
    const payload = await p

    const tlvs = decodeTlv(payload || new Uint8Array(0))
    const code = tlvFirst(tlvs, T_ERROR)
    const err = code && code.length ? code[0] : 0x01
    const result = {
      ok: err === 0x00,
      code: err,
      message: CONFIG_ERR[err] || '未知错误（0x' + err.toString(16) + '）',
      ip: fmt.ipOf(tlvFirst(tlvs, T_IP)),
      mac: fmt.macOf(tlvFirst(tlvs, T_MAC)),
    }
    return result
  }

  // ---------- 4. 收尾 ----------
  async close() {
    this._clearWaiters()
    try {
      await this.ble.disconnect()
    } catch (e) {}
    try {
      await this.ble.closeAdapter()
    } catch (e) {}
  }
}

module.exports = { Provisioner, parseApLine, CONFIG_ERR }
