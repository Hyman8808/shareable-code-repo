// 全局入口
// 说明：本版只做「记录 / 配网 / 设备管理」三件事，无账号体系、无支付（iOS 禁虚拟支付）
const store = require('./utils/store')
const demo = require('./utils/demo')

App({
  globalData: {
    version: '0.1.0',
    statusBar: 20, // 自定义导航栏要自己让出状态栏高度
  },

  onLaunch() {
    store.init()

    // 首次打开：没有任何设备时自动挂一台演示机，
    // 让「今天 / 详情」能立刻看到内容，不必先配网。
    // 用户一旦点过「不用演示数据」，就不再自动装。
    if (!store.getDevices().length && !demo.isDismissed()) {
      demo.install({ silent: true })
    }

    try {
      const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
      this.globalData.statusBar = info.statusBarHeight || 20
    } catch (e) {}
  },

  onShow() {},

  onError(err) {
    console.error('[app] onError', err)
  },
})
