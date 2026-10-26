package com.turing.thumbcard.data.repo

import android.content.Context
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 接口耗时观测（轻量埋点）。
 *
 * ## 为什么 App 侧要自己做这件事
 *
 * 产品要求「全球访问时长 2 秒内」。这个指标有一半在云端（区域接入、就近解析、
 * 边缘缓存），但**用户感知的是 App 从发起请求到卡片出现的那一段**。
 * 云端改好了、App 还在用 8 秒超时 + 单域名 + 无重试，指标一样不达标。
 *
 * 因此 App 侧必须能把「我这边花了多久」量出来，否则：
 *  - 海外用户反馈慢，无法判断是网络、云端还是渲染
 *  - 云端优化上线后，没有客户端侧的前后对比
 *
 * ## 为什么不用日志
 *
 * 部分 ROM（含本项目测试平板）抓不到 `Log.d`；而且用户现场反馈时不可能带 logcat。
 * 落 SP 后，诊断页可以直接显示「近 20 次：平均 / P50 / 最大」，
 * 截图就能作为验收证据。
 *
 * ## 代价
 *
 * 每次请求一次 SP 读改写。列表同步是秒级操作，这点开销可忽略；
 * 且只保留最近 [MAX_SAMPLES] 条，不会无限增长。
 */
object LatencyStore {

    private const val TAG = "LatencyStore"
    private const val PREF = "thumbcard_perf"
    private const val MAX_SAMPLES = 20

    /** 记录接口（list）的一次耗时样本 */
    private const val KEY_LIST = "list_ms"

    data class Stats(
        val count: Int,
        val avgMs: Long,
        val p50Ms: Long,
        val maxMs: Long,
        val lastMs: Long
    ) {
        val isEmpty: Boolean get() = count == 0
    }

    /** 进程内缓存，避免诊断页多次读 SP */
    private val cache = CopyOnWriteArrayList<Long>()
    @Volatile
    private var loaded = false

    /**
     * 记录一次耗时。
     *
     * @param ms 从发请求到响应解析完成（不含 UI 渲染）
     */
    fun recordListRequest(ctx: Context, ms: Long) {
        if (ms <= 0) return
        runCatching {
            load(ctx)
            cache.add(ms)
            while (cache.size > MAX_SAMPLES) cache.removeAt(0)
            val csv = cache.joinToString(",")
            ctx.applicationContext
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_LIST, csv).apply()
        }.onFailure { Log.w(TAG, "record failed: ${it.javaClass.simpleName}") }
    }

    fun listStats(ctx: Context): Stats {
        runCatching { load(ctx) }
        val v = cache.toList().sorted()
        if (v.isEmpty()) return Stats(0, 0, 0, 0, 0)
        val avg = v.sum() / v.size
        val p50 = v[(v.size - 1) / 2]
        return Stats(v.size, avg, p50, v.last(), v.last())
    }

    /** 是否达到「2 秒内」目标（用 P50 判定，避免个别慢请求误导） */
    fun meetsTwoSecondTarget(ctx: Context): Boolean {
        val s = listStats(ctx)
        return !s.isEmpty && s.p50Ms <= 2000
    }

    fun clear(ctx: Context) {
        cache.clear()
        loaded = true
        runCatching {
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().remove(KEY_LIST).apply()
        }
    }

    private fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = runCatching {
                ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getString(KEY_LIST, null)
            }.getOrNull()
            if (!raw.isNullOrBlank()) {
                raw.split(',').mapNotNull { it.trim().toLongOrNull() }
                    .takeLast(MAX_SAMPLES)
                    .forEach { cache.add(it) }
            }
            loaded = true
        }
    }
}
