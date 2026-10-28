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

function request(path, params) {
  return new Promise((resolve, reject) => {
    const t0 = Date.now()
    wx.request({
      url: BASE + path,
      data: params || {},
      method: 'GET',
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

module.exports = { BASE, listRecords, recordDetail, latencyStats, clearLatency }
