// 演示设备（图灵联调样机）
//
// 用途：没有硬件、或暂时不想走配网时，直接能看到「记录 / 卡片详情」两页的真实内容。
// 这些照片是图灵样机 real 拍真识别的数据，不是假图，所以界面效果与真机一致。
//
// 🔴 演示机与真实配网机在数据层没有区别（都是 deviceId + apikey 读老栈），
//    唯一的区别是 demo:true 这个标记 —— 界面上用它来打「演示」角标，
//    不要让用户误以为这是自己相机拍的照片。

const DEVICE = {
  deviceId: 'c8586a630000',
  apikey: '<DEVICE_API_KEY>',
  name: '演示机（图灵样机）',
  fw: 'demo',
  demo: true,
}

const K_DISMISSED = 'tc.demoDismissed'

function isDemo(dev) {
  return !!(dev && dev.demo)
}

/** 是否已装过（避免重复 unshift） */
function installed() {
  const store = require('./store')
  return store.getDevices().some((d) => d.deviceId === DEVICE.deviceId)
}

/**
 * 装上演示机并设为当前
 * @param {{silent?:boolean}} opt
 */
function install(opt) {
  const store = require('./store')
  const list = store.upsertDevice(DEVICE)
  store.setCurrentId(DEVICE.deviceId)
  if (!opt || !opt.silent) wx.removeStorageSync(K_DISMISSED)
  return list
}

/** 用户明确「我不用演示数据」后置位，onLaunch 不再自动装 */
function dismiss() {
  wx.setStorageSync(K_DISMISSED, 1)
}

function isDismissed() {
  return !!wx.getStorageSync(K_DISMISSED)
}

module.exports = { DEVICE, isDemo, installed, install, dismiss, isDismissed }
