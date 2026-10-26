package com.turing.thumbcard.data.i18n

import android.os.Build
import com.turing.thumbcard.data.dict.DictEntry
import java.util.concurrent.ConcurrentHashMap

/**
 * 简体 → 繁体转换（本机完成，不走服务端）。
 *
 * 为什么放本地：
 *  - 繁体中文与简体是**同一门语言**，不是「翻译」。走 6.6 翻译接口既慢又没必要，
 *    而且那个接口还没上线——真走它，用户选「繁體中文」会看到「待翻译 + 简体原文」。
 *  - 转换是纯字符映射，离线可用，零延迟，也不消耗任何额度。
 *
 * 两级实现：
 *  1. **优先 ICU**（`android.icu.text.Transliterator`，Android 7.0+ 自带），
 *     它带词典，能处理「头发→頭髮」「面条→麵條」这类一词多形的情况；
 *  2. ICU 不可用时退到内置字表（[PAIRS]），覆盖家具家电、文具、食物、界面词汇等
 *     常见物品名词。字表外的字原样保留——**宁可少转，也不乱转**。
 *
 * ⚠️ 转换只作用于**展示**，绝不回写数据库或云端：
 *    简体原文是识物接口的原始结果，回写会让「切回简体」变成二次转换。
 */
object HantConverter {

    /** 已转换过的字符串缓存（列表滚动时同一批名称会被反复绑到不同 View 上） */
    private val cache = ConcurrentHashMap<String, String>()
    private const val CACHE_MAX = 4096

    /** 转换一段文本；空白或 null 原样返回 */
    fun convert(text: String?): String? {
        if (text.isNullOrBlank()) return text
        cache[text]?.let { return it }
        val out = viaIcu(text) ?: viaTable(text)
        if (cache.size < CACHE_MAX) cache[text] = out
        return out
    }

    /** 批量转换（保持顺序与空值语义） */
    fun convertAll(list: List<String>?): List<String> =
        list?.map { convert(it) ?: it } ?: emptyList()

    // ---------------------------------------------------------------- ICU

    private fun viaIcu(text: String): String? {
        // android.icu 从 API 24 起才有；minSdk 23 的机器直接走字表
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return Icu.transliterate(text)
    }

    /**
     * 单独抽成一个对象：只有 [viaIcu] 真的调用时才会加载，
     * 避免 API 23 上因为类里出现 `android.icu.*` 而触发校验失败。
     */
    private object Icu {
        /** ICU 的转换器 ID 有几个别名，不同 Android 版本/数据裁剪情况不一定都给全 → 逐个试 */
        private val instance = runCatching {
            listOf("Simplified-Traditional", "Hans-Hant", "zh-Hans-zh-Hant")
                .firstNotNullOfOrNull { id ->
                    runCatching { android.icu.text.Transliterator.getInstance(id) }.getOrNull()
                }
        }.getOrNull()

        fun transliterate(text: String): String? =
            instance?.let { runCatching { it.transliterate(text) }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
    }

    // -------------------------------------------------------------- 字表

    private fun viaTable(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) sb.append(TABLE[ch] ?: ch)
        return sb.toString()
    }

    private val TABLE: Map<Char, Char> by lazy {
        val m = HashMap<Char, Char>(256)
        PAIRS.split(' ').forEach { p -> if (p.length == 2) m[p[0]] = p[1] }
        m
    }

    /**
     * 简体-繁体字对，空格分隔。
     *
     * 只收**没有歧义**的字：像「干→乾/幹」「面→麵/面」「台→臺/檯」这种一词多形，
     * 交给 ICU 判断，字表里故意不写——写错了比不转更糟。
     */
    private const val PAIRS =
        "纸紙 张張 条條 灯燈 电電 脑腦 话話 视視 机機 书書 笔筆 铅鉛 学學 习習 " +
        "单單 词詞 语語 汉漢 义義 图圖 库庫 储儲 盘盤 线線 网網 络絡 连連 传傳 " +
        "输輸 数數 据據 设設 备備 时時 间間 记記 录錄 删刪 费費 额額 币幣 员員 " +
        "会會 账帳 号號 码碼 验驗 证證 权權 隐隱 协協 议議 户戶 约約 应應 软軟 " +
        "频頻 继繼 续續 开開 关關 闭閉 钥鑰 柜櫃 厨廚 卫衛 铺鋪 盖蓋 帘簾 袜襪 " +
        "裤褲 衬襯 围圍 湿濕 锅鍋 壶壺 炉爐 扫掃 篮籃 积積 筝箏 气氣 饼餅 苹蘋 " +
        "樱櫻 伞傘 镜鏡 药藥 医醫 诊診 疗療 护護 铁鐵 银銀 铜銅 锁鎖 钟鐘 车車 " +
        "轮輪 辆輛 运運 报報 闻聞 热熱 凉涼 冻凍 酱醬 盐鹽 麦麥 谷穀 鱼魚 虾蝦 " +
        "鸡雞 鸭鴨 圆圓 边邊 长長 宽寬 红紅 绿綠 蓝藍 黄黃 树樹 叶葉 种種 简簡 " +
        "体體 湾灣 广廣 东東 爱愛 亲親 儿兒 妈媽 爷爺 买買 卖賣 钱錢 贵貴 赠贈 " +
        "称稱 标標 识識 别別 认認 发發 现現 丢丟 见見 听聽 说說 读讀 写寫 贴貼 " +
        "后後 总總 计計 统統 价價 咸鹹 志誌 级級 页頁 点點 击擊 键鍵 显顯 声聲 " +
        "头頭 个個 们們 为為 与與 从從 来來 对對 实實 让讓 请請 谢謝 该該 论論 " +
        "过過 还還 这這 达達 质質 态態 鲜鮮 养養 没沒 无無 万萬 亿億 师師 饲飼 " +
        "鸟鳥 虫蟲 丝絲 织織 缝縫 锻鍛 钢鋼 铝鋁 胶膠 蜡蠟 洁潔 涤滌"
}

/**
 * 把词条里的**中文内容**转成繁体。
 *
 * 只转中文文本，音标、英文名、音频地址、词性缩写一个字都不动——
 * 繁体中文使用者要的是「同样的内容、繁体的写法」，不是「换一套音标」。
 * 词条本身的查询键（[DictEntry.word]）保持简体：它是服务端的原始返回，用于对账。
 */
fun DictEntry.inHant(): DictEntry = copy(
    meanings = meanings.map { it.copy(meaning = HantConverter.convert(it.meaning) ?: it.meaning) },
    examples = examples.map { it.copy(cn = HantConverter.convert(it.cn)) },
    phrases = phrases.map { it.copy(cn = HantConverter.convert(it.cn)) },
    synonyms = HantConverter.convertAll(synonyms),
    antonyms = HantConverter.convertAll(antonyms),
    combineWords = HantConverter.convertAll(combineWords),
    radical = HantConverter.convert(radical),
    structure = HantConverter.convert(structure)
)
