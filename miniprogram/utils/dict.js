// 在线词典（图灵 TuringOS-Retrieval-V2.0）
//
// 与记录接口不同，词典是 **WebSocket**：`wss://ws-api.turingapi.com/api/v2`
// 协议是「一连接一次请求」，服务端依次回：
//   210 参数初始化成功 → 220 参数上传完成 → 200 success（带 nlpResponse）→ done=true
// 🔴 必须等 done=true 才关连接，收到第一帧就走会拿不到结果。
//
// 🔴 data 字段用**明文**（实测 2026-10-28：wss 握手 510ms，全链路 729ms 返回）：
//    官方文档写的 AES 密文服务端不接受（恒返 4008），所以这里**不需要 secret**。
//    这也是为什么小程序能直连 —— 若将来服务端强制加密，必须改走自家后端代理，
//    不能把 secret 打进小程序包。
//
// ⚠️ 三种返回结构完全不同，必须分别归一化：
//    en_word  → phoneticUs/phoneticEn + explanations[{pos,meaning}] + examples/phrases
//    cn_word  → polyphone[{pinyin, explanations[], examples[], audio}]
//    others   → 只有 info.translation（词没被词典收录，给的是机器翻译）

const WS_URL = 'wss://ws-api.turingapi.com/api/v2'
const TIMEOUT = 8000
// 🔴 key 不写死：用当前设备自己的 apikey（与记录接口同一个 key，计费与鉴权都挂在它名下）。
//    这里只是兜底默认值（联调样机），正常路径由调用方传入。
const KEY_FALLBACK = '<DEVICE_API_KEY>'

// 官方建议顺序：避免返回古诗词之类用不上的类型
const DICTS_CN = ['cn_char', 'cn_idiom', 'cn_word_person', 'cn_word']
const DICTS_EN = ['en_word', 'en_phrase']

function buildRequest(content, dicts, key) {
  const inner = {
    // 官方要求 16 位字母数字
    deviceId: 'thumbcard0000001',
    requestType: [1], // 1 = NLP
    nlpRequest: {
      content: [{ data: content }],
      clientInfo: {
        appState: { code: 1000633, operateState: 1101 },
        robotSkill: { 1000633: { dicts } },
      },
    },
  }
  return JSON.stringify({ key: key || KEY_FALLBACK, timestamp: String(Date.now()), data: JSON.stringify(inner) })
}

/**
 * 查一个词
 * @param {string} content 词，如 'Carton' / '杯子'
 * @param {string[]} dicts 词库顺序
 * @param {string} [key] 平台 apikey（设备自己的）
 * @returns {Promise<object|null>} 归一化词条；无结果返回 null；失败 reject
 */
function query(content, dicts, key) {
  const text = String(content || '').trim()
  if (!text) return Promise.resolve(null)

  return new Promise((resolve, reject) => {
    let settled = false
    let task = null
    const finish = (fn, arg) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      try {
        if (task) task.close({ code: 1000 })
      } catch (e) {}
      fn(arg)
    }

    const timer = setTimeout(() => finish(reject, new Error('timeout')), TIMEOUT)

    try {
      task = wx.connectSocket({ url: WS_URL })
    } catch (e) {
      finish(reject, new Error('connect failed'))
      return
    }

    task.onOpen(() => {
      try {
        task.send({ data: buildRequest(text, dicts || DICTS_EN, key) })
      } catch (e) {
        finish(reject, new Error('send failed'))
      }
    })

    task.onMessage((res) => {
      let o = null
      try {
        o = JSON.parse(res.data)
      } catch (e) {
        return
      }
      // 210 / 220 是过程帧，跳过；200 带结果
      if (o && o.code === 200) {
        const entry = normalize(o, text)
        finish(resolve, entry)
      } else if (o && o.done === true) {
        // 有 done 但没有 200：算没查到，不算失败
        finish(resolve, null)
      }
    })

    task.onError(() => finish(reject, new Error('socket error')))
    task.onClose(() => {
      // 已 settled 时是正常关闭
      if (!settled) finish(resolve, null)
    })
  })
}

/**
 * 把服务端原始响应归一化成 UI 唯一需要消费的结构
 * @param {object} resp 200 帧的 JSON
 * @param {string} [fallbackWord] 服务端没回 word 时用的词
 * @returns {object|null} null 表示没查到
 */
function normalize(resp, fallbackWord) {
  const result =
    resp &&
    resp.nlpResponse &&
    resp.nlpResponse.intent &&
    resp.nlpResponse.intent.parameters &&
    resp.nlpResponse.intent.parameters.result
  if (!result) {
    // 有些返回把内容放在 nlpResponse.results[].values.text（纯翻译）
    const t = pickText(resp)
    if (t) return { dict: 'others', word: fallbackWord || '', translation: t, isEmpty: false }
    return null
  }

  const info = result.info || {}
  const dict = result.dict || 'others'

  if (dict === 'en_word' || dict === 'en_phrase') {
    const meanings = (info.explanations || [])
      .map((e) => ({ pos: e.pos || '', meaning: e.meaning || '' }))
      .filter((e) => e.meaning)
    const examples = (info.examples || [])
      .map((e) => ({ en: e.en || '', cn: e.cn || '' }))
      .filter((e) => e.en || e.cn)
    const phrases = (info.phrases || [])
      .map((e) => ({ en: e.en || '', cn: e.cn || '' }))
      .filter((e) => e.en || e.cn)
    const forms = []
    const pushForm = (label, v) => {
      if (v) forms.push({ label, v: String(v) })
    }
    pushForm('复数', info.nounPlurals)
    pushForm('第三人称单数', info.thirdSingular)
    pushForm('过去式', info.preterit)
    pushForm('过去分词', info.pastParticiple)
    pushForm('现在分词', info.presentParticiple)
    pushForm('比较级', info.comparativeDegree)
    pushForm('最高级', info.superlativeDegree)

    return {
      dict,
      word: info.word || fallbackWord || '',
      phoneticUs: info.phoneticUs || '',
      phoneticEn: info.phoneticEn || '',
      pinyin: '',
      meanings,
      examples,
      phrases,
      synonyms: (info.synonyms || []).map((s) => s.en || s.cn || '').filter(Boolean),
      antonyms: (info.antonyms || []).map((s) => s.en || s.cn || '').filter(Boolean),
      forms,
      audio: info.wordAudio || '',
      audioUs: info.phoneticUsAudio || '',
      audioEn: info.phoneticEnAudio || '',
      translation: '',
      isEmpty: !meanings.length && !examples.length,
    }
  }

  if (dict === 'cn_word' || dict === 'cn_char' || dict === 'cn_idiom') {
    const poly = (info.polyphone || [])[0] || {}
    const meanings = (poly.explanations || []).map((x) => ({ pos: '', meaning: String(x) }))
    const examples = (poly.examples || [])
      .map((e) => ({ en: e.en || '', cn: e.cn || '' }))
      .filter((e) => e.en || e.cn)
    return {
      dict,
      word: info.word || fallbackWord || '',
      phoneticUs: '',
      phoneticEn: '',
      pinyin: poly.pinyin || '',
      meanings,
      examples,
      phrases: [],
      synonyms: (poly.synonyms || []).map((s) => s.cn || s.en || '').filter(Boolean),
      antonyms: (poly.antonyms || []).map((s) => s.cn || s.en || '').filter(Boolean),
      forms: [],
      audio: poly.audio || info.wordAudio || '',
      audioUs: '',
      audioEn: '',
      translation: '',
      isEmpty: !meanings.length && !examples.length,
    }
  }

  // others：词典没收录，只给了机器翻译
  const tr = info.translation || pickText(resp) || ''
  return {
    dict: 'others',
    word: info.word || fallbackWord || '',
    phoneticUs: '',
    phoneticEn: '',
    pinyin: '',
    meanings: tr ? [{ pos: '', meaning: tr }] : [],
    examples: [],
    phrases: [],
    synonyms: [],
    antonyms: [],
    forms: [],
    audio: '',
    audioUs: '',
    audioEn: '',
    translation: tr,
    isEmpty: !tr,
  }
}

function pickText(resp) {
  const rs = (resp && resp.nlpResponse && resp.nlpResponse.results) || []
  for (let i = 0; i < rs.length; i++) {
    const v = rs[i] && rs[i].values
    if (v && v.text) return String(v.text)
  }
  return ''
}

// ---------- 缓存 ----------
// 同一个词反复打开详情不必重复请求；只缓内存，切页就没了，不占存储
const cache = {}
const CACHE_MAX = 200

function cacheKey(word, kind) {
  return kind + ':' + String(word).toLowerCase()
}

/**
 * 查一组词（英文 + 中文），并发；任一失败不影响另一个
 * @param {{en?:string, cn?:string, key?:string}} p
 * @returns {Promise<{en:object|null, cn:object|null}>}
 */
function lookup(p) {
  const jobs = []
  const out = { en: null, cn: null }

  const one = (word, kind, dicts) => {
    if (!word) return Promise.resolve()
    const k = cacheKey(word, kind)
    if (cache[k] !== undefined) {
      out[kind] = cache[k]
      return Promise.resolve()
    }
    return query(word, dicts, p.key)
      .then((e) => {
        // 只缓存有内容的，否则一次网络抖动会把「空」记成永久结果
        if (e && !e.isEmpty) {
          if (Object.keys(cache).length < CACHE_MAX) cache[k] = e
        }
        out[kind] = e
      })
      .catch(() => {})
  }

  jobs.push(one(p.en, 'en', DICTS_EN))
  jobs.push(one(p.cn, 'cn', DICTS_CN))
  return Promise.all(jobs).then(() => out)
}

module.exports = { query, normalize, lookup, DICTS_EN, DICTS_CN, WS_URL }
