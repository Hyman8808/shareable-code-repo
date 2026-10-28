// 界面语言：文案查表
//
// 语言包来自 App 的 res/values*/strings.xml（438 条 × 4 语），
// 由脚本导出、与 App 一字不差 —— 不是另写一套翻译。
//
// 用法：
//   页面 onLoad / onShow 里  this.setData(i18n.page())
//   wxml 里                  {{t.nav_home}}
//
// 🔴 切换界面语言后要 `wx.reLaunch` 回首页（App 是重建 Activity）：
//    否则已经渲染过的页面还拿着旧文案，出现「半中半英」。

const prefs = require('./prefs')

const PACKS = {
  'zh-CN': require('./lang/zh-CN'),
  'zh-TW': require('./lang/zh-TW'),
  en: require('./lang/en'),
  ar: require('./lang/ar'),
}

/** 当前界面语言包（缺的 key 回退简体，避免出现空标题） */
function pack() {
  const tag = prefs.getUiLang()
  const p = PACKS[tag] || PACKS['zh-CN']
  return p
}

/** 取一条文案；查不到回退简体，再查不到返回 key 本身（便于开发期发现漏 key） */
function t(key) {
  const tag = prefs.getUiLang()
  const p = PACKS[tag] || PACKS['zh-CN']
  if (p && p[key] !== undefined) return p[key]
  if (PACKS['zh-CN'][key] !== undefined) return PACKS['zh-CN'][key]
  return key
}

/** 页面 data 注入：{ t, rtl, uiTag } */
function page() {
  const uiTag = prefs.getUiLang()
  return { t: PACKS[uiTag] || PACKS['zh-CN'], rtl: prefs.isRtl(uiTag), uiTag }
}

/** 带占位符的文案：fmt('cards_count_fmt', [3]) —— 兼容 %1$d / %1$s / %s */
function fmt(key, args) {
  let s = t(key)
  const a = args || []
  s = s.replace(/%(\d+)\$[sd]/g, (m, i) => {
    const v = a[Number(i) - 1]
    return v === undefined || v === null ? '' : String(v)
  })
  s = s.replace(/%[sd]/g, () => {
    const v = a.shift()
    return v === undefined || v === null ? '' : String(v)
  })
  return s
}

module.exports = { pack, t, page, fmt, PACKS }
