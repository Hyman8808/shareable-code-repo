// 我的 —— 照搬 App fragment_profile.xml（V2.0 墨标 6.5「设置行」）
//
// 设置行：56dp / 无底无描边 / 靠 divider 分隔 / 15sp 主字 + 右侧 12sp 当前值 + chevron
// divider marginStart 从主字起（不切图标）
//
// ⚠️ 与 App 的差异（都写成「说明」而不是「假按钮」）：
//   · 账号：App 也是开发中（LoginActivity 三渠道只弹提示、不建假账号），这里同样只说明
//   · 设备语言：本版设备没有语言 UI，语言由云端按 deviceId 下发 → 不提供下发入口
//   · 从相册找回：App 扫的是系统 MediaStore，小程序拿不到静默读相册的权限
//   · 云端存储 / 额度：都要云端接口，未上线 → 只说明现状

const store = require('../../utils/store')
const demo = require('../../utils/demo')
const prefs = require('../../utils/prefs')
const auth = require('../../utils/auth')
const i18n = require('../../utils/i18n')
const icons = require('../../utils/icons')

Page({
  data: {
    statusBar: 20,
    version: '0.1.0',
    hasDevice: false,
    camName: '',
    camMeta: '',
    camState: '',
    isDemo: false,
    deviceCount: 0,
    favCount: 0,
    nameLangLabel: '',
    uiLangLabel: '',
    logged: false,
    acctName: '',
    acctMeta: '',
    icons: {
      chevron: icons.chevronT3,
      camera: icons.camera,
      me: icons.me,
      speak: icons.speakT3,
      refresh: icons.refreshT3,
      download: icons.downloadT3,
      diag: icons.refreshT3,
      bt: icons.btT3,
    },
  },

  onLoad() {
    const g = getApp().globalData || {}
    this.setData(Object.assign(i18n.page(), { statusBar: g.statusBar || 20, version: g.version || '0.1.0' }))
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 2, list: this.getTabBar().data.list })
    }
    this.refresh()
  },

  refresh() {
    const cur = store.getCurrent()
    const list = store.getDevices()
    const nameLang = prefs.getNameLang()
    const acct = auth.account()
    let local = {}
    try {
      local = wx.getStorageSync('tc.acctProfile') || {}
    } catch (e) {}
    this.setData({
      logged: !!acct,
      // 已登录显示昵称（用户自己填的），未登录显示「点击登录」
      acctName: acct ? (acct.nickname || local.nick || i18n.t('mp_login_nav')) : i18n.t('profile_login'),
      acctMeta: i18n.t('mp_acct_meta'),
      hasDevice: !!cur,
      isDemo: demo.isDemo(cur),
      deviceCount: list.length,
      favCount: store.getFavorites().length,
      camName: cur ? cur.name || i18n.t('home_default_device') : i18n.t('home_default_device'),
      camMeta: cur ? cur.deviceId : i18n.t('device_empty'),
      camState: cur ? '' : i18n.t('profile_camera_unbound'),
      // 名称语言显示「本地写法」（日本語 / العربية），界面语言显示各自语言的自称
      nameLangLabel: nameLang.native,
      uiLangLabel: prefs.uiDisplayName(prefs.getUiLang()),
    })
  },

  goLanguage() {
    wx.navigateTo({ url: '/pages/language/language' })
  },

  goDevices() {
    wx.navigateTo({ url: '/pages/devices/devices' })
  },

  goProvision() {
    wx.navigateTo({ url: '/pages/provision/provision' })
  },

  goDiagnose() {
    wx.navigateTo({ url: '/pages/diagnose/diagnose' })
  },

  goAi() {
    wx.navigateTo({ url: '/pages/ai/ai' })
  },

  /** 账号：进微信登录页。登录方式只有微信一条（小程序里没有支付宝登录这回事） */
  onAccount() {
    wx.navigateTo({ url: '/pages/login/login' })
  },

  /** 设备语言：本版设备没有语言 UI，硬下发只会增加一次失败 */
  onDeviceLang() {
    wx.showModal({
      title: i18n.t('profile_device_lang'),
      content: i18n.t('mp_device_lang_body'),
      showCancel: false,
      confirmText: i18n.t('common_ok'),
    })
  },

  /** 从相册找回：小程序只能由用户主动选图，不能静默扫相册 */
  onRestore() {
    wx.showModal({
      title: i18n.t('profile_restore_gallery'),
      content: i18n.t('mp_restore_body'),
      showCancel: false,
      confirmText: i18n.t('common_ok'),
    })
  },

  onCloudStorage() {
    wx.showModal({
      title: i18n.t('profile_cloud_storage'),
      content: i18n.t('cloud_storage_body') + '\n\n' + i18n.t('cloud_storage_soon'),
      showCancel: false,
      confirmText: i18n.t('common_ok'),
    })
  },

  onPrivacy() {
    wx.showModal({
      title: i18n.t('profile_privacy'),
      content: i18n.t('mp_privacy_body'),
      showCancel: false,
      confirmText: i18n.t('common_ok'),
    })
  },

  onAbout() {
    wx.showModal({
      title: i18n.t('profile_about'),
      content: i18n.fmt('profile_version_fmt', [this.data.version]) + '\n\n' + i18n.t('mp_about_slogan'),
      showCancel: false,
      confirmText: i18n.t('common_ok'),
    })
  },

  onClearCache() {
    wx.showModal({
      title: i18n.t('diag_clear'),
      content: i18n.t('mp_clear_body'),
      confirmText: i18n.t('common_ok'),
      success: (res) => {
        if (!res.confirm) return
        store.clearAll()
        this.refresh()
        wx.showToast({ title: i18n.t('diag_cleared'), icon: 'none' })
      },
    })
  },

  onAddDemo() {
    demo.install()
    this.refresh()
    wx.showToast({ title: i18n.t('device_connected_toast'), icon: 'none' })
  },
})
