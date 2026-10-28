// 本地存储：设备清单 / 当前设备 / 本地收藏
// 🔴 多设备唯一真源是 deviceId，不是数组下标（下标会因为删除、排序漂移）

const K_DEVICES = 'tc.devices'
const K_CURRENT = 'tc.current'
const K_FAV = 'tc.favorites'

function init() {
  if (!Array.isArray(wx.getStorageSync(K_DEVICES))) wx.setStorageSync(K_DEVICES, [])
  if (!Array.isArray(wx.getStorageSync(K_FAV))) wx.setStorageSync(K_FAV, [])
}

function getDevices() {
  const v = wx.getStorageSync(K_DEVICES)
  return Array.isArray(v) ? v : []
}

function saveDevices(list) {
  wx.setStorageSync(K_DEVICES, list || [])
}

/**
 * 新增或更新一台设备
 * @param {{deviceId:string, apikey:string, name?:string, fw?:string, demo?:boolean}} dev
 */
function upsertDevice(dev) {
  if (!dev || !dev.deviceId) return getDevices()
  const list = getDevices()
  const i = list.findIndex((d) => d.deviceId === dev.deviceId)
  if (i >= 0) {
    list[i] = Object.assign({}, list[i], dev)
  } else {
    list.unshift(Object.assign({ addedAt: Date.now() }, dev))
  }
  saveDevices(list)
  if (!getCurrentId()) setCurrentId(dev.deviceId)
  return list
}

function removeDevice(deviceId) {
  const list = getDevices().filter((d) => d.deviceId !== deviceId)
  saveDevices(list)
  if (getCurrentId() === deviceId) {
    setCurrentId(list.length ? list[0].deviceId : '')
  }
  // 顺手清掉该设备的本地收藏
  wx.setStorageSync(K_FAV, getFavorites().filter((f) => f.deviceId !== deviceId))
  return list
}

function getCurrentId() {
  return wx.getStorageSync(K_CURRENT) || ''
}

function setCurrentId(deviceId) {
  wx.setStorageSync(K_CURRENT, deviceId || '')
}

/** 当前设备；没有则取列表第一台；都没有返回 null */
function getCurrent() {
  const list = getDevices()
  if (!list.length) return null
  const id = getCurrentId()
  return list.find((d) => d.deviceId === id) || list[0]
}

// ---------- 收藏（本版只存本地，云端无收藏接口） ----------
//
// 🔴 收藏**存快照**，不只存 id：老栈没有「按 id 批量取」的接口，
//    只存 id 的话收藏页要为每条记录单发一次 detail（10 条 = 10 次请求）。
//    快照落本地后，收藏页离线也能渲染，点进去再用 id 拉最新详情。
//
// 兼容旧数据：老版本存的是 'deviceId:recordId' 字符串，读的时候归一化成对象。

function favKey(deviceId, recordId) {
  return deviceId + ':' + recordId
}

function normalizeFav(v, deviceId) {
  if (v && typeof v === 'object') return v.id !== undefined ? v : null
  if (typeof v === 'string' && v) {
    const i = v.indexOf(':')
    if (i <= 0) return null
    return { key: v, deviceId: v.slice(0, i), id: v.slice(i + 1) }
  }
  return null
}

function getFavorites() {
  const v = wx.getStorageSync(K_FAV)
  if (!Array.isArray(v)) return []
  return v.map((x) => normalizeFav(x)).filter(Boolean)
}

function isFavorite(deviceId, recordId) {
  return getFavorites().some((f) => f.deviceId === deviceId && String(f.id) === String(recordId))
}

/**
 * 切换收藏
 * @param {string} deviceId
 * @param {number|string} recordId
 * @param {object} [snapshot] 行数据快照 {img, headword, cjk, meta}
 * @returns {boolean} 切换后的状态（true = 已收藏）
 */
function toggleFavorite(deviceId, recordId, snapshot) {
  const list = getFavorites()
  const i = list.findIndex((f) => f.deviceId === deviceId && String(f.id) === String(recordId))
  if (i >= 0) {
    list.splice(i, 1)
    wx.setStorageSync(K_FAV, list)
    return false
  }
  const s = snapshot || {}
  list.unshift({
    key: favKey(deviceId, recordId),
    deviceId,
    id: recordId,
    img: s.img || '',
    headword: s.headword || '',
    cjk: s.cjk || '',
    meta: s.meta || '',
    at: Date.now(),
  })
  wx.setStorageSync(K_FAV, list)
  return true
}

function clearAll() {
  wx.removeStorageSync(K_CURRENT)
  wx.removeStorageSync(K_FAV)
  // 设备清单保留：清缓存不该让用户丢设备
}

module.exports = {
  init,
  getDevices,
  upsertDevice,
  removeDevice,
  getCurrentId,
  setCurrentId,
  getCurrent,
  getFavorites,
  isFavorite,
  toggleFavorite,
  clearAll,
}
