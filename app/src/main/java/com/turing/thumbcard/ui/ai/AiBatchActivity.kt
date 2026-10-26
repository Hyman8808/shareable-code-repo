package com.turing.thumbcard.ui.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.turing.thumbcard.R
import com.turing.thumbcard.data.ai.AiPolicy
import com.turing.thumbcard.data.ai.AiQuotaStore
import com.turing.thumbcard.data.ai.AiStyle
import com.turing.thumbcard.data.ai.AiTaskCenter
import com.turing.thumbcard.data.image.ImageStore
import com.turing.thumbcard.databinding.ActivityAiBatchBinding
import com.turing.thumbcard.util.loadAny
import kotlinx.coroutines.launch

/**
 * 批量修复的**观察窗口**（不是执行者）。
 *
 * v2.4 起，真正跑队列的是 [com.turing.thumbcard.data.ai.AiTaskService]（前台服务），
 * 这一页只做三件事：开跑前确认、跑的时候看进度、跑完看结果。
 *
 * 为什么要把执行搬走（v2.3 的教训）：
 *  ① 原来任务挂在 `lifecycleScope` 上，用户按返回 / 切 Tab / 锁屏，队列直接取消，
 *     已经发出去的请求白花钱；
 *  ② 一张 60–90 秒、8 张十几分钟，没人会一直盯着这一页；
 *  ③ 被杀之后原来没有任何续跑能力。
 *
 * 所以这里的「返回」= **转后台**，不是「停止」：
 *  停止是破坏性操作（剩下不做），必须单独一个按钮 + 一次确认。
 */
class AiBatchActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_IDS = "extra_batch_ids"
        private const val EXTRA_STYLE = "extra_batch_style"

        /**
         * @param ids   要处理的卡片 id（按用户在收藏页勾选的顺序）
         * @param style 统一风格：批量不混风格，否则进度条没有意义
         */
        fun start(ctx: Context, ids: LongArray, style: AiStyle) {
            ctx.startActivity(
                Intent(ctx, AiBatchActivity::class.java)
                    .putExtra(EXTRA_IDS, ids)
                    .putExtra(EXTRA_STYLE, style.code)
            )
        }
    }

    private lateinit var binding: ActivityAiBatchBinding

    private var ids: LongArray = LongArray(0)
    private var style: AiStyle = AiStyle.HD

    /** 这一页是「发起者」还是「旁观已有任务」 */
    private var started = false

    /** 上一次显示在预览框里的卡片，避免每帧都重新请求图片 */
    private var lastShownId: Long? = null

    /** 需要通知权限才能看到后台进度/完成提醒（拒绝也能跑，只是没通知） */
    private var pendingStart: (() -> Unit)? = null

    private val notifyPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 无论允许还是拒绝都要开跑：通知是「方便」，不是「前提」
        val action = pendingStart
        pendingStart = null
        action?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiBatchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ids = intent.getLongArrayExtra(EXTRA_IDS) ?: LongArray(0)
        style = AiStyle.fromCode(intent.getStringExtra(EXTRA_STYLE)) ?: AiStyle.HD

        binding.btnBatchBack.setOnClickListener { leaveToBackground() }
        binding.btnBatchStop.setOnClickListener { confirmStop() }
        binding.btnBatchFinish.setOnClickListener { leaveToBackground() }
        binding.pbBatch.max = ids.size.coerceAtLeast(1)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                leaveToBackground()
            }
        })

        observe()

        // 已经有任务在跑（例如从通知回来 / 页面被重建）→ 直接当旁观者
        if (AiTaskCenter.isRunning()) {
            started = true
            return
        }

        if (ids.isEmpty()) {
            toast(getString(R.string.batch_nothing_to_do))
            finish()
            return
        }

        binding.tvBatchSub.text = getString(
            if (style == AiStyle.HD) R.string.batch_sub_hd else R.string.batch_sub_cartoon,
            ids.size
        )
        binding.tvBatchCounter.text = getString(R.string.batch_counter_fmt, 0, ids.size)
        confirmStart()
    }

    // ==================== 观察 ====================

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AiTaskCenter.state.collect { render(it) }
                }
                launch {
                    AiTaskCenter.events.collect { e ->
                        if (e is AiTaskCenter.Event.QueueEnd) onQueueEnd(e)
                    }
                }
            }
        }
    }

    private fun render(s: AiTaskCenter.State) {
        // Idle 快照（没有任务）不能盖掉「已完成」的收尾文案：runQueue 结束会先复位状态，
        // 这时候 total/done 全是 0，照着画会把进度条和统计清空。
        if (!s.running && s.total == 0) return

        if (s.total > 0) binding.pbBatch.max = s.total
        binding.pbBatch.progress = s.done
        if (s.total > 0) {
            binding.tvBatchCounter.text = getString(R.string.batch_counter_fmt, s.done, s.total)
            binding.tvBatchSub.text = getString(
                if (s.style == AiStyle.HD) R.string.batch_sub_hd else R.string.batch_sub_cartoon,
                s.total
            )
        }
        binding.tvBatchStats.text = getString(R.string.batch_stats_fmt, s.ok, s.skipped, s.failed)

        if (s.running) {
            binding.tvBatchHint.visibility = View.VISIBLE
            binding.tvBatchHint.setText(
                if (s.canceling) R.string.batch_stopping_hint else R.string.batch_bg_hint
            )
            binding.btnBatchStop.visibility = View.VISIBLE
            binding.btnBatchStop.isEnabled = !s.canceling
            binding.btnBatchFinish.visibility = View.VISIBLE
            binding.btnBatchFinish.setText(R.string.batch_to_background)

            val word = s.currentWord
            val id = s.currentId
            if (word != null) {
                binding.tvBatchWord.text = word
                binding.tvBatchStatus.text = getString(R.string.batch_now_fmt, word)
            }
            // 只在「换了一张卡」时重新加载，否则每次进度刷新都会重设一遍图片
            if (id != null && id != lastShownId) {
                lastShownId = id
                binding.ivBatchPreview.loadAny(ImageStore.displayFile(this, id, null))
            }
            if (s.done == 0 && word == null) {
                binding.tvBatchStatus.text = getString(R.string.batch_preparing)
            }
        } else {
            binding.tvBatchHint.visibility = View.GONE
            binding.btnBatchStop.visibility = View.GONE
            binding.btnBatchFinish.visibility = View.VISIBLE
            binding.btnBatchFinish.setText(R.string.batch_finish)
        }
    }

    private fun onQueueEnd(e: AiTaskCenter.Event.QueueEnd) {
        val s = e.state
        // 收尾三件套自己摆好：这一刻 state 已经复位成 Idle，render 那边会直接 return
        binding.tvBatchHint.visibility = View.GONE
        binding.btnBatchStop.visibility = View.GONE
        binding.btnBatchFinish.visibility = View.VISIBLE
        binding.btnBatchFinish.setText(R.string.batch_finish)
        binding.pbBatch.progress = s.total

        binding.tvBatchStats.text = getString(R.string.batch_stats_fmt, s.ok, s.skipped, s.failed)
        binding.tvBatchWord.text = getString(R.string.batch_done_title)
        binding.tvBatchStatus.text = getString(R.string.batch_done_msg, s.ok, s.skipped, s.failed)
        if (s.skipped > 0) {
            binding.tvBatchStatus.append("\n" + getString(R.string.batch_skipped_hint))
        }
        when {
            // 额度见底：队列还在（剩下的等买了张数继续），必须给购买入口
            s.quotaOut -> {
                binding.tvBatchStatus.text = getString(R.string.batch_quota_paused, s.ok)
                AiPurchaseDialog.show(this) { count ->
                    toast(getString(R.string.ai_buy_done, count))
                }
            }
            // 一张都没做：全是跳过 → 告诉用户「不用做」而不是「完成」
            s.ok == 0 && s.failed == 0 && s.skipped > 0 ->
                binding.tvBatchStatus.text = getString(R.string.batch_nothing_to_do)
        }
    }

    // ==================== 开跑前的确认 ====================

    /**
     * 花的是真钱，所以点「高清修复」之后**再确认一次**。
     *
     * 文案里必须同时给出四件事：几张、多久、扣多少、**能不能走开**。
     * 第四件是 v2.4 新加的 —— 用户最大的顾虑是「这十几分钟我是不是得盯着」。
     */
    private fun confirmStart() {
        lifecycleScope.launch {
            val unlimited = AiPolicy.currentUnlimited(this@AiBatchActivity)
            val left = AiQuotaStore.remaining(this@AiBatchActivity)
            val costLine = if (unlimited) {
                getString(R.string.batch_confirm_unlimited)
            } else {
                getString(R.string.batch_confirm_cost_fmt, ids.size, left)
            }

            AlertDialog.Builder(this@AiBatchActivity)
                .setTitle(R.string.batch_confirm_title)
                .setMessage(
                    getString(R.string.batch_confirm_msg, ids.size, costLine) + "\n\n" +
                        getString(R.string.batch_confirm_bg)
                )
                .setPositiveButton(R.string.batch_start) { _, _ -> startWithNotifyPermission() }
                .setNegativeButton(R.string.common_cancel) { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    /** Android 13+ 要先要通知权限，否则后台跑起来用户看不到进度，还会以为没开始 */
    private fun startWithNotifyPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            doStart()
            return
        }
        pendingStart = { doStart() }
        runCatching { notifyPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            .onFailure {
                pendingStart = null
                doStart()
            }
    }

    private fun doStart() {
        if (started) return
        started = true
        if (!AiTaskCenter.enqueue(this, ids.toList(), style, redo = false)) {
            toast(getString(R.string.batch_start_failed))
            started = false
        }
    }

    // ==================== 退出 / 停止 ====================

    /**
     * 离开这一页。**任务留在后台继续**，这是 v2.4 的核心体验：
     * 用户不需要守着屏幕，也不需要在「看着它」和「用别的功能」之间二选一。
     */
    private fun leaveToBackground() {
        if (AiTaskCenter.isRunning()) {
            toast(getString(R.string.batch_bg_toast))
        }
        finish()
    }

    private fun confirmStop() {
        if (!AiTaskCenter.isRunning()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.batch_stop_confirm_title)
            .setMessage(R.string.batch_stop_confirm_msg)
            .setPositiveButton(R.string.batch_stop_btn) { _, _ ->
                AiTaskCenter.requestCancel()
                toast(getString(R.string.batch_stopping_toast))
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
