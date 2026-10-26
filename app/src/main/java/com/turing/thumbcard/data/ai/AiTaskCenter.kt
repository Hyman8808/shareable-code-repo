package com.turing.thumbcard.data.ai

import android.content.Context
import android.util.Log
import com.turing.thumbcard.data.image.ImageStore
import com.turing.thumbcard.data.repo.RecordRepository
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * AI 出图任务的**进程内中枢**（状态 + 事件 + 队列执行）。
 *
 * 为什么要有这一层（而不是让 Activity 自己 `lifecycleScope.launch` 跑）：
 *  ① **页面可以走，任务不能停** —— 用户按返回、切到别的 Tab、甚至锁屏，
 *     Activity 会被销毁，`lifecycleScope` 一并取消，正在跑的那张图就白花钱了；
 *  ② 跑队列的必须是 [AiTaskService] 的 `CoroutineScope(Service)`，
 *     它只跟进程同生命周期，页面只是**观察者**；
 *  ③ 单张和批量用同一条代码路径：单张 = 长度为 1 的队列。
 *     两套实现必然分叉（一套有断点续跑、一套没有），这里只有一套。
 *
 * 三个出口：
 *  - [state]：进度（页面 + 通知都读它）
 *  - [events]：`CardUpdated`（列表要立刻换封面）/ `QueueEnd`（收尾文案）
 *  - [AiTaskStore]：落盘（进程被杀后还能续跑）
 */
object AiTaskCenter {

    private const val TAG = "AiTaskCenter"

    /**
     * 一次队列的快照。
     *
     * `done` 由 `total - pending.size` 推出，不单独维护 —— 两个计数器必然会出现
     * 「进度条和数字对不上」的经典 bug。
     */
    data class State(
        val running: Boolean = false,
        val total: Int = 0,
        val done: Int = 0,
        val ok: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val currentId: Long? = null,
        val currentWord: String? = null,
        val style: AiStyle = AiStyle.HD,
        /** 用户按了「停止」：当前这张会跑完，剩下的不再开始 */
        val canceling: Boolean = false,
        /** 额度中途见底，队列**保留**，买完可以接着跑 */
        val quotaOut: Boolean = false,
        /** 收尾快照专用：这次是「被用户停掉的」而不是跑完的 */
        val canceled: Boolean = false
    )

    sealed class Event {
        /**
         * 这张卡的图已经换成 AI 图（DB 里的 aiStyle 已更新）→ 列表重绘这一格。
         *
         * @param pixels 服务端返回的尺寸文案（如 `2160x1080`），给单张结果页显示用
         */
        data class CardUpdated(val cardId: Long, val pixels: String? = null) : Event()

        /** 队列结束（跑完 / 被停 / 额度不够） */
        data class QueueEnd(val state: State, val canceled: Boolean) : Event()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** 停止请求。用 volatile 而不是取消 Job：正在飞的那张 HTTP 不该被硬切 */
    @Volatile
    private var cancelFlag = false

    /** 服务还活着吗（进程内握手；进程被杀 = false，就是「需要续跑」的判据） */
    @Volatile
    var serviceAlive: Boolean = false
        private set

    fun isRunning(): Boolean = _state.value.running

    /**
     * 通知各列表「这一格的封面换了」。
     *
     * 目前只有「从相册找回 AI 图」用它：找回不经过队列、不调服务，
     * 但结果与生成完一样需要列表立刻换封面。
     * 复用同一个 [Event.CardUpdated] 而不是另起一套刷新机制 ——
     * 列表侧（首页/收藏/全部记录/详情/预览）已经订阅好了，
     * 复制一份只会让两条路径日后行为不一致。
     */
    fun notifyCardUpdated(cardId: Long) {
        _events.tryEmit(Event.CardUpdated(cardId))
    }

    fun markServiceAlive(alive: Boolean) {
        serviceAlive = alive
    }

    /**
     * 服务被销毁时复位。
     *
     * 正常收尾时 state 已经被 [runQueue] 复位过；这里兜的是「服务被杀/被系统回收、
     * 但进程还活着」的情况 —— 不复位的话，App 会一直以为任务在跑，
     * 「继续修复」弹窗永远不会出现，用户也再也点不动生成。
     */
    fun resetIfRunning() {
        if (_state.value.running) publish(State())
    }

    // ==================== 入队 ====================

    /**
     * 新建一次任务并启动服务。
     *
     * @param ids   要处理的卡片（批量 = 多张；单张页 = 一张）
     * @param style 统一风格：批量不混风格，否则进度条没有意义
     * @param redo  允许重跑已生成过的卡。单张的「再生成一张」必须传 true，
     *              否则用户点半天没反应（那条「同风格跳过」是给批量省额度用的）
     * @return false 表示已经有任务在跑，本次没入队
     */
    fun enqueue(ctx: Context, ids: List<Long>, style: AiStyle, redo: Boolean): Boolean {
        if (ids.isEmpty()) return false
        if (isRunning()) {
            Log.w(TAG, "enqueue ignored: queue busy")
            return false
        }
        val app = ctx.applicationContext
        cancelFlag = false
        AiTaskStore.save(
            app,
            AiTaskStore.Run(
                style = style.code,
                pending = ids.toList(),
                total = ids.size,
                redo = redo,
                startedAt = System.currentTimeMillis()
            )
        )
        publish(
            State(
                running = true,
                total = ids.size,
                style = style
            )
        )
        if (!AiTaskService.start(app)) {
            // 系统不让起前台服务（Android 12+ 后台启动限制）→ 回滚，别让界面一直「正在生成」
            AiTaskStore.clear(app)
            publish(State())
            return false
        }
        return true
    }

    /** 续跑：进程被杀 / 用户选了「继续修复」后调用 */
    fun resume(ctx: Context) {
        if (isRunning()) return
        val run = AiTaskStore.load(ctx.applicationContext) ?: return
        cancelFlag = false
        publish(
            State(
                running = true,
                total = run.total,
                done = run.done(),
                ok = run.ok,
                skipped = run.skipped,
                failed = run.failed,
                style = AiStyle.fromCode(run.style) ?: AiStyle.HD
            )
        )
        AiTaskService.start(ctx.applicationContext)
    }

    /** 用户按「停止」：当前这张跑完就收工，剩下的不再开始 */
    fun requestCancel() {
        cancelFlag = true
        _state.update { it.copy(canceling = true) }
    }

    /** 彻底放弃剩余任务（「继续修复」弹窗里的「放弃」也走这里） */
    fun discard(ctx: Context) {
        cancelFlag = true
        AiTaskStore.clear(ctx.applicationContext)
        publish(State())
    }

    private fun publish(s: State) {
        _state.value = s
    }

    // ==================== 执行（只由 AiTaskService 调） ====================

    /**
     * 从 [AiTaskStore] 里读出剩下的活，一张一张跑完。
     *
     * @param onProgress 每张开始/结束时回调（服务用它刷新通知）
     *
     * 与旧 `AiBatchActivity` 里的实现保持一致的三条账目规则：
     *  1. 余额不足**在发请求前**停（仓库层每次生成都会自检），队列保留，买完能接着跑；
     *  2. 失败不扣次数（扣减在仓库层「图落盘成功之后」）；
     *  3. 批量里「已生成过同风格」直接跳过（省额度）；单张 redo=true 时不跳过。
     */
    suspend fun runQueue(ctx: Context, onProgress: (State) -> Unit): State {
        val app = ctx.applicationContext
        val run = AiTaskStore.load(app) ?: run {
            publish(State())
            return State()
        }
        val style = AiStyle.fromCode(run.style) ?: AiStyle.HD
        val repo = RecordRepository(app)
        val ai = AiRepository(app)

        var ok = run.ok
        var skipped = run.skipped
        var failed = run.failed
        var quotaOut = false
        var canceled = false
        val pending = ArrayList(run.pending)

        publish(
            State(
                running = true,
                total = run.total,
                done = run.done(),
                ok = ok,
                skipped = skipped,
                failed = failed,
                style = style
            )
        )
        onProgress(_state.value)

        while (pending.isNotEmpty()) {
            if (cancelFlag) {
                canceled = true
                break
            }

            val id = pending[0]
            val card = runCatching { repo.findCard(id) }.getOrNull()
            if (card == null) {
                // 卡片在同步/换机后消失：算失败但不该卡住整个队列
                failed++
                pending.removeAt(0)
                persist(app, run, pending, ok, skipped, failed)
                continue
            }

            publish(
                _state.value.copy(
                    done = (run.total - pending.size).coerceAtLeast(0),
                    currentId = id,
                    currentWord = card.nameEnglish?.takeIf { it.isNotBlank() }
                        ?: card.nameChinese?.takeIf { it.isNotBlank() }
                )
            )
            onProgress(_state.value)

            val already = ImageStore.ai(app, id, style) != null
            if (already && !run.redo) {
                skipped++
            } else {
                when (val outcome = ai.generate(card, style)) {
                    is AiRepository.Outcome.Ok -> {
                        ok++
                        _events.emit(Event.CardUpdated(id, outcome.pixels))
                    }
                    is AiRepository.Outcome.NoQuota -> {
                        // 注意：**不**从 pending 里摘掉这张 —— 买完额度要接着做它
                        quotaOut = true
                        break
                    }
                    is AiRepository.Outcome.NoSource,
                    is AiRepository.Outcome.Err -> failed++
                }
            }

            pending.removeAt(0)
            persist(app, run, pending, ok, skipped, failed)
            publish(
                _state.value.copy(
                    done = (run.total - pending.size).coerceAtLeast(0),
                    ok = ok,
                    skipped = skipped,
                    failed = failed,
                    currentId = null
                )
            )
            onProgress(_state.value)
        }

        val finalState = _state.value.copy(
            running = false,
            currentId = null,
            canceling = false,
            canceled = canceled,
            quotaOut = quotaOut,
            ok = ok,
            skipped = skipped,
            failed = failed,
            done = (run.total - pending.size).coerceAtLeast(0)
        )

        // 只有「用户主动停」才清空队列；额度不足要留着等续跑；
        // 进程被杀的路径根本走不到这里 —— 那时 SP 里的 pending 还在，正好用来续跑。
        if (canceled || (!quotaOut && pending.isEmpty())) {
            AiTaskStore.clear(app)
        }

        Log.d(
            TAG,
            "queue end: ok=$ok skipped=$skipped failed=$failed quotaOut=$quotaOut canceled=$canceled rest=${pending.size}"
        )
        publish(State())
        _events.emit(Event.QueueEnd(finalState, canceled))
        return finalState
    }

    private fun persist(
        ctx: Context,
        base: AiTaskStore.Run,
        pending: List<Long>,
        ok: Int,
        skipped: Int,
        failed: Int
    ) {
        AiTaskStore.save(
            ctx,
            base.copy(
                pending = pending.toList(),
                ok = ok,
                skipped = skipped,
                failed = failed
            )
        )
    }
}
