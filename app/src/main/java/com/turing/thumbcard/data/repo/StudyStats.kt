package com.turing.thumbcard.data.repo

import com.turing.thumbcard.data.db.WordCardEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 学习报告（首页顶部总结）。
 *
 * 两个口径必须先分清，否则数字永远对不上：
 *  - **张数** = 记录条数（一次快门 = 一张照片 = 一条云端记录）
 *  - **词数** = 去重后的单词数（同一件东西拍 3 次只算 1 个词）
 *
 * 去重键优先用英文名（大小写/首尾空格归一），无英文名时用中文名；
 * 两者都没有（识别失败的照片）**不计入词数**，只计入张数——
 * 把识别失败的照片算成「学会了某词」是假数据。
 *
 * 时间口径取云端 `create_time`；解析失败才退回本地 savedAt。
 * 「本周」= 自然周（周一 00:00 起），与用户对「这周」的理解一致。
 */
data class StudyStats(
    val todayPhotos: Int = 0,
    val todayWords: Int = 0,
    val weekPhotos: Int = 0,
    val weekWords: Int = 0,
    val totalPhotos: Int = 0,
    val totalWords: Int = 0
) {

    val hasAny: Boolean get() = totalPhotos > 0

    companion object {

        private val TIME_PATTERNS = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy-MM-dd"
        )

        /** 设备时区下的今天 00:00（首页「今天拍的」也用这个口径） */
        fun startOfToday(now: Long = System.currentTimeMillis()): Long =
            Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

        /** 单张卡片的拍摄时间（云端 create_time 解析失败才退本地 savedAt） */
        fun timeOf(card: WordCardEntity): Long = parseTime(card.createTime, card.savedAt)

        /**
         * 首页「今天拍的」：只留今天 00:00 之后的记录，保持原顺序（id 倒序）。
         *
         * 为什么首页只显示今天：产品口径是「今天拍了什么」，
         * 历史照片走「全部 ›」页，避免首屏无限长。
         */
        fun todayOf(
            cards: List<WordCardEntity>,
            now: Long = System.currentTimeMillis()
        ): List<WordCardEntity> {
            if (cards.isEmpty()) return emptyList()
            val start = startOfToday(now)
            return cards.filter { timeOf(it) >= start }
        }

        /** 本周一 00:00（Calendar 里周日 = 1，需要单独处理） */
        private fun startOfWeek(now: Long): Long = Calendar.getInstance().apply {
            timeInMillis = startOfToday(now)
            val dow = get(Calendar.DAY_OF_WEEK)
            val back = if (dow == Calendar.SUNDAY) 6 else dow - Calendar.MONDAY
            add(Calendar.DAY_OF_MONTH, -back)
        }.timeInMillis

        /** 解析云端时间戳，失败返回 fallback */
        fun parseTime(text: String?, fallback: Long): Long {
            val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return fallback
            for (p in TIME_PATTERNS) {
                val v = runCatching {
                    SimpleDateFormat(p, Locale.CHINA).apply { isLenient = false }.parse(t)?.time
                }.getOrNull()
                if (v != null) return v
            }
            return fallback
        }

        /** 单词去重键；null 表示这条记录没有可用名称，不计入词数 */
        private fun wordKey(card: WordCardEntity): String? {
            card.nameEnglish?.trim()?.takeIf { it.isNotEmpty() }?.let {
                return "en:${it.lowercase(Locale.ROOT)}"
            }
            card.nameChinese?.trim()?.takeIf { it.isNotEmpty() }?.let {
                return "cn:$it"
            }
            return null
        }

        /**
         * 统计某台设备的全部卡片。
         *
         * @param now 便于测试注入；默认取当前时间
         */
        fun of(cards: List<WordCardEntity>, now: Long = System.currentTimeMillis()): StudyStats {
            if (cards.isEmpty()) return StudyStats()

            val todayStart = startOfToday(now)
            val weekStart = startOfWeek(now)

            var todayPhotos = 0
            var weekPhotos = 0
            val todayWords = HashSet<String>()
            val weekWords = HashSet<String>()
            val totalWords = HashSet<String>()

            cards.forEach { c ->
                val t = parseTime(c.createTime, c.savedAt)
                val key = wordKey(c)

                if (t >= todayStart) {
                    todayPhotos++
                    key?.let { todayWords.add(it) }
                }
                if (t >= weekStart) {
                    weekPhotos++
                    key?.let { weekWords.add(it) }
                }
                key?.let { totalWords.add(it) }
            }

            return StudyStats(
                todayPhotos = todayPhotos,
                todayWords = todayWords.size,
                weekPhotos = weekPhotos,
                weekWords = weekWords.size,
                totalPhotos = cards.size,
                totalWords = totalWords.size
            )
        }
    }
}
