const { Provisioner } = require('../../utils/provision')
const store = require('../../utils/store')
const fmt = require('../../utils/fmt')
const i18n = require('../../utils/i18n')
const icons = require('../../utils/icons')

// step: ready 准备 / scan 搜设备 / connect 连接+读信息 / wifi 选热点 / run 下发中 / done 成功 / fail 失败

Page({
  data: {
    step: 'ready',

    statusBar: 20,
    icons: {
      back: icons.back,
      chevron: icons.chevronT3,
      wifi: icons.wifi,
      camera: icons.cameraT3,
      search: icons.searchT3,
    },

    devices: [],
    scanEmpty: false,

    info: null,
    infoMasked: '',

    aps: [],
    apMeta: null,
    scanningWifi: false,
    wifiErr: '',
    manual: false,

    sheet: false,
    sheetSsid: '',
    sheetPwd: '',
    pwdFocus: false,

    manualSsid: '',
    manualPwd: '',

    runText: '',
    result: null,
    failMsg: '',
    failHint: '',
    logs: [],
  },

  onLoad() {
    this.pv = new Provisioner((m) => this._log(m))
    this._scanTimer = null
    this.setData(
      Object.assign(i18n.page(), {
        statusBar: (getApp().globalData || {}).statusBar || 20,
        runText: i18n.t('mp_pw_sending'),
      })
    )
  },

  onBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/today/today' })
  },

  onUnload() {
    this._cleanup()
  },

  _cleanup() {
    if (this._scanTimer) {
      clearTimeout(this._scanTimer)
      this._scanTimer = null
    }
    if (this.pv) this.pv.close()
  },

  _log(msg) {
    const logs = this.data.logs.concat([msg]).slice(-6)
    this.setData({ logs })
    console.log('[provision]', msg)
  },

  // ---------- 1. 搜索设备 ----------
  onStart() {
    this.setData({ step: 'scan', devices: [], scanEmpty: false, failMsg: '' })
    this.pv
      .openBluetooth()
      .then(() =>
        this.pv.startScan((list) =>
          this.setData({ devices: list, foundText: i18n.fmt('mp_pw_found_fmt', [list.length]) })
        )
      )
      .then(() => {
        // 12 秒没扫到就给个说法，不要无限转圈
        this._scanTimer = setTimeout(() => {
          if (!this.data.devices.length) this.setData({ scanEmpty: true })
        }, 12000)
      })
      .catch((e) => this._fail(e.message, i18n.t('scan_empty')))
  },

  onRescan() {
    if (this._scanTimer) clearTimeout(this._scanTimer)
    this.setData({ devices: [], scanEmpty: false })
    this.onStart()
  },

  // ---------- 2. 连接并读设备信息 ----------
  onTapDevice(e) {
    const ds = e.currentTarget.dataset
    const dev = this.data.devices.find((d) => d.deviceId === ds.id)
    if (!dev) return
    if (this._scanTimer) clearTimeout(this._scanTimer)
    this.setData({ step: 'connect', failMsg: '' })
    this._log('连接 ' + dev.name)

    this.pv
      .connect(ds.id)
      .then(() => {
        this._log('已连接，读取设备信息')
        return this.pv.readDeviceInfo()
      })
      .then((info) => {
        this.setData({
          step: 'wifi',
          info,
          infoMasked: fmt.maskKey(info.apikey),
        })
        this._log('deviceId=' + info.deviceId + ' 固件=' + (info.fw || '未知'))
        this.loadWifi()
      })
      .catch((e) => this._fail(e.message, '靠近相机再试一次；还是不行就重启相机'))
  },

  // ---------- 3. 扫 WiFi ----------
  loadWifi() {
    this.setData({ scanningWifi: true, wifiErr: '', aps: [], apMeta: null, manual: false })
    this.pv
      .scanWifi((list, meta) => {
        // 边收边显示，不等全部到齐
        this.setData({ aps: list, apMeta: meta, apText: i18n.fmt('mp_pw_ap_count_fmt', [meta ? meta.total : list.length]) })
      })
      .then((r) => {
        const meta = r.meta || { total: r.total, done: true }
        this.setData({
          scanningWifi: false,
          aps: r.list,
          apMeta: meta,
          apText: i18n.fmt('mp_pw_ap_count_fmt', [meta.total || r.list.length]),
        })
        if (!r.list.length) {
          this.setData({ wifiErr: i18n.t('wifi_scan_slow') })
        }
      })
      .catch((e) => {
        this.setData({
          scanningWifi: false,
          wifiErr: e.message || '扫描失败',
          manual: true,
        })
      })
  },

  // ---------- 4. 选热点 / 输密码 ----------
  onTapAp(e) {
    const i = e.currentTarget.dataset.index
    const ap = this.data.aps[i]
    if (!ap) return
    if (ap.needPwd) {
      this.setData({ sheet: true, sheetSsid: ap.ssid, sheetPwd: '', pwdFocus: true })
      return
    }
    // 开放网络：直接下发，不带密码 TLV
    this.startConfig(ap.ssid, '')
  },

  onSheetInput(e) {
    this.setData({ sheetPwd: e.detail.value })
  },

  onSheetCancel() {
    this.setData({ sheet: false, sheetPwd: '' })
  },

  noop() {},

  onSheetConfirm() {
    const pwd = this.data.sheetPwd
    if (pwd.length < 8) {
      wx.showToast({ title: i18n.t('mp_pw_pwd_short'), icon: 'none' })
      return
    }
    this.setData({ sheet: false })
    this.startConfig(this.data.sheetSsid, pwd)
  },

  onManualSsid(e) {
    this.setData({ manualSsid: e.detail.value })
  },

  onManualPwd(e) {
    this.setData({ manualPwd: e.detail.value })
  },

  onToggleManual() {
    this.setData({ manual: !this.data.manual })
  },

  onManualSubmit() {
    const ssid = (this.data.manualSsid || '').trim()
    if (!ssid) {
      wx.showToast({ title: i18n.t('wifi_ssid_empty'), icon: 'none' })
      return
    }
    this.startConfig(ssid, this.data.manualPwd || '')
  },

  // ---------- 5. 下发配网 ----------
  startConfig(ssid, pwd) {
    this.setData({ step: 'run', runText: i18n.t('mp_pw_sending'), failMsg: '' })
    this._log('配网 ' + ssid)

    // 设备连 AP + 拿 IP，最多等 45 秒
    const slow = setTimeout(() => {
      if (this.data.step === 'run') this.setData({ runText: i18n.t('mp_pw_connecting_wait') })
    }, 6000)

    this.pv
      .provision(ssid, pwd, 45000)
      .then((r) => {
        clearTimeout(slow)
        if (r.ok) {
          this._saveDevice()
          this.setData({ step: 'done', result: r })
          return
        }
        this.setData({ step: 'fail', failMsg: r.message, failHint: i18n.t('mp_pw_2g_remind') })
      })
      .catch((e) => {
        clearTimeout(slow)
        this._fail(e.message, i18n.t('mp_pw_after_ok_hint'))
      })
  },

  _saveDevice() {
    const info = this.data.info
    if (!info) return
    const devices = store.upsertDevice({
      deviceId: info.deviceId,
      apikey: info.apikey,
      name: '拇指相机 ' + String(info.deviceId).slice(-4),
      fw: info.fw,
      orgId: info.orgId,
    })
    store.setCurrentId(info.deviceId)
    this._log('已保存设备，共 ' + devices.length + ' 台')
  },

  _fail(msg, hint) {
    this.setData({ step: 'fail', failMsg: msg || i18n.t('mp_pw_fail'), failHint: hint || '' })
  },

  // ---------- 6. 收尾 ----------
  onRetry() {
    // 连接层面失败：回到扫设备重来（BLE 链路状态已不可信）
    this._cleanup()
    this.pv = new Provisioner((m) => this._log(m))
    this.setData({ step: 'ready', info: null, aps: [], result: null, failMsg: '' })
  },

  onDone() {
    this.pv.close()
    wx.switchTab({ url: '/pages/index/index' })
  },

  onCopyId() {
    const info = this.data.info
    if (!info) return
    wx.setClipboardData({ data: info.deviceId })
  },
})
