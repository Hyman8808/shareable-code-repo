// 全部记录 —— 照搬 App activity_card_list.xml
//
// 与「今天」的区别：这里是纯列表，没有今日卡，头更矮，一屏能看 4 行多。
// 搜索是**本地过滤已加载的页**（老栈没有搜索接口），所以输入时注明「已加载 N 张内搜」。

const api = require('../../utils/api')
const store = require('../../utils/store')
const fmt = require('../../utils/fmt')
const prefs = require('../../utils/prefs')
const names = require('../../utils/names')
const i18n = require('../../utils/i18n')
const icons = require('../../utils/icons')

const PAGE = 20

Page({
  data: {
    statusBar: 20,
    all: [],
    list: [],
    total: 0,
    page: 1,
    loading: false,
    hasMore: false,
    err: '',
    keyword: '',
    back: icons.back,
    searchIcon: icons.searchT3,
    cameraIcon: icons.cameraT3,
  },

  onLoad() {
    this.setData(
      Object.assign(i18n.page(), { statusBar: (getApp().globalData || {}).statusBar || 20 })
    )
    this.load(true)
  },

  onReachBottom() {
    if (this.data.loading || !this.data.hasMore) return
    this.load(false)
  },

  load(reset) {
    const device = store.getCurrent()
    if (!device) {
      this.setData({ loading: false, err: '还没有绑定设备' })
      return Promise.resolve()
    }
    const page = reset ? 1 : this.data.page + 1
    this.setData({ loading: true, err: reset ? '' : this.data.err })

    return api
      .listRecords({
        apikey: device.apikey,
        deviceId: device.deviceId,
        page,
        size: PAGE,
      })
      .then((data) => {
        const raw = (data && data.list) || []
        const items = raw.map((r) => this._toRow(r, device))
        const all = reset ? items : this.data.all.concat(items)
        const g = getApp().globalData || (getApp().globalData = {})
        g.listIds = all.map((x) => x.id)
        const total = Number(data && data.total) || all.length
        this.setData(
          {
            all,
            total,
            page,
            countText: i18n.fmt('cards_count_fmt', [total]),
            loading: false,
            hasMore: all.length < total && items.length > 0,
          },
          () => this.filter()
        )
      })
      .catch((e) => this.setData({ loading: false, err: e.message || '加载失败' }))
  },

  _toRow(r, device) {
    const nv = names.row(r, fmt.shortTime(r.create_time))
    return {
      id: r.id,
      img: fmt.toHttps(r.image_url),
      headword: nv.headword,
      cjk: nv.cjk,
      meta: nv.meta,
      fav: store.isFavorite(device.deviceId, r.id),
    }
  },

  onSearch(e) {
    this.setData({ keyword: (e.detail.value || '').trim() }, () => this.filter())
  },

  filter() {
    const kw = this.data.keyword.toLowerCase()
    const list = kw
      ? this.data.all.filter(
          (x) =>
            String(x.headword).toLowerCase().indexOf(kw) >= 0 ||
            String(x.cjk).toLowerCase().indexOf(kw) >= 0 ||
            String(x.meta).toLowerCase().indexOf(kw) >= 0
        )
      : this.data.all
    this.setData({
      list,
      noResultText: kw ? i18n.fmt('cards_no_result_fmt', [this.data.keyword]) : '',
    })
  },

  onTapRow(e) {
    wx.navigateTo({ url: '/pages/detail/detail?id=' + e.detail.id })
  },

  onFavRow(e) {
    const id = e.detail.id
    const device = store.getCurrent()
    if (!device) return
    const row = this.data.all.find((x) => x.id === id)
    const next = store.toggleFavorite(device.deviceId, id, row)
    const patch = (arr) => arr.map((x) => (x.id === id ? Object.assign({}, x, { fav: next }) : x))
    this.setData({ all: patch(this.data.all) }, () => {
      this.setData({ list: patch(this.data.list) })
    })
  },

  onRetry() {
    this.load(true)
  },

  onBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/today/today' })
  },
})
