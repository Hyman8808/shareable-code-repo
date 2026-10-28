// 语言设置 —— 一页装两个互不相干的设置
//
// 照搬 App `LanguageActivity`（物品名称语言 10 项）+ `dialog_ui_language`（界面语言 4 档）。
// App 上界面语言是「我的」里的弹窗，这里合成一页：两个都是语言，分开摆反而要多点一次。
//
// 🔴 两件事不能混：
//   · 物品名称语言 → 相机拍的物品「中文那一栏」显示成哪国语言（走云端 name_localized）
//   · 界面语言     → 小程序自己的文案用哪国语言（走 utils/lang/*.js）

const prefs = require('../../utils/prefs')
const i18n = require('../../utils/i18n')
const icons = require('../../utils/icons')
const langSync = require('../../utils/langSync')

Page({
  data: {
    statusBar: 20,
    nameList: [],
    uiList: [],
    curName: '',
    curUi: '',
    saving: false,
    icons: { back: icons.back, check: icons.checkWhite },
  },

  onLoad() {
    this.setData({ statusBar: (getApp().globalData || {}).statusBar || 20 })
    this.render()
    // 上次没报成功（没配网 / 断网 / 还没 apikey）就补一次；没有 pending 时直接返回，不发请求
    langSync.flush()
  },

  render() {
    const p = i18n.page()
    const uiTag = p.uiTag
    const cur = prefs.getNameLang()
    const curUi = prefs.getUiLang()

    this.setData(
      Object.assign({}, p, {
        curName: cur.native,
        curUi: prefs.uiDisplayName(curUi),
        nameList: prefs.NAME_LANGS.map((l) => ({
          code: l.code,
          // 主标题用该语言的本地写法：阿语用户要能认出 العربية，写中文等于没写
          title: l.native,
          // 副标题按界面语言给中文名或英文名，禁直读 label
          sub: prefs.labelFor(l, uiTag),
          sel: l.code === cur.code,
        })),
        uiList: prefs.UI_LANGS.map((l) => ({
          tag: l.tag,
          title: l.name,
          // 副标题跟随当前界面语言；语言名本身不翻译
          sub: i18n.t(l.subKey),
          sel: l.tag === curUi,
          rtl: !!l.rtl,
        })),
      })
    )
  },

  onPickName(e) {
    const code = e.currentTarget.dataset.code
    if (!code || code === prefs.getNameLang().code) return
    prefs.setNameLang(code)
    const lang = prefs.nameLang(code)
    this.render()

    // 🔴 必须报给云端，否则「选了日语、拍出来还是中文」。
    //    云端在识别时按 deviceId 查语言并固化进记录，识别请求不收 lang 参数，
    //    只写本地 storage 等于没设。失败会自动留 pending，下次进本页补报。
    //
    // 不弹「同步失败」：本地已切好，云端这条会自己补；多弹一句只会让用户以为设置没成。
    langSync.push(code)

    // 提示口径照搬 App：中文＝识别原文；繁体＝本机转换；其他＝已切换
    // 🔴 不再用 lang_switched_pending_fmt（「译名服务接入中」）——
    //    云端 2026-10-28 已上线按 deviceId 出名称，这句会误导用户以为功能没做。
    let msg = ''
    if (code === 'zh') msg = i18n.t('lang_switched_src')
    else if (code === 'zh-Hant') msg = i18n.t('lang_switched_hant')
    else msg = i18n.t('lang_switched_fmt').replace('%1$s', lang.native)
    wx.showToast({ title: msg, icon: 'none', duration: 2200 })
  },

  onPickUi(e) {
    const tag = e.currentTarget.dataset.tag
    if (!tag || tag === prefs.getUiLang()) return
    prefs.setUiLang(tag)
    // App 是重建 Activity；小程序用 reLaunch 重载本页，切完立刻能看到新文案
    wx.reLaunch({
      url: '/pages/language/language',
      success: () => {
        setTimeout(() => {
          wx.showToast({
            title: i18n.t('ui_lang_changed_fmt').replace('%1$s', prefs.uiDisplayName(tag)),
            icon: 'none',
          })
        }, 200)
      },
    })
  },

  onBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/profile/profile' })
  },
})
