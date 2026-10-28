// 单词卡详情 —— 照搬 App activity_card_detail.xml（V2.0 墨标 6.2）
//
// 首屏必须回答「这是什么词、怎么读」：
//   图片（相纸，出血到卡边）→ 英文衬线大字 → 音标 → 中文释义 → 播放
// 不滚动就能拿到。英文 34sp serif 排第一行，中文 17sp medium 降为释义 ——
// 旧版「中文 28sp 在上、英文 18sp 在下」读起来像「中文词的英文注释」，反了。
//
// ⚠️ 老栈（nglearn）没有音标字段，也没有 name_localized / explore / information / learning，
//    所以音标区整块隐藏、拓展区不渲染。代码按「有字段才画」写，云端改造上线后不用改。

const api = require('../../utils/api')
const store = require('../../utils/store')
const fmt = require('../../utils/fmt')
const i18n = require('../../utils/i18n')
const names = require('../../utils/names')
const dict = require('../../utils/dict')
const icons = require('../../utils/icons')

Page({
  data: {
    statusBar: 20,
    id: '',
    loading: true,
    err: '',
    vm: null,
    dv: null,
    hasPrev: false,
    hasNext: false,
    dictLoading: false,
    dictErr: '',
    fav: false,
    playingKey: '',
    icons: {
      back: icons.back,
      heartOff: icons.heartOff,
      heartOn: icons.heartOn,
      speakWhite: icons.speakWhite,
      speak: icons.speak,
      camera: icons.cameraT3,
    },
  },

  onLoad(query) {
    this.audio = null
    this.setData(
      Object.assign(i18n.page(), {
        statusBar: (getApp().globalData || {}).statusBar || 20,
        id: query.id || '',
      })
    )
    this._syncPos()
    this.load()
  },

  /** 当前这张在列表里的位置（列表页写进 globalData.listIds） */
  _syncPos() {
    const g = getApp().globalData || {}
    this._ids = Array.isArray(g.listIds) ? g.listIds : []
    const i = this._ids.findIndex((x) => String(x) === String(this.data.id))
    this.setData({
      hasPrev: i > 0,
      hasNext: i >= 0 && i < this._ids.length - 1,
    })
  },

  _switchTo(newId) {
    this._stopAudio()
    this.setData({ id: String(newId), vm: null, dv: null, loading: true, err: '' })
    this._syncPos()
    this.load()
  },

  onPrevCard() {
    const g = getApp().globalData || {}
    const ids = Array.isArray(g.listIds) ? g.listIds : []
    const i = ids.findIndex((x) => String(x) === String(this.data.id))
    if (i > 0) this._switchTo(ids[i - 1])
  },

  onNextCard() {
    const g = getApp().globalData || {}
    const ids = Array.isArray(g.listIds) ? g.listIds : []
    const i = ids.findIndex((x) => String(x) === String(this.data.id))
    if (i >= 0 && i < ids.length - 1) this._switchTo(ids[i + 1])
  },

  // 左右滑切卡：与 App 的左右滑一致；纵向滑动不拦（页面本身要能滚）
  onTouchStart(e) {
    const t = e.touches && e.touches[0]
    if (!t) return
    this._tx = t.clientX
    this._ty = t.clientY
  },

  onTouchEnd(e) {
    const t = e.changedTouches && e.changedTouches[0]
    if (!t || this._tx === undefined) return
    const dx = t.clientX - this._tx
    const dy = t.clientY - this._ty
    this._tx = undefined
    // 横向位移要明显大于纵向，否则会把「下滑看释义」误判成切卡
    if (Math.abs(dx) < 70 || Math.abs(dy) > Math.abs(dx) * 0.7) return
    if (dx < 0) this.onNextCard()
    else this.onPrevCard()
  },

  onUnload() {
    this._stopAudio()
  },

  load() {
    const device = store.getCurrent()
    if (!device) {
      this.setData({ loading: false, err: '还没有绑定设备' })
      return
    }
    if (!this.data.id) {
      this.setData({ loading: false, err: '缺少记录编号' })
      return
    }
    this.setData({ loading: true, err: '' })
    api
      .recordDetail({ apikey: device.apikey, id: this.data.id })
      .then((r) => {
        this._raw = r
        this.setData({
          loading: false,
          vm: this._toVm(r, device),
          fav: store.isFavorite(device.deviceId, r.id),
        })
        wx.setNavigationBarTitle && wx.setNavigationBarTitle({ title: i18n.t('card_detail_title') })
        this._loadDict(r)
      })
      .catch((e) => this.setData({ loading: false, err: e.message || '加载失败' }))
  },

  _toVm(r, device) {
    // 名称由 names 统一解析（名称语言决定中文栏显示什么），不要在页面里各拼一套
    const nv = names.resolve(r)
    const headword = nv.headword
    const cjk = nv.cjk

    const first = (arr) => fmt.toHttps((Array.isArray(arr) ? arr[0] : arr) || '')
    const audios = []
    // 三路音频的标签：设定语言用该语言的本地写法，其余用 App 的固定文案
    const langName = nv.lang === 'none' ? '' : names.langNative(nv.lang)
    if (first(r.tts_url_localized)) {
      audios.push({ key: 'localized', label: langName || i18n.t('card_play_english'), url: first(r.tts_url_localized) })
    }
    if (first(r.tts_url_english)) audios.push({ key: 'en', label: i18n.t('card_play_english'), url: first(r.tts_url_english) })
    if (!nv.isNone && first(r.tts_url)) audios.push({ key: 'zh', label: i18n.t('card_play_chinese'), url: first(r.tts_url) })

    const blocks = []
    const push = (key, label, v) => {
      if (!v) return
      const text = Array.isArray(v) ? v.filter(Boolean).join('\n') : String(v)
      if (text.trim()) blocks.push({ key, label, text })
    }
    push('explore', '拓展', r.explore)
    push('information', '小知识', r.information)
    push('learning', '学习', r.learning)

    return {
      id: r.id,
      img: fmt.toHttps(r.image_url),
      headword,
      cjk,
      en: nv.en,
      isNone: nv.isNone,
      // 选了外语却拿到中文：必须标出来，否则用户会判定「语言切换坏了」
      langFallback: nv.fallback ? i18n.fmt('detail_no_translation_fmt', [nv.lang]) : '',
      lang: r.name_localized_lang || '',
      time: r.create_time || '',
      timeText: i18n.fmt('detail_time_fmt', [fmt.shortTime(r.create_time) || r.create_time || '—']),
      deviceId: device.deviceId,
      audios,
      blocks,
    }
  },

  // ---------- 词典 ----------
  //
  // 英文查 en_word（内容最全：音标/释义/例句/词形），中文查 cn_word（拼音/汉语释义）。
  // 两个并发，任一失败不影响另一个 —— 词典挂了不该让整页报错。
  _loadDict(r) {
    const isNone = names.resolve(r).isNone
    const en = String(r.name_english || '').trim()
    const cn = isNone ? '' : String(r.name_chinese || '').trim()
    if (!en && !cn) {
      this.setData({ dictLoading: false, dv: null })
      return
    }
    this.setData({ dictLoading: true, dictErr: '' })
    const device = store.getCurrent()
    dict
      .lookup({ en, cn, key: device ? device.apikey : '' })
      .then((res) => {
        this.setData({ dictLoading: false, dv: this._buildDictView(res, isNone) })
      })
      .catch(() => {
        this.setData({
          dictLoading: false,
          dictErr: i18n.t('detail_dict_en_failed'),
        })
      })
  },

  /** 把中英两个词条合成一份展示结构（wxml 只渲染，不做判断） */
  _buildDictView(res, isNone) {
    const en = res && res.en
    const cn = res && res.cn
    const dv = {
      phonetics: [],
      meanings: [],
      examples: [],
      phrases: [],
      forms: [],
      synonyms: [],
      antonyms: [],
      pinyin: '',
      translation: '',
    }

    if (en && !en.isEmpty) {
      // 音标：美音/英音分开展示，词典只给一个时不拿另一个顶替
      if (en.phoneticUs) dv.phonetics.push({ key: 'us', label: i18n.t('detail_us'), v: en.phoneticUs, audio: en.audioUs })
      if (en.phoneticEn) dv.phonetics.push({ key: 'uk', label: i18n.t('detail_uk'), v: en.phoneticEn, audio: en.audioEn })
      en.meanings.forEach((m) => dv.meanings.push({ pos: m.pos, meaning: m.meaning, src: 'en' }))
      dv.examples = en.examples.slice(0, 6)
      dv.phrases = en.phrases.slice(0, 8)
      dv.forms = en.forms
      dv.synonyms = en.synonyms.slice(0, 6)
      dv.antonyms = en.antonyms.slice(0, 6)
    }

    if (cn && !cn.isEmpty && !isNone) {
      if (cn.pinyin) dv.pinyin = cn.pinyin
      // 中文词条只在英文没给出释义时补位，避免同一条意思出现两次
      if (!dv.meanings.length) {
        cn.meanings.forEach((m) => dv.meanings.push({ pos: '', meaning: m.meaning, src: 'cn' }))
      }
      if (!dv.examples.length && cn.examples.length) dv.examples = cn.examples.slice(0, 6)
      // others = 词典没收录，只给了机器翻译 → 单独标，不当释义
      if (cn.dict === 'others' && cn.translation) dv.translation = cn.translation
    }

    // wxml 里 wx:key 需要稳定字段；这几组都是纯展示数据，用下标即可
    const withIdx = (arr) => arr.map((x, i) => Object.assign({ idx: i }, x))
    dv.meanings = withIdx(dv.meanings)
    dv.examples = withIdx(dv.examples)
    dv.phrases = withIdx(dv.phrases)
    dv.forms = withIdx(dv.forms)

    const empty =
      !dv.phonetics.length &&
      !dv.meanings.length &&
      !dv.examples.length &&
      !dv.phrases.length &&
      !dv.forms.length &&
      !dv.synonyms.length &&
      !dv.translation
    return empty ? null : dv
  },

  onRetryDict() {
    if (this._raw) this._loadDict(this._raw)
  },

  onPreview() {
    const vm = this.data.vm
    if (!vm || !vm.img) return
    wx.navigateTo({
      url:
        '/pages/viewer/viewer?ids=' +
        vm.id +
        '&index=0&title=' +
        encodeURIComponent(vm.headword || ''),
    })
  },

  onPlay(e) {
    const { key, url } = e.currentTarget.dataset
    if (!url) return
    if (this.data.playingKey === key) {
      this._stopAudio()
      return
    }
    this._stopAudio()
    const ctx = wx.createInnerAudioContext()
    ctx.src = url
    ctx.onEnded(() => this.setData({ playingKey: '' }))
    ctx.onError(() => {
      this.setData({ playingKey: '' })
      wx.showToast({ title: '这条音频播放失败', icon: 'none' })
    })
    ctx.play()
    this.audio = ctx
    this.setData({ playingKey: key })
  },

  _stopAudio() {
    if (this.audio) {
      try {
        this.audio.stop()
        this.audio.destroy()
      } catch (e) {}
      this.audio = null
    }
    if (this.data.playingKey) this.setData({ playingKey: '' })
  },

  onToggleFav() {
    const device = store.getCurrent()
    const vm = this.data.vm
    if (!device || !vm) return
    const next = store.toggleFavorite(device.deviceId, vm.id, {
      img: vm.img,
      headword: vm.headword,
      cjk: vm.cjk,
      meta: vm.time ? fmt.shortTime(vm.time) : '',
    })
    this.setData({ fav: next })
    wx.showToast({ title: next ? '已收藏' : '已取消', icon: 'none' })
  },

  onBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/today/today' })
  },

  onRetry() {
    this.load()
  },
})
