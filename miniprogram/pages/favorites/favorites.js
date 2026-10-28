// 收藏 Tab —— 照搬 App fragment_cards.xml（V2.0 墨标）
//
// 数据来源：本地快照（store.getFavorites()）。老栈没有收藏接口，也不支持按 id 批量取，
// 所以收藏表存的是「行快照」，这一页离线也能渲染；点进详情再用 id 拉最新。
//
// 密度口径：88dp 行 + 64dp 图，页面可用 389dp → 4 整行 + 第 5 行露 42%。
// 这是全 App 的密度指标页，所以**不做 2 列网格**（网格放不下 19sp 衬线词头）。

const store = require('../../utils/store')
const fmt = require('../../utils/fmt')
const hant = require('../../utils/hant')
const prefs = require('../../utils/prefs')
const i18n = require('../../utils/i18n')
const icons = require('../../utils/icons')

Page({
  data: {
    statusBar: 20,
    all: [],
    list: [],
    keyword: '',
    loading: false,
    searchIcon: icons.searchT3,
    cameraIcon: icons.favT3,
  },

  onLoad() {
    this.setData(
      Object.assign(i18n.page(), { statusBar: (getApp().globalData || {}).statusBar || 20 })
    )
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 1 })
    }
    this.refresh()
  },

  refresh() {
    // 收藏存的是快照（老栈没有按 id 批量取），名称是收藏那一刻的。
    // 名称语言切到繁体时对本机转换一次；切到外语要重新请求，点进详情会拿到新译名。
    const trad = prefs.getNameLang().code === 'zh-Hant'
    const all = store.getFavorites()
      .slice()
      .sort((a, b) => (b.at || 0) - (a.at || 0))
      .map((f) => ({
        id: f.id,
        img: fmt.toHttps(f.img),
        headword: f.headword || '',
        cjk: trad ? hant.convert(f.cjk || '') : f.cjk || '',
        meta: f.meta || '',
        deviceId: f.deviceId,
      }))
    const g = getApp().globalData || (getApp().globalData = {})
    g.listIds = all.map((x) => x.id)
    this.setData({ all, countText: i18n.fmt('cards_count_fmt', [all.length]) }, () => this.filter())
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

  /** 在收藏页里点心 = 取消收藏，直接从列表移除 */
  onFavRow(e) {
    const id = e.detail.id
    const item = this.data.all.find((x) => x.id === id)
    if (!item) return
    store.toggleFavorite(item.deviceId, id)
    this.refresh()
    wx.showToast({ title: i18n.t('home_fav_removed'), icon: 'none' })
  },

  goToday() {
    wx.switchTab({ url: '/pages/today/today' })
  },
})
