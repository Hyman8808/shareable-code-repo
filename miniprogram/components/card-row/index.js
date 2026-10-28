// 单词卡 = 词典的**一行**（不是一张卡）
//
// 照搬 App item_word_card.xml：
//   · 去卡化：无白底、无圆角、无描边、无外边距 —— 纸底直接露。
//     一屏白卡总面积要 ≤ 屏高 45%，列表再铺白卡就成了「卡片瀑布」。
//   · 英文当主角：19sp serif（38rpx），中文 15sp sans 退到第二层。
//   · 88dp 定高：64dp 图 + 三行文字，上下各 12dp 呼吸。
//
// ⚠️ 整行是热区；右侧心形视觉 30dp、热区 44dp（点击用 catchtap，别冒泡成进详情）。

const icons = require('../../utils/icons')

Component({
  options: { addGlobalClass: true },

  properties: {
    item: { type: Object, value: {} },
    fav: { type: Boolean, value: false },
    showFav: { type: Boolean, value: true },
    // 最后一行不画分隔线，否则列表底部会多一条悬空的线
    showLine: { type: Boolean, value: true },
  },

  data: {
    heartOff: icons.heartOff,
    heartOn: icons.heartOn,
  },

  methods: {
    onTap() {
      // ⚠️ 事件名不能叫 tap：会和原生 tap 冒泡撞车，父级一次点击收到两次
      this.triggerEvent('rowtap', { id: this.data.item.id })
    },
    onFav() {
      this.triggerEvent('rowfav', { id: this.data.item.id })
    },
  },
})
