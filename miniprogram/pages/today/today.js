// 首页「今天」—— 照搬 App fragment_home.xml（V2.0 墨标 6.3）
//
// 结构：顶栏（设备名 + deviceId·日期）→ 今日白卡（三指标）→ 最近记录（行列表）
// 关键判断：**首页首屏是「今天概览」，不按行数考核**；密度由「收藏」Tab 承担。
//
// 统计口径（老栈只有 /record/list，没有统计接口，所以本地算）：
//   · 一次拉 100 条（服务端上限 100），今天 / 本周 / 去重词数都在这一批里算
//   · 总量超过 100 时「今天」仍准（今天的一定在最新 100 条内），「本周」可能偏小 —— 已知限制

const api = require('../../utils/api')
const store = require('../../utils/store')
const fmt = require('../../utils/fmt')
const demo = require('../../utils/demo')
const prefs = require('../../utils/prefs')
const names = require('../../utils/names')
const i18n = require('../../utils/i18n')
const icons = require('../../utils/icons')

const FETCH = 100
const RECENT = 8

Page({
  data: {
    statusBar: 20,
    device: null,
    isDemo: false,
    name: '',
    subline: '',
    dateText: '',
    loading: true,
    err: '',
    todayCount: 0,
    weekCount: 0,
    todayWords: 0,
    weekWords: 0,
    favToday: 0,
    total: 0,
    metrics: [],
    weekText: '',
    allText: '',
    moreText: '',
    recent: [],
    more: 0,
    hasMultiple: false,
    btIcon: icons.bt,
    cameraIcon: icons.cameraT3,
  },

  onLoad() {
    this.setData(
      Object.assign(i18n.page(), { statusBar: (getApp().globalData || {}).statusBar || 20 })
    )
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 0 })
    }
    const device = store.getCurrent()
    const changed = !this.data.device || !device || this.data.device.deviceId !== device.deviceId
    if (!device) {
      this.setData({ device: null, loading: false, err: '', recent: [], total: 0 })
      return
    }
    this.setData({
      device,
      isDemo: demo.isDemo(device),
      name: device.name || '拇指相机',
      subline: device.deviceId,
      dateText: fmt.dateLine(),
      hasMultiple: store.getDevices().length > 1,
    })
    if (changed || !this.data.recent.length) this.load()
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh())
  },

  load() {
    const device = this.data.device
    if (!device || this.loading === 'busy') return Promise.resolve()
    this.loading = 'busy'
    this.setData({ loading: true, err: '' })

    return api
      .listRecords({
        apikey: device.apikey,
        deviceId: device.deviceId,
        page: 1,
        size: FETCH,
        // 不传 lang：语言由云端按 deviceId 的设定语言下发（lang 参数已失效，见 utils/api.js）
      })
      .then((data) => {
        const list = (data && data.list) || []
        const total = Number(data && data.total) || list.length
        this._apply(list, total, device)
        this.setData({ loading: false })
      })
      .catch((e) => {
        this.setData({ loading: false, err: e.message || '加载失败' })
      })
      .then(() => {
        this.loading = ''
      })
  },

  _apply(list, total, device) {
    const today = list.filter((r) => fmt.isToday(r.create_time))
    const week = list.filter((r) => fmt.isWithinDays(r.create_time, 7))

    const uniqWords = (arr) => {
      const s = new Set()
      arr.forEach((r) => {
        const k = r.name_english || r.name_chinese
        if (k) s.add(String(k).toLowerCase())
      })
      return s.size
    }

    const favSet = new Set(store.getFavorites().map((f) => f.id))
    const favToday = today.filter((r) => favSet.has(r.id)).length

    const recent = list.slice(0, RECENT).map((r) => this._toRow(r, device))
    // 详情页靠这份顺序做左右切卡；只在内存里，不落盘
    const g = getApp().globalData || (getApp().globalData = {})
    g.listIds = list.map((r) => r.id)

    const wCount = week.length
    const wWords = uniqWords(week)
    const t = i18n.t
    this.setData({
      todayCount: today.length,
      todayWords: uniqWords(today),
      weekCount: wCount,
      weekWords: wWords,
      favToday,
      total,
      recent,
      more: Math.max(0, total - recent.length),
      // 指标与文案在这里拼好：wxml 里只做渲染，不做字符串拼接
      metrics: [
        { n: today.length, u: t('home_unit_photos') },
        { n: uniqWords(today), u: t('home_unit_words') },
        { n: favToday, u: t('nav_cards') },
      ],
      weekText: t('home_week') + ' ' + wCount + ' · ' + wWords,
      allText: t('home_all'),
      moreText: i18n.fmt('home_today_more_fmt', [Math.max(0, total - recent.length)]),
    })
  },

  _toRow(r, device) {
    // 名称统一由 names 解析：英文当词头（主角），中文栏按名称语言取
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

  onTapRow(e) {
    wx.navigateTo({ url: '/pages/detail/detail?id=' + e.detail.id })
  },

  onFavRow(e) {
    const id = e.detail.id
    const device = this.data.device
    if (!device) return
    const row = this.data.recent.find((x) => x.id === id)
    const next = store.toggleFavorite(device.deviceId, id, row)
    const recent = this.data.recent.map((x) => (x.id === id ? Object.assign({}, x, { fav: next }) : x))
    this.setData({ recent, favToday: this.data.favToday + (next ? 1 : -1) })
  },

  goCards() {
    wx.navigateTo({ url: '/pages/cards/cards' })
  },

  goProvision() {
    wx.navigateTo({ url: '/pages/provision/provision' })
  },

  goDevices() {
    wx.navigateTo({ url: '/pages/devices/devices' })
  },

  onRetry() {
    this.load()
  },

  onUseDemo() {
    demo.install()
    this.setData({ device: null, recent: [] }, () => this.onShow())
  },

  onDropDemo() {
    wx.showModal({
      title: '不用演示数据',
      content: '会移除这台演示机，首页回到空态。之后重新打开也不会再自动添加。',
      confirmText: '移除',
      success: (res) => {
        if (!res.confirm) return
        demo.dismiss()
        store.removeDevice(demo.DEVICE.deviceId)
        this.setData({ device: null, recent: [], total: 0, todayCount: 0, weekCount: 0 })
      },
    })
  },
})
