// 云端接口 —— 老栈 nglearn（App / 小程序读接口）
//
// 🔴 这里**故意不加密、不带 secret**：AES + secret 那套只属于「设备 → 云端」，
//    secret 烧在固件里，不可能放进小程序包（内置即公开全部批次额度）。
//    读接口现状就是明文可用：带 key + deviceId 即可，服务端不校验 secret。
//
// 🔴 老栈成功码是 200（不是 0），字段是 data.list / data.record
//    新栈（thumbcamera-api #2835）才是 code:0 + cursor 分页，别混。

const BASE = 'https://nglearn.turingapi.com'

const ERR_TEXT = {
  4005: '设备授权信息无效，请在「设备」里重新配网',
  4006: '设备号无效，请在「设备」里重新配网',
  40000: '这条记录已不存在',
}

// ---------- 时延采样（诊断页用） ----------
// App 有 LatencyStore 记「近 N 次」；小程序只留最近 10 次，够看趋势。
const K_LAT = 'tc.latency'
const LAT_MAX = 10

function pushLatency(ms) {
  try {
    const v = wx.getStorageSync(K_LAT)
    const arr = Array.isArray(v) ? v : []
    arr.push(Math.round(ms))
    while (arr.length > LAT_MAX) arr.shift()
    wx.setStorageSync(K_LAT, arr)
  } catch (e) {}
}

/** 近 N 次时延统计；没样本返回 null */
function latencyStats() {
  const v = wx.getStorageSync(K_LAT)
  const arr = (Array.isArray(v) ? v : []).filter((x) => typeof x === 'number' && x >= 0)
  if (!arr.length) return null
  const sorted = arr.slice().sort((a, b) => a - b)
  const avg = Math.round(arr.reduce((s, x) => s + x, 0) / arr.length)
  return {
    count: arr.length,
    avgMs: avg,
    p50Ms: sorted[Math.floor(sorted.length / 2)],
    maxMs: sorted[sorted.length - 1],
  }
}

function clearLatency() {
  try {
    wx.removeStorageSync(K_LAT)
  } catch (e) {}
}

function isOk(body) {
  return body && (body.code === 200 || body.code === 0)
}

/**
 * 参数拼到 query 上（GET / POST 都一样）。
 *
 * 🔴 不能交给 `wx.request` 的 `data`：它在 POST 时会当成 body 发出去，
 *    而 `/api/device/lang` **不解析 POST 的 JSON body** —— 参数放 body 会拿到
 *    `4005 apikey信息错误`，看起来像密钥错，实际只是位置错。手动拼 query 才是对的。
 */
function toQuery(params) {
  const p = params || {}
  const parts = []
  Object.keys(p).forEach((k) => {
    const v = p[k]
    if (v === undefined || v === null) return
    parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v))
  })
  return parts.length ? '?' + parts.join('&') : ''
}

function request(path, params, method) {
  return new Promise((resolve, reject) => {
    const t0 = Date.now()
    wx.request({
      url: BASE + path + toQuery(params),
      method: (method || 'GET').toUpperCase(),
      timeout: 12000,
      success(res) {
        pushLatency(Date.now() - t0)
        const body = res.data || {}
        if (isOk(body)) {
          resolve(body.data || {})
          return
        }
        const msg = ERR_TEXT[body.code] || body.message || 'code=' + body.code
        const err = new Error(msg)
        err.code = body.code
        reject(err)
      },
      fail(e) {
        const err = new Error('网络请求失败，请检查网络')
        err.raw = e && e.errMsg
        reject(err)
      },
    })
  })
}

/**
 * 记录列表
 * @param {{apikey:string, deviceId:string, page?:number, size?:number, lang?:string}} p
 * @returns {Promise<{total:number, page:number, size:number, list:Array}>}
 */
function listRecords(p) {
  return request('/api/record/list', {
    key: p.apikey,
    deviceId: p.deviceId,
    page: p.page || 1,
    size: Math.min(p.size || 20, 100), // 🔴 上限 100，超了服务端直接报错
    // 🔴 不再传 lang：2026-10-28 实测该参数已失效（带与不带返回一致）。
    //    语言在**拍摄时**由云端按 deviceId 的设定固化进 name_localized / tts_url_localized，
    //    传 lang 只会让人误以为「换语言能改历史记录」。
  })
}

/**
 * 记录详情（必须带 key，否则 4005）
 * @param {{apikey:string, id:number|string}} p
 * @returns {Promise<object>} 记录本体（data.record）
 */
function recordDetail(p) {
  return request('/api/record/detail', { id: p.id, key: p.apikey }).then((d) => d.record || d)
}

// ---------- 设备语言设定（2026-10-28 云端上线） ----------
//
// 为什么必须调：云端改成「**拍摄时**按 deviceId 查语言并固化进记录」，
// 识别请求不再接受 lang 参数。所以用户在设置页选的语言必须写到云端，
// 只存本地 storage 等于没设 —— 拍出来还是中文。
//
// 🔴 语言码白名单：zh / zh-Hant / en / ja / ko / es / fr / de / ru / ar
//    **没有 none**。小程序里「无（仅英文）」是本地模式开关，上报时要映射成 en
//    （见 language.js 的 cloudCodeOf）。
// 🔴 幂等：重复提交同一个 lang 返回 200，可以安全重试。

/**
 * 写入一台设备的名称语言
 * @param {{apikey:string, deviceId:string, lang:string}} p
 */
function setDeviceLang(p) {
  return request(
    '/api/device/lang',
    { key: p.apikey, deviceId: p.deviceId, lang: p.lang },
    'POST'
  )
}

/**
 * 读取一台设备当前的名称语言（`data.device_id` + `data.lang`）
 * @param {{apikey:string, deviceId:string}} p
 */
function getDeviceLang(p) {
  return request('/api/device/lang', { key: p.apikey, deviceId: p.deviceId })
}

module.exports = {
  BASE,
  listRecords,
  recordDetail,
  setDeviceLang,
  getDeviceLang,
  latencyStats,
  clearLatency,
}
