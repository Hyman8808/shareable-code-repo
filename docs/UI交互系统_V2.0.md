# 单词卡相机 · UI 交互系统 V2.0

> 适用：Android 原生（XML + Kotlin，minSdk 23，View 体系，动效仅 View/ObjectAnimator）
> 上游：`docs/UI设计规范_V1.0.md`（视觉 token，本文不改其值）。本文只讲**交互与结构**。
> 配色一律写语义 token（`brand_primary` / `text_primary` / `bg_card` / `divider` / `warning`），无 hex。

## 0. 一句话交互主张

**「打开就看今天拍的，拇指按住就收藏」——把「看到今天第 1 张卡」从 2 次划动压到 0 次划动，把「收藏」从瞄准图片右上角那颗 30dp 的心，改成按住 328×88 的整行 400ms（心形 44dp 热区的一键路径同时保留）。** 场景锚点：早高峰地铁单手扶杆 → 只用「单击 + 长按」；午休工位 → 可搜索、可展开；睡前躺着 → 只听发音。

## 1. 动作账本（7 条不满 → 目标动作数）

| # | 不满 | 现状动作 | V2 目标 | 归属 |
|---|---|---|---|---|
| 1 | 首屏 60% 被 4 层头部占掉 | 1 次划动 | **0 次划动** | §3 |
| 2 | 学习报告每天同一组 0，无势能 | 190dp 常驻卡 | 44dp 条 + 连续天数 | §3 |
| 3 | 20 张要划 ~9 次（2 张/屏） | 约 10 屏 | **收藏页 4.5 屏 / 3.5 次划动**（考核口径，4 张/屏）；首页现状 6.1 屏（不按张数考核） | §4 |
| 4 | 想看怎么读要滚到中间 | 划动 + 找按钮 | **0 次划动** | §6 |
| 5 | 通勤单手点不中 30dp 的心 | 1 次精确点击 | **长按整行（100% 命中）**＋心形 44dp 热区 | §5 |
| 6 | 切 Tab / 返回无过渡 | 硬切 | 160ms / 200ms 位移淡入 | §8 |
| 7 | 六种状态长得一样 | 分不清 | 6 态可区分 | §7 |

## 2. 信息架构调整

**结论（已拍板采纳）：4 Tab → 3 Tab（今天 / 收藏 / 我的），设备降为二级。** 理由：① 设备是一次性页面（配网一辈子 1–2 次），却占底部 25% 一等位置，把「我的」挤到最难 reach 的右角；② 设备留**两个不同职责**的入口：首页顶栏 `btnDevicesEntry`（18dp 图标 / 44×44 热区 / 无文字，**上下文 + 快切**：这台相机拍的卡就在下面，**仅多设备时显示**）＋「我的 › 设备 › 我的相机」（**管理 / 配对**：换机、加第二台、解绑）——两者语义不同，不是"两个入口进同一页"的缺陷，与「设置」同时出现在顶栏和「我的」里同类；③「首页 / 单词卡」命名有歧义——**所有照片都是单词卡**，改成「今天 / 收藏」正好对上「拍 ≠ 收藏」的产品口径；④ 3 项仍在 Material 建议区间（3–5）。

**两条不变项（本轮绝不动）**：
1. **未绑定设备时，首页 `groupUnbound` 的「添加设备」大卡仍是唯一主路径**，位置、尺寸、文案全部保持，3 Tab 改动不影响它。
2. **配网成功仍回首页**：`WifiConfigActivity:254` 的 `EXTRA_SELECT_TAB = TAB_HOME` 保持不变，`MainActivity.onNewIntent` 逻辑不动。

| 旧 | 新 | 类型 | 说明 |
|---|---|---|---|
| `nav_home`「首页」 | 「今天」（**id 不变**） | 只改文案 | `@string/nav_home=今天`；图标沿用 `ic_nav_home`；`MainActivity.TAB_HOME` 不变 |
| `nav_cards`「单词卡」 | 「收藏」（**id 不变**） | 只改文案 | `@string/nav_cards=收藏`；图标沿用 `ic_nav_cards`；`MainActivity.TAB_CARDS` 不变 |
| `nav_devices`「设备」 | **删除** | 移除 | 设备管理统一由**已存在的** `DeviceListActivity`（`activity_device_list.xml`）承载——**不新增 Activity**；`DevicesFragment` / `fragment_devices.xml` 变为无引用 |
| `nav_profile`「我的」 | 不变，**新增独立的「设备」组（1 行「我的相机」）** | 新入口（**管理语义**；首页顶栏另有一个"上下文/快切"入口，见结论②） | 位置：紧贴账号卡之后、**「语言偏好」组之前**；行结构复用 `rowLanguage`（56dp，左 20dp 图标 → **12dp**（`space_12`）→ 主字 → chevron），`divider` 的 `marginStart` = **48dp**（16+20+12），图标 `ic_bluetooth`，id `rowMyCamera`。⚠️ 现有 5 行里的 `14dp` 是 V1.9 遗留裸值（`dimens.xml` 无此档），本轮连新增行一起 **6 行统一改 `space_12` + 48dp**，否则新行与旧行并排差 2dp 一眼可见 |

> 保留 `nav_home` / `nav_cards` 的 **id 字面值**（只换 label）是刻意的：`TAB_HOME` / `TAB_CARDS` / `TAB_PROFILE` 三个常量、`KEY_TAB` 状态恢复、`WifiConfigActivity` 回跳全部无需改动，技改面收敛到「统一删掉 ② 里那 3 处 `devices` 相关代码」。

**新增文案（简 / 英 / 繁）**　落地：**工程侧已落盘，我这份只记原文，不改 `strings.xml`**。三语基线 228 条 → **232** 条。

| key（已落盘的实际键名） | 中文 | English | 繁體（台灣用語） | 用在哪 |
|---|---|---|---|---|
| `profile_group_device` | 设备 | Device | 裝置 | 「我的」页新分组标题：`Text.Label`（11sp，**letterSpacing +0.06**）。⚠️ 现有 4 处组标题本轮一并迁到 `Text.Label`，否则新组标题与「语言偏好」并排差 0.02em，肉眼可见 |
| `profile_camera` | 我的相机 | My camera | 我的相機 | 该行主字（15sp `text_body`） |
| `profile_camera_unbound` | 未配对 | Not paired | 未配對 | 该行右侧当前值（12sp `text_secondary`，`maxWidth=150dp`）；已绑定则显示现有 `device_count_fmt` |
| `cards_count_unit_fmt` | %1$d 张 | %1$d cards | %1$d 張 | 卡片数单位（由工程按既有格式串用法接入） |

**按 id 的跟改清单（技改 3 处 + 新增 1 行；入口保留、不删）**

| 必须跟改的入口（按 id） | 现状 | 改成 |
|---|---|---|
| ① `menu_bottom_nav.xml` | 4 个 `<item>` | **删 `nav_devices` 一项**；`nav_home` / `nav_cards` 的 id 与图标不动，只改 `@string` 文案（第二项文案是「收藏」，**不是**「单词卡」） |
| ② `MainActivity.kt:59`、`:149-150` | `R.id.nav_devices -> switchTo(devicesFragment)`；`val TAB_DEVICES = R.id.nav_devices` | 删 `when` 分支 + `TAB_DEVICES` 常量 + `devicesFragment` 字段（**共 3 处，无其它引用**） |
| ③ `HomeFragment.kt:98` | `btnDevicesEntry → selectTab(TAB_DEVICES)` | **入口保留**，回调改为 `startActivity(DeviceListActivity)`（Tab 已删，需换目标；不要删这个监听） |
| ④ `fragment_profile.xml` | 「语言偏好」组之前 | 插入「设备」组标题 + `profile_camera` 行（id `rowMyCamera`），点击 `startActivity(DeviceListActivity)`；连带 5 行 `14dp→space_12`、4 处组标题→`Text.Label`、5 条 `divider marginStart 50→48` |
| `DevicesFragment.kt` / `fragment_devices.xml` | 设备 Tab 的 Fragment | **无引用，保留文件不删**（后续清理）；其中 `:62` 的 `selectTab(TAB_CARDS)` 随之下线，无需改写 |
| `fragment_cards.xml:37` | 页面标题 `@string/nav_cards` | 自动跟随改名（「收藏」），零改动 |
| `fragment_home.xml` | `btnDevicesEntry` 带「管理」文字 + `tvDeviceMeta`（设备 ID 行） | **入口保留**：只留 **18dp 蓝牙图标 + 44×44 热区、去掉文字、`brand_primary` tint**；**常显改为条件显隐**：`btnDevicesEntry.visibility = if (devices.size > 1) VISIBLE else GONE`（单设备时首页不需要"看哪台"）；`tvDeviceName` **保留为 22sp 页面标题**（用户自己起的名字，值一个标题位，**`clickable=false`，不进设备页**）；`tvDeviceMeta` **保留不删**（见 §3） |
| `WifiConfigActivity.kt:254` | `EXTRA_SELECT_TAB = TAB_HOME` | **不变** |
| `strings.xml` | — | 上表 4 条已落盘（228→**232**，简/繁/英键一致）。⚠️ `nav_devices` 字符串**保留不删**：`fragment_devices.xml:30` 仍引用它，删则 AAPT 报错；底栏不显示它没有副作用 |

## 3. 首屏重构（最重要）

**结构改变**：现状 `SwipeRefreshLayout > NestedScrollView > LinearLayout` 里套 `wrap_content` 的 `rvRecent`，20 张卡全量 measure 且头部随页滚走。V2 改为：

```
SwipeRefreshLayout > LinearLayout(vertical)
  ├ 固定头 88dp（不滚动）: groupTodayHead 44dp + groupStatsStrip 44dp
  └ RecyclerView rvRecent (0dp / weight=1 / **LinearLayoutManager 单列**)
      item0 提示条「长按整行收藏」36dp（首次）
      item1..n 词典行（88dp/行 + 1dp divider）
      itemN 「全部记录 ›」44dp（≥20 行）
```

| # | 块 | id | 内容 | 高 | 折叠 | 随滚收起 |
|---|---|---|---|---|---|---|
| 1 | 相机头 | `groupTodayHead` | **左 = `tvDeviceName`**（用户自己命名的相机名）`Text.PageTitle`（`text_h1` = **22sp** medium，V2.0 无 24sp 档），`maxLines=1` `ellipsize=end`，**`clickable=false` / `focusable=false`（纯文本，不做第二热区）**；右端 `btnDevicesEntry` = **18dp 蓝牙图标 + 44×44 热区、无文字**，**`devices.size > 1` 才 VISIBLE，单设备 GONE**（进 `DeviceListActivity` 的唯一入口）；中间 `tvSyncState` 11sp「3 分钟前同步」（纯状态，不可点） | **44dp** | 否 | 否（固定） |
| 2 | 统计条 | `groupStatsStrip` | 单行 `今天 12 张 · 6 个词 ⌄`（数字 `brand_primary`）——注意**「今天」这个语义由统计条承担**，标题位让给相机名 | **44dp** | 是（展开 140dp） | 否（固定） |
| 3 | 提示条 | `itemHintLongPress` | 11sp「长按任意行即可收藏」 | 36dp | **仅 `today.isEmpty()` 时随空状态一起出现**（长按的发现性主要由常驻心形承担，见 §5） | 是 |
| 4 | 卡片列表 | `rvRecent` | 单列词典行（§4），**LinearLayoutManager 非 Grid** | 88dp/行 | — | 是 |
| 5 | 底部出口 | `btnAllRecords` | 「全部记录（共 37 张）›」 | 44dp | ≥20 张才出现 | 是 |

**删除**：`cardStats`（190dp 常驻报告卡）、`home_shoot_hint` 提示条（≈60dp 纯静态说明，并入空状态）、`tvFavoriteHint`（并入 item3）、`groupEmpty`（并入 §7）。
**保留（不要删）**：`tvDeviceName`（22sp 页面标题，用户自己起的名字值一个标题位，**不降级为脚注**；`clickable=false`）、`btnDevicesEntry`（**18dp 图标 + 44×44 热区、无文字**，点击进 `DeviceListActivity`；**`devices.size > 1` 才显示，单设备 GONE**——一台机器时顶栏不需要"看哪台"，见 §2 结论②）、**`tvDeviceMeta`**（11sp `text_tertiary` 副行，`deviceId · 日期`——它是 App 里**唯一不点进二级页就能看到设备 ID** 的地方，用户报障时客服要这个号；`HomeFragment.showBound()` 已在往里写，删节点会让 ViewBinding 崩）。

**学习报告怎么处理**

| 项 | 规格 |
|---|---|
| 收起态 | **44dp 单行**：`今天 12 张 · 6 个词` + 16dp `ic_chevron_down`（`text_tertiary`） |
| 展开态 | 44→140dp，4 行明细（今天/本周/连续/累计，每行 24dp），200ms `Decelerate`；再点收起 |
| 出现条件 | `totalPhotos == 0` → **整条 GONE**（不留「0 张 0 词」的尸体）；`totalPhotos > 0` 恒显示 |
| 势能（**纯本地派生，零新接口**） | ① **连续天数** `streakDays`：把 `StudyStats.timeOf()` 的日期去重后从今天往前数；**今天尚无记录则从昨天起数**（否则每天早上一睁眼就断签）；上限回看 90 天 ② 今天用**去重词数**而非张数（同物重拍不加分，防刷量）③ 当日首次出数据时数字 0→N 的 320ms 滚动（`ValueAnimator`） |
| 不做 | 不做「比上周 +30%」：`SyncScope.LATEST` 最多 3 页/300 条，历史不全→算错。**错数字比没数字更伤信任** |

**高度验算（唯一基数表，别换基数）**：`可用内容高 = 屏高 − 24(状态栏) − 导航(三键 48 / 手势 24) − 64(底栏) − 1(hairline)` → 640dp 屏 = **503**（**手势 527**；若不计 hairline 为 504，差 1dp 不影响落档）；`列表可用 = 可用 − 头部`；**行距 = 88dp**（分割线画在行内、`getItemOffsets` 返回 0，**不占位，所以不是 89**）。**头部 214dp（逐项）** = `page_top 8 + 顶栏 44 + 副行 15 + gap 8 + 今日卡 81 + 区块头 56 + rvRecent mt 2`（今日卡 81 = `padding 12×2 + 第1行 35 + gap 6 + 第2行 16`，`baselineAligned` 已拉平；`tvFavoriteHint` 仅在**一条收藏都没有**时 VISIBLE、`groupShootHint` 仅在 `today.isEmpty()` 时 VISIBLE → **两者都不进常态基数**）。→ 三键 `(503−214)/88 = **3.28**` → **3 整行 + 第 4 行露 28%**；**手势 `(527−214)/88 = 3.56`** → 3 整行 + 露 56%。**验收门槛写整数、不写小数**（「≥3 整行」而不是「≥3.28 行」）——**保守要落在门槛上，不能落在测量值上**，否则基准就允许与 XML 不一致，这正是 532 / 616 的成因。**空态基数不同**：今天 0 张时 `groupShootHint` +52 → 头部 **266**，`(503−266)/88 = **2.69 行**` → 2 整行 + 露 69%（第三态「无收藏」不参与验收，别做成三值条款）。❗首页头部 **214dp**（逐项 `8+44+15+8+81+56+2`，照 `fragment_home.xml` 可复算）；曾出现的 `210`（漏 4dp）/ `216`（把 `rvRecent` 的 2dp 当 4dp）两个口径**均作废**。（方法论两条：① **先定位到具体项**，再取值；② 取值一律**取对用户不利的一侧**——**容量类**（一屏几行/几张/几屏）取小行数、**门槛类**（对比度 ≥4.5 / 热区 ≥44dp / 字号 ≥11sp）取严、**可容纳性**（`542 ≤ 568`）取"栈大 + 可用小"。缺了 ① 就会像本轮 216 那样把"把 2dp 当 4dp"的项错包装成"保守"；缺了 ② 会退化成"一律取小数"，在对比度上把 5.46 判达标、在容纳性上把溢出判放得下。）❗**以下数字全部作废、别再引用**：`210 / 216`（见上）、`220 / 262 / 272`（今日卡 85 那版）、`283 / 353 / 351`（压缩前）、`96 / 98 / 147`（今日卡早期取值）、`396 / 510 / 551 / 550 / 89 / 45%`、`616`（未消费 inset）。**现行只有**：可用 **503 / 527**、头部 **214（常态）/ 266（空态）**、行距 **88**、收藏头 **114**、收藏 **4.42（三键）/ 4.69（手势）**。§3 目标态（88dp 固定头）→ `503−88 = 415` → 4 整行，**本轮不启用、不进汇报**。

## 4. 列表密度方案

**结论：单列「词典行」（已定稿、实现中）。** 否决网格不是口味问题：2 列 156dp 卡装不下 19sp 衬线词头——而「英文词头当主角」是这版设计的立身之处；且一屏只有 2 张白方块，仍带「模板感」。行式**一屏 4 整行（4 张）**，对现状是 **2 倍密度**。**密度口径（可复核，全表同口径）**：一屏 = 首页目标态列表区 `503 − 88(固定头) = 415dp`，扣各自行距向下取整。

| 候选 | 单元尺寸 | 一屏（目标态 415dp 内） | 结论 |
|---|---|---|---|
| A 2 列大卡（现状） | 156×230 | 1 整行 = **2 张** | 否：20 张要划 9–10 次，图 132dp 独占首屏 |
| B 2 列紧凑 | 156×172 | 2 整行 = **4 张** | **否**：156dp 宽放不下 19sp 衬线词头；满屏白方块 |
| C 3 列瀑布 | 100×150 | 2 整行 = **6 张** | 否：密度最高但中文名必须 1 行截断、1.3× 字号 5 字名溢出、44dp 热区占卡宽 44%、100dp 图难认物 |
| D 横向卡带 | 132×200 | 1 行 = **2.5 张** | 否：单手横滑命中率低于纵滑；20 张横滑 7 次且无法一眼扫完 |
| **E 单列词典行（选定）** | **328×88** | 4 整行 = **4 张** | 是：装得下 19sp 词头；纸底直露无模板感；心形有独立 44dp 热区、不吃文本宽度 |

| `item_word_card.xml` 规格（**行式，重写**） | 值 |
|---|---|
| 行容器 | **328 × 88dp**（`row_height_compact`），`minHeight=88dp`；**无卡底、无描边、无 elevation**，纸底（`bg_page`）直接露 |
| 横向布局 | RecyclerView `paddingHorizontal=20dp`（= `page_horizontal`），item 无外边距 → 行宽 **328dp** |
| 缩略图 | **64×64dp**（`thumb_row`），`centerCrop`，圆角 `radius_sm` **10dp**（行内唯一有圆角的东西） |
| 图 ↔ 文字间距 | **14dp** → 文字列可用宽 **206dp**（328−64−14−44） |
| 英文词头（主角） | **19sp serif**，`maxLines=1` `ellipsize=end`，`text_primary`（行高 26dp）。不得为塞字降字号 |
| 中文名（副） | 13sp `text_secondary`，`maxLines=1` `ellipsize=end`（行高 18dp） |
| meta（时间/拼音） | 11sp `text_micro`，**默认 GONE**：首页都是今天→无信息量；仅「全部记录」页由 Adapter 打开 |
| 分隔线 | 1dp `divider`，`marginStart=76dp`（20+64+14，对齐文字不切图） |
| 心形 | 视觉 **30dp 圆底**、热区 **44×44dp**、右端垂直居中、**常驻可点**（单击切换）；**长按整行**同样切换——两条路都留 |
| 行高构成 | 12 + 64 + 12 = **88dp** |

**一屏（同一基数表，分页给数）**：**收藏 / 全部记录页才是密度考核页**——头部 114dp（页内 22sp 标题 32 + paddingTop 14 + marginTop 14 + 搜索 44 + paddingBottom 10，**没有 44 顶栏**）→ **三键** `(503−114)/88 = 389/88 = **4.42**` → **4 整行 + 第 5 行露 42%**；**手势** `(527−114)/88 = 4.69` → 约 **5 整行**。20 张 = **4.5 屏 / 3.5 次划动**（汇报口径，按三键）。**首页不按张数考核**（只验「不滚动能否看到今天第一张」）：**3.28 行（三键）/ 3.56（手势）**，20 张 ≈ **6.1 屏**；§3 目标态 4 整行，**本轮不启用**。V1.9 首页 2 张/屏 ≈ 10 屏 / 9 次划动。**`6–7 行` 是"整屏纯列表"的裸除法（`568/88`，没扣底栏也没扣头），任何真实页面都不成立，不要写进验收。**

| 取舍 | 决定 | 依据 |
|---|---|---|
| 图 132→**64dp** | 让 | 认物由「英文 19sp + 中文 13sp」兜底；64dp 是记忆锚点，不是阅读主体 |
| 词头 19sp serif | **不允许放宽** | 这是「英文当主角」的唯一物理载体，网格正是死在这一条 |
| 心形缩 20dp / 隐藏 | **不允许** | 长按是**不可发现**手势，藏掉常驻心形 = 拿掉界面上唯一可见的收藏线索 |
| 卡底 + 描边 | 否决 | 满屏白方块 = 模板感；纸底直露才像词典/词表 |
| 3 列瀑布 / 横向卡带 | 否决 | 见上表 |

## 5. 手势与快捷操作地图

| # | 界面 | 手势 | 动作 | 反馈 | 误触保护 |
|---|---|---|---|---|---|
| 1 | 今天列表 | 单击行 | 进详情 | 行 `scale 0.99→1` 100ms + 整行 ripple | — |
| 2 | 今天列表 | **长按行 400ms** | 切换收藏 | 心形 `1→1.28→1` 320ms `Overshoot(1.6)` + 圆底 `ArgbEvaluator` 120ms + 触觉 | 400ms 阈值；位移 >8dp 取消（RecyclerView 原生）；成功后 600ms 内忽略重复触发 |
| 3 | 今天列表 | **单击心形**（右端 44dp） | 切换收藏 | 同上（短版 320ms） | 心形热区 44×44dp 与整行单击分离：`leftIcon` 不吞行点击 |
| 4 | 今天列表 | 双击 / 左滑行 | **不做** | — | 双击与 1、2 冲突；行左滑无法预期是"删"还是"下一个" |
| 5 | 今天/收藏 | 下拉 | 同步最新 | SwipeRefresh `brand_primary` + 完成时新行 stagger 淡入 | 下拉 >64dp 触发 |
| 6 | 统计条 | 单击 | 展开/收起明细 | 高度 44↔140dp 200ms + 箭头 180° | 整条 44dp，无子热区 |
| 7 | 详情 | 单击 ▶ | 播美音 / 再点停止 | 图标切 pause + `brand_primary` 脉冲 400ms×2 | 播放中再点=停止（不是重播），避免"点了没反应" |
| 8 | 详情 | 单击美 / 英 音标行 | 播对应口音 | 同上，仅该行高亮 | 整行 44dp 可点 |
| 9 | 详情 | 长按英文词 | 系统文本选择/复制 | 系统菜单 | 走 `textIsSelectable`，不自造长按 |
| 10 | 详情 | 下滑（`scrollY==0` 起 >80dp） | 关闭详情（P2） | 跟随位移 + 松手回弹 | 仅滚动在顶部时生效 |
| 11 | 详情 | **左 / 右滑** | **本轮不做**（见 §12） | — | 与 Android 10+ 边缘返回手势冲突，且需 `CardDetailActivity` 改持整个 id 列表 |
| 12 | 底部 Tab | 单击 | 切 Tab | 目标页 `alpha 0→1`+`translateY 8→0` 160ms；图标 `1→1.1→1` | — |
| 13 | 底部 Tab | 双击当前 Tab | 列表回顶 | `smoothScrollToPosition(0)` 250ms | 仅当前 Tab；300ms 内两次判定 |
| 14 | 收藏页 | 长按行 / 单击心形 | 取消收藏（行消失） | 心形反向 260ms + `announceForAccessibility("已取消收藏")` | 同 #2 |
| 15 | 全部记录 | 长按行 / 单击心形 | 收藏切换（行**不**消失） | 同 #2 | 同 #2 |
| 16 | 收藏页 | 聚焦搜索框 | 键盘 + 清除按钮 | — | 本地过滤，无网络态 |
| 17 | 设备页 | 单击行 | 重连 | 行内 18dp spinner 400ms | 连点合并 |
| 18 | 设备页 | 长按 400ms | 删除确认 | 触觉 + `AlertDialog` | 400ms + 弹窗二次确认（双重） |
| 19 | 全局 | 系统边缘返回 | 返回上层 | 系统 | 详情页无横滑，边缘手势回归系统独占 |

**单手收藏（终稿，两条路都留）**：① **主入口 = 长按整行 400ms**，热区 328×88dp（对比原 900dp² 的心形，命中率接近 100%）；② **可见入口 = 右端常驻心形**，视觉 30dp、热区 44×44dp、**单击即切换**——它是"能收藏"这件事在界面上的唯一常驻线索，因此**不做"仅已收藏才显示"**；③ **可发现性补强**：`totalFavorites == 0` 时列表首行上方插 36dp 提示「长按任意行即可收藏」，成功一次后写 `SharedPreferences` 永久隐藏；④ **误触成本 = 0**：两种入口都是可逆 toggle，误收藏再点/再长按即取消，不弹框、不 toast。⑤ 详情页顶栏 ♡ 48dp 为第三入口。
**快速切卡：本轮不做。** 详情页左右滑与 Android 10+ 系统边缘返回手势抢左右 24dp；且 `CardDetailActivity` 目前只持有单条 `EXTRA_CARD_ID`，做「上/下一张」需额外传整个 id 列表并处理列表失效，改动面超出本轮。**不引入「底部 ‹ 12/20 › 条」这类半成品分页控件**——留一条走不通的分页 UI 比没有更糟。切卡需求进 §12 + P2。

## 6. 详情页信息优先级重排

| # | 块 | 内容 | 高 | 首屏内 |
|---|---|---|---|---|
| 1 | **固定顶栏**（不滚动） | `btnBack` 48dp ／ 标题区（滚动前空）／ `btnFavoriteCard` 48dp | **48dp** | ✓ |
| 2 | `cardHero` | 🟡 **v2.5.2 改**：`ivDetailImage` 改为**按图片真实比例自适应**（`adjustViewBounds` + `fitCenter` + `minHeight 160dp` + 代码 `maxHeight`≈屏高 55%），并改读**全尺寸图**（`ImageStore.detailFile`）。旧口径「固定 216dp / 4:3，不做宽高比兜底」作废：那个框里放的是**列表封面的裁切图**，用户点开卡片看到的就是缩略图，且 2:1 的相机图被再裁一次 | 屏高 ≤55% | ✓ |
| 3 | 同上 | **`tvDetailEnglish` 英文词 `text_headword_xl` 34sp serif（第一行，最重要）**，**固定值**，`maxLines=2` + `ellipsize=end`，**不用 autosize**（理由见下） | 42dp | ✓ |
| 4 | 同上 | `tvDetailChinese` **中文名 17sp（`text_h2`）+ `sans-serif-medium` + `text_secondary`** + `tvDetailLangTag` pill。中文名是"释义"不是"标签"，15sp 会与列表行中文撞成同一层级 | 24dp | ✓ |
| 5 | 同上 | 美/英两行（`grpPhoneticUs`/`grpPhoneticUk`）：每行 44dp = 喇叭 `ImageButton` 44dp 热区（图标视觉 24dp、padding 10dp）+ 「美」/「英」12sp + 音标 `Text.Phonetic` **14sp**（`includeFontPadding=true`、上下 2dp） | 94dp | ✓ |
| 6 | `cardBasics` | 拼音 / 释义（`maxLines=3`+「展开」）/ 时间 | 弹性 | 部分 |
| 7 | `cardPhraseSection` | 短语拆解词砖（≥2 词才出现） | 弹性 | ✗ |
| 8 | `cardDictSection` | 英典（例句默认 **1 条** + 「全部 N 条」） | 弹性 | ✗ |
| 9 | `cardCnSection` | 汉典（**默认整卡折叠为 44dp 一行**） | 弹性 | ✗ |
| 10 | `btnDeleteCard` | 删除（描边 `danger` 48dp） | 48dp | ✗ |

**首屏预算（实测口径，360×640 视口 / content 自顶部起算）**
`6(paddingTop) + 44(顶栏) + 8(margin) + 216(图) + 20(内边距) + 42(词头单行) + 10 + 44(美) + 8 + 44(英) = **442dp**` → 音标最后一行底边在 442dp；再往下 `12 + 24(中文名) + 16 + 48(按钮行) = 100` → **542dp**，即两个播放入口下沿。
**验收基准 = 568dp**（详情是独立 Activity、无底栏），`568 − 542 =` **余 26dp**。**该基准已由工程保证**：9 个 Activity 根布局加 `fitsSystemWindows`，API 35 强制 edge-to-edge 时内容区照样钉回 568、API ≤34 行为不变 → **Android 14 / 15 同一套 dp 通用，不再有双值**（此前出现过的 `616` 是未消费 inset 的故障态，**已废，不得当基准引用**）。**词头 2 行 = `542 + 42 = 584 > 568` → 按钮行掉出 16dp**，这正是 P1-10 必须做的原因（**不是"物理上不会发生"**，是"今天就会发生、那台 API 35 机把它掩盖了"）。**结论：本轮一个回退杠杆都不动**，图 216 / 208 / 192 三档不启用，若后续加吸顶条再启用。❗早前文档里的 392 / 432 / 468 / 616 四组数全部作废（分别对应 140dp 图、2 行词头、只算到音标、未修 inset），以 **442 / 542 / 568** 为准（**`532` 是加法错误：`12+24+16+48=100` 不是 90**，已废止）。**468 那种「▶ 塞进音标行、取消独立播放行」的排法也不成立**——`activity_card_detail.xml` 里 `btnPlayChinese` / `btnPlayEnglish` 两个按钮都在，删掉就没有中文发音入口了；`516`（单独加播放行）/ `556`（换行与播放行同时）都只是中间值。
❗**层级反转的改动边界（已拍板）**：只动 ① `activity_card_detail.xml` 里两个 `TextView` 的**上下顺序与字号**；② `applyNames()` 的**取文顺序**（`lang.isNone` 时把名称写进 `tvDetailEnglish`，`tvDetailChinese` 置 GONE）。**数据来源一律不变**：`primary` 仍来自 `NameResolver.resolve()`，`secondary` 仍是固定英文名，不新增字段、不改 `NameLanguage` 判定、不动 `DetailNames` 结构。其余 id 全保留 → `CardDetailActivity` 的业务逻辑零改。

**英文词头：固定 34sp，不做 autosize（已拍板）**
```xml
android:fontFamily="serif"
android:textSize="34sp"
android:maxLines="2"
android:ellipsize="end"
```
理由：`app:autoSizeTextType="uniform"` 与 `wrap_content` + `maxLines=2` 组合时，autosize 会和 `maxLines` 互相算，低版本 support 库存在算出 0 行高的已知坑。`ellipsize="end"` 必须有——只有 `maxLines` 时超长短语会被硬切而没有省略号，看起来像数据坏了。
34 / 17（中文名）= **2.0 倍**，正好守住「英文视觉权重 ≥ 中文 2 倍」这条公理。长词实测走 `pair of scissors`（15 字符）真机 2 行验证。
**长词不降字号（已与 visual-director 统一口径）**：不采纳「按字符数在代码里降一档 30sp」——`eng-screens` 已按固定 34sp 落地并注释说明，再叠一层 `setTextSize` 等于给同一控件两条互相覆盖的规则；且 34sp 单行约容 18 个字符、两行约 35 字符，而本产品词头是物品名（`electric kettle` 15 / `pair of scissors` 16），**截断阈值几乎不触发**（⚠️这条结论**依赖「词头恒为物品名、单物体 ≤2 词」**；若产品改成收藏整句、用户自输短语或长词组，18/35 两阈值立即失效，必须重估内容分布，不能沿用「反正不触发」）。**两个失败模式同一个出口**：垂直溢出（词头 2 行越过 532dp 遮住播放行）由「音标行 ▶ 44dp 热区」保底发音；水平截断（`…`）由详情页下方「短语拆解」卡逐词看全兜底。仅当真机仍截到认不出，由工程提 lead 单点裁定，不作为默认行为。

**吸顶规则**：`scrollY ≥ 324`（= `cardHero` 底 − 顶栏 48dp，即 hero 完全滚出）时一次性切换，顶栏中部 120ms 交叉淡入「`tea tray` 17sp」+ 40dp ▶（默认美音）；`btnBack`/`btnFavoriteCard` 不动，**顶栏恒 48dp 不重排**；`scrollY < 300` 淡出（40dp 迟滞防抖）。**与「词头 2 行」不是互斥，只差 8dp**：吸顶条**替掉** 48dp 按钮行而非额外叠加 → `542 − 48 + 40 = 534 ≤ 568`（**净省 8dp**，1 行词头成立）；2 行词头 `534 + 42 = 576 > 568`，差 8dp，三个候选补法任选其一：① 吸顶条压到 40dp、内部热区溢出到 44（条本身不是热区）；② 图 216→196（**不建议**，相纸比例掉到 1.84:1）；③ 2 行词头时词头收到 38sp。**降级**：不做吸顶，顶栏常驻 40dp ▶（播放在任何时刻都是合法动作，但永久占标题位）。

**折叠规则**：释义 `maxLines=3` + 44dp「展开」；例句默认 1 条 + 「全部 N 条例句 ›」44dp；汉典默认 44dp 一行「汉语词典 ›」；短语拆解常显。→ 页面滚动长度约减 60%。

## 7. 状态机（首页六态，必须区分「没拍」和「没同步上」）

**适用范围：本章只覆盖首页。** 收藏页**不走这套**——它只有两个输入（有没有绑定、有没有收藏过），沿用现有三态、本轮已改文案：未绑定 → `cards_empty_unbound`；已绑定但一条没收藏 → `cards_empty_fav`（文案改为「在『今天』长按任一卡片即可收藏」）；搜索无结果 → `cards_no_result_fmt`。**不新增第四态**：收藏是纯本地数据，不存在"没同步上"，多一态就是误导。

**判定输入（全本地可得，零新接口）**：`boundDevices.isEmpty()` ／`allCards.size`（`loadLocal`）／`today.size` ／`BoundDeviceEntity.lastSyncTime`（**已有字段**，`BindRepository.markSynced` 已在写）／本次 `SyncResult`（内存态）／`ConnectivityManager` ／蓝牙扫描权限。

| 状态 | 判定 | 视觉表现 | 文案（工程直接用） | 动作 |
|---|---|---|---|---|
| **S1 加载中** | 首帧 + 本地 0 条 + 同步中 | **6 行骨架**（每行：64dp 缩略图灰块 + 两条文字灰条）：灰块用 `bg_skeleton`（`radius_sm` **10dp**、填充 token `placeholder`、无描边无渐变、`alpha 0.4↔0.8` 1200ms 线性循环）；**不显示文字** | — | 无；>3s 落 S2/S3 |
| **S2 没拍过** | `allCards==0` **且** 同步成功（`now-lastSyncTime<10min`） | 80dp 圆底 `ic_camera` + 2 行居中 | 「这台相机还没拍过照片。\n按一下快门，拍下的东西会出现在这里。」 | 未绑定→主按钮「配对相机」；已绑定→11sp「怎么拍 ›」(P2) |
| **S3 没同步上** | `allCards==0` **且**（本次失败 或 `lastSyncTime` 空/超 10min） | 80dp 圆底 `ic_cloud_off`（`text_tertiary`）+ 2 行 + 11sp 上次同步时间 | 「暂时连不上服务器，今天拍的可能还没传过来。\n上次同步：2 小时前」 | 主按钮「重试」52dp |
| **S4 今天还没拍** | `allCards>0 && today==0` 且同步成功 | **小空块**（非全屏）：56dp 圆底图标 + 1 行 + 文字链；上方统计条照常 | 「今天还没拍。以前的都在『全部记录』里。」 | 文字链「全部记录 ›」44dp |
| **S5 同步失败有缓存** | 本次 `SyncResult.Failure` **且** `allCards>0` | **顶部 44dp 提示条**（`bg_hint_warning`，`warning` 文字 + `ic_cloud_off` 20dp），**不遮挡不清空**列表；6s 后收起为 11sp 单行 | 「网络不太好，现在显示的是本机缓存（6 张）。」 | 条内 44dp「重试」；也可下拉 |
| **S6 离线** | 无网 **且** `allCards>0` | 同 S5 结构，但底 `bg_card_alt`、图标 `text_secondary`、**不用 `warning`**（离线不是错误） | 「当前没有网络，显示本机缓存。」 | 「重试」置灰；网络恢复 300ms 自动消失并静默同步 |
| **S7 未绑定** | `boundDevices.isEmpty()` | 沿用 `groupUnbound`（96dp 圆底 + `text_h2` + 说明 + 主按钮） | 「还没有配对相机」+ 现有 `home_pair_hint` | 主按钮「配对相机」→`ScanDeviceActivity` |
| **S8 权限缺失** | 蓝牙扫描/通知权限被拒（仅设备页、扫描页） | 44dp 提示条（`bg_hint_warning`）+ 44dp 按钮 | 「配对需要「附近的设备」权限，否则搜不到相机。」 | 「去开启」→系统设置 |

**「没拍」vs「没同步上」判定矩阵（本次核心修复）**

| | 同步**成功** | 同步**失败** |
|---|---|---|
| **本地 0 条** | S2「还没拍过照片」 | **S3「暂时连不上服务器，今天拍的可能还没传过来」**（← 现状错误地显示 S2 文案） |
| **本地 >0 条** | S4「今天还没拍」/ 正常列表 | **S5「显示的是本机缓存」**（← 现状只弹一个转瞬即逝的 Toast） |

三条硬规则：① 同步失败**永不清空**本地列表、不整页替换成空状态；② 失败态**必须**给「上次同步：X 前」（`<1min 刚同步 / <60min N 分钟前 / 今天 HH:mm / 昨天 / M-d`），用户据此判断数据新鲜度；③「重试」与下拉刷新走同一条 `sync(deviceId, lang, SyncScope.LATEST)`，无新接口。

## 8. 反馈与动效语义

| 动作 | 反馈类型 | 实现 | 时长 / 曲线 |
|---|---|---|---|
| 长按进入"待收藏" | 无（不预震） | — | — |
| **收藏成功** | 视觉+触觉 | 心形 `scaleX/Y 1→1.28→1` 320ms；圆底 `ArgbEvaluator` 从 `overlay_control` → `brand_accent` 120ms（图标常驻、不消失，故无 alpha 出现动画）；`performHapticFeedback(VIRTUAL_KEY)` | **320ms** `Overshoot(1.6)` |
| 取消收藏 | 视觉+触觉 | 心形短版 `scale 1→0.9→1` 260ms `Decelerate`；**不做颜色交叉淡入**（底色直接切回 `overlay_control`） | 260ms |
| 返回（←） | 视觉 | 按下 `scale 0.92`；Activity 用系统默认 transition | 100ms |
| Tab 切换 | 视觉 | 目标页 `alpha 0→1`+`translateY 8→0`；图标 `1→1.1→1` | **160ms** `Decelerate` |
| Tab 双击回顶 | 视觉 | `smoothScrollToPosition(0)` | 250ms 系统 |
| 下拉刷新进行中 | 视觉 | SwipeRefresh 22dp `brand_primary` | 系统默认 |
| 下拉刷新完成 | 视觉 | 新卡 stagger：`alpha 0→1`+`scale 0.96→1`，前 6 张每张 +40ms | 240ms/张 `Decelerate` |
| 音频播放中 | 视觉 | 喇叭 `tint→brand_primary` + `alpha 1↔0.55` 脉冲 ×2；主按钮文案→「停止」 | 400ms/循环 |
| 统计条展开/收起 | 视觉 | 高度 44↔140dp + 箭头 `rotation 0↔180` | 200ms `Decelerate` |
| 骨架屏 | 视觉 | `alpha 0.4↔0.8` | 1200ms `Linear` repeat |
| 设备重连 / 删除 | 视觉 | 行内 18dp `ProgressBar`；删除走 `AlertDialog` + 行收起 | 400ms 起 / 200ms |

**触觉实现约定（minSdk 23）**

| 场景 | 方式 | 说明 |
|---|---|---|
| 收藏 / 取消收藏 | `Vibrator` 12ms | **仅此一处**用 Vibrator（强确认）。API 26+ `VibrationEffect.createOneShot(12, DEFAULT_AMPLITUDE)`；23–25 `@Suppress("DEPRECATION") vibrator.vibrate(12)`。需 `<uses-permission android:name="android.permission.VIBRATE"/>` |
| 其余（长按删除、Tab 双击回顶、统计条展开到位） | `View.performHapticFeedback(...)` | 无需权限、跟随系统「触感反馈」开关，尊重用户关闭意愿 |

**全局约束**：动效入口先判 `ValueAnimator.areAnimatorsEnabled()`（API 26+）；<26 读 `Settings.Global.ANIMATOR_DURATION_SCALE`，为 0 时时长设 0（直接终态），禁止"关了动画反而更慢"。

## 9. 可达性与包容性

**44dp 热区清单**

| 控件 | 视觉 | 热区 | 备注 |
|---|---|---|---|
| 列表行（整行可点 / 可长按收藏） | 328×88 | **328×88** | 最大热区，替代原 900dp² 的心形 |
| 行内心形（单击切换收藏） | 30dp 圆底 | **44×44** | 常驻可点；与整行单击分离，不吞行点击 |
| 详情 `btnBack` / `btnFavoriteCard` | 24dp | 48×48 | |
| 详情音标行（美/英） | 全宽 ×44 | 全宽 ×44 | 喇叭在行内，整行可点；**淡底 `bg_pill_soft` 保留**（浅色约 1.1:1，读作极淡的条而非一个面，但它承担整行可点的可供性），**只是不再叫 chip** |
| 详情 ▶（吸顶态） | 24dp | 40×40 | |
| 统计条 / 底部出口 `btnAllRecords` | 全宽 | 44 高 | |
| 「展开」/「全部 N 条」/「汉语词典」行 | 文字 | ≥44 高 | `minHeight=44dp` |
| 提示条「重试」 | 40dp | 44×44 | 原 36dp 不足 |
| 首页 `btnDevicesEntry` | 18dp 图标 | **44×44** | 无文字，热区靠外层 FrameLayout 撑到 44；**单设备 `GONE`**（不可见即不可聚焦，无需另设 a11y 状态） |
| Tab 各项 / 设备行、设置行 | 图标22+文字11 / 56dp | ≥56 高 | BottomNavigationView 默认 / 整行 |

**不依赖颜色**

| 状态 | 除颜色之外还有什么 |
|---|---|
| 收藏 / 未收藏 | **未收藏 = `overlay_control` 半透明圆底 + 白色 `ic_heart_outline`；已收藏 = `brand_accent` 荧光圆底 + `text_on_accent` 实心心**（形状 + 填充 + 底色三重差异，去色仍可辨）+ TalkBack「已收藏」 |
| 美音 / 英音 | **「美」「英」文字标** + 固定位置（上=美，下=英）；蓝/玫红只作辅助 |
| 同步失败 / 离线 | **图标不同**（`ic_cloud_off` vs 无）+ 文案；失败 `warning`、离线 `text_secondary` |
| 连续天数 | **数字 + 「天」字**，不用红点/火焰 emoji |
| Tab 选中 | 药丸底 + 图标实心/描边差异（已有），不单靠颜色。⚠️ 选中图标/文字 tint 必须是 `text_on_accent`，**不能用 `brand_primary`**——深色下后者压在荧光药丸上只有 1.47:1，会糊成一片 |

**TalkBack 朗读顺序**

| 界面 | 顺序与实现 |
|---|---|
| 今天 | ①`groupTodayHead`「今天，12 张，3 分钟前同步」→②统计条「今天 12 张 6 个词，双击展开明细」→③**每一行一个焦点**（单列自上而下，不做行列矩阵）→④底部出口 |
| 行 | 合并为 1 个焦点：`contentDescription="茶盘，tea tray，14:32，已收藏"` + `android:focusable=true`，子 View 全 `importantForAccessibility="no"`（**心形除外**，它是独立可点焦点，`contentDescription="收藏"` / `"已收藏"`）；长按由系统 `ACTION_LONG_CLICK` 自动暴露，描述末尾加「双击并按住可收藏」 |
| 详情 | 顶栏(返回/收藏)→英文词→中文名→美音行→英音行→释义→短语→英典→汉典→删除。**音标必须读出来**：音标行整行的 `contentDescription="美式音标 /ˈtiː treɪ/，双击播放"` |
| 动效播报 | 收藏后 `announceForAccessibility("已收藏")`；同步完成播「同步完成，今天 12 张」；**同步失败不播报**（不打断读屏），只在提示条设 `accessibilityLiveRegion="polite"` |

**字号放大 1.3 倍（不崩）**

| 位置 | 必须 `maxLines` | 必须允许换行 | 其它 |
|---|---|---|---|
| 列表行 | 英文词头 19sp 1 行 `ellipsize=end` | 中文名 1 行 `ellipsize=end` | 行高 `minHeight=88dp` 撑开（1.3× → 约 104dp；行高实际由 64dp 缩略图主导，未必真涨；若涨则一屏少约 0.3 行，不设杠杆）；meta 默认 GONE |
| 今日头 | 「今天」1 行 | — | 头行 `wrap_content`+`minHeight=44dp`；`tvSyncState` 在 1.3× 时 GONE（宽度不足优先牺牲它） |
| 统计条 | 单行 `maxLines=1` `ellipsize=end` | — | 收起态只放「今天 X 张 · Y 个词」，本周/连续移入展开区 → 结构上不会溢出 |
| 详情英文词 | `maxLines=2` + `ellipsize=end` | — | **固定 34sp，不随系统字号放大**；长词截断而非缩字。中文 17sp → 34/17 = 2.0 倍 |
| 音标行 | 音标 1 行 `ellipsize=end` | 行高 `wrap_content`+`minHeight=44dp` | 增长由 `minHeight` 兜底，不裁切 |
| 释义 / 设置行 | 释义 3 行；行内 1 行 `ellipsize=end` | 释义 ✓ | 行高 `wrap_content`+`minHeight=56dp`，chevron `center_vertical` |
| 全局 | — | — | 禁止任何 `layout_height` 定高包文字（`fragment_devices.xml` 的 `btnAddMore=54dp` 需改 `minHeight`） |

## 10. 改动优先级

| 档 | # | 改动 | 文件 | 量级 |
|---|---|---|---|---|
| **P0** | 1 | **行式化**：`item_word_card.xml` 从 2 列卡重写为单列词典行（88dp 行 / 64dp 缩略图 / 19sp serif 词头 / 无卡底无描边 / divider 76dp）+ **长按整行收藏 + 心形 44dp 单击收藏** + 合并 contentDescription | `item_word_card.xml` / `WordCardAdapter.kt` | XML 重写 ~60 行（结构全变）；Kotlin +35 行（长按、ArgbEvaluator、a11y） |
| **P0** | 2 | 首屏重排：88dp 固定头 + `rvRecent` 改 **LinearLayoutManager 单列** + item 化提示条/底部出口 | `fragment_home.xml` / `HomeFragment.kt` | XML 重写 ~200 行；Kotlin 中（header/footer viewType 或 `ConcatAdapter`） |
| **P0** | 3 | 学习报告 → 44dp 统计条（可展开）+ 连续天数 | `fragment_home.xml` / `HomeFragment.kt` / `StudyStats.kt` | Kotlin +45 行（纯本地 `streakDays`） |
| **P0** | 4 | 六状态区分 + 同步失败提示条 + 「上次同步 X 前」 | `fragment_home.xml`(新增 `groupSyncBanner`) / `HomeFragment.kt` / `CardsFragment.kt` | Kotlin +70 行（`SyncResult` 需从 `showBound` 传到渲染层） |
| **P0** | 5 | 详情 hero 重排（英文 34sp 上移 / 中文 **17sp** `text_h2` 下调 / 图 **216dp** / 音标前置 / 删宽高比兜底） | `activity_card_detail.xml` + `applyNames()` 6 行 | XML 重排 ~130 行（id 全保留） |
| **P1** | 6 | 3 Tab + 改名 + 设备降二级 + 「我的 › 设备 › 我的相机」行 + **首页入口保留但精简**（18dp 图标 / 44×44 热区 / 去文字 / 删设备 ID 行） | `menu_bottom_nav.xml` / `MainActivity.kt`(3 处) / `fragment_home.xml` / `HomeFragment.kt` / `fragment_profile.xml`（含 6 行 `space_12`、4 处 `Text.Label`、5 条 `divider 48dp`） / 三语 strings | 中（5 文件，**无新 Activity**：复用 `DeviceListActivity`） |
| **P1** | 7 | 动效全套 + Tab 双击回顶 + 统计条展开 + 设备长按统一 400ms | `MainActivity.kt` / `WordCardAdapter.kt` / 新 `bg_skeleton.xml` / 各 Fragment | 中（+110 行，无新依赖） |
| **P1** | 8 | 详情：吸顶 mini 播放 + 折叠（释义/例句/汉典） | `activity_card_detail.xml` / `CardDetailActivity.kt` | 中（滚动监听 + 折叠 +55 行） |
| **P1** | 9 | a11y：卡片/音标 contentDescription、`announceForAccessibility`、定高改 `minHeight` | 各 XML + Adapter | 小 |
| **P2** | 10 | 行式**密度** A/B（行高 80 / 88 / 96 三档，先埋点：一屏可见数、翻屏次数）；完整报告 BottomSheet（7 天柱状，纯本地 View 自绘）；详情下滑关闭 / 「怎么拍」图解 | `fragment_home.xml` / 新 `dialog_week_report.xml` | 中 |
| **P2** | 11 | **详情左右滑切卡**：需先让 `CardDetailActivity` 持整个 id 列表（`EXTRA_IDS` + 当前 index），再解决与系统边缘返回手势共存 | `CardDetailActivity.kt` / `CardListActivity.kt` / `CardsFragment.kt` | 大（跨 3 文件 + 手势冲突验证），本轮不做 |

**明确不做**（超出能力边界，写进规格是为防止被排进需求）：App 触发拍照、远程遥控快门、云端收藏同步、语音跟读打分、账号体系。详见 §12。

## 11. 高风险交互与降级方案

| 风险 | 后果 | 降级方案 |
|---|---|---|
| 长按收藏**不可发现** | 用户永远不知道能收藏 | ①**右端常驻心形**（可见、可点、44dp 热区）承担主线索 ②`today` 为空时 36dp 提示条「长按任意行即可收藏」 ③详情顶栏 ♡ 保底 ④收藏页空态 `cards_empty_fav` 文案直接写「在『今天』长按任一卡片即可收藏」 |
| 吸顶监听抖动 | 数字跳变、闪屏 | 40dp 迟滞 + 一次性交叉淡化（不做逐像素联动）；再不行退化为顶栏常驻 ▶ |
| Tab 结构改动致状态丢失 | 重建后停在错误 Tab | `KEY_TAB` 存 `selectedItemId`（id 未改名，恢复逻辑零改）；恢复时仍校验该 id 是否存在，否则落 `nav_home` |
| 骨架屏 >3s 不消失 | 用户以为卡死 | 3s 硬超时落 S2/S3，骨架必须能被错误态覆盖 |
| 连续天数基于本机不全数据 | 用户觉得数字不对 | 展开区标注「基于本机记录」；不做跨设备/全局口径 |
| 收藏本地丢失 | 重装后全空，用户投诉 | 文案**禁止**出现「云同步 / 已保存到账号」；收藏页空态写明「收藏保存在本机」（见 §12） |
## 12. 本轮不做的交互（防止工程/验收误判为漏做）

| 项 | 为什么不做 | 替代方案（用户仍能完成这件事） |
|---|---|---|
| **详情页左右滑切卡** | 与 Android 10+ 系统边缘返回手势抢左右 24dp；且 `CardDetailActivity` 只持有单条 `EXTRA_CARD_ID`，分页要额外传整个 id 列表 + 处理列表失效，改动跨 3 文件 | 返回列表再点下一张（**不做**「‹ 12/20 ›」半成品分页条）。排期见 §10 P2 #11 |
| **语音跟读 / 发音打分** | 无该能力，也无该接口 | 只听不说：`▶` 播美音 + 两条音标行可反复试听 |
| **收藏云端同步** | 收藏是**纯本地字段**，云端无收藏接口 | 收藏即时生效、离线可用；重装会丢——文案已明令禁止承诺同步，收藏页空态写明「收藏保存在本机」 |

> 另有三项属于「能力边界外」，不列排期：App 触发拍照、远程遥控快门、账号体系（登录页为占位）。

## 13. 本轮实现对照表（写给接手的人）

> 这张表存在的唯一目的：**没有它，下一个人会以为 P0-4 漏做了。** 本轮是视觉/结构重构（V2.0 皮肤 + 行式列表 + 3 Tab），不含统计逻辑变更与同步链路改造。

| 条目 | 状态 | 未实现原因 / 归属 |
|---|---|---|
| P0-1 行式列表 + 长按收藏 + 心形两路 | **已实现** | `item_word_card.xml`：`minHeight=row_height_compact`、缩略图 64dp、30dp 心形 + 44dp 热区、padding 12，五个 id 未改名 |
| P0-2 首屏压缩 | **只做了一半** | ✅ `cardStats` 从 190dp 压到 **81dp**（`tvFavoriteHint`/`groupShootHint` 有数据时 GONE）、清掉 `home_shoot_hint`；❌ **88dp 固定头 + `RecyclerView` 独立滚动未做**——`fragment_home.xml` 仍是 `SwipeRefreshLayout > NestedScrollView > LinearLayout` 整页滚动、`rvRecent` 仍 `wrap_content`（20 条全量 measure）。头部 **214dp（常态）/ 266dp（空态）**，首屏 **3.28 行（三键）/ 3.56（手势）**、空态 2.69 行，而非 4 行（§3 已写验收值）。**下一轮**：换结构不改业务 |
| P0-5 详情 hero 重排（英文 34sp 第一行 / 音标前置 / 图 216dp 出血） | **已实现** | — |
| P1-6 3 Tab + 设备降二级 + 「我的 › 我的相机」 | **已实现** | — |
| P1-9 可达性（44dp 热区、虚线环空态、卡片 contentDescription） | **已实现** | 完整 a11y 清单里 `announceForAccessibility` 与 TalkBack 顺序校验留到下一轮 |
| **P1-10 中文发音行内入口**（已定档，修法已与 visual-director 钉死） | **未实现** | 词头 2 行时 `584 > 568`，按钮行掉出 16dp → **中文发音今天就是 0 入口**（英文还有音标行 ▶）。修法：`btnPlayChineseInline` 挂 `tvDetailChinese` 行尾——44dp 热区 / 24dp `ic_speaker` / tint 沿用 `text_secondary` / 复用 `@string/card_play_chinese`（**不新增 drawable、不新增 string，232 不变**）；**容器必须 `FrameLayout`**（横排会把中文名左移 22dp、破坏中轴居中）；**常驻显示**，不做"仅溢出时显示"（`ViewTreeObserver` 判屏内最不可靠的时刻正是词头 2 行时）。代价：中文行 24→**44dp**、栈 542→**562**（按 568 余 6dp）。⚠️**代价不止于 2 行词头**：1.3× 字体 + **1 行**词头也超（`574.6 > 568`，超 6.6dp）。**验收判据不是"按钮行是否可见"**，而是**两个语种的发音是否都能触达**（该判据已移交 `release/安装说明.md`，本文不双写）——大字体下按钮行不在首屏属预期，**不许当 bug 去"修"按钮行**，否则会把 P1-10 的收益改回去。顺带把「溢出态」讲成**结构说明**而非补丁：英文由音标行 44dp ▶ 承担、中文由中文名行承担，**两者都在按钮行之上**，所以溢出只影响按钮行、不影响任一语种发音（这条也解释了音标行为什么必须是 44dp 独立热区，别砍）。**下一轮** |
| **P0-3 连续天数 `streakDays`** | **未实现** | 需在 `StudyStats` 新增派生口径（去重日期、今天无记录从昨天起数、90 天上限），属统计逻辑变更，本轮不混进来。**下一轮** |
| **P0-4 六态状态机** | **未实现** | 是真 bug（同步失败时首页显示"还没拍过照片"），但要改 `HomeFragment` 的 `SyncResult` 传递链路，风险比视觉改动高一个量级。本轮保持与 v1.9 完全一致的行为，**下一轮单独做、单独验证** |
| **P1-7 动效全套**（列表 stagger / 收藏弹跳 / Tab 位移） | **未实现** | 本轮只有系统默认 ripple 与 M3 药丸过渡。**下一轮**（规格仍在 §8，可直接照做） |
| **P1-8 吸顶 mini 播放 + 折叠** | **未实现** | 依赖 P1-7 的滚动监听基础，**下一轮**（规格仍在 §6.2/§6.3） |
| P2（行式密度 A/B、周报 BottomSheet、下滑关闭、怎么拍图解） | **未排期** | 见 §10 P2 |
