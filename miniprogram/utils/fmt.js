// 展示层格式化：时间 / 信号 / 加密方式 / IP / MAC

/** 'YYYY-MM-DD HH:mm:ss' → 今天 15:09 / 昨天 15:09 / 10月27日 */
function timeAgo(str) {
  if (!str) return ''
  // iOS 不认 'YYYY-MM-DD HH:mm:ss'，必须替换成 '/'
  const d = new Date(String(str).replace(/-/g, '/'))
  if (isNaN(d.getTime())) return String(str)
  const now = new Date()
  const pad = (n) => (n < 10 ? '0' + n : '' + n)
  const hm = pad(d.getHours()) + ':' + pad(d.getMinutes())
  const sameDay = (a, b) =>
    a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
  if (sameDay(d, now)) return '今天 ' + hm
  const y = new Date(now.getTime() - 86400000)
  if (sameDay(d, y)) return '昨天 ' + hm
  if (d.getFullYear() === now.getFullYear()) return d.getMonth() + 1 + '月' + d.getDate() + '日'
  return d.getFullYear() + '/' + (d.getMonth() + 1) + '/' + d.getDate()
}

/** RSSI → 1~4 格；越接近 0 越强 */
function rssiLevel(rssi) {
  const v = Number(rssi)
  if (!isFinite(v)) return 0
  if (v >= -55) return 4
  if (v >= -67) return 3
  if (v >= -78) return 2
  return 1
}

const AUTH_LABEL = {
  0: '开放',
  1: 'WEP',
  2: 'WPA/WPA2',
  3: 'WPA2',
  4: 'WPA3',
  5: 'WPA2/WPA3',
  9: '未知加密',
}

function authLabel(auth) {
  const v = Number(auth)
  if (AUTH_LABEL[v]) return AUTH_LABEL[v]
  return v === 0 ? '开放' : '未知加密'
}

/** 开放网络不需要密码（AUTH=0） */
function needPassword(auth) {
  return Number(auth) !== 0
}

/** 4 字节 → 点分 IP，如 c0 a8 01 05 → 192.168.1.5 */
function ipOf(bytes) {
  if (!bytes || bytes.length < 4) return ''
  return [bytes[0], bytes[1], bytes[2], bytes[3]].join('.')
}

/** 6 字节 → MAC */
function macOf(bytes) {
  if (!bytes || !bytes.length) return ''
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join(':')
    .toUpperCase()
}

/** 4 位十六进制（ORG-ID / TYPE 之类的小整数） */
function hexShort(bytes) {
  if (!bytes || !bytes.length) return ''
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
    .toUpperCase()
}

/** apikey 打码：前 6 后 4 */
function maskKey(s) {
  if (!s) return ''
  if (s.length <= 12) return s
  return s.slice(0, 6) + '****' + s.slice(-4)
}

/**
 * 统一升级成 https
 * 🔴 老栈 tts_url 返回的是 http://turing-iot-tts-oss.turingapi.com/...
 *    小程序真机一律拦 http（image / innerAudioContext 都放不出），
 *    实测同一路径换 https 返回 200 且内容一致，所以在这里就地升级，不改云端。
 */
function toHttps(u) {
  if (!u || typeof u !== 'string') return ''
  if (u.indexOf('http://') === 0) return 'https://' + u.slice(7)
  return u
}

/** 今天 0 点的 Date（本地时区） */
function startOfDay(d) {
  const x = new Date(d || Date.now())
  x.setHours(0, 0, 0, 0)
  return x
}

/** 'YYYY-MM-DD HH:mm:ss' → Date（iOS 不认横杠，统一换斜杠） */
function parseTime(str) {
  if (!str) return null
  const d = new Date(String(str).replace(/-/g, '/'))
  return isNaN(d.getTime()) ? null : d
}

/** 是否今天 */
function isToday(str) {
  const d = parseTime(str)
  if (!d) return false
  return d >= startOfDay()
}

/** 是否最近 n 天（含今天） */
function isWithinDays(str, n) {
  const d = parseTime(str)
  if (!d) return false
  return d >= startOfDay(new Date(Date.now() - (n - 1) * 86400000))
}

/** '11月6日 周三' */
function dateLine(d) {
  const x = d || new Date()
  const w = '日一二三四五六'.charAt(x.getDay())
  return x.getMonth() + 1 + '月' + x.getDate() + '日 周' + w
}

/** '10-20 14:32'（列表行 meta 用，长度稳定） */
function shortTime(str) {
  const d = parseTime(str)
  if (!d) return ''
  const pad = (n) => (n < 10 ? '0' + n : '' + n)
  return pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes())
}

module.exports = {
  timeAgo,
  rssiLevel,
  authLabel,
  needPassword,
  ipOf,
  macOf,
  hexShort,
  maskKey,
  toHttps,
  parseTime,
  isToday,
  isWithinDays,
  dateLine,
  shortTime,
  AUTH_LABEL,
}
