// 物品名称解析：决定一张卡「主词头 / 中文栏」各显示什么
//
// 照搬 App `data/i18n/NameResolver.kt` 的判定口径（V2.0 墨标：英文是主角）：
//   · 主词头 = 英文名（name_english）；没有英文才退到中文/译名
//   · 中文栏 = 名称语言对应的那一列：
//       none    → 不显示（中文内容全部退场：中文名、释义）
//       zh      → name_chinese 原文
//       zh-Hant → name_chinese 本机转繁体（不走服务端，也不会出现「待同步」）
//       其他    → name_localized（云端按 lang 下发）；拿不到就**回退中文原文，绝不伪造**
//
// 🔴 老栈（nglearn）目前 `lang=ja` 无效，name_localized 恒空 →
//    选日语/阿语时中文栏显示简体原文，这是「云端改造未上线」的正常表现，不是 bug。
//    详情页会给出 `detail_no_translation_fmt` 的提示。

const hant = require('./hant')
const prefs = require('./prefs')

/**
 * @param {object} r 云端记录（name_chinese / name_english / name_localized / name_localized_lang）
 * @param {string} [code] 名称语言代码，不传取本地设置
 * @returns {{headword:string, cjk:string, en:string, isNone:boolean, isChinese:boolean, fallback:boolean, lang:string}}
 */
function resolve(r, code) {
  const lang = prefs.nameLang(code || prefs.getNameLang())
  const cn = (r && r.name_chinese) || ''
  const en = (r && r.name_english) || ''
  const loc = (r && r.name_localized) || ''

  let cjk = ''
  let fallback = false

  if (lang.code === 'none') {
    cjk = ''
  } else if (lang.code === 'zh') {
    cjk = cn
  } else if (lang.code === 'zh-Hant') {
    cjk = hant.convert(cn)
  } else {
    // 云端译名：只有语言码对得上才用，避免「选日语却显示上次的法语」
    const got = String(r && r.name_localized_lang ? r.name_localized_lang : '').toLowerCase()
    const want = lang.code.toLowerCase()
    if (loc && (got === '' || got === want || got.indexOf(want) === 0)) {
      cjk = loc
    } else {
      cjk = cn
      fallback = !!cn
    }
  }

  const headword = en || cjk || cn
  const isChinese = lang.code === 'zh' || lang.code === 'zh-Hant'

  return {
    headword,
    cjk: cjk && cjk !== headword ? cjk : '',
    en: en && en !== headword ? en : '',
    isNone: lang.code === 'none',
    isChinese,
    fallback,
    lang: lang.code,
  }
}

/** 列表行：{ headword, cjk, meta } —— meta 里放「副语言 + 时间」 */
function row(r, timeText) {
  const v = resolve(r)
  const meta = [v.en, timeText].filter(Boolean).join('  ·  ')
  return { headword: v.headword, cjk: v.cjk, meta, isNone: v.isNone, lang: v.lang }
}

/** 语言代码 → 该语言的本地写法（日本語 / العربية）；用于「设定语言」那个播放按钮的标签 */
function langNative(code) {
  return prefs.nameLang(code).native
}

module.exports = { resolve, row, langNative }
