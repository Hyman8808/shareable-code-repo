package com.turing.thumbcard.data.ai

import android.content.Context
import android.util.Log
import com.google.gson.Gson

/**
 * 批量出图任务的**持久化队列**。
 *
 * 为什么不能只把队列放在内存里（原来 `AiBatchActivity` 的做法）：
 *  ① 用户按返回、切到别的 App、甚至锁屏，Activity 都会被销毁 —— 队列跟着没了；
 *  ② 华为/鸿蒙这类 ROM 会主动杀进程，一张图要 60–90 秒，被杀概率并不低；
 *  ③ 「被杀之后重新打开 App 继续跑」这件事，只能靠落盘的状态。
 *
 * 存的是**还没做的 id**（`pending`），做完一张就把它从 pending 里删掉再存一次 ——
 * 于是「断点续跑」不需要额外的游标：剩下的 pending 就是要跑的。
 *
 * ⚠️ 因为状态是「每张图做完才落盘一次」，进程恰好死在「服务端已经出图、本地还没写文件」
 *    的瞬间时，这一张重启后会重跑一次（多花一张的钱）。要做到严格不重不漏需要
 *    服务端 idempotency key，当前方舟接口没有，所以选择「宁可重跑，不能漏做」。
 */
object AiTaskStore {

    private const val TAG = "AiTaskStore"
    private const val PREF = "thumbcard_ai_task"
    private const val KEY_RUN = "run"

    private val gson = Gson()

    /**
     * 一次批量任务。
     *
     * @param pending 还没处理的卡片 id（做完一张少一个）
     * @param total   本次一共几张（用于进度分母，不随 pending 变）
     * @param redo    是否允许重跑已生成过的卡：批量=false（省额度），单张「再生成一张」=true
     */
    data class Run(
        val style: String = AiStyle.HD.code,
        val pending: List<Long> = emptyList(),
        val total: Int = 0,
        val ok: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val redo: Boolean = false,
        /** 任务创建时间：既用于「同一次任务只问一次」，也是日志里的定位点 */
        val startedAt: Long = 0L
    ) {
        fun done(): Int = (total - pending.size).coerceAtLeast(0)
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): Run? {
        val raw = runCatching { prefs(ctx).getString(KEY_RUN, null) }.getOrNull() ?: return null
        return runCatching { gson.fromJson(raw, Run::class.java) }
            .onFailure { Log.w(TAG, "parse failed", it) }
            .getOrNull()
            // Gson 反序列化走 Unsafe，字段缺失时给的是 null 而不是 Kotlin 默认值 ——
            // `pending` 一旦为 null，`isNotEmpty()` 就是崩溃，所以这里必须防御一次
            ?.takeIf { runCatching { it.pending.isNotEmpty() }.getOrDefault(false) }
    }

    fun save(ctx: Context, run: Run) {
        runCatching {
            prefs(ctx).edit().putString(KEY_RUN, gson.toJson(run)).apply()
        }.onFailure { Log.w(TAG, "save failed", it) }
    }

    fun clear(ctx: Context) {
        runCatching { prefs(ctx).edit().remove(KEY_RUN).apply() }
    }

    /** 有没有「没跑完」的任务（进程被杀后重新打开 App 就靠它发现） */
    fun hasPending(ctx: Context): Boolean = load(ctx) != null
}
