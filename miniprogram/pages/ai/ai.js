// AI 美化说明页 —— App 的 AiBeautifyActivity / AiBatchActivity / AiPurchaseDialog 三合一
//
// 🔴 小程序端**不做生成**，原因只有一个：出图要调 ARK（火山方舟），密钥必须放在服务端。
//    打进小程序包 = 反编译即拿到 key，任何人都能白烧额度 —— 这比"没有这个功能"更糟。
//    所以这一页只讲清楚「是什么、长什么样、为什么这里用不了」，不摆一个点了没反应的按钮。
//
// 🔴 价格不写死：App 里 10/50/100/500 张的价格必须由云端 `/v1/ai/tiers` 下发
//    （写进包里的价格，一旦调价，旧版本就是错的）。所以这一页只列档位，不列价格。

const i18n = require('../../utils/i18n')
const icons = require('../../utils/icons')

Page({
  data: {
    statusBar: 20,
    styles: [],
    tiers: [],
    icons: { back: icons.back, chevron: icons.chevronT3 },
  },

  onLoad() {
    this.setData(Object.assign(i18n.page(), { statusBar: (getApp().globalData || {}).statusBar || 20 }))
    const t = i18n.t
    this.setData({
      styles: [
        { key: 'hd', title: t('ai_style_hd'), desc: t('ai_style_hd_desc'), badge: t('ai_badge_hd') },
        { key: 'cartoon', title: t('ai_style_cartoon'), desc: t('ai_style_cartoon_desc'), badge: t('ai_badge_cartoon') },
      ],
      tiers: [
        { n: i18n.fmt('ai_tier_count_fmt', [10]), hint: t('ai_tier_hint_10') },
        { n: i18n.fmt('ai_tier_count_fmt', [50]), hint: t('ai_tier_hint_50') },
        { n: i18n.fmt('ai_tier_count_fmt', [100]), hint: t('ai_tier_hint_100') },
        { n: i18n.fmt('ai_tier_count_fmt', [500]), hint: t('ai_tier_hint_500') },
      ],
    })
  },

  onWhy() {
    wx.showModal({
      title: i18n.t('ai_title'),
      content:
        '出图要调用云端大模型，密钥只能放在服务端。小程序包是公开可下载的，密钥进去等于公开，所以这一版不在小程序里做。\n\nApp 版已经能正常生成，同一台相机、同一批额度，切到 App 就能用。',
      showCancel: false,
      confirmText: '知道了',
    })
  },

  onBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/profile/profile' })
  },
})
