// 诊断信息 —— 照搬 App `DiagnoseActivity`
//
// App 那份报告有「崩溃栈 / Room 初始化 / BLE 权限 / 容器环境」几段，小程序没有这些概念，
// 对应换成小程序真实可查的：基础库版本、蓝牙适配器状态、存储用量、网络类型。
// 一份诊断报告的价值在于**每一条都是真查出来的**，不是模板填空。
//
// 输出两段：本地环境（同步，立即出）+ 云端连通性（异步，点一下才查）。

const api = require('../../utils/api')
const store = require('../../utils/store')
const fmt = require('../../utils/fmt')
const prefs = require('../../utils/prefs')
const i18n = require('../../utils/i18n')
const icons = require('../../utils/icons')

// 参考设备：图灵联调样机。它必须一直有数据，用来区分「云端挂了」和「你这台没拍」
const REF_DEVICE = 'c8586a630000'
const REF_KEY = '<DEVICE_API_KEY>'

Page({
  data: {
    statusBar: 20,
    report: '',
    cloudResult: '',
    checking: false,
    icons: { back: icons.back, refresh: icons.refreshT3 },
  },

  onLoad() {
    this.setData(Object.assign(i18n.page(), { statusBar: (getApp().globalData || {}).statusBar || 20 }))
    this.build()
    this.checkCloud()
  },

  _sys() {
    // 新基础库把 getSystemInfoSync 拆成三个，先试新的再回退，避免拿到一堆 undefined
    let dev = {},
      app = {},
      win = {}
    try {
      // 老基础库没有这三个接口，存在但返 undefined 也要兜住，否则下面 dev.model 直接炸
      if (wx.getDeviceInfo) dev = wx.getDeviceInfo() || {}
      if (wx.getAppBaseInfo) app = wx.getAppBaseInfo() || {}
      if (wx.getWindowInfo) win = wx.getWindowInfo() || {}
    } catch (e) {}
    if (!dev.model) {
      try {
        const s = wx.getSystemInfoSync()
        dev = { brand: s.brand, model: s.model, system: s.system, platform: s.platform }
        app = { SDKVersion: s.SDKVersion, version: s.version, language: s.language }
        win = { screenWidth: s.screenWidth, screenHeight: s.screenHeight, pixelRatio: s.pixelRatio }
      } catch (e) {}
    }
    return { dev, app, win }
  },

  _appId() {
    try {
      const a = wx.getAccountInfoSync()
      return (a && a.miniProgram && a.miniProgram.appId) || ''
    } catch (e) {
      return ''
    }
  },

  _storage() {
    try {
      const s = wx.getStorageInfoSync()
      return { kb: s.currentSize, keys: (s.keys || []).length }
    } catch (e) {
      return { kb: -1, keys: 0 }
    }
  },

  async build() {
    const { dev, app, win } = this._sys()
    const st = this._storage()
    const lat = api.latencyStats()
    const g = (getApp().globalData || {})
    const devices = store.getDevices()
    const cur = store.getCurrent()
    const nameLang = prefs.getNameLang()
    const uiLang = prefs.getUiLang()

    let net = '未知'
    try {
      net = await new Promise((res) => {
        wx.getNetworkType({ success: (r) => res(r.networkType || '未知'), fail: () => res('未知') })
      })
    } catch (e) {}

    const bt = await this._bleState()

    const L = []
    L.push('═══ 应用信息 ═══')
    L.push('版本: ' + (g.version || '0.1.0'))
    L.push('形态: 微信小程序')
    L.push('AppID: ' + (this._appId() || '-'))
    L.push('基础库: ' + (app.SDKVersion || '-'))
    L.push('')
    L.push('═══ 运行环境 ═══')
    L.push('微信: ' + (app.version || '-'))
    L.push('系统: ' + (dev.system || '-') + '  ' + (dev.platform || '-'))
    L.push('机型: ' + (dev.brand || '') + ' ' + (dev.model || ''))
    L.push('屏幕: ' + (win.screenWidth || '-') + '×' + (win.screenHeight || '-') + ' @' + (win.pixelRatio || '-') + 'x')
    L.push('网络: ' + net)
    L.push('')
    L.push('═══ 能力检测 ═══')
    L.push('蓝牙: ' + bt)
    L.push('本地存储: ' + (st.kb < 0 ? '❌ 不可用' : st.kb + ' KB / ' + st.keys + ' 项'))
    L.push('')
    L.push('═══ 网络时延 ═══')
    if (!lat) {
      L.push('记录接口: 暂无样本（刷新一次首页后复测）')
    } else {
      L.push(
        '记录接口(近' +
          lat.count +
          '次): 平均 ' +
          lat.avgMs +
          'ms / P50 ' +
          lat.p50Ms +
          'ms / 最大 ' +
          lat.maxMs +
          'ms'
      )
      L.push(lat.p50Ms <= 2000 ? '  ✅ 达标 ≤2000ms' : '  ⚠️ 超过 2000ms')
    }
    L.push('')
    L.push('═══ 语言 ═══')
    L.push('物品名称语言: ' + nameLang.native + ' (' + nameLang.code + ')')
    L.push('界面语言: ' + prefs.uiDisplayName(uiLang) + ' (' + uiLang + ')')
    L.push('')
    L.push('═══ 设备 ═══')
    L.push('已绑定: ' + devices.length + ' 台')
    if (!devices.length) {
      L.push('（本机没有添加任何设备）')
    } else {
      devices.forEach((d) => {
        L.push(
          '  ' +
            (d.name || '拇指相机') +
            '  ' +
            d.deviceId +
            (d.deviceId === (cur && cur.deviceId) ? '  ← 当前' : '') +
            (d.demo ? '  [演示]' : '')
        )
        L.push('    授权: ' + fmt.maskKey(d.apikey || '') + (d.fw ? '   固件: ' + d.fw : ''))
      })
    }
    L.push('')
    L.push('═══ 说明 ═══')
    L.push('小程序版没有崩溃栈与本地数据库：数据直接走云端接口，本地只缓存收藏与设备清单。')
    L.push('排查「看不到照片」时，先看上面云端自检的结果。')

    this.setData({ report: L.join('\n') })
  },

  /** 蓝牙适配器是否可用（不能只看 wx.openBluetoothAdapter 是否存在） */
  _bleState() {
    return new Promise((resolve) => {
      if (!wx.openBluetoothAdapter) {
        resolve('❌ 当前基础库不支持')
        return
      }
      wx.openBluetoothAdapter({
        success: () => {
          wx.getBluetoothAdapterState({
            success: (r) => {
              resolve((r.available ? '✅ 可用' : '⚠️ 适配器未就绪') + (r.discovering ? '（正在扫描）' : ''))
              // 诊断只查状态，查完立刻关，避免占着适配器影响配网页
              wx.closeBluetoothAdapter({})
            },
            fail: () => resolve('⚠️ 状态未知'),
          })
        },
        fail: (e) => {
          const m = String((e && e.errMsg) || '')
          resolve(m.indexOf('auth') >= 0 || m.indexOf('deny') >= 0 ? '⚠️ 未授权蓝牙' : '❌ 不可用')
        },
      })
    })
  },

  /** 云端连通性：本机每台设备各查一次 total，再加一台参考设备做对照 */
  checkCloud() {
    if (this.data.checking) return
    this.setData({ checking: true, cloudResult: i18n.t('diag_checking') })

    const devices = store.getDevices().slice(0, 5)
    const jobs = devices.map((d) =>
      api
        .listRecords({ apikey: d.apikey, deviceId: d.deviceId, page: 1, size: 1 })
        .then((r) => ({ id: d.deviceId, n: Number(r && r.total) || 0, ok: true }))
        .catch(() => ({ id: d.deviceId, n: -1, ok: false }))
    )
    jobs.push(
      api
        .listRecords({ apikey: REF_KEY, deviceId: REF_DEVICE, page: 1, size: 1 })
        .then((r) => ({ id: REF_DEVICE, n: Number(r && r.total) || 0, ok: true, ref: true }))
        .catch(() => ({ id: REF_DEVICE, n: -1, ok: false, ref: true }))
    )

    Promise.all(jobs).then((all) => {
      const L = []
      L.push('【本机绑定设备】')
      if (!devices.length) L.push('  （本机未找到已绑定设备）')
      all
        .filter((x) => !x.ref)
        .forEach((x) => L.push('  ' + x.id + '  → ' + (x.n < 0 ? '查询失败' : '云端 ' + x.n + ' 条')))
      const ref = all.find((x) => x.ref)
      L.push('')
      L.push('【参考设备（联盛德测试机）】')
      L.push('  ' + REF_DEVICE + '  → ' + (ref.n < 0 ? '查询失败' : '云端 ' + ref.n + ' 条'))
      L.push('')
      if (!ref.ok) L.push(i18n.t('diag_unreachable'))
      else if (ref.n > 0) L.push(i18n.t('diag_ok') + '\n' + i18n.t('diag_ok_detail'))
      else L.push(i18n.t('diag_ref_zero'))

      this.setData({ checking: false, cloudResult: L.join('\n') })
    })
  },

  onCopy() {
    const text = this.data.report + '\n\n═══ 云端自检 ═══\n' + (this.data.cloudResult || '未检查')
    wx.setClipboardData({
      data: text,
      success: () => wx.showToast({ title: i18n.t('diag_copied_toast'), icon: 'none' }),
    })
  },

  onClear() {
    wx.showModal({
      title: i18n.t('diag_clear'),
      content: i18n.t('diag_sub'),
      success: (res) => {
        if (!res.confirm) return
        api.clearLatency()
        this.build()
        wx.showToast({ title: i18n.t('diag_cleared'), icon: 'none' })
      },
    })
  },

  onBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/profile/profile' })
  },
})
