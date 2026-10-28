// 大图预览 —— 照搬 App `ImagePreviewActivity`
//
// 深色全屏 + 双指缩放 + 左右切图 + 存相册。
// App 上还有「原图/高清/卡通」三档切换：小程序没有 AI 图（生成要 ARK 密钥，只能在服务端），
// 所以风格条只渲染「原图」一档，并明确标注 AI 未开通 —— 不摆一个点了没反应的按钮。
//
// 🔴 图片域名要在「服务器域名 → downloadFile 合法域名」里，否则真机存不了相册。

const api = require('../../utils/api')
const store = require('../../utils/store')
const fmt = require('../../utils/fmt')
const i18n = require('../../utils/i18n')
const names = require('../../utils/names')
const icons = require('../../utils/icons')

Page({
  data: {
    statusBar: 20,
    ids: [],
    index: 0,
    total: 0,
    img: '',
    title: '',
    pos: '',
    loading: true,
    err: '',
    icons: {
      back: icons.backWhite || icons.back,
      download: icons.download || icons.refresh,
      prev: icons.back,
      next: icons.chevron,
    },
  },

  onLoad(q) {
    const ids = String(q.ids || '')
      .split(',')
      .map((x) => x.trim())
      .filter(Boolean)
    const index = Math.max(0, Number(q.index) || 0)
    this._cache = {}
    this.setData(
      Object.assign(i18n.page(), {
        statusBar: (getApp().globalData || {}).statusBar || 20,
        ids,
        index,
        total: ids.length,
        title: q.title ? decodeURIComponent(q.title) : '',
      })
    )
    if (!ids.length) {
      this.setData({ loading: false, err: i18n.t('detail_missing_card_info') })
      return
    }
    this.load(index)
  },

  onUnload() {
    this._cache = null
  },

  load(index) {
    const id = this.data.ids[index]
    const device = store.getCurrent()
    if (!device) {
      this.setData({ loading: false, err: i18n.t('cards_empty_unbound') })
      return
    }
    const cached = this._cache && this._cache[id]
    if (cached) {
      this._show(index, cached)
      return
    }
    this.setData({ loading: true, err: '' })
    api
      .recordDetail({ apikey: device.apikey, id })
      .then((r) => {
        const vm = { img: fmt.toHttps(r.image_url), title: names.resolve(r).headword }
        if (this._cache) this._cache[id] = vm
        this._show(index, vm)
      })
      .catch((e) => this.setData({ loading: false, err: e.message || i18n.t('preview_load_failed') }))
  },

  _show(index, vm) {
    this.setData({
      index,
      loading: false,
      err: '',
      img: vm.img,
      title: vm.title || this.data.title,
      pos: index + 1 + ' / ' + this.data.ids.length,
    })
  },

  onPrev() {
    if (this.data.index <= 0) return
    this.load(this.data.index - 1)
  },

  onNext() {
    if (this.data.index >= this.data.ids.length - 1) return
    this.load(this.data.index + 1)
  },

  onSave() {
    const url = this.data.img
    if (!url) {
      wx.showToast({ title: i18n.t('preview_save_wait'), icon: 'none' })
      return
    }
    wx.showLoading({ title: '' })
    wx.downloadFile({
      url,
      success: (res) => {
        if (res.statusCode !== 200) {
          wx.hideLoading()
          wx.showToast({ title: i18n.t('preview_save_failed'), icon: 'none' })
          return
        }
        wx.saveImageToPhotosAlbum({
          filePath: res.tempFilePath,
          success: () => {
            wx.hideLoading()
            wx.showToast({
              title: i18n.fmt('preview_saved', [this.data.title || this.data.pos]),
              icon: 'none',
            })
          },
          fail: (e) => {
            wx.hideLoading()
            // 用户拒绝过相册权限：引导去设置
            if (e && String(e.errMsg || '').indexOf('auth') >= 0) {
              wx.showModal({
                title: i18n.t('preview_save_failed'),
                content: '',
                showCancel: false,
                success: () => wx.openSetting({}),
              })
            } else {
              wx.showToast({ title: i18n.t('preview_save_failed'), icon: 'none' })
            }
          },
        })
      },
      fail: () => {
        wx.hideLoading()
        wx.showToast({ title: i18n.t('preview_save_failed'), icon: 'none' })
      },
    })
  },

  onPreview() {
    if (!this.data.img) return
    wx.previewImage({ urls: [this.data.img], current: this.data.img })
  },

  onRetry() {
    this.load(this.data.index)
  },

  onBack() {
    wx.navigateBack()
  },
})
