// 语言偏好：界面语言（4 档）+ 物品名称语言（10 项）
//
// 照搬 App `UiLanguage.kt` / `NameLanguage.kt`。两者**互不相干**：
//   · 界面语言 = 小程序自己的文案用哪种语言
//   · 名称语言 = 相机拍到的物品，中文那一栏显示成哪种语言
// 一个用户完全可以「界面用阿语 + 物品名用英语」。
//
// 🔴 与 App 的差异：小程序**不用** zh-TW / zh-Hant 混写。
//    界面语言沿用 App 的 4 档 tag（zh-CN / zh-TW / en / ar）；
//    名称语言沿用协议口径（zh / zh-Hant / ja …），繁体统一写 zh-Hant。

const K_UI = 'tc.uiLang'
const K_NAME = 'tc.nameLang'

/**
 * 界面语言 10 档（本版支持的**全部**界面语言）。
 *
 * `name` 用各自语言写自己，**不翻译**：阿语用户要能认出「العربية」，
 * 写「阿拉伯语」等于没写；同理德语用户只认「Deutsch」。
 * 副标题走 `subKey` → i18n 查表，跟随当前界面语言。
 *
 * 顺序：中文系 → 英文 → 东亚 → 欧洲 → 阿拉伯（RTL 置尾）。
 */
const UI_LANGS = [
  { tag: 'zh-CN', name: '中文（简体）', subKey: 'ui_lang_row_zh_sub' },
  { tag: 'zh-TW', name: '中文（繁體）', subKey: 'ui_lang_row_tw_sub' },
  { tag: 'en', name: 'English', subKey: 'ui_lang_row_en_sub' },
  { tag: 'ja', name: '日本語', subKey: 'ui_lang_row_ja_sub' },
  { tag: 'ko', name: '한국어', subKey: 'ui_lang_row_ko_sub' },
  { tag: 'es', name: 'Español', subKey: 'ui_lang_row_es_sub' },
  { tag: 'fr', name: 'Français', subKey: 'ui_lang_row_fr_sub' },
  { tag: 'de', name: 'Deutsch', subKey: 'ui_lang_row_de_sub' },
  { tag: 'ru', name: 'Русский', subKey: 'ui_lang_row_ru_sub' },
  { tag: 'ar', name: 'العربية', subKey: 'ui_lang_row_ar_sub', rtl: true },
]

/**
 * 物品名称语言 10 项
 *
 * NONE 置首（它不是「第 8 种语言」，而是一个模式开关：只留英文，中文内容全部退场）；
 * AR 置尾（语言列表按 中文系 → 东亚 → 欧洲 → 其他 排）。
 *
 * ⚠️ `label` 是硬编码中文，**禁直读**：非中文界面一律走 `labelFor(uiTag)`，
 *    否则英文/阿语界面里会冒出「日本語   日语」这种半中半外的标题。
 */
const NAME_LANGS = [
  { code: 'none', label: '无（仅英文）', labelEn: 'None (English only)', native: 'English only' },
  { code: 'zh', label: '中文（简体）', labelEn: 'Chinese (Simplified)', native: '简体中文' },
  { code: 'zh-Hant', label: '中文（繁体）', labelEn: 'Chinese (Traditional)', native: '繁體中文' },
  { code: 'ja', label: '日语', labelEn: 'Japanese', native: '日本語' },
  { code: 'ko', label: '韩语', labelEn: 'Korean', native: '한국어' },
  { code: 'es', label: '西班牙语', labelEn: 'Spanish', native: 'Español' },
  { code: 'fr', label: '法语', labelEn: 'French', native: 'Français' },
  { code: 'de', label: '德语', labelEn: 'German', native: 'Deutsch' },
  { code: 'ru', label: '俄语', labelEn: 'Russian', native: 'Русский' },
  { code: 'ar', label: '阿拉伯语', labelEn: 'Arabic', native: 'العربية' },
]

const DEFAULT_UI = 'zh-CN'
const DEFAULT_NAME = 'zh'

/**
 * 任意 tag 收敛到 10 档之一
 * 🔴 新增语言必须在这里加分支：漏了会被 `else` 静默吞成中文，
 *    表现为「设置里显示阿语、界面还是中文」，而且不报任何错。
 *
 * 手机系统语言常常带地区后缀（ja-JP / ko-KR / es-419 / fr-CA / de-AT / ru-RU），
 * 一律按**前缀**归并；繁体三种写法（zh-TW / zh-HK / zh-Hant）都落到同一份资源。
 */
function normalizeUi(tag) {
  if (!tag) return DEFAULT_UI
  const t = String(tag)
  if (t.indexOf('en') === 0) return 'en'
  if (t.indexOf('zh-TW') === 0 || t.indexOf('zh-HK') === 0 || t.indexOf('zh-Hant') === 0) return 'zh-TW'
  if (t.indexOf('ja') === 0) return 'ja'
  if (t.indexOf('ko') === 0) return 'ko'
  if (t.indexOf('es') === 0) return 'es'
  if (t.indexOf('fr') === 0) return 'fr'
  if (t.indexOf('de') === 0) return 'de'
  if (t.indexOf('ru') === 0) return 'ru'
  if (t.indexOf('ar') === 0) return 'ar'
  return DEFAULT_UI
}

function getUiLang() {
  return normalizeUi(wx.getStorageSync(K_UI) || DEFAULT_UI)
}

function setUiLang(tag) {
  wx.setStorageSync(K_UI, normalizeUi(tag))
}

/** 界面语言显示名（语言名不翻译，各自写自己） */
function uiDisplayName(tag) {
  const t = normalizeUi(tag)
  const f = UI_LANGS.find((x) => x.tag === t)
  return f ? f.name : UI_LANGS[0].name
}

function isRtl(tag) {
  return normalizeUi(tag || getUiLang()) === 'ar'
}

/** 代码 → 名称语言对象；兼容旧值 zh-TW / zh-HK */
function nameLang(code) {
  const c = String(code || '').trim()
  if (!c) return NAME_LANGS[1]
  const hit = NAME_LANGS.find((x) => x.code.toLowerCase() === c.toLowerCase())
  if (hit) return hit
  if (c === 'zh-TW' || c === 'zh-HK') return NAME_LANGS[2]
  return NAME_LANGS[1]
}

function getNameLang() {
  return nameLang(wx.getStorageSync(K_NAME) || DEFAULT_NAME)
}

function setNameLang(code) {
  wx.setStorageSync(K_NAME, nameLang(code).code)
}

/** 非中文界面给英文名；中文界面给中文名 */
function labelFor(lang, uiTag) {
  const t = normalizeUi(uiTag || getUiLang())
  return t.indexOf('zh') === 0 ? lang.label : lang.labelEn
}

/** 名称语言是否需要请求云端译名（繁体本机转、简体是原文、none 不要中文） */
function needsCloudName(code) {
  const c = nameLang(code).code
  return c !== 'zh' && c !== 'zh-Hant' && c !== 'none'
}

/**
 * 已废弃：列表/详情请求**不再需要** lang 参数（2026-10-28 实测失效）。
 *
 * 语言在拍摄时由云端按 deviceId 设定固化进 `name_localized` / `tts_url_localized`，
 * 换语言不会回溯旧记录。保留本函数只为兼容老调用点，不要在新代码里用。
 */
function langParam(code) {
  const c = nameLang(code).code
  return needsCloudName(c) ? c : ''
}

module.exports = {
  UI_LANGS,
  NAME_LANGS,
  DEFAULT_UI,
  DEFAULT_NAME,
  normalizeUi,
  getUiLang,
  setUiLang,
  uiDisplayName,
  isRtl,
  nameLang,
  getNameLang,
  setNameLang,
  labelFor,
  needsCloudName,
  langParam,
}
