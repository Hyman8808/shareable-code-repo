// 协议帧编解码 —— 严格对齐 W800 BleWiFi 协议 V1.2 与 Android App 实现
// 帧结构：Cmd(1) + Seq(1) + Flag(1) + NO(1) + PAYLOAD(N) + CRC(1)
// 14 字节 PAYLOAD 时整帧 = 19 字节，正好不超过小程序单次写 BLE 的 20 字节上限

const { crc8 } = require('./crc8')

// ---------- 命令码 ----------
const CMD = {
  CONFIG_STA: 0x0a,      // 配网（下发 SSID/密码）
  QUERY_CONFIG: 0x0d,    // 查询设备信息
  WIFI_SCAN: 0x0e,       // 请求扫描热点
  KEY_EXCHANGE: 0x0f,    // 公钥交换（RSA 握手）
  ACK: 0x10,
  BIND_RESULT: 0x11,     // 绑定结果回传
  DEVICE_CONFIG: 0x12,   // 设备配置（语言/存储天数，设备可忽略）
}
const RESP = {
  CONFIG_STA: 0x8a,
  QUERY_CONFIG: 0x8d,
  WIFI_SCAN: 0x8e,
  KEY_EXCHANGE: 0x8f,
  ACK: 0x90,
  BIND_RESULT: 0x91,
  DEVICE_CONFIG: 0x92,
}

// ---------- TLV ----------
const TLV = {
  AP_SSID: 0x01,
  AP_PASSWORD: 0x02,
  AP_BSSID: 0x03,
  PUB_KEY: 0x09,          // 0x0F 帧内承载公钥
  BIND_STATUS: 0x0f,
  ITEM_LANG: 0x10,
  UI_LANG: 0x11,
  CLOUD_STORE_DAYS: 0x12,
  PLACEHOLDER: 0x00,      // 无参请求必须用零长占位 TLV
  // 0x8D 响应（设备身份凭据）
  R_APIKEY: 0x0a,
  R_DEVICEID: 0x0b,
  R_ORGID: 0x0c,
  R_DEVICE_TYPE: 0x0d,
  R_FW_VER: 0x0e,
  R_PUB_ENC_KEY: 0x87,    // 🔴 是「密钥」不是「列表」，编号空间易混
}

// ---------- Flag 位 ----------
const FLAG_ENCRYPT = 0x80
const FLAG_ACK = 0x40
// 🔴 语义是反的：**置位表示「还有后续帧」**，未置位才是最后一片。
//    判断收齐必须看这一位，不能靠序号（设备不告诉总片数）。
const FLAG_LAST_PACKET = 0x20

const MAX_PAYLOAD = 14

// ---------- UTF-8（小程序旧基础库无 TextEncoder，自己实现） ----------
function strToUtf8(str) {
  const out = []
  for (let i = 0; i < str.length; i++) {
    let c = str.charCodeAt(i)
    if (c < 0x80) {
      out.push(c)
    } else if (c < 0x800) {
      out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f))
    } else if (c >= 0xd800 && c <= 0xdbff) {
      // 代理对（emoji 等）
      const c2 = str.charCodeAt(++i)
      const cp = 0x10000 + ((c - 0xd800) << 10) + (c2 - 0xdc00)
      out.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f))
    } else {
      out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f))
    }
  }
  return Uint8Array.from(out)
}

function utf8ToStr(bytes) {
  let s = ''
  for (let i = 0; i < bytes.length; ) {
    const b = bytes[i]
    if (b < 0x80) { s += String.fromCharCode(b); i += 1 }
    else if (b < 0xe0) { s += String.fromCharCode(((b & 0x1f) << 6) | (bytes[i + 1] & 0x3f)); i += 2 }
    else if (b < 0xf0) { s += String.fromCharCode(((b & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f)); i += 3 }
    else {
      const cp = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12) | ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f)
      s += String.fromCharCode(0xd800 + ((cp - 0x10000) >> 10), 0xdc00 + ((cp - 0x10000) & 0x3ff))
      i += 4
    }
  }
  return s
}

// ---------- TLV 组包 ----------
class TlvBuilder {
  constructor() { this.items = [] }

  putBytes(type, bytes) {
    const body = bytes instanceof Uint8Array ? bytes : Uint8Array.from(bytes)
    // 🔴 Type(1) + Length(**1 字节**) + Value —— 长度只有 1 字节，
    //    写成 2 字节小端会让设备整帧解析失败（已与 MessageEncode.serialize 逐行核对）
    this.items.push(new Uint8Array([type & 0xff, body.length & 0xff]))
    this.items.push(body)
    return this
  }

  putString(type, str) { return this.putBytes(type, strToUtf8(str)) }

  /** 无参请求必须放一个零长占位 TLV，否则设备不收 */
  putPlaceholder() { return this.putBytes(TLV.PLACEHOLDER, new Uint8Array(0)) }

  build() {
    let total = 0
    this.items.forEach(p => { total += p.length })
    const out = new Uint8Array(total)
    let off = 0
    this.items.forEach(p => { out.set(p, off); off += p.length })
    return out
  }
}

// ---------- TLV 解包 ----------
/**
 * 🔴 必须用数组保留重复类型：固件会把多条 0x88 塞进同一帧，
 * 用 Map 按类型存会只剩最后一条（App 曾因此少显示热点）。
 */
function decodeTlv(bytes) {
  const list = []
  let i = 0
  while (i + 2 <= bytes.length) {
    const type = bytes[i]
    const len = bytes[i + 1]        // 长度 1 字节
    const start = i + 2
    if (start + len > bytes.length) break
    list.push({ type, value: bytes.slice(start, start + len) })
    i = start + len
  }
  return list
}

/** 类型唯一时取值；重复时取第一条 */
function tlvFirst(list, type) {
  const hit = list.find(t => t.type === type)
  return hit ? hit.value : null
}

// ---------- 帧组装 ----------
/**
 * @param {number} cmd 命令码
 * @param {number} seq 序号（**分片级自增**，每一片都 +1，0~255 循环，连接建立后归零）
 * @param {number} no  分片序号（🔴 **0 基**：第 1 片是 0，不是 1）
 * @param {Uint8Array} payload 已加密、已切片的数据
 * @param {boolean} ack 是否要求对方回 ack（发送方向 App 恒传 false）
 * @param {boolean} encrypt 是否加密（与 payload 加密状态一致）
 * @param {boolean} hasMore 🔴 非末片必须置 true（对应 flag&0x20），否则设备当成单帧处理
 */
function buildFrame(cmd, seq, no, payload, { ack = false, encrypt = false, hasMore = false } = {}) {
  const frame = new Uint8Array(payload.length + 5)
  frame[0] = cmd & 0xff
  frame[1] = seq & 0xff
  frame[2] = (encrypt ? FLAG_ENCRYPT : 0) | (ack ? FLAG_ACK : 0) | (hasMore ? FLAG_LAST_PACKET : 0)
  frame[3] = no & 0xff
  frame.set(payload, 4)
  frame[frame.length - 1] = crc8(frame, frame.length - 1, 0)
  return frame
}

/** 解析收到的帧；CRC 不符返回 null */
function parseFrame(bytes) {
  if (!bytes || bytes.length < 5) return null
  const expect = crc8(bytes, bytes.length - 1, 0)
  if (expect !== bytes[bytes.length - 1]) return null
  return {
    cmd: bytes[0],
    seq: bytes[1],
    flag: bytes[2],
    no: bytes[3],
    payload: bytes.slice(4, bytes.length - 1),
    encrypted: (bytes[2] & FLAG_ENCRYPT) !== 0,
    ackRequired: (bytes[2] & FLAG_ACK) !== 0,
    hasMore: (bytes[2] & FLAG_LAST_PACKET) !== 0,   // true = 后面还有帧
  }
}

/** 把一段数据按 14 字节切片 */
function slice14(data) {
  const out = []
  for (let i = 0; i < data.length; i += MAX_PAYLOAD) {
    out.push(data.slice(i, i + MAX_PAYLOAD))
  }
  return out.length ? out : [new Uint8Array(0)]
}

module.exports = {
  CMD, RESP, TLV, FLAG_ENCRYPT, FLAG_ACK, FLAG_LAST_PACKET, MAX_PAYLOAD,
  TlvBuilder, decodeTlv, tlvFirst,
  buildFrame, parseFrame, slice14,
  strToUtf8, utf8ToStr,
}
