// 自定义底部导航 —— 照搬 App V2.0「墨标」6.4 节
//
// 三重编码表达「当前在哪」：
//   ① 荧光药丸 40×26（块）② text_on_accent 深色 tint（色）③ medium 字重（重）
// 其中两重不依赖颜色（药丸 + 字重），色弱用户与深色模式下仍能区分。
//
// ⚠️ 药丸只包图标，不包文字：包住文字会让 11sp 的 label 进到 26dp 高度里被裁。
// ⚠️ 文案走 i18n（nav_home / nav_cards / nav_profile），切界面语言后由 reLaunch 重建。

const icons = require('../utils/icons')
const i18n = require('../utils/i18n')

function buildList() {
  const t = i18n.pack()
  return [
    { pagePath: '/pages/today/today', text: t.nav_home, off: icons.todayT3, on: icons.today },
    { pagePath: '/pages/favorites/favorites', text: t.nav_cards, off: icons.favT3, on: icons.fav },
    { pagePath: '/pages/profile/profile', text: t.nav_profile, off: icons.meT3, on: icons.me },
  ]
}

Component({
  data: {
    selected: 0,
    list: buildList(),
  },

  attached() {
    // 组件可能比页面先于语言切换存活，挂载时重算一次
    this.setData({ list: buildList() })
  },

  methods: {
    onTap(e) {
      const i = Number(e.currentTarget.dataset.index)
      if (i === this.data.selected) return
      const item = this.data.list[i]
      if (!item) return
      // 先切本地选中态，避免等页面 onShow 才亮起来（有 1 帧延迟）
      this.setData({ selected: i })
      wx.switchTab({ url: item.pagePath })
    },
  },
})
