// 设备管理（二级页，从「我的」进入）—— App 里是 DeviceListActivity
//
// 设计上设备不再占 Tab：V2.0 的底部导航只有 今天/收藏/我的 三项，
// 「设备管理」降为「我的 › 我的相机」二级入口。

const store = require('../../utils/store')
const fmt = require('../../utils/fmt')
const demo = require('../../utils/demo')
const i18n = require('../../utils/i18n')
const icons = require('../../utils/icons')

Page({
  data: {
    statusBar: 20,
    list: [],
    currentId: '',
    icons: {
      back: icons.back,
      chevron: icons.chevronT3,
      bt: icons.bt,
      plus: icons.plus,
    },
  },

  onLoad() {
    this.setData(
      Object.assign(i18n.page(), { statusBar: (getApp().globalData || {}).statusBar || 20 })
    )
  },

  onShow() {
    this.refresh()
  },

  refresh() {
    const currentId = store.getCurrentId()
    const list = store.getDevices().map((d) => ({
      deviceId: d.deviceId,
      name: d.name || i18n.t('home_default_device'),
      fw: d.fw || '',
      isDemo: !!d.demo,
      apikeyMasked: fmt.maskKey(d.apikey || ''),
      isCurrent: d.deviceId === currentId,
    }))
    if (!currentId && list.length) list[0].isCurrent = true
    this.setData({ list, currentId, countText: i18n.fmt('device_count_fmt', [list.length]) })
  },

  onTapItem(e) {
    const id = e.currentTarget.dataset.id
    if (id === this.data.currentId) return
    store.setCurrentId(id)
    this.refresh()
    const dev = store.getDevices().find((d) => d.deviceId === id)
    const name = dev ? dev.name || i18n.t('home_default_device') : id
    wx.showToast({ title: i18n.fmt('device_switched_fmt', [name]), icon: 'none' })
  },

  onMore(e) {
    const id = e.currentTarget.dataset.id
    const dev = this.data.list.find((d) => d.deviceId === id)
    if (!dev) return
    wx.showActionSheet({
      itemList: [i18n.t('device_menu_rename'), i18n.t('device_menu_unbind')],
      success: (res) => {
        if (res.tapIndex === 0) this._rename(id, dev.name)
        if (res.tapIndex === 1) this._remove(id, dev.name)
      },
    })
  },

  _rename(id, oldName) {
    wx.showModal({
      title: i18n.t('device_rename_title'),
      editable: true,
      placeholderText: i18n.t('device_rename_hint'),
      content: oldName,
      success: (res) => {
        if (!res.confirm) return
        const name = (res.content || '').trim()
        if (!name) return
        const dev = store.getDevices().find((d) => d.deviceId === id)
        if (!dev) return
        store.upsertDevice(Object.assign({}, dev, { name }))
        this.refresh()
      },
    })
  },

  _remove(id, name) {
    wx.showModal({
      title: i18n.t('device_unbind_title'),
      content: i18n.t('device_unbind_msg_alt'),
      confirmText: i18n.t('device_unbind_ok'),
      confirmColor: '#b4281c',
      success: (res) => {
        if (!res.confirm) return
        store.removeDevice(id)
        this.refresh()
        wx.showToast({ title: i18n.t('device_unbound'), icon: 'none' })
      },
    })
  },

  onAdd() {
    wx.navigateTo({ url: '/pages/provision/provision' })
  },

  onUseDemo() {
    demo.install()
    this.refresh()
    wx.showToast({ title: i18n.t('device_connected_toast'), icon: 'none' })
  },

  onBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/profile/profile' })
  },
})
