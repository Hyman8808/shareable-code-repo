# 拇指相机 · UI 设计系统 V2.0「墨标 Inkmark」
> 取代 `docs/UI设计规范_V1.0.md`（V1.9.0）的**视觉层**。Token 命名沿用 `brand_primary` / `text_primary` / `radius_lg` 前缀，工程增量替换，不推倒重来。文中对比度均按 WCAG 2.1 公式实测 `(L1+0.05)/(L2+0.05)`，非估算。

## 1. 设计定位
**代号：墨标（Inkmark）｜一句话：把每一张单词卡当成一页词典词条 —— 墨色做骨架，荧光笔做标记，衬线体当主角。**
- **它解决**：V1.9 的「任何模板站都能生成」。判据很硬——把 V2.0 任意一屏截图丢进模板生成器，它复制不出来：这套界面没有渐变、没有彩色大按钮、没有满屏白圆角卡；辨识度全来自三件只有「人做过排版决策」才会出现的东西：**字体对立（衬线词头 vs 无衬线中文）+ 纸感底 + 荧光标记**。
- **它拒绝**：① 彩色主按钮（`#4C6FFF` 蓝 + 白字是 B 端通用语汇，不是品牌）；② 一屏三种以上圆角；③ 所有卡片长一个样；④ 靠加粗拉层级；⑤ 深色模式当反色做。

**三条推导公理**（后续条款由它们推出，冲突时按此裁决）：

| | 公理 | 可测判据 |
|---|---|---|
| A | **一屏最多两种表面** | 纸底 + 白卡；列表用「行」不用「卡」；单屏白卡总面积 ≤ 屏高 45% |
| B | **英文永远是主角** | 同屏出现英文时，英文视觉权重（字号 × 对比度）≥ 中文的 2 倍 |
| C | **荧光色只做填充** | `brand_accent` 永不做细线、不做浅底文字，只做「垫在深色字形下面的色块」 |

## 2. 候选方向与取舍
| | 方向 | 气质 | 代表色 | 致命风险 | 结论 |
|---|---|---|---|---|---|
| A | **墨标 Inkmark** | 印刷词典 / 编辑设计 / 克制的叛逆 | 墨蓝 `#172742` + 荧光黄绿 `#D9F24F` + 暖纸 `#F4F2EC` | 荧光色在浅底对比仅 **1.25:1**，只能做填充，误用即翻车 | **采用** |
| B | 暗房 Darkroom | 相机硬件同源 / 胶片 | 炭黑 `#131210` + 琥珀 `#E0A24C` | 主场景是通勤白天，深底户外反光下可读性最差；词典页动辄 6–10 行释义，深底长阅读疲劳；且与「夜间模式」形态重合，双皮肤被吃掉一个 | 放弃 |
| C | 潮流卡 Neon Flash | 潮玩 / 游戏化 / 高饱和 | 紫蓝渐变 `#6C4CFF→#22D3EE` + 24dp 圆角 + 玻璃卡 | 玻璃拟态要 `RenderEffect` blur（API 31+），minSdk 23 只能用半透明色假装、边缘发脏；荧光渐变弱光刺眼；且「大圆角＋渐变＋玻璃」正是 AI 生成 UI 的最高频产物，而目标用户恰好对此最敏感 | 放弃 |

**选 A 的三条具体理由**：① 只有 A 与「查词」这个产品本体发生关系（B 关系硬件、C 关系情绪），而学英语需要的是权威感不是娱乐感；② 只允许系统字体时，衬线/无衬线的对立比任何颜色都抗「模板感」——渐变、玻璃、图标字体全被砍掉后，字体排印是唯一还有纵深的地方；③ 落地成本最低：全靠 `colors.xml` + `styles.xml` + 3 个新 drawable，而 C 需要 blur、B 需要重做全部图片蒙层，同工期下 A 的可见变化最大。

## 3. 色彩系统
唯一来源 `values/colors.xml` + `values-night/colors.xml`（只覆盖同名）。布局内不得出现 `#RRGGBB`（现状 0 处，保持）。

### 3.1 亮色
| Token | HEX | 用途 | 实测对比度 |
|---|---|---|---|
| `brand_primary` | `#172742` | 主按钮底、词头/图标色、链接、选中态 —— **墨** | 纸底 **13.35:1** / 白卡 **14.94:1** |
| `brand_primary_hover` / `_light` / `_soft` | `#0E1B30` / `#E7EAF2` / `#D9E0EE` | 按下态 / 浅底块（提示条、选中底）/ ripple | hover 17.24:1；浅底上墨字 **12.42:1** |
| `brand_accent` / `accent_soft` | `#D9F24F` / `#F1F8C8` | **荧光笔**：导航选中药丸 / 已收藏圆底 / 今日标记条；浅底 | 仅填充；其上墨字 **11.93:1** / 13.52:1 |
| `accent_graphics` | `#7C8F14` | 需 ≥3:1 的荧光**图形**（进度条、柱） | 纸底 **3.24:1** ✔ |
| `text_primary` | `#1A1917` | 正文 / 标题 | **15.69:1** / 17.57:1 |
| `text_secondary` | `#57534A` | 次级正文、中文释义 | **6.84:1** / 7.66:1 |
| `text_tertiary` | `#6E695E` | 11–12sp 辅助、音标、meta | **4.88:1** / 5.46:1 ✔ |
| `text_disabled` | `#A8A29A` | 仅禁用图形；**不得用于 placeholder** | 2.26:1 |
| `text_on_brand` / `text_on_accent` | `#FFFFFF` / `#172742` | 墨底字 / 荧光底字 | 14.94:1 / 11.93:1 |
| `bg_page` | `#F4F2EC` | 页面底＝暖纸（H42° S20% L94%），状态栏/导航栏同色 | — |
| `bg_card` / `bg_card_alt` | `#FFFFFF` / `#F7F5EE` | 白卡（仅「浮起层」用）/ 卡内嵌块、输入框、搜索框 | 卡 vs 纸 1.12；alt vs 卡 1.09（仅填充） |
| `divider` / `stroke_soft` / `stroke_strong` | `#E6E2D8` / `#E4E0D6` / `#D3CDBE` | 行间分隔 / 卡描边 / 虚线环 | 1.29 / 1.18 / 1.42（仅分隔用途） |
| `success` / `warning` / `danger` | `#16794F` / `#8A5200` / `#B4281C` | 成功 / 前提条件提醒 / 删除·错误 | 5.41 / 6.39 / 6.44 ✔ |
| `phonetic_us` | `#172742` | 美音（＝墨，默认口音不吃专属色） | 14.94:1 |
| `phonetic_uk` | `#8E3450` | 英音（酒红：老派/传统，非语义色） | **7.62:1** ✔ |
| `phonetic_us_soft` / `phonetic_uk_soft` | `#E7EAF2` / `#F7E8EE` | 音标 chip 底（旧组件保留） | 其上字 12.42 / 6.43 |
| `overlay_control` / `_active` / `photo_dim` | `#59000000` / `#F2FFFFFF` / `#00000000` | 图片上圆钮底 / 激活态 / 亮色不加遮罩 | — |

> **`brand_accent` 只有三个合法场合，第四处出现即违规**：① 底部导航「当前在哪」的药丸；② 「已收藏」的心形圆底；③ 「今天」的标记条与高亮划条。

### 3.2 深色（按睡前躺读单独调，不是反色）
| Token | 亮 → 深 | 深色取值理由 & 实测 |
|---|---|---|
| `bg_page` | `#F4F2EC` → `#14130F` | 不用纯黑也不用蓝黑：**暖调近黑（H45° S15% L7%）**，蓝光成分低，OLED 边缘不发蓝 |
| `bg_card` / `bg_card_alt` | `#FFFFFF` → `#1D1B16` / `#242119` | 白卡降到 L11%，夜间不再是一块「发光的纸」 |
| `text_primary` | `#1A1917` → `#E9E4D9` | 暖白 L91%，不用纯白——睡前减眩光的第一支 |
| `text_secondary` / `_tertiary` / `_disabled` | → `#B4AE9F` / `#928B7C` / `#6B665C` | 7.78:1 / **5.08:1** ✔ / 3.02:1（仅禁用） |
| `brand_primary` | `#172742` → `#C3CFE4` | **明度翻转 + 降饱和**：S 48%→25%、L 17%→83%。墨色在暗底不能用作面（会消失），翻转成「纸蓝」，同时兼作按钮底与文字色，10.95:1 |
| `brand_primary_light` / `_hover` | → `#262C36` / `#D6E0F0` | 其上 `brand_primary` **8.93:1** |
| `text_on_brand` | `#FFFFFF` → `#12151C` | 墨底白字 → 纸底墨字，**11.62:1** |
| `brand_accent` / `accent_graphics` / `text_on_accent` | → `#9FB44B` / `#C9DE6A` / `#14130F` | 荧光**降饱和降亮度**：S 88%→41%、L 63%→52%（原亮度夜间会「发光」）；7.02–8.06 / 12.52 / 8.06 |
| `phonetic_us` / `phonetic_uk` | → `#C3CFE4` / `#D08FA6` | 10.95:1 / 6.69:1 ✔ |
| `divider` / `stroke_soft` / `stroke_strong` | → `#262420` / `#2A2721` / `#3A362D` | 卡上仅 1.11:1 —— 暗色下表面色差天然更小，分隔**一律靠 hairline，不靠色块差** |
| `success` / `warning` / `danger` | → `#63C99B` / `#D9A65C` / `#E8796C` | 9.17 / 8.46 / 6.53，全部降饱和 |
| `photo_dim` | → `#40000000` | **睡前专属**：图片叠 25% 黑。夜间照片高光是最大的刺眼源，V1.9 完全没做 |

**深色三条硬规则**：① 不出现纯白（最亮 `#E9E4D9`）；② 主色不做「提亮」，做「明度翻转 + 降饱和」；③ 全局正文对比由 17.6:1 主动降到 13.6:1 —— 仍远超 AA，但眩光显著降低。

## 4. 字体系统
可用族：`serif`（→ Noto Serif）/ `sans-serif` / `sans-serif-medium` / `sans-serif-condensed`，无自定义 ttf。

### 4.1 字阶表（12 阶，唯一合法来源，表外字号一律不允许）
| Token | 字号 | 行高 | 字族/字重 | 字距 | 用在哪 |
|---|---|---|---|---|---|
| `text_headword_xl` | **34sp** | 40sp | `serif` regular | -0.02 | 详情页英文词头（主角） |
| `text_headword_xl_cjk` | **30sp** | 38sp | `sans-serif-medium` | +0.01 | 词头为中文时的替身 |
| `text_headword` | **19sp** | 24sp | `serif` regular | -0.01 | 列表行英文词 |
| `text_headword_cjk` | **15sp** | 20sp | `sans-serif` | 0 | 列表行中文释义 |
| `text_phonetic` | **14sp** | 20sp | `serif` regular | +0.02 | 音标（含 IPA） |
| `text_metric` | **30sp** | 32sp | `sans-serif-condensed` | -0.02 | 今日/本周数字 |
| `text_h1` | **22sp** | 28sp | `sans-serif-medium` | -0.01 | 页面标题（原 24sp 收一档） |
| `text_h2` | **17sp** | 24sp | `sans-serif-medium` | 0 | 区块 / 分区标题 |
| `text_body` / `_sm` | **15sp** / **13sp** | 24sp / 20sp | `sans-serif` | 0 | 释义正文 / 次级正文、按钮小号 |
| `text_caption` | **12sp** | 18sp | `sans-serif` | 0 | 说明、时间 |
| `text_label` | **11sp** | 16sp | `sans-serif-medium` | **+0.06** | 分组标题、单位、meta |

### 4.2 三个必须回答的问题
**① 英文单词 → `serif`（Noto Serif）regular，不加粗。** 形状辨识：衬线体有真实粗细对比，词形轮廓（ascender/descender/x-height 构成的剪影）比无衬线清晰，背单词时「扫一眼认形」是实际收益；权威感：用户认知里「词典＝衬线体」（朗文/牛津/MW 的 headword 全是），零成本语义借用；张力：中文用 `sans-serif-medium`，整页形成「两种字体系统」，模板生成器只会给同一字体加粗减细。**不加粗**的硬理由：`textStyle="bold"` 在无真 Bold 时是合成伪粗，会在 serif 细笔画外描一圈边，e/a/g 内腔糊死；34sp regular 视觉重量已足够。**长词兜底**：固定 **34sp**（**不用 autosize**：`wrap_content` 高度的 TextView 不支持，且与 `maxLines` 同用会在低版本算出 0 行高）+ `maxLines 2` + `ellipsize=end`。**默认不按字符数降档**——本产品的词头是"物品名"（`electric kettle` 15 字符、`pair of scissors` 16 字符），34sp 单行约容 **18** 字符、两行约容 **35** 字符，基本不会触发（⚠️ 该结论依赖「词头恒为物品名、单物体 ≤2 词」；若改为收藏整句 / 用户自输长短语，18/35 两阈值立即失效，须重估内容分布，不能沿用「反正不触发」），加一条按长度 `setTextSize` 的规则等于给同一控件两套字号、按 30sp 也未必省下一行。截断风险的真实兜底是详情页下方的「**短语拆解**」卡（多词短语可逐词看全）+ `…` 的显式提示；若真机上仍出现词头被截到认不出，由工程提 lead 单点裁定，**不作为默认行为**。**中文词头不套 serif**（会落到 NotoSerifCJK 或直接回退 sans，字重不可控）。

**② 音标怎么排才不糊、不裁切。**

| 项 | 规定 | 原因 |
|---|---|---|
| 字族 | **`serif`**，禁 `sans-serif-condensed` | condensed 把 `ˈ ˌ` 压成窄钩、`ː` 压成细竖线，14sp 下直接糊 |
| 字号 / 行高 | **14sp**（下限 13sp）/ 20sp（×1.42） | 12sp 时 `ə ɪ ʊ` 开闭口差异丢失；`ˈ` 在 cap height 之上、`g ʃ` 有下伸部 |
| 内边距 | `includeFontPadding="true"` + 上下各 2dp | 关掉 FontPadding 会裁掉 `ˈ` 上半截——这是「音标被切掉」最常见的原因 |
| 字距 / 包裹 | `letterSpacing 0.02`；前后加斜杠 `/.../`（斜杠 12sp `text_tertiary`） | `ˈ` 与后接元音易粘连；斜杠把音标框成整体，与释义区分 |
| 其他 | 禁 `textAllCaps`、禁 bold | 无意义且会放大缺陷 |

**③ 中文不加粗怎么拉层级 → 字号跃迁 ≥3sp + 颜色 4 阶 + 字距正负。** 字号 15→17→22→30，每跳 ≥3sp（V1.9 的问题正是 17→20 只差 3sp 又同色同重，看不出层级；同层级两个元素**不允许**只差 2sp）。颜色 15.7:1 → 6.8:1 → 4.9:1，每级对比度差 ≥2:1。字距：`text_label`（11sp）用 **+0.06** 让「标签感」从间距里出来，标题（22sp）用 **-0.01** 收紧——这一对正负号是替代加粗的关键手段。

**旧字阶映射**：`text_display`30→`text_headword_xl_cjk`30；`text_h1`24→22；`text_h2`20→`text_h2`17；`text_h3`17→`text_headword_cjk`15 或 `text_h2`17（按是否标题分流）；`text_body`/`_sm`/`text_caption` 同名保留；`text_micro`11→`text_label`；`text_metric`28→30 condensed。

## 5. 空间与形状
**间距**（4dp 基准，垂直节奏取 8 的倍数）：`space_2/4/6/8/12/16/20/24/32/40/56`。页面 `page_horizontal 20dp`（详情 16dp，主卡自带 20dp 内边距）、`page_top 12dp`、`page_bottom 28dp`。组内 8/12，组间 24/32；「区块之间」用 24，「卡片之间」用 12。

**圆角 6 档**（尺寸越大圆角越大，一屏最多出现 3 种）：`radius_xs 6dp`（色点容器、极小标签）/ `radius_sm 10dp`（**行内缩略图**、Ghost 按钮）/ `radius_md 14dp`（主次按钮、输入框、卡内嵌块、详情页图）/ `radius_lg 20dp`（白卡、详情主卡）/ `radius_xl 28dp`（sheet、弹窗）/ `radius_pill 999dp`（chip、导航药丸、搜索框）。
> 与 V1.9 的差别：主卡 16→20、行内图 12→**10**。**差距被拉开一倍**——大卡更有体积、小图更像贴纸；V1.9 的 12/16 挤在一起，等于没有层级。

**描边 vs 阴影**（规则：能用留白解决就不用描边，能用描边解决就不用阴影，阴影只留给真浮起来的那一层）：

| 场景 | 1dp 描边 | 阴影 | 说明 |
|---|---|---|---|
| 页面底 / 列表行 | ✗ | ✗ | 列表行靠 `divider`（`marginStart 76dp`，对齐文字不切图）分隔 |
| 白卡（普通：今日卡、分区卡） | ✓ `stroke_soft` | ✗ | 白卡与纸底仅 1.12:1，边界只能靠描边 |
| 白卡（浮起：详情主卡） | ✗ | ✓ `2dp` | 阴影是更强的边界，同时表达「浮在内容上」 |
| 底部导航 | ✓ 顶部 hairline | ✗ | 与页同色，靠 hairline 切割；V1.9 的 8dp 阴影在纸底上会留一圈灰边 |
| 弹窗 / sheet | ✗ | ✓ `8dp` | 唯一允许大阴影的地方 |
| 输入框 / 图片上圆形控件 | ✓（focus 2dp 墨）/ ✗ | ✗ | 后者用 `overlay_control` 半透明圆做底 |

⚠️ `outlineAmbientShadowColor` 需 API 28+，minSdk 23 上阴影是系统黑 → 阴影只用于大面积浮层（弹窗、主卡），不用在小组件上。

**密度两档**：

| | `compact`（首页今天 / 全部记录 / 收藏 Tab / 搜索） | `comfortable`（详情页 / 空状态 / 收藏卡） |
|---|---|---|
| 行高 / 容器 | **88dp**（中文名缺失时 76dp，不留空行） | 自适应 |
| 缩略图 | 64×64 `radius_sm` | 全宽 **216dp**（360dp 宽下 1.67:1），**出血到卡边**，只有外侧圆角 |
| 英文 / 中文 | 19sp serif / 15sp secondary | 34sp serif（固定字号，**不降档**）/ 15sp secondary |
| 音标 | 13sp 单行（美音优先） | 14sp × 2 行（美/英，缺则整行隐藏），**每行 44dp 独立热区** |
| 收藏 / 卡底 | 30dp 圆（热区 44）/ **无卡底**，纸底直接排 | 36dp 圆（热区 44）/ 白卡 `radius_lg` + `elevation 2dp` |
| 首屏可见行数（**全文档只用一条基数链，每一项带名字**：`屏高 − 24(状态栏) − 48(三键) / 24(手势) − 64(底栏 `nav_bar_height`) − 1(hairline) − 头部 = 列表可用` → 三键 **503** / 手势 **527**；行距 **88** —— `RowDividerDecoration` 不改 `getItemOffsets`、线画在行底边上，**行高仍是 88**，「89」是从"线要占位"的直觉来的，别加回来） | 首页头部 **214dp（逐项可复算，照 `fragment_home.xml` 直接加）**：`8(page_top) + 44(顶栏) + 15(tvDeviceMeta) + 8(gap) + 81(今日卡) + 56(区块头 12+44) + 2(rvRecent mt) = 214`；今天 0 张时 `groupShootHint` +52 → **266dp 空态**。`(503−214)/88 = 3.28` / 手势 `3.56` → **门槛 ≥3 整行**；800 档 5.10 / 5.38 → **≥5 整行**；空态 `(503−266)/88 = 2.69` → **≥2 整行**。收藏 / 全部记录：固定头 114dp → `389/88 = 4.42` / 手势 `4.69` → **4 整行 + 第 5 行露 42%（手势档约 5 整行）**；纯列表无头部上限 **5.7 行** | — |

## 6. 组件视觉规格
**6.1 单词卡（列表行 `compact`，首页 / 收藏 Tab / 全部记录三处共用）**

```
[64×64 r10 图]  electric kettle        ← 19sp serif text_primary
                /ɪˈlektrɪk ˈketl/      ← 13sp serif text_tertiary   [♡ 30dp 热区 44]
                电水壶                  ← 15sp sans text_secondary
```
- 容器 88dp，`paddingHorizontal 20dp`，背景**透明**（纸底直接露），`foreground` 用**方形** ripple（不再是卡，不用圆角 ripple）。
- 图 64×64 `radius_sm` + `clipToOutline`；收藏 `centerVertical`：未收藏 = `overlay_control` 圆底 + 白色 `ic_heart_outline`；已收藏 = `brand_accent` 圆底 + `ic_heart_filled`（tint `text_on_accent`）。
- 行间 `divider` 1dp `marginStart 76dp`；**去掉 V1.9 每卡 12dp 的外边距**（一屏少看一条）。
- 收藏 Tab 与首页同构：**单列 88dp 行 + 64dp 缩略图**，不做 2 列网格——行式才放得下 19sp 衬线词头。**密度口径（可复核）**：页面可用 **503dp**（屏高 − 24 − 48 − 64 − 1 hairline，见 §5 基数链；全文档只用这一条），收藏 Tab 固定头 **114dp**（页内标题 32 + 搜索 44 + 上 14 / 标题下 14 / 下 10 的 padding），列表区 **389dp ÷ 88 = 4.42** → **4 整行 + 第 5 行露 42%**，对比 V1.9 的 132dp 图竖卡（单卡约 220dp，首屏 2 张）≈ **翻倍**；首页不按行数考核——它首屏是"今天概览"（头部 **214dp（逐项可复算）**、今天 0 张时 **266dp 空态**），本就只露 3 行，密度指标由收藏 Tab 承担（**283 / 351 / 353 / 220 / 272 / 216 / 268 / 210 / 262 / 96 / 98 全部作废**，错因各别：210 漏 4dp、216 把 `rvRecent` 的 2dp 当 4dp；**现行只有两个数：常态 214、空态 266**，今日卡 `wrap_content` 实测 81）。心形**常驻显示**（右侧 44dp 全是热区、不占文本宽度；长按收藏是不可发现的手势，收起未收藏态的心等于拿掉唯一可见线索）。

**6.2 详情页主卡（`comfortable`）**
- 白卡 `radius_lg 20dp` + `elevation 2dp` + **无描边** + `clipToOutline="true"`，`padding 0`。
- 图片 **出血**：`match_parent × 216dp`（360dp 宽下 1.67:1，对 4:3 源图的裁切在可接受范围，**不做 fitCenter 例外分支**），靠 `clipToOutline` 只裁外侧四角 —— 取代「白卡里塞一张缩进小图」的关键动作，现在它像一张相纸，不像文件卡片。
- 文字区 `padding 20dp`：英文词头 34sp serif 居中 `maxLines 2`（**无额外 mt**，靠文字区 paddingTop 20dp）。**不用 autosize**——AppCompat 的 autosize 要求宽高有界，`wrap_content` 高度不支持；且与 `maxLines` 同用会在低版本算出 0 行高（工程已实测）。改为**固定 34sp + `maxLines 2` + `ellipsize=end`，默认不降档**（理由与兜底见 §4.2）；`maxLines 2` 必须保留，否则长词占 3 行以上会挤爆首屏。→ 音标区（mt 10dp，两行各 44dp + **8dp** gap = **96dp**，见下）→ 中文 **17sp `text_h2`** `sans-serif-medium` `text_secondary`（mt 12dp，行高 24dp —— **不用 `text_headword_cjk` 15sp**，那是列表行中文释义的档，会让详情页中文与列表行撞成同一层级）→ 按钮行（mt **16dp** = `space_16`）。
- 音标单行 **高 44dp（独立热区，两行 44×2 + 8dp gap（`space_8`）= **96dp**；不可压成 26dp，否则热区掉到 26 违反 44 底线）**：`6dp 圆点(phonetic_us/uk) + 4dp + "US" 11sp label 同色 + 10dp + /音标/ 14sp serif + weight=1 + ▶ 44dp`。**取消 V1.9 的音标 chip 底**——chip 是「圆角块」语汇，一屏多了就像后台表单；改成「色点 + 小字标签」，保留双色编码又减掉一整类圆角矩形。
- 播放：**1 个** Primary（英文 52dp）+ 1 个 Ghost（中文，无底墨字）。删除用 Ghost + `danger` 字色，不用实心红。

**6.3 首页顶部**

```
[设备名 22sp text_h1]                                  [bt44]   ← 顶栏 44dp，page_top 12dp
[c8586a630000 · 11月6日 周三   11sp label +0.06 tertiary]
┌ 白卡 r20 e2dp p20 ─────────────────────────────┐
│ ▍今天                            查看报告 →    │ ← 3dp×16dp accent 竖条 + 11sp label
│ 12 张      5 词      2 收藏                    │ ← 30sp condensed 墨 + 11sp tertiary 单位，列间 1dp×28dp
│ ─────────────────────────                     │
│ 本周 48 张 · 17 词                12sp tertiary│
└───────────────────────────────────────────────┘
[提示条 40dp] [今天拍的 · 5   全部 →] [列表行 ×N]
```
- 相对 V1.9 省约 24dp 纵向空间（顶部 14–16→12、大标题下 18dp 空隙并入今日卡间距）。
- 指标数字**去掉主色**（V1.9 第一个数字是蓝的，一眼只能看到一个数）；三个数字同为墨色，靠 condensed 窄比例形成「仪表盘」感。提示条从大色块降级为一行 40dp。
- **首页顶栏右侧只有 1 个图标**：`btnDevicesEntry`，44×44 热区、18dp `ic_bluetooth`、tint `brand_primary`、`contentDescription=@string/home_devices_btn`，点击进 `DeviceListActivity`；**显隐随设备数**：`visibility = if (devices.size > 1) VISIBLE else GONE`（单设备时"看的是哪台"不存在，省 44dp）。**不做"点一下换下一台"的轮换**：轮换没有可预期性（不知道下一台是哪台、一共几台），还得在切 Tab / Activity 重建 / 切语言三处维护 `deviceIndex`，是给低频场景引入状态机；"有得选"时就该看见完整列表。**不放相机、不放设置**——App 不能触发拍照（快门在硬件上）、设置页此时不存在（语言/诊断/关于都在「我的」里）；给不存在的功能画入口，用户点下去只能得到"没反应"或一个 toast，比留空更伤。
- 设备名/deviceId 留在顶栏（属「当前上下文」）：deviceId 与日期**合并成一行 11sp 副行**（`c8586a630000 · 11月6日 周三`），不额外占一行；它与「我的 › 我的相机」同名不同职——首页＝上下文，我的＝配对/删除/重命名。

**6.4 底部导航**（**3 项**：今天 / 收藏 / 我的——`nav_devices` 已删，设备管理降为「我的 › 我的相机」二级页；高 58dp + 手势 inset，底色 `bg_page`，顶部 1dp `divider`，`elevation 0`）

| 状态 | 图标 | 文字 | 背景 |
|---|---|---|---|
| 未选中 | 描边版 22dp，tint `text_tertiary` | 11sp regular，`text_tertiary` | 无 |
| 选中 | 同一个描边版 22dp，tint `text_on_accent` | 11sp **medium**，`text_on_accent` | **荧光药丸 40×26 `radius_pill`，`brand_accent`** |

三重编码：**荧光药丸**（块）+ **`text_on_accent` 深色 tint**（色）+ **`sans-serif-medium` 字重**（重）；其中不靠颜色的有两重（药丸 + 字重），所以色弱用户和深色模式下仍能区分布当前项。
⚠️ **不做实心/描边双版本**：M3 `BottomNavigationView` 只有 `itemIconTint`，要让选中态换图就必须在 `setOnItemSelectedListener` 里逐项 `menu.findItem(id).setIcon(...)`，切 Tab / 重建 / 深色切换三处都得跟着同步——为一点视觉增量引入状态同步代码，风险大于收益。因此**全 App 只维护一套描边图标 22dp，选中态只靠 `nav_item_color` 的 `state_checked` 切 tint**（`ic_nav_devices` 随 Tab 删除、文件保留不删）。选中 tint **必须**是 `text_on_accent`：深色下 `brand_primary`(#C3CFE4) 压在荧光药丸(#9FB44B) 上只有 **1.47:1**，会糊成一团。3 项时每项等分约 120dp，药丸与 M3 均分布局都无需额外改动。

**6.5 按钮 / 输入 / 搜索 / chip / 空状态 / 提示条**

| 组件 | 尺寸 | 底 / 描边 | 字 | 状态 |
|---|---|---|---|---|
| **Primary** | 52dp `radius_md` | 实心 `brand_primary` | 15sp medium `text_on_brand` | 按下 `_hover` + scale 0.98/120ms；禁用 40% alpha；一屏 ≤1 个 |
| **Secondary** | 44dp `radius_md` | `brand_primary_light` | 13sp medium `brand_primary` | 按下 `_soft` |
| **Ghost** | 36dp `radius_sm` | 无底（可选 1dp `stroke_soft`） | 13sp medium `brand_primary`（危险版 `danger`） | ripple；禁用 `text_disabled` |
| **Icon** | 热区 44 / 图形 24 | 圆形 ripple | — | 返回、设置、播放 |
| **输入框** | 52dp `radius_md` | `bg_card_alt` + 1dp `stroke_soft`；focus **2dp 墨** | 15sp，hint `text_tertiary` | error：2dp `danger` + 下方 12sp 错误行；标签 11sp `text_label` 上移 6dp |
| **搜索框** | 44dp `radius_pill` | `bg_card_alt`，**无描边** | 14sp，icon 18dp tertiary | 有描边会像表单，搜索框不能用描边 |
| **Chip** | 28dp `radius_pill` px10 | 未选 `bg_card_alt` / 选中 `brand_primary` | 11sp medium +0.04 | 未选 secondary / 选中 `text_on_brand` / 禁用 40% |
| **设置行**（我的页 6 行 + 新增「我的相机」） | 56dp | 无底无描边，靠 `divider` | 15sp `text_body` 主字 + 右侧 12sp `text_secondary` 当前值 + 16dp chevron | 图标 20dp `text_secondary`，图标↔主字 **12dp**（V1.9 的 14dp 无对应 token，顺手统一），`divider` `marginStart 48dp`；组标题用 `Text.Label`（11sp **+0.06**），不用旧 `text_micro`/0.04 |
| **空状态** | 88dp 环 + 15sp 文案 | **1dp 虚线环** `stroke_strong`（dash 4/5dp），非实心圆 | 图标 32dp tertiary，文案 15sp secondary ≤2 行 | 可选 1 个 Ghost 按钮；**禁止**单行灰字 |
| **提示条** | ≥40dp `radius_md` | `brand_primary_light` + 左侧 **3dp 竖条**（`brand_primary`/warning/danger） | 13sp，字色＝竖条色 | 竖条取代整块彩底，信息量不变、面积减半 |

> 空状态的虚线环是本体系第二个「签章」：它表达「这里等着被填满」，而不是「这里出错了」——V1.9 的实心浅圆看起来像加载失败。
## 7. 动效规范
时长：微交互 120ms / 元素进出 240ms / 转场 320ms / 庆祝 420ms。曲线：标准 `PathInterpolator(0.4,0,0.2,1)`（XML 近似 `fast_out_slow_in`）、进入 `PathInterpolator(0.2,0,0,1)`（XML 近似 `decelerate`）、庆祝 `OvershootInterpolator(2.2f)`。总则：`ANIMATOR_DURATION_SCALE==0` 时必须全部跳过；离屏 item 不播。

| # | 动效 | 参数 | 落地要点 |
|---|---|---|---|
| 1 | 列表进屏 | `alpha 0→1` + `translationY 12dp→0`，240ms 进入曲线，stagger `32ms × min(index,6)` | ViewHolder **首次** bind 播（打 `tag` 标记，复用不重播）；不要用 `layoutAnimation`（滚动会反复触发） |
| 2 | 收藏弹跳 | `1→0.86`(80ms) → `0.86→1.12`(160ms, Overshoot2.2) → `1.12→1`(180ms)，共 420ms；圆底 120ms `ArgbEvaluator` 过渡 `overlay_control→brand_accent`，图标 120ms 交叉淡入 | 取消收藏只播 `1→0.9→1`，不播颜色过渡（避免「高亮一下又消失」的噪音） |
| 3 | 详情页进入 | 主卡 `alpha 0→1` + `translationY 16dp→0` 320ms；图片独立 `scale 1.04→1.0` 420ms | `windowBackground` 必须是 `bg_page`，否则入场瞬间闪白 |
| 4 | Tab 切换 | 内容 `alpha 0→1` + `translationX ±8dp→0` 180ms；药丸 `scaleX 0.6→1` 160ms Overshoot1.6；图标 `1→1.08→1` 200ms | listener 里驱动，Fragment 用 `setCustomAnimations` |
| 5 | 播放脉冲 | 喇叭 `scale 1→1.15` 900ms `REVERSE` 无限；外圈两环 `alpha 0.28→0` + `scale 1→2.2` 1200ms，`startDelay` 0/300 | 播放结束或 `onPause` 必须 `cancel()` 并复位 scale1/alpha1，否则停在放大态 |

骨架屏：`alpha 0.4↔0.8` **1200ms** `Linear` 循环（与交互规范对齐），灰块用 `placeholder`、**不用渐变 shader**。

## 8. 落地映射（可直接粘贴）

### 8.1 `res/values/colors.xml`（亮色）
```xml
<resources>
    <color name="white">#FFFFFFFF</color>                <color name="black">#FF000000</color>
    <!-- ① 墨（品牌） -->
    <color name="brand_primary">#FF172742</color>        <color name="brand_primary_hover">#FF0E1B30</color>
    <color name="brand_primary_light">#FFE7EAF2</color>  <color name="brand_primary_soft">#FFD9E0EE</color>
    <color name="brand_accent">#FFD9F24F</color>         <color name="accent_soft">#FFF1F8C8</color>
    <color name="accent_graphics">#FF7C8F14</color>
    <!-- ② 文字 4 阶 -->
    <color name="text_primary">#FF1A1917</color>         <color name="text_secondary">#FF57534A</color>
    <color name="text_tertiary">#FF6E695E</color>        <color name="text_disabled">#FFA8A29A</color>
    <color name="text_on_brand">#FFFFFFFF</color>        <color name="text_on_accent">#FF172742</color>
    <!-- ③ 表面 -->
    <color name="bg_page">#FFF4F2EC</color>              <color name="bg_card">#FFFFFFFF</color>
    <color name="bg_card_alt">#FFF7F5EE</color>          <color name="bg_scrim">#66000000</color>
    <color name="divider">#FFE6E2D8</color>              <color name="stroke_soft">#FFE4E0D6</color>
    <color name="stroke_strong">#FFD3CDBE</color>
    <!-- ④ 语义 -->
    <color name="success">#FF16794F</color>              <color name="success_soft">#FFE6F4EE</color>
    <color name="warning">#FF8A5200</color>              <color name="warning_soft">#FFFBF0DF</color>
    <color name="danger">#FFB4281C</color>               <color name="danger_soft">#FFFBEAE7</color>
    <!-- ⑤ 音标 -->
    <color name="phonetic_us">#FF172742</color>          <color name="phonetic_uk">#FF8E3450</color>
    <color name="phonetic_us_soft">#FFE7EAF2</color>     <color name="phonetic_uk_soft">#FFF7E8EE</color>
    <!-- ⑥ 图片与浮层 -->
    <color name="placeholder">#FFEDEAE1</color>          <color name="placeholder_shimmer">#FFF7F5EF</color>
    <color name="overlay_control">#59000000</color>      <color name="overlay_control_active">#F2FFFFFF</color>
    <color name="photo_dim">#00000000</color>
    <color name="brand_wechat">#FF07C160</color>         <color name="brand_alipay">#FF1677FF</color>
</resources>
```
### 8.2 `res/values-night/colors.xml`（深色，只覆盖同名）
```xml
<resources>
    <color name="brand_primary">#FFC3CFE4</color>        <color name="brand_primary_hover">#FFD6E0F0</color>
    <color name="brand_primary_light">#FF262C36</color>  <color name="brand_primary_soft">#FF313846</color>
    <color name="brand_accent">#FF9FB44B</color>         <color name="accent_soft">#FF2E3316</color>
    <color name="accent_graphics">#FFC9DE6A</color>
    <color name="text_primary">#FFE9E4D9</color>         <color name="text_secondary">#FFB4AE9F</color>
    <color name="text_tertiary">#FF928B7C</color>        <color name="text_disabled">#FF6B665C</color>
    <color name="text_on_brand">#FF12151C</color>        <color name="text_on_accent">#FF14130F</color>
    <color name="bg_page">#FF14130F</color>              <color name="bg_card">#FF1D1B16</color>
    <color name="bg_card_alt">#FF242119</color>          <color name="bg_scrim">#99000000</color>
    <color name="divider">#FF262420</color>              <color name="stroke_soft">#FF2A2721</color>
    <color name="stroke_strong">#FF3A362D</color>
    <color name="success">#FF63C99B</color>              <color name="success_soft">#FF1A2E26</color>
    <color name="warning">#FFD9A65C</color>              <color name="warning_soft">#FF33281A</color>
    <color name="danger">#FFE8796C</color>               <color name="danger_soft">#FF3A211E</color>
    <color name="phonetic_us">#FFC3CFE4</color>          <color name="phonetic_uk">#FFD08FA6</color>
    <color name="phonetic_us_soft">#FF262C36</color>     <color name="phonetic_uk_soft">#FF36222A</color>
    <color name="placeholder">#FF24211A</color>          <color name="placeholder_shimmer">#FF2C281F</color>
    <color name="overlay_control">#66000000</color>      <color name="photo_dim">#40000000</color>
</resources>
```
### 8.3 `res/values/dimens.xml`
```xml
<resources>
    <dimen name="space_2">2dp</dimen>   <dimen name="space_4">4dp</dimen>   <dimen name="space_6">6dp</dimen>   <dimen name="space_8">8dp</dimen>
    <dimen name="space_12">12dp</dimen> <dimen name="space_16">16dp</dimen> <dimen name="space_20">20dp</dimen> <dimen name="space_24">24dp</dimen>
    <dimen name="space_32">32dp</dimen> <dimen name="space_40">40dp</dimen> <dimen name="space_56">56dp</dimen>
    <dimen name="radius_xs">6dp</dimen>   <dimen name="radius_sm">10dp</dimen> <dimen name="radius_md">14dp</dimen>
    <dimen name="radius_lg">20dp</dimen>  <dimen name="radius_xl">28dp</dimen> <dimen name="radius_pill">999dp</dimen>
    <dimen name="page_horizontal">20dp</dimen>         <dimen name="page_horizontal_detail">16dp</dimen> <dimen name="page_top">12dp</dimen>
    <dimen name="page_bottom">28dp</dimen>             <dimen name="touch_min">44dp</dimen>              <dimen name="icon_touch">40dp</dimen>
    <dimen name="icon_sm">16dp</dimen>                 <dimen name="icon_md">20dp</dimen>                <dimen name="icon_lg">24dp</dimen>
    <dimen name="btn_height">52dp</dimen>              <dimen name="btn_height_md">44dp</dimen>          <dimen name="btn_height_sm">36dp</dimen>
    <dimen name="row_height_compact">88dp</dimen>      <dimen name="row_height">56dp</dimen>             <dimen name="thumb_row">64dp</dimen>
    <dimen name="detail_image_height">216dp</dimen>    <dimen name="card_padding">20dp</dimen>           <dimen name="card_padding_compact">16dp</dimen>
    <dimen name="card_elevation">2dp</dimen>           <dimen name="nav_bar_height">58dp</dimen>         <dimen name="nav_indicator_w">40dp</dimen>
    <dimen name="nav_indicator_h">26dp</dimen>         <dimen name="divider_height">1dp</dimen>
    <dimen name="text_headword_xl">34sp</dimen>        <dimen name="text_headword_xl_cjk">30sp</dimen>   <dimen name="text_headword">19sp</dimen>
    <dimen name="text_headword_cjk">15sp</dimen>       <dimen name="text_phonetic">14sp</dimen>          <dimen name="text_metric">30sp</dimen>
    <dimen name="text_h1">22sp</dimen>                 <dimen name="text_h2">17sp</dimen>                <dimen name="text_body">15sp</dimen>
    <dimen name="text_body_sm">13sp</dimen>            <dimen name="text_caption">12sp</dimen>           <dimen name="text_label">11sp</dimen>
</resources>
```
### 8.4 `res/values/styles.xml`（字阶 12 + 语义 Text 5 + 按钮 4 + 导航 5）
```xml
<resources xmlns:tools="http://schemas.android.com/tools">
    <style name="TextAppearance.ThumbCard.HeadwordXl" parent="TextAppearance.Material3.HeadlineMedium"><item name="android:textSize">@dimen/text_headword_xl</item><item name="android:fontFamily">serif</item><item name="android:textColor">@color/text_primary</item><item name="android:letterSpacing">-0.02</item><item name="android:includeFontPadding">false</item></style>
    <style name="TextAppearance.ThumbCard.HeadwordXlCjk" parent="TextAppearance.Material3.HeadlineMedium"><item name="android:textSize">@dimen/text_headword_xl_cjk</item><item name="android:fontFamily">sans-serif-medium</item><item name="android:textColor">@color/text_primary</item><item name="android:letterSpacing">0.01</item><item name="android:includeFontPadding">false</item></style>
    <style name="TextAppearance.ThumbCard.Headword" parent="TextAppearance.Material3.TitleMedium"><item name="android:textSize">@dimen/text_headword</item><item name="android:fontFamily">serif</item><item name="android:textColor">@color/text_primary</item><item name="android:letterSpacing">-0.01</item></style>
    <style name="TextAppearance.ThumbCard.HeadwordCjk" parent="TextAppearance.Material3.BodyLarge"><item name="android:textSize">@dimen/text_headword_cjk</item><item name="android:textColor">@color/text_secondary</item></style>
    <style name="TextAppearance.ThumbCard.Phonetic" parent="TextAppearance.Material3.BodyMedium"><item name="android:textSize">@dimen/text_phonetic</item><item name="android:fontFamily">serif</item><item name="android:textColor">@color/text_primary</item><item name="android:letterSpacing">0.02</item><item name="android:includeFontPadding">true</item><item name="android:lineSpacingMultiplier">1.42</item></style>
    <style name="TextAppearance.ThumbCard.Metric" parent="TextAppearance.Material3.HeadlineMedium"><item name="android:textSize">@dimen/text_metric</item><item name="android:fontFamily">sans-serif-condensed</item><item name="android:textColor">@color/text_primary</item><item name="android:letterSpacing">-0.02</item><item name="android:includeFontPadding">false</item></style>
    <style name="TextAppearance.ThumbCard.H1" parent="TextAppearance.Material3.HeadlineSmall"><item name="android:textSize">@dimen/text_h1</item><item name="android:fontFamily">sans-serif-medium</item><item name="android:textColor">@color/text_primary</item><item name="android:letterSpacing">-0.01</item></style>
    <style name="TextAppearance.ThumbCard.H2" parent="TextAppearance.Material3.TitleMedium"><item name="android:textSize">@dimen/text_h2</item><item name="android:fontFamily">sans-serif-medium</item><item name="android:textColor">@color/text_primary</item></style>
    <style name="TextAppearance.ThumbCard.Body" parent="TextAppearance.Material3.BodyLarge"><item name="android:textSize">@dimen/text_body</item><item name="android:textColor">@color/text_primary</item><item name="android:lineSpacingMultiplier">1.55</item></style>
    <style name="TextAppearance.ThumbCard.BodySm" parent="TextAppearance.Material3.BodyMedium"><item name="android:textSize">@dimen/text_body_sm</item><item name="android:textColor">@color/text_secondary</item><item name="android:lineSpacingMultiplier">1.5</item></style>
    <style name="TextAppearance.ThumbCard.Caption" parent="TextAppearance.Material3.BodySmall"><item name="android:textSize">@dimen/text_caption</item><item name="android:textColor">@color/text_tertiary</item><item name="android:lineSpacingMultiplier">1.4</item></style>
    <style name="TextAppearance.ThumbCard.Label" parent="TextAppearance.Material3.LabelSmall"><item name="android:textSize">@dimen/text_label</item><item name="android:fontFamily">sans-serif-medium</item><item name="android:textColor">@color/text_tertiary</item><item name="android:letterSpacing">0.06</item></style>
    <!-- ⚠️ 语义 Text 必须 parent=""，否则 AAPT 会去找不存在的父样式 Text -->
    <style name="Text.Headword" parent=""><item name="android:textSize">@dimen/text_headword</item><item name="android:fontFamily">serif</item><item name="android:textColor">@color/text_primary</item><item name="android:letterSpacing">-0.01</item><item name="android:maxLines">1</item><item name="android:ellipsize">end</item></style>
    <style name="Text.HeadwordCjk" parent=""><item name="android:textSize">@dimen/text_headword_cjk</item><item name="android:textColor">@color/text_secondary</item><item name="android:maxLines">1</item><item name="android:ellipsize">end</item></style>
    <style name="Text.Phonetic" parent=""><item name="android:textSize">@dimen/text_phonetic</item><item name="android:fontFamily">serif</item><item name="android:textColor">@color/text_primary</item><item name="android:letterSpacing">0.02</item><item name="android:includeFontPadding">true</item><item name="android:paddingTop">2dp</item><item name="android:paddingBottom">2dp</item></style>
    <style name="Text.Label" parent=""><item name="android:textSize">@dimen/text_label</item><item name="android:fontFamily">sans-serif-medium</item><item name="android:textColor">@color/text_tertiary</item><item name="android:letterSpacing">0.06</item><item name="android:includeFontPadding">false</item></style>
    <style name="Text.PhoneticSlash" parent=""><item name="android:textSize">@dimen/text_caption</item><item name="android:fontFamily">serif</item><item name="android:textColor">@color/text_tertiary</item></style>
    <!-- 按钮 3 级：统一 stateListAnimator=@null，禁掉 M3 默认抬升 -->
    <style name="Widget.ThumbCard.Button.Primary" parent="Widget.Material3.Button"><item name="android:layout_height">@dimen/btn_height</item><item name="android:textSize">@dimen/text_body</item><item name="android:fontFamily">sans-serif-medium</item><item name="android:textColor">@color/text_on_brand</item><item name="backgroundTint">@color/brand_primary</item><item name="cornerRadius">@dimen/radius_md</item><item name="android:letterSpacing">0</item><item name="android:insetTop">0dp</item><item name="android:insetBottom">0dp</item><item name="android:stateListAnimator">@null</item></style>
    <style name="Widget.ThumbCard.Button.Secondary" parent="Widget.Material3.Button"><item name="android:layout_height">@dimen/btn_height_md</item><item name="android:textSize">@dimen/text_body_sm</item><item name="android:fontFamily">sans-serif-medium</item><item name="android:textColor">@color/brand_primary</item><item name="backgroundTint">@color/brand_primary_light</item><item name="cornerRadius">@dimen/radius_md</item><item name="android:insetTop">0dp</item><item name="android:insetBottom">0dp</item><item name="android:stateListAnimator">@null</item></style>
    <style name="Widget.ThumbCard.Button.Ghost" parent="Widget.Material3.Button.TextButton"><item name="android:layout_height">@dimen/btn_height_sm</item><item name="android:minWidth">0dp</item><item name="android:textSize">@dimen/text_body_sm</item><item name="android:fontFamily">sans-serif-medium</item><item name="android:textColor">@color/brand_primary</item><item name="android:paddingStart">10dp</item><item name="android:paddingEnd">10dp</item><item name="cornerRadius">@dimen/radius_sm</item><item name="android:insetTop">0dp</item><item name="android:insetBottom">0dp</item></style>
    <style name="Widget.ThumbCard.Button.Ghost.Danger" parent="Widget.ThumbCard.Button.Ghost"><item name="android:textColor">@color/danger</item></style>
    <!-- 底部导航：选中＝荧光药丸 + 实心图标 + medium 字 -->
    <style name="Widget.ThumbCard.BottomNav" parent="Widget.Material3.BottomNavigationView"><item name="android:background">@color/bg_page</item><item name="android:elevation">0dp</item><item name="itemIconSize">22dp</item><item name="labelVisibilityMode">labeled</item><item name="itemTextAppearanceActive">@style/TextAppearance.ThumbCard.NavActive</item><item name="itemTextAppearanceInactive">@style/TextAppearance.ThumbCard.NavInactive</item><item name="itemActiveIndicatorStyle">@style/Widget.ThumbCard.NavIndicator</item></style>
    <style name="TextAppearance.ThumbCard.NavActive" parent="TextAppearance.Material3.LabelMedium"><item name="android:textSize">@dimen/text_label</item><item name="android:fontFamily">sans-serif-medium</item></style>
    <style name="TextAppearance.ThumbCard.NavInactive" parent="TextAppearance.Material3.LabelMedium"><item name="android:textSize">@dimen/text_label</item></style>
    <style name="Widget.ThumbCard.NavIndicator" parent="Widget.Material3.BottomNavigationView.ActiveIndicator"><item name="android:color">@color/brand_accent</item><item name="android:width">@dimen/nav_indicator_w</item><item name="android:height">@dimen/nav_indicator_h</item><item name="shapeAppearance">@style/ShapeAppearance.ThumbCard.Pill</item></style>
    <style name="ShapeAppearance.ThumbCard.Pill" parent="ShapeAppearance.Material3.Corner.Full" />
</resources>
```
`res/color/nav_item_color.xml`（**选中必须是 `text_on_accent`**）：`<item android:color="@color/text_on_accent" android:state_checked="true"/>` + `<item android:color="@color/text_tertiary"/>`，外层 `<selector>`。

### 8.5 新增 / 改造 drawable
| 文件 | 规格 |
|---|---|
| `bg_card.xml` / `bg_card_hero.xml` | 改值：卡 = `radius_lg`(20) + `bg_card` + 1dp `stroke_soft`；hero 版同形但**无描边**（配 `elevation 2dp`） |
| `bg_card_alt.xml` / `bg_skeleton.xml` | 新：alt = `radius_md` + `bg_card_alt`，无描边（内嵌块 / 输入框 / 搜索框）；skeleton = `radius_sm`(10) + 纯色 `placeholder`，无渐变、无描边 |
| `bg_empty_ring.xml` | 新：oval，1dp `stroke_strong`，`dashWidth 4dp` / `dashGap 5dp`，88dp |
| `bg_hint.xml` + warning/danger | 改：`layer-list`——底层 `radius_md` 实心 `brand_primary_light`；上层 3dp 竖条（左侧 `radius_md` 圆角），色换 `warning_soft`+`warning` / `danger_soft`+`danger` |
| `bg_input_focus.xml` / `bg_heart_unfav`+`bg_heart_fav` | 新/改：输入框 focus = `radius_md` + `bg_card_alt` + 2dp `brand_primary`（配 selector `state_focused`）；心圆底 oval 30dp，色 `overlay_control` / `brand_accent` |
| `ic_cloud_off.xml` / `ic_chevron_down_16.xml` | 新：24dp / 16dp，1.75dp 描边、圆端，单色（由调用处 tint）。cloud_off 必须是「云 + 45° 斜杠」，与 `ic_camera`（横圆角矩形 + 圆镜头）语汇完全区分——这是「没拍过 / 没同步上」的唯一视觉抓手，且必须配 13sp 文字与一个 Ghost「重试」，图标单独出现不成立 |
## 9. 验收清单（真机，肉眼可判断）
| # | 场景 | 判定标准 |
|---|---|---|
| 1 | 任意一屏 | 全屏**找不到一个彩色（蓝/紫/橙）大按钮**，主按钮是近黑的墨色 |
| 2 | 首页首屏 | 标题→日期→今日卡→提示条→第一行单词卡，**在 503dp 内**（= 屏高 − 24(状态栏) − 48(三键导航) − 64(底栏) − 1(hairline)；**首页在带底栏的 MainActivity 里，不能用 568 那个数**，568 只属于无底栏的详情 Activity）不滚动就能看到第一行卡（空态头部 266 + 88 = 354 ≤ 503，成立） |
| 3 | 单词列表 | 一屏内**没有白色圆角卡片**（是纸底上的行 + 细线）；英文在中文上方且更大 |
| 4 | 音标 | `ˈ ˌ ː` 三个符号**完整可见、无一像素被裁切**，且与上方英文词不是同一字体 |
| 5 | 详情页 | 图片**顶到卡片左右边缘**（无缩进白边），只有卡片外侧四角是圆角；**在 568dp 可用高**的 5.5" 机上（详情是独立 Activity、无底栏，568 即全部内容高——**首页/收藏要再扣 58 底栏，两处口径不可混用**；且这个 568 的**前提是应用消费了窗口 inset**：9 个 Activity 根布局已加 `fitsSystemWindows="true"`（API ≤34 时 DecorView 仍消费、根布局拿到 0，老系统一个 dp 不变；API 35 上 insets 下发给根布局、padding 补回系统栏高度，内容区退回 568）——**已修，568 基准成立，Android 15 及以上与 14 及以下同一套 dp 数值通用**）词头 + 双音标 + 发音热区全在一屏，构成 `6+44+8+216+20+42+10+96+12+24+16+48 = 542dp`，余 26dp；词头涨到 2 行（+42）时两个底部按钮落出首屏，此时**英文发音仍有音标行各自 44dp 的 ▶**，**中文发音只剩按钮行的 Ghost 一个入口**，已定档 P1（下一轮在中文名行尾加 44dp `btnPlayChineseInline`） |
| 6 | 收藏 | 点亮后是**荧光黄绿圆底 + 深色实心心**，不是变蓝；取消时没有颜色闪烁 |
| 7 | 底部导航 | 当前 tab 有**荧光药丸**在图标后面、图标与文字转深墨色、文字比其它两个粗一档（**图标仍是同一套描边版，不换图**） |
| 8 | 深色（夜间） | 页面底是**暖黑**（偏棕不偏蓝），全屏**找不到纯白**（最亮是米白文字），照片明显比白天暗 |
| 9 | 深色（对比） | 切深色后主按钮变**浅底 + 深字**，不是深底 + 白字 |
| 10 | 空状态 | 是**虚线圆环 + 图标 + 一句话**，不是浅色实心圆，也不是一行灰字 |
| 11 | 字体放大 130% | 单词行不重叠、音标不裁切、今日卡不溢出 |
| 12 | 屏幕亮度 20% | 正文仍可读，**辅助文字（4.88:1）不发灰发糊** |
