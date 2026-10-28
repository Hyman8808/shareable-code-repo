// 账号会话 —— 微信登录（小程序唯一登录方式）
//
// 🔴 为什么小程序只做微信登录：
//   · 小程序只能跑在微信里，`wx.login` 拿 code 是零输入、零成本的，比短信验证码（0.05 元/条 + 手输 6 位）好一个量级
//   · 支付宝登录在小程序里**根本不存在**（那是支付宝小程序的 SDK），这里是微信小程序，没有这个选项可做
//   · iOS App 单独一套登录方式（手机号验证码），不接微信 SDK → 按 App Store 4.8 可豁免 Sign in with Apple
//     一旦 iOS 接了微信登录，就必须同时补 SIWA。这个耦合写进 #2826，别只在一端加
//
// 🔴 安全边界（不能商量）：
//   · appsecret **只在服务端**。前端只交 `code`，服务端去调 jscode2session。前端拿 secret 等于公开泄露
//   · access 短效（10 分钟量级）+ refresh 轮换；refresh 二次使用视为泄露，服务端撤销该账号全部 refresh
//   · 设备凭证（tcd_ / wxt_）与账号凭证是**两套**：设备凭证证明「这台相机是你的」，账号凭证证明「你是谁」
//
// 🔴 当前状态（2026-10-28）：
//   `POST /v1/auth/wx` 尚未实现（#2835 账号域 16 接口里没有微信登录这一路）。
//   所以这里不做「假装登录成功」—— 接口不存在时明确抛 E_NOT_READY，页面照实说「云端待上线」，
//   王秀文实现后本文件**零改动**即可用。

const AUTH_BASE = 'https://thumbcamera-api.turingapi.com'
const K_SESSION = 'tc.session'
const TIMEOUT = 10000

// 与 #2835 §2.5 错误码对齐（沿用 4xxxx 风格）
const ERR_TEXT = {
  40101: '验证码错误或已过期',
  40102: '请求过于频繁',
  40103: '登录态已过期',
  40104: '登录已失效，请重新登录',
  40105: '微信凭证校验失败',
  40106: '设备与授权码不匹配',
  40301: '无权访问该设备',
}

function _err(code, message, extra) {
  return Object.assign({ code, message }, extra || {})
}

/** 统一 POST：超时、错误码翻译、404 识别成「接口未上线」 */
function post(path, body, token) {
  return new Promise((resolve, reject) => {
    wx.request({
      url: AUTH_BASE + path,
      method: 'POST',
      timeout: TIMEOUT,
      header: Object.assign(
        { 'content-type': 'application/json' },
        token ? { Authorization: 'Bearer ' + token } : {}
      ),
      data: body || {},
      success(res) {
        const b = res.data || {}
        if (res.statusCode === 404) {
          // 路由不存在 ≠ 业务失败。必须分开，否则会把「还没开发」显示成「你操作错了」
          reject(_err('E_NOT_READY', path + ' 尚未上线', { status: 404 }))
          return
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && (b.code === 0 || b.code === undefined)) {
          resolve(b.data !== undefined ? b.data : b)
          return
        }
        const c = b.code || res.statusCode
        reject(_err(c, ERR_TEXT[c] || b.message || b.msg || '请求失败', { status: res.statusCode }))
      },
      fail(e) {
        reject(_err('E_NET', (e && e.errMsg) || '网络不通', { raw: e }))
      },
    })
  })
}

/* ---------------- 会话存取 ---------------- */

function getSession() {
  try {
    const s = wx.getStorageSync(K_SESSION)
    return s && s.access ? s : null
  } catch (e) {
    return null
  }
}

function setSession(s) {
  try {
    wx.setStorageSync(K_SESSION, s)
  } catch (e) {}
}

function clearSession() {
  try {
    wx.removeStorageSync(K_SESSION)
  } catch (e) {}
}

function isLoggedIn() {
  return !!getSession()
}

/** 当前账号（未登录返回 null） */
function account() {
  const s = getSession()
  return s ? s.account || null : null
}

/* ---------------- 登录 / 登出 ---------------- */

/**
 * 微信一键登录
 * 前端只做两件事：拿 code、把 code 交出去。openid / unionid / 建号全在服务端。
 * @returns {Promise<{account, isNew}>}
 */
function login() {
  return new Promise((resolve, reject) => {
    wx.login({
      timeout: 8000,
      success(r) {
        if (!r || !r.code) {
          reject(_err('E_WXLOGIN', '微信没有返回 code'))
          return
        }
        post('/v1/auth/wx', { code: r.code })
          .then((d) => {
            // 后端可能把令牌包在 data 里也可能平铺，两种都接
            const access = d.access || d.access_token
            const refresh = d.refresh || d.refresh_token
            if (!access) {
              reject(_err('E_SHAPE', '登录响应里没有 access'))
              return
            }
            setSession({
              access,
              refresh: refresh || '',
              exp: Date.now() + (d.expires_in || 600) * 1000,
              account: d.account || { id: '', nickname: '微信用户' },
            })
            resolve({ account: d.account || null, isNew: !!d.is_new })
          })
          .catch(reject)
      },
      fail(e) {
        reject(_err('E_WXLOGIN', (e && e.errMsg) || '微信登录失败'))
      },
    })
  })
}

/** 需要 access 前先看一眼：剩余不足 60 秒就换新的 */
function ensureFresh() {
  const s = getSession()
  if (!s) return Promise.reject(_err('E_NOLOGIN', '未登录'))
  if (s.exp - Date.now() > 60000 || !s.refresh) return Promise.resolve(s.access)
  return post('/v1/auth/refresh', { refresh: s.refresh })
    .then((d) => {
      const next = Object.assign({}, s, {
        access: d.access || d.access_token,
        refresh: d.refresh || s.refresh,
        exp: Date.now() + (d.expires_in || 600) * 1000,
      })
      setSession(next)
      return next.access
    })
    .catch((e) => {
      // refresh 失效只能重新登录，不能静默吞掉（否则后面每个请求都 401）
      if (e.code === 40104 || e.code === 40103) clearSession()
      throw e
    })
}

function logout() {
  const s = getSession()
  clearSession()
  if (!s || !s.refresh) return Promise.resolve()
  // 本地已经清了，服务端撤销失败也不该拦着用户登出
  return post('/v1/auth/logout', { refresh: s.refresh }).catch(() => {})
}

/* ---------------- 手机号（可选，用于跨端找回） ---------------- */

/**
 * 微信手机号快速验证。拿到的是服务端可解的一次性 code，不是明文号码。
 * 需要小程序「手机号快速验证」权限（企业主体，0.03 元/次），未开通会直接 fail。
 * @param {string} phoneCode button open-type="getPhoneNumber" 回调里的 code
 */
function bindPhone(phoneCode) {
  if (!phoneCode) return Promise.reject(_err('E_NOPHONE', '没有拿到手机号凭证'))
  return ensureFresh().then((t) => post('/v1/auth/wx/phone', { code: phoneCode }, t))
}

module.exports = {
  AUTH_BASE,
  ERR_TEXT,
  getSession,
  setSession,
  clearSession,
  isLoggedIn,
  account,
  login,
  ensureFresh,
  logout,
  bindPhone,
}
