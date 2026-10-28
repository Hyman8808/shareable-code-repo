// 把「物品名称语言」写到云端 /api/device/lang
//
// 与 api.js 的分工：api.js 只管单次请求；这里管「报给谁、失败了怎么补」。
//
// ## 为什么必须有这一层（2026-10-28）
//
// 云端改成「**拍摄时**按 deviceId 查语言并固化进记录」，识别请求不再接受 lang 参数。
// 也就是说用户在设置页选的语言必须写一次云端，只写本地 storage 等于没设 ——
// 拍出来还是中文，而且不报任何错。
//
// ## 三个必须在这里处理的点
//
//  1. **多设备逐台上报**：云端语言是按 deviceId 存的。只报「当前设备」会出现
//     「这台改了、换一台又不行」，而语言页本身没有「当前设备」的概念。
//  2. **失败留 pending**：没配网 / 断网 / 还没 apikey 都会失败，
//     记下来等进设置页或绑定设备时重试，不能让用户以为设置丢了。
//  3. **none → en 映射**：云端白名单没有 `none`（只有 zh/zh-Hant/en/ja/ko/es/fr/de/ru/ar），
//     `none` 是本地「仅英文」模式，语义上等于 en。不映射会拿到 40000。

const api = require('./api')
const store = require('./store')

const K_PENDING = 'tc.langPending'

/** 本地语言码 → 云端白名单码（`none` 不在白名单，映射成 en） */
function cloudCodeOf(code) {
  return code === 'none' ? 'en' : code
}

function setPending(code) {
  try {
    wx.setStorageSync(K_PENDING, code)
  } catch (e) {}
}

/** 待补报的语言码；没有返回 '' */
function getPending() {
  try {
    return wx.getStorageSync(K_PENDING) || ''
  } catch (e) {
    return ''
  }
}

function clearPending() {
  try {
    wx.removeStorageSync(K_PENDING)
  } catch (e) {}
}

/**
 * 上报到全部已绑设备。
 *
 * @param {string} code 本地语言码（none / zh / zh-Hant / ja …）
 * @returns {Promise<{ok:number, failed:number, noDevice:boolean}>}
 *   全部成功才算成功；`noDevice` 表示还没有可用设备，已记 pending。
 */
function push(code) {
  const cloud = cloudCodeOf(code)
  const devices = store.getDevices().filter((d) => d && d.deviceId && d.apikey)

  if (!devices.length) {
    setPending(cloud)
    return Promise.resolve({ ok: 0, failed: 0, noDevice: true })
  }

  return Promise.all(
    devices.map((d) =>
      api
        .setDeviceLang({ apikey: d.apikey, deviceId: d.deviceId, lang: cloud })
        .then(() => true)
        .catch(() => false)
    )
  ).then((results) => {
    const ok = results.filter(Boolean).length
    const failed = results.length - ok
    if (failed === 0) clearPending()
    else setPending(cloud)
    return { ok, failed, noDevice: false }
  })
}

/**
 * 有 pending 才重试；没有则什么都不做（不发请求）。
 * 调用点：语言页 onLoad、绑定设备成功后。
 */
function flush() {
  const code = getPending()
  if (!code) return Promise.resolve(null)
  return push(code)
}

module.exports = { cloudCodeOf, push, flush, getPending, clearPending }
