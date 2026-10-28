// 账号 —— 微信登录页
//
// 照搬 App `LoginActivity` 的位置，但**登录方式按小程序现实重做**：
//   App 那版是「微信 / 支付宝 / 手机号」三渠道，且三个都只弹「开发中」。
//   小程序里支付宝登录不存在（那是支付宝小程序 SDK），手机号验证码成本高、体验差，
//   所以这里只有一条路：微信一键登录。这不是少做，是选对。
//
// 🔴 三件事必须诚实：
//   1. `POST /v1/auth/wx` 目前**没有**（#2835 账号域 16 接口里没这一路）。点了会明确告诉用户
//      「云端接口还没上线」，不假装登录成功，也不做一个假的本地登录态。
//   2. 微信登录只给 openid，**不给昵称头像**（2022-10-25 起 wx.getUserProfile 已收回）。
//      昵称头像必须用「头像昵称填写能力」让用户自己填：`open-type="chooseAvatar"` + `input type="nickname"`。
//   3. 手机号绑定要「手机号快速验证」权限（企业主体，0.03 元/次）。没开通就点不通，
//      所以这里不摆一个看起来能用的按钮，而是先试、失败就说清为什么。

const auth = require('../../utils/auth')
const i18n = require('../../utils/i18n')
const icons = require('../../utils/icons')

const K_PROFILE = 'tc.acctProfile' // 昵称/头像只存本地：服务端还没有 profile 接口

Page({
  data: {
    statusBar: 20,
    logged: false,
    nick: '',
    avatar: '',
    phone: '',
    accountId: '',
    accountIdText: '',
    busy: false,
    phoneBusy: false,
    // 提示区：kind = '' | 'notReady' | 'fail' | 'ok'
    kind: '',
    tipTitle: '',
    tipBody: '',
    icons: {
      back: icons.back,
      wechatWhite: icons.wechatWhite,
      me: icons.meT3,
      phone: icons.phoneT3,
      logout: icons.logoutT3,
      shield: icons.shieldT3,
    },
  },

  onLoad() {
    this.setData(Object.assign(i18n.page(), { statusBar: (getApp().globalData || {}).statusBar || 20 }))
    this.refresh()
  },

  onShow() {
    this.refresh()
  },

  refresh() {
    const a = auth.account()
    let local = {}
    try {
      local = wx.getStorageSync(K_PROFILE) || {}
    } catch (e) {}
    const id = (a && a.id) || ''
    this.setData({
      logged: !!a,
      nick: (a && a.nickname) || local.nick || '',
      avatar: (a && a.avatar) || local.avatar || '',
      phone: (a && a.phone) || '',
      accountId: id,
      accountIdText: id ? i18n.fmt('mp_login_id_fmt', [id.slice(0, 12)]) : '',
    })
  },

  /* ---------------- 登录 ---------------- */

  onLogin() {
    if (this.data.busy) return
    this.setData({ busy: true, kind: '', tipTitle: '', tipBody: '' })
    auth
      .login()
      .then((r) => {
        this.refresh()
        this.setData({
          busy: false,
          kind: 'ok',
          tipTitle: r && r.isNew ? i18n.t('mp_login_new') : i18n.t('mp_login_success'),
          tipBody: '',
        })
        wx.showToast({ title: i18n.t('mp_login_success'), icon: 'success', duration: 1500 })
      })
      .catch((e) => {
        this.setData({
          busy: false,
          // 404 是「还没开发」，不是「你操作错了」，两者提示必须分开
          kind: e.code === 'E_NOT_READY' ? 'notReady' : 'fail',
          tipTitle: e.code === 'E_NOT_READY' ? i18n.t('mp_login_not_ready') : i18n.fmt('mp_login_fail_fmt', [e.message || e.code]),
          tipBody: e.code === 'E_NOT_READY' ? i18n.t('mp_login_not_ready_body') : '',
        })
      })
  },

  onSkip() {
    // 从「我的」进来的有上一页，直接退；直接进来的回记录页
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/today/today' })
  },

  onBack() {
    this.onSkip()
  },

  /* ---------------- 昵称 / 头像（用户自己填） ---------------- */

  onChooseAvatar(e) {
    const url = e.detail && e.detail.avatarUrl
    if (!url) return
    this._saveProfile({ avatar: url })
  },

  onNickInput(e) {
    this.setData({ nick: e.detail.value })
  },

  onNickBlur(e) {
    const v = (e.detail.value || '').trim()
    if (!v) return
    this._saveProfile({ nick: v })
    wx.showToast({ title: i18n.t('mp_login_saved'), icon: 'none' })
  },

  _saveProfile(patch) {
    let local = {}
    try {
      local = wx.getStorageSync(K_PROFILE) || {}
    } catch (e) {}
    const next = Object.assign({}, local, patch)
    try {
      wx.setStorageSync(K_PROFILE, next)
    } catch (e) {}
    this.refresh()
  },

  /* ---------------- 手机号 ---------------- */

  onBindPhone(e) {
    const code = e.detail && e.detail.code
    if (!code) {
      // 用户点了拒绝，或小程序没开通「手机号快速验证」——后者更常见，直接说清
      wx.showToast({ title: i18n.t('mp_login_phone_todo'), icon: 'none', duration: 2400 })
      return
    }
    if (this.data.phoneBusy) return
    this.setData({ phoneBusy: true })
    auth
      .bindPhone(code)
      .then((d) => {
        const phone = (d && (d.phone || d.phone_number)) || ''
        this.setData({ phoneBusy: false, phone })
        wx.showToast({ title: i18n.t('mp_login_saved'), icon: 'none' })
      })
      .catch((e) => {
        this.setData({
          phoneBusy: false,
          kind: e.code === 'E_NOT_READY' ? 'notReady' : 'fail',
          tipTitle: e.code === 'E_NOT_READY' ? i18n.t('mp_login_not_ready') : i18n.fmt('mp_login_fail_fmt', [e.message || e.code]),
          tipBody: e.code === 'E_NOT_READY' ? i18n.t('mp_login_not_ready_body') : '',
        })
      })
  },

  /* ---------------- 退出 ---------------- */

  onLogout() {
    wx.showModal({
      title: i18n.t('mp_login_logout'),
      content: i18n.t('mp_login_guest_body'),
      confirmText: i18n.t('mp_login_logout'),
      cancelText: i18n.t('mp_login_skip'),
      success: (r) => {
        if (!r.confirm) return
        auth.logout().then(() => {
          this.setData({ kind: '', tipTitle: '', tipBody: '', phone: '' })
          this.refresh()
          wx.showToast({ title: i18n.t('mp_login_logout_done'), icon: 'none' })
        })
      },
    })
  },
})
