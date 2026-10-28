# 拇指相机 · 单词卡 App（Android）

> 智能拇指相机配套 App。拍照 → 云端识别 → 生成单词卡 → 双语朗读。
> **当前版本 v2.11.2（versionCode 47）**

| 项 | 值 |
|---|---|
| 包名 | `com.turing.thumbcard`（标准版）／`com.turing.thumbcard.ai`（AI 版）／`com.turing.thumbcard.devtool`（联调工具） |
| minSdk / targetSdk | 23 / 35 |
| 语言 | Kotlin（纯原生 View，无 Compose） |
| 硬件 | 联盛德 W800，BLE 配网 |
| 归档形态 | 同一套源码出三个可共存安装的 APK（product flavor：`standard` / `ai` / `devtool`） |

### 三个产物分别给谁用

| flavor | applicationId | 用途 |
|---|---|---|
| `standard` | `com.turing.thumbcard` | 标准版。配网绑定、卡片浏览、词典、存相册。日常功能联调 |
| `ai` | `com.turing.thumbcard.ai` | AI 版。多出图片美化/卡通化、相册找回。演示与验收 |
| `devtool` | `com.turing.thumbcard.devtool` | 联调工具。网络与设备（扫描/连接/`0x8D` 设备信息/`0x12` 配置下发）、报文日志（收发帧逐条十六进制）、卡片核对。**不依赖业务逻辑**，用来判断问题在设备、云端还是 App |

> 对外交付的成品与校验值见 §十二；工单见 [turing #2729](https://pep.uzoo.cn/tickets/2729)。

---

## ⚠️ 零、凭据与第三方能力（务必先读）

本仓库**不含任何真实凭据**。以下能力在拿到凭据之前**不可用，属预期行为**，不是代码缺陷。

### 0.1 图片美化（AI 出图）—— 需要图灵自建服务端代理

**这是目前唯一依赖外部付费账号的能力，也是必须由图灵接手的第一件事。**

| 项 | 说明 |
|---|---|
| 模型 | 火山方舟 `doubao-seedream-5-0-pro`（Seedream 5.0） |
| 开发期做法 | 使用**项目负责人（王宏元）的私人火山方舟账号**，密钥通过 `ARK_API_KEY` 注入，**不入库、不进 APK 源码** |
| 为什么现在能用 | 仅为了让功能可演示、可验收，属于临时方案 |
| 🔴 必须做的事 | **图片美化能力需要图灵自己开发**：App → 图灵服务端 → 方舟。密钥留在服务端，App 只持用户身份。这样密钥才不会随 APK 分发泄露，也才能按账号计费、限流、审计 |
| 现在的表现 | 未配置 `ARK_API_KEY` 时，美化按钮不可用（请求前就被拦下），不会崩溃 |

> 同理适用于其它第三方能力：**任何密钥都不应编进客户端**。本仓库已把所有凭据改为构建期从 `gradle.properties` 或 `-P` 注入，仓库内只留占位。

### 0.2 设备 APIKEY

出厂烧录、**整批设备公用**的应用级密钥（32 位十六进制）。仓库内以 `<DEVICE_API_KEY>` 占位。

- 生产环境应改为运行时从设备读取（BLE `0x8D` 扩展 TLV `0x0A`），不硬编码
- App 侧用 `EncryptedSharedPreferences`（Android Keystore）加密存储，日志仅留首尾 4 位

### 0.3 新云端 clientToken

`thumbcamera-api` 的客户端令牌由 Nacos 下发，**不入库**。仓库内以 `<CLIENT_TOKEN>` 占位。

### 0.4 词典接口

**不需要 Secret。** 实测 `data` 字段用明文即可查询成功；文档描述的 AES 加密反而恒返回 `4008`。因此这里不内置任何密钥，从根上避免反编译泄露。

### 0.5 演示机白名单

`AI_UNLIMITED_DEVICES`（不扣减美化额度的设备）的 deviceId 不入库，构建时传入。

### 0.6 本地如何补齐凭据

**建议放用户级 `~/.gradle/gradle.properties`，不要写进仓库目录内的 `gradle.properties`**（后者是跟踪文件，容易被误提交）：

```properties
DEFAULT_API_KEY=你的32位apikey
ARK_API_KEY=你的火山方舟key
AI_UNLIMITED_DEVICES=演示机deviceId
```

或构建时临时传：

```bash
./gradlew assembleStandardDebug -PDEFAULT_API_KEY=xxx -PARK_API_KEY=yyy
```

---

## 一、快速开始

### 1.1 环境

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 8.2 |
| Android Gradle Plugin | 8.2.2 |
| compileSdk | 35 |
| Build Tools | 34.0.0 |
| KSP | 必须配 `androidx.room:room-compiler:2.6.1` |

### 1.2 构建

```bash
# 需要先在 local.properties 写 sdk.dir
echo "sdk.dir=/your/android/sdk" > local.properties

# 三个 flavor 都要出包
./gradlew clean assembleStandardDebug assembleAiDebug assembleDevtoolDebug
```

产物：

```
app/build/outputs/apk/standard/debug/app-standard-debug.apk
app/build/outputs/apk/ai/debug/app-ai-debug.apk
app/build/outputs/apk/devtool/debug/app-devtool-debug.apk
```

> **flavor 资源会覆盖 main 同名文件**：`devtool` 里的布局/字符串必须加 `dev_` 前缀，
> 否则会把产品代码的同名资源顶掉，导致 `main` 侧编译失败。

> **构建红线**：出包后必须校验 dex 里存在三个 Room 生成的 `_Impl` 类
> （`AppDatabase_Impl` / `BoundDeviceDao_Impl` / `WordCardDao_Impl`），
> 否则说明 KSP 没跑，运行时会崩在数据库初始化。

---

## 二、工程结构

```
app/src/
├── main/            公共代码与资源（产品代码、BLE 协议、数据库、UI）
├── standard/        标准版 flavor（无 AI 美化入口）
├── ai/              AI 版 flavor（多出美化相关资源与入口）
└── devtool/         联调工具 flavor（独立入口 DevToolActivity，不引用业务 UI）
```

```
com.turing.thumbcard
├── ThumbCardApp.kt            Application 入口
├── ble/                       BLE 协议层（移植自联盛德官方 SDK）
│   ├── BleConstants.kt        协议常量（含扩展 TLV）
│   ├── Crc8.kt                CRC8 查表
│   ├── MessagePacket.kt       帧组装 / 解析
│   ├── MessageEncode.kt       TLV 编解码 + AES + 14 字节分片
│   └── BleWiFiClient.kt       连接流程 + 设备信息查询
├── crypto/                    AES-128/ECB/PKCS7 · RSA-1024 PKCS1
├── data/
│   ├── api/                   Retrofit 云端接口
│   ├── db/                    Room（word_card / bound_device，含显式 Migration）
│   ├── repo/                  RecordRepository / BindRepository / CurrentDeviceStore
│   ├── device/                多设备与设备语言
│   ├── dict/                  词典 WS 客户端 + 词条缓存 + 短语拆解
│   ├── i18n/                  9 种语言的译名解析与繁简转换
│   ├── image/                 图片存储 / 相册找回（EXIF 锚点）
│   └── ai/                    火山方舟出图、额度、任务队列（AI 版）
├── provision/                 配网流程编排
├── ui/
│   ├── home / card / device / profile     3 Tab 主框架
│   ├── card/ImagePreviewActivity.kt       大图页（左右滑卡 + 双击缩放）
│   ├── ai/                    美化 / 批量 / 购买
│   ├── diag/DiagnoseActivity.kt           诊断页（时延、绑定、设备信息）
│   └── settings/LanguageActivity.kt       界面语言
└── util/                      音频播放、凭据加密存储、图片加载扩展
```

---

## 三、功能清单（v2.11.2）

### 3.1 配网与设备

- BLE 扫描（ORG-ID `0x070C` 过滤）→ RSA 协商 → 读取凭据 → 下发 WiFi → 回执
- 一台手机绑定多台相机；**当前设备唯一真源是 `CurrentDeviceStore`**（存 deviceId，不存下标）
- 被解绑的设备会自动落回最近绑定的一台
- 设备支持命名，列表点一下即切换

### 3.2 单词卡

- 首页「今天拍的」≤4 张卡 + 「全部 ›」列表
- 3 Tab：记录 / 收藏 / 我的
- 收藏纯本地，不承诺云同步
- 详情页：音标（美/英双行）、拼音、例句短语拆解、词典释义
- 详情页与列表都支持**左右滑动切卡**（浏览型操作，到头回弹 + 页内提示，不弹窗）

### 3.3 大图预览

- 左右滑上一张/下一张，**不用位移**（满屏图一移露黑边）：旧图原地不动，新图解码完成后淡入
- 双击/双指缩放，放大态不切卡
- 缩放到边界时横拖给出提示，不误切
- 失败态有「点击重试」，不用 Toast（统一页内 pill）

### 3.4 多语言

- **界面语言 10 档**：简体 / 繁體 / English / 日本語 / 한국어 / Español / Français / Deutsch / Русский / العربية
  - App 资源对应 `values/`、`values-zh-rTW/`、`values-en/`、`values-ja/` …、`values-ar/` 各一份 `strings.xml`，每份 451 条
  - 小程序 10 份语言包 `miniprogram/utils/lang/*.js`，各 531 键
  - 两端**同源生成**：译表在 `tools/i18n_src/*.tsv`，改文案改译表 + 重跑 `node tools/i18n_gen.js`，不手改生成物
- **物品名称语言 9 项**：简 / 繁 / 日 / 韩 / 西 / 法 / 德 / 俄 / 阿，另有 `none` 开关（不显示译名，只显示原词）。比界面语言少一个 English —— 英文是历史第 4 档，同时充当外语兜底
- 繁体在本机转换（`HantConverter`），**永不写回 DB 与云端**
- 🔴 **语言在拍摄时固化**，`/api/record/list` 的 `lang` 参数**已无作用**（实测：带与不带返回完全一致）。云端按 `deviceId` 命中用户设定语言，写入 `name_localized` / `name_localized_lang` / `tts_url_localized`
- 译名取值优先用响应回显的 `name_localized_lang`，可挡住「服务端未支持该语言、把中文原样塞进 `name_localized`」
- 朗读回落链：`tts_url_localized` → `tts_url_english` → `tts_url` → 静音
- 🔴 **历史卡不跟随语言**：译名在识别时落库即为终值，用户之后切语言不回改已有记录；只有切换之后新拍的卡才按新语言生成
- 🔴 阿拉伯语为 RTL：布局全部用 Start/End（无 left/right）、方向图标 `autoMirrored`、`‹ › →` 在阿语 strings 里手写成 `‹` `←`

### 3.5 AI 图片美化（仅 AI 版）

- 两种风格，免费 2 张（共用），之后扣点卡（10/50/100/500 张档位）
- 后台生成（`AiTaskService` + 任务中心），单张可重做
- **只在图片落盘成功后扣额度**；额度不足在发请求前就拦
- 相册找回：靠 EXIF `ImageDescription` 写 `ThumbCard|v=1|id=|style=`，重装后可无损还原

### 3.6 v2.11.x 增量（v2.10.0 之后）

| 版本 | 变更 |
|---|---|
| v2.11.0 | 大图页左右滑卡（手势出口放在 `ZoomImageView` 内部，避免与 Activity 两套事件解析冲突）；切卡不用位移 |
| v2.11.1 | 配网手输 WiFi 时**自动预填当前连接的 WiFi 名称**（`CurrentWifi.ssid()`）；5GHz 热点给出「W800 只支持 2.4GHz」提示；读不到 SSID 时退回手输 |
| v2.11.2 | 修复**相册找回漏捞**：`taken` 占位从「校验前」挪到「尺寸校验后」，此前同一张卡被前一张失败记录占位后，后面的正确图会被误判为重复而跳过；跳过原因细化到 5 类并带卡片 id / 实际尺寸 |

---

## 四、配网流程

```
扫描设备（ORG-ID 过滤 0x070C）
   ↓
GATT 连接（Service 0x1824 / Char 0x2ABC）→ 开启 Indicate
   ↓
密钥协商：发送 RSA-1024 公钥（0x0F）
   ↓
接收 RSA 加密的 AES-128 密钥（0x8F），私钥解密
   ↓
查询设备信息（0x0D）→ 读取 apikey + deviceId（0x8D 扩展 TLV）
   ↓
下发 WiFi 参数（0x0A）
   ↓
接收配网结果（0x8A）
   ↓
建立本地绑定，写入加密存储 → （可选）回写 0x11
```

### 关键实现说明

| 项 | 说明 |
|---|---|
| 分片长度 | **14 字节**（`PACKET_MAX_LENGTH_800`），不可调整 |
| AES 模式 | **ECB/PKCS7**，非文档描述的 CBC —— 以官方 SDK 为准 |
| RSA 填充 | **RSA/NONE/PKCS1Padding**（无摘要裸填充） |
| 扫描过滤 | 广播包 `0xFF` 制造商数据中的 ORG-ID = `0x070C` |
| 空 PAYLOAD 帧 | 最小合法帧长 **5 字节**，解析时用 `< 5` 判定非法 |
| Flag 位 | `0x80` 加密 / `0x40` 要 Ack / `0x20` 有后续帧 |
| Sequence | **分片级**，每片 +1，发一片等一片（1s 超时） |
| 无参请求 | 必须放零长占位 TLV（`0x00, Len=0`），否则设备不回 |

### 扩展 TLV（`0x8D` 响应内）

| 子类型 | 含义 |
|---|---|
| `0x0A` | APIKEY（32 字节 ASCII） |
| `0x0B` | DEVICE_ID |
| `0x0C` | ORG_ID |
| `0x0D` | DEVICE_TYPE |
| `0x0E` | FW_VER |
| `0x0F` | BIND_STATUS |

### `0x12` DEVICE_CONFIG（下发语言与存储天数）

| TLV | 含义 |
|---|---|
| `0x10` | ITEM_LANG（物品语言） |
| `0x11` | UI_LANG（设备提示语言） |
| `0x12` | CLOUD_STORE_DAYS（**2 字节小端**，单字节会把它读成 109） |

> 🔴 下发成功只代表**帧写出**，不代表设备已接受。固件未实现时不回执，**不能中断配网流程**，失败项要留 `pending` 重发。

---

## 五、绑定判据（重要）

绑定成立需**同时**满足三个条件：

1. 从设备成功读到 apikey 与 deviceId（TLV `0x0A` / `0x0B`）
2. 两者格式合法（apikey 32 位十六进制；deviceId 字母数字且 ≤18 位）
3. 设备已成功联网（`0x8A` 响应 `Error_ID = 0`）

**不以「能否拉到记录」作为绑定判据** —— 云端对不存在的 deviceId 也返回 `200` + 空列表，无法区分「新设备无记录」与「deviceId 填错」。

> 🔴 deviceId 取**云端权威值**（BLE `0x8D` 返回的那串），不是 BLE 广播 MAC。广播 MAC 末位普遍 +1，拿它当 deviceId 会永远匹配不上。

---

## 六、云端接口

项目有两套云端，**路径 / 认证 / 分页 / 响应码 / id 类型全都不同**，不能混用。

### 6.1 现用：老云端 `nglearn`

```
GET https://nglearn.turingapi.com/api/record/list?key=<apikey>&deviceId=<deviceId>&page=1&size=20
GET https://nglearn.turingapi.com/api/record/detail?key=<apikey>&id=<id>
```

- `size ≤ 100`（超出自动降为 100）；`page=0` 归一为 1
- 响应 `code: 200`；**id 是数字**
- 名称字段只有 `name_chinese` / `name_english`
- `lang` 参数**被完全忽略**（实测带与不带响应逐字节相同）

**实测响应**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 1,
    "page": 1,
    "size": 20,
    "list": [{
      "id": 2,
      "device_id": "c8586a630100",
      "image_url": "https://...jpg",
      "image_format": "jpg",
      "age": 7,
      "name_chinese": "纸箱",
      "name_english": "Carton",
      "information": {
        "pinyin": "zhǐ xiāng",
        "phonetic": "/ˈkɑːtən/",
        "description": "纸箱是用硬纸板做成的盒子…"
      },
      "tts_url": ["http://...mp3"],
      "tts_url_english": ["http://...mp3"],
      "create_time": "2026-10-16 13:43:54"
    }]
  }
}
```

> **list 返回全量字段，与 detail 完全一致**，正常情况下无需调 detail。

### 6.2 接口边界（已实测）

| 场景 | 响应 | 处理 |
|---|---|---|
| 正常 | `200` + 列表 | 正常渲染 |
| 无记录 / deviceId 不存在 | `200` + `total:0` | 显示空态，**不报错** |
| key 错误 / 缺失 | `4005` | 提示凭据异常 |
| deviceId 缺失 | `4006` | 代码缺陷 |
| 记录不存在 | `40000` | 跳过 |
| `size > 100` | 自动降为 100 | — |
| 不传 page/size | 默认 `1/20` | — |

### 6.3 已知接口缺口

| # | 缺口 | 影响 |
|---|---|---|
| 1 | **不校验 deviceId 归属** | apikey 泄露即可枚举全量数据（**安全红线**） |
| 2 | 无删除记录接口 | 删除仅本地生效 |
| 3 | 无绑定关系接口 | 换手机丢失绑定 |
| 4 | 无增量同步参数 | 只能全量拉取 |
| 5 | 排序规则未声明 | 无法可靠做增量 |
| 6 | apikey 走 URL 明文 | 进日志 / 代理记录 |

当前用**全量拉取 + 本地按 id 去重**规避第 4、5 项。

### 6.4 备选：新云端 `thumbcamera-api`（尚未接入）

`https://thumbcamera-api.turingapi.com`，`Bearer clientToken` 鉴权，`code:0`、`limit/cursor` 分页、`name_localized` 字段、**id 是 UUID 字符串**。

设备侧拉配置是 **pull 不是 push**：`POST /v1/device/config` 上报 `item_lang` / `ui_lang`，云端返 `corrected` 与权威值。

> 🔴 字段必须**下划线**（`item_lang`）。写成驼峰会被静默忽略，不报错。
> 切换到新云端意味着 Room 迁移（id 从 Long 变 UUID）+ 存量记录映射，**不是改个域名就能用**。

---

## 七、安全说明

### 7.1 apikey 处理

apikey 是**整批设备公用**的应用级密钥，泄露即影响该应用下**所有**设备。

- 用 `EncryptedSharedPreferences`（Android Keystore）加密存储
- 日志脱敏（仅首尾各 4 位）
- **禁止**随崩溃上报、埋点外发
- 生产包应移除 `BuildConfig.DEFAULT_API_KEY`

### 7.2 遗留风险

由于**不自建服务端**，App 必须直连云端并持有 apikey，以下风险无法在客户端解决：

- 应用可被反编译，apikey 存在被提取的可能
- 云端不校验 deviceId 归属，apikey 泄露即可遍历所有设备记录
- apikey 作为 URL 查询参数，会留下代理 / CDN 日志

> **建议**：推动服务端增加「用户—设备」归属校验，App 改为只持用户 token。

---

## 八、文档索引

| 文档 | 路径 |
|---|---|
| **客户接入文档（对外交付）** | [`docs/图灵AI拍学机接口接入说明文档_V3.1_2025.10.26_客户版.md`](docs/图灵AI拍学机接口接入说明文档_V3.1_2025.10.26_客户版.md) ／ [PDF](docs/图灵AI拍学机接口接入说明文档_V3.1_2025.10.26.pdf) |
| 设备端对接说明（固件侧） | [`docs/拍学机_设备端对接说明_V3.1-HW.md`](docs/拍学机_设备端对接说明_V3.1-HW.md) |
| App 技术方案（最新） | [`docs/拇指相机App技术方案_V3.0.md`](docs/拇指相机App技术方案_V3.0.md) |
| BLE 配网协议 V1.2（图灵扩展修订版） | [`docs/WM_W800_BleWiFi蓝牙配网协议_V1.2_图灵扩展修订版.md`](docs/WM_W800_BleWiFi蓝牙配网协议_V1.2_图灵扩展修订版.md) |
| BLE 配网协议 V1.1（历史基线） | [`docs/WM_W800_BleWiFi蓝牙配网协议_V1.1_图灵扩展修订版.md`](docs/WM_W800_BleWiFi蓝牙配网协议_V1.1_图灵扩展修订版.md) |
| 配网时序图 V1.2（可视化预览） | [`docs/preview/配网时序图_V1.2_预览.html`](docs/preview/配网时序图_V1.2_预览.html) |
| 固件端协议扩展规格书 | [`docs/固件端协议扩展规格书_V1.0.md`](docs/固件端协议扩展规格书_V1.0.md) |
| 新云端接口协议 V2.0 | [`docs/拇指相机_云端接口协议_V2.0.md`](docs/拇指相机_云端接口协议_V2.0.md) |
| 设备配置接口补充 | [`docs/拇指相机_设备配置接口补充_V1.1.md`](docs/拇指相机_设备配置接口补充_V1.1.md) |
| 拍学机接口文档 V3.1 更新需求 | [`docs/图灵AI拍学机接口接入说明文档_V3.1_更新需求.md`](docs/图灵AI拍学机接口接入说明文档_V3.1_更新需求.md) |
| 联调工具使用说明 v1.1 | [`docs/拍学机联调工具_使用说明_v1.1.md`](docs/拍学机联调工具_使用说明_v1.1.md) |
| 样机联调清单 v2.11.1 | [`docs/样机联调清单_v2.11.1.md`](docs/样机联调清单_v2.11.1.md) |
| 版本演进与真机验收 | [`docs/安装说明.md`](docs/安装说明.md) |
| UI 设计系统 V2.0（墨标 Inkmark） | [`docs/UI设计系统_V2.0_年轻白领.md`](docs/UI设计系统_V2.0_年轻白领.md) |
| UI 交互系统 V2.0 | [`docs/UI交互系统_V2.0.md`](docs/UI交互系统_V2.0.md) |
| 词典接口实测报告 | [`docs/词典接口实测报告_2026-10-20.md`](docs/词典接口实测报告_2026-10-20.md) |
| 多语言排版核对（v2.9.0 历史截图，9 语言 × 4 界面） | [`docs/v2.9.0_多语言排版核对.html`](docs/v2.9.0_多语言排版核对.html) |
| **多语言 10 档与 App 最新接口对齐**（2026-10-28） | [`docs/多语言10档与App最新接口对齐_2026-10-28.md`](docs/多语言10档与App最新接口对齐_2026-10-28.md) |
| 阿语支持验收说明（RTL / 译名 / 朗读边界） | [`docs/阿语支持_验收说明_2026-10-27.md`](docs/阿语支持_验收说明_2026-10-27.md) |
| 拍学机设备端对接说明（客户版，无工单号） | [`docs/拍学机_设备端对接说明_V3.1-HW_客户版_2026-10-28.md`](docs/拍学机_设备端对接说明_V3.1-HW_客户版_2026-10-28.md) |

> **产品与商务文档不在本仓库**（PRD、立项报告、成本测算、定价策略），见私有仓库 `thumbcard-product-docs`，访问需由项目负责人开通。

---

## 九、已知限制 / 未完成

| # | 项 | 说明 |
|---|---|---|
| 1 | **阿语 TTS 缺音色** | 文本层已全通（`tts_url_localized` 已接、阿语界面与 RTL 已做），但老栈 TTS 为单音色 `x2xiaoxue-v4`，CosyVoice 通道语种为 zh/en/ja/ko/yue **不含 ar** → 阿语当前「能显示不能读」。两条路：降级播英文 / 换引擎，待定 |
| 2 | **固件未实现 `0x12` DEVICE_CONFIG** | App 可以把语言下发下去，但设备不会应用，也不会回执 |
| 3 | **固件未实现 `0x0E` 热点列表** | 只回一行空列表，因此手输 SSID 是客户必需路径（v2.11.1 已做预填） |
| 4 | **不使用 `thumbcamera-api` 新栈** | `#2835` 那套 V2.1 新协议不作为客户端交付面：App / 小程序代码**零处引用**该域名，`BASE_URL` 均为 `nglearn.turingapi.com`。本版以「老 nglearn 增量」交付（只加 3 个字段 + deviceId 可控），故新栈的成功码差异（`code:0`）、UUID 主键、cursor 分页均不涉及 |
| 5 | 语言维度已上 prod，但有边界 | 10-28 `nglearn` 语言能力已上生产（`study_device_lang` 表 + `study_record` 加 3 列）。剩余：记录详情是 `data.record` 嵌套；`tts_url*` 全为 `http://`，小程序 `downloadFile` 需 https |
| 6 | 支付为本地模拟 | 点卡购买未接真实支付通道 |
| 7 | 密钥编在客户端 | 见 §0：必须由服务端代理，属待改造项 |
| 8 | 图片美化依赖私人账号 | 见 §0.1，需图灵自建服务端 |

---

## 十、调试

### 10.1 日志

```bash
adb logcat -s BleWiFiClient:* AesUtils:* RsaUtils:* RecordRepository:*
```

> 部分 ROM（如 G10-M 平板）抓不到 App 的 `Log.d`，此时以界面 / 截图为证。

### 10.2 抓包验证要点

配网过程中应能看到：

1. `0x0F` 密钥协商请求（含 RSA 公钥）
2. `0x8F` 响应（含加密的 AES 密钥）
3. `0x0D` 设备信息查询（5 字节空 PAYLOAD 帧）
4. `0x8D` 响应多帧，Sequence 递增，非末帧 Flag 含 `0x20`
5. `0x0A` WiFi 配置请求
6. `0x8A` 配网结果响应

### 10.3 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 扫描不到设备 | 蓝牙 / 定位未开，或 ORG-ID 不匹配 | 检查权限与广播包 ORG-ID |
| 密钥协商失败 | AES 模式用了 CBC | 必须用 ECB |
| 帧被丢弃 | CRC8 实现不匹配 | 用官方查表，勿替换为通用算法 |
| 读不到凭据 | 固件未实现 TLV 扩展 | 升级固件 |
| 列表为空 | deviceId 与云端不一致 | 核对产线烧录值 |
| 发音无声 | `tts_url` 为空或网络异常 | 检查 URL 与网络 |
| 美化按钮不可用 | 未配置 `ARK_API_KEY` | 见 §0.1，属预期行为 |

---

## 十一、License

见 [LICENSE](LICENSE)。代码仅用于项目评审与技术交流，硬件协议、商标与第三方接口归各自权利人所有。

---

## 十二、对外交付物（与工单 #2729 同步）

| 文件 | 字节 | SHA-256 前 16 位 |
|---|---|---|
| 单词卡相机 v2.11.2（standard） | 10,283,930 | `a69810e839284516` |
| 单词卡相机AI v2.11.2（ai） | 10,283,981 | `97d5fe190815b23e` |
| 拍学机联调工具 v1.1（devtool） | 10,344,987 | `49fed88c73a6688f` |
| 图灵AI拍学机接口接入说明文档 V3.1（PDF，25 页） | 1,447,225 | 见工单附件 |

> APK 不进仓库（内含已编译的凭据占位与构建期注入值），统一由工单 #2729 附件分发。
> 本仓库只出可复现构建：`./gradlew clean assembleStandardDebug assembleAiDebug assembleDevtoolDebug`。

---
