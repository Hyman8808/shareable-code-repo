package com.turing.thumbcard.ui.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.turing.thumbcard.BuildConfig
import com.turing.thumbcard.R
import com.turing.thumbcard.data.ai.AiPolicy
import com.turing.thumbcard.data.ai.AiQuotaStore
import com.turing.thumbcard.data.ai.AiRepository
import com.turing.thumbcard.data.ai.AiStyle
import com.turing.thumbcard.data.ai.AiTaskCenter
import com.turing.thumbcard.data.ai.AiTaskStore
import com.turing.thumbcard.data.ai.ArkImageClient
import com.turing.thumbcard.data.db.WordCardEntity
import com.turing.thumbcard.data.image.ImageStore
import com.turing.thumbcard.data.image.GalleryTag
import com.turing.thumbcard.data.repo.RecordRepository
import com.turing.thumbcard.databinding.ActivityAiBeautifyBinding
import com.turing.thumbcard.ui.card.ImagePreviewActivity
import com.turing.thumbcard.util.GallerySaver
import com.turing.thumbcard.util.loadAny
import kotlinx.coroutines.launch
import java.io.File

/**
 * AI 美化 / 卡通化。
 *
 * 状态只有三个，用 [render] 一个函数统一收敛，避免「按钮能不能点」散落在七八处：
 * ```
 *  空闲 ──点生成──▶ 生成中 ──成功──▶ 有结果
 *                    └──失败──▶ 空闲（并保留错误文案在屏幕上）
 * ```
 *
 * 三条硬规则：
 *  1. **余额不足不发请求**：先弹购买，不让用户白等 90 秒。
 *  2. **失败不扣次数**：扣减在仓库层「图落盘成功之后」，这里只管展示。
 *  3. **生成完立刻生效**：成功后这张卡自动切到新图，
 *     详情页/列表页 [CardDetailActivity] 回到前台时重绘即可看到。
 */
class AiBeautifyActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CARD_ID = "extra_ai_card_id"

        /**
         * 可选：进页面时预选哪一档。
         *
         * 只有预览页的「换个风格」会传 —— 它已经知道这张卡有哪些图，
         * 替用户把档位点好，省掉一次「进来还要再点一下」的流失。
         */
        const val EXTRA_STYLE = "extra_ai_style"
    }

    private lateinit var binding: ActivityAiBeautifyBinding
    private lateinit var repo: RecordRepository
    private lateinit var aiRepo: AiRepository

    private var cardId: Long = -1L
    private var card: WordCardEntity? = null

    private var selectedStyle: AiStyle = AiStyle.HD

    /** 入口点名的预选风格；null = 没指定，按「已经生成过就用已有的那档」走 */
    private var preferStyle: AiStyle? = null

    /** 生成中：主按钮禁用，防止连点扣两次 */
    private var running = false

    /** 这次的结果（成功生成或这张卡原本就有的 AI 图） */
    private var resultFile: File? = null
    private var resultStyle: AiStyle? = null

    private var pendingSave: File? = null

    /** 等通知权限回调后再开跑（拒绝也照跑，只是没有后台提醒） */
    private var pendingGenerate: (() -> Unit)? = null

    private val notifyPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        val action = pendingGenerate
        pendingGenerate = null
        action?.invoke()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val target = pendingSave
        pendingSave = null
        if (granted && target != null) doSave(target)
        else toast(getString(R.string.preview_need_permission))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiBeautifyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cardId = intent.getLongExtra(EXTRA_CARD_ID, -1L)
        if (cardId <= 0) {
            toast(getString(R.string.detail_missing_card_info)); finish(); return
        }

        preferStyle = AiStyle.fromCode(intent.getStringExtra(EXTRA_STYLE))
        preferStyle?.let { selectedStyle = it }

        repo = RecordRepository(this)
        aiRepo = AiRepository(this)

        binding.tvAiVipTag.visibility = if (BuildConfig.IS_AI_EDITION) View.VISIBLE else View.GONE

        binding.btnAiBack.setOnClickListener { finish() }
        binding.tileHd.setOnClickListener { pick(AiStyle.HD) }
        binding.tileCartoon.setOnClickListener { pick(AiStyle.CARTOON) }
        binding.btnAiGenerate.setOnClickListener { startGenerate() }
        binding.btnAiBuy.setOnClickListener {
            AiPurchaseDialog.show(this) { count ->
                toast(getString(R.string.ai_buy_done, count))
                renderQuota()
            }
        }
        binding.btnAiSave.setOnClickListener { saveResult() }
        binding.btnAiView.setOnClickListener { openPreview() }
        binding.btnAiRevert.setOnClickListener { revertToOriginal() }

        observeTask()
        load()
    }

    /**
     * 订阅后台任务。
     *
     * 生成改由前台服务执行（[AiTaskCenter]），这一页只负责「看结果」——
     * 于是**用户随时可以离开**，回来结果还在；退到后台也不会白花那 2 毛钱。
     */
    private fun observeTask() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AiTaskCenter.events.collect { e ->
                        when (e) {
                            is AiTaskCenter.Event.CardUpdated ->
                                if (e.cardId == cardId) onGenerated(e.pixels)
                            is AiTaskCenter.Event.QueueEnd ->
                                if (running) onQueueEnd(e.state)
                        }
                    }
                }
            }
        }
    }

    private fun onGenerated(pixels: String?) {
        running = false
        resultStyle = selectedStyle
        resultFile = ImageStore.ai(this, cardId, selectedStyle)
        binding.ivAiPreview.loadAny(resultFile)
        showStatus(
            if (pixels != null) getString(R.string.ai_status_done_px, pixels)
            else getString(R.string.ai_status_done)
        )
        // 卡片记录里的 aiStyle 已经被仓库层改写，本地这份要跟上，
        // 否则「换回原图」按钮和入口文案会按旧状态走
        lifecycleScope.launch {
            repo.findCard(cardId)?.let { card = it }
            render()
            renderQuota()
        }
    }

    private fun onQueueEnd(s: AiTaskCenter.State) {
        running = false
        // 成功的那张会先发 CardUpdated；走到这里还没有结果 = 这张没成
        if (resultFile == null) {
            when {
                s.quotaOut -> {
                    showStatus(getString(R.string.ai_no_quota))
                    AiPurchaseDialog.show(this) { count ->
                        toast(getString(R.string.ai_buy_done, count))
                        renderQuota()
                    }
                }
                else -> showStatus(getString(R.string.ai_err_generic))
            }
        }
        render()
        renderQuota()
    }

    override fun onResume() {
        super.onResume()
        // 从购买页/别的入口回来，额度可能变了
        renderQuota()
        reconcile()
    }

    /**
     * 回到前台时「对账」：拿数据库 + 磁盘 + 队列三处真相重新推导界面状态。
     *
     * 为什么不能只靠事件（v2.4.1 修的 bug）：
     *  页面退到后台时 `repeatOnLifecycle(STOPPED)` 会取消 events 收集，而
     *  `SharedFlow(replay = 0)` 在没有订阅者时**直接丢弃**新事件 —— 于是
     *  后台跑完的那次 [AiTaskCenter.Event.CardUpdated] 页面根本没收到，
     *  `running` 就永远停在 true，按钮一直禁用、预览一直是原图。
     *
     *  旧代码的补刷条件写成 `if (!running && !isRunning())`，而事件丢失时
     *  `running` 恰好还是 true → 补刷被自己的标志位挡死，这就是「离开一下就没了」。
     *
     * 所以这里**不看 running**，只看事实：库里有没有 aiStyle、文件在不在、队列里还有没有这张。
     */
    private fun reconcile() {
        val current = card ?: return // load() 还没回来，等它自己 render
        lifecycleScope.launch {
            val fresh = runCatching { repo.findCard(cardId) }.getOrNull() ?: return@launch
            card = fresh

            val run = runCatching { AiTaskStore.load(this@AiBeautifyActivity) }.getOrNull()
            val inQueue = run?.pending?.contains(cardId) == true
            val st = AiTaskCenter.state.value
            val mineRunning = st.running && (st.currentId == cardId || inQueue)

            // 「已生成过且文件还在」才算数：只有 aiStyle 没有文件（换机恢复）要退回原图
            val done = AiStyle.fromCode(fresh.aiStyle)?.takeIf {
                ImageStore.ai(this@AiBeautifyActivity, fresh.id, it) != null
            }

            when {
                done != null -> {
                    running = false
                    if (resultStyle != done || resultFile?.isFile != true) {
                        resultFile = ImageStore.ai(this@AiBeautifyActivity, cardId, done)
                        resultStyle = done
                        selectedStyle = done
                        binding.ivAiPreview.loadAny(resultFile)
                        showStatus(
                            getString(R.string.ai_status_existing, getString(done.badgeRes))
                        )
                    }
                }

                mineRunning -> running = true

                // 队列里还有这张，但服务已经不在了（被 ROM 杀掉 / 服务异常退出）
                // → 用户就站在这一页，直接接着做，比弹窗问一次更符合预期
                inQueue && !st.running -> {
                    running = true
                    showStatus(getString(R.string.ai_resuming))
                    AiTaskCenter.resume(this@AiBeautifyActivity)
                }

                // 之前显示「生成中」，但任务已经没了、也没有结果 → 明确告诉用户中断了，
                // 别让他对着一个永久禁用的按钮猜
                running -> {
                    running = false
                    showStatus(getString(R.string.ai_err_interrupted))
                }

                else -> {
                    running = false
                    if (resultFile == null) {
                        binding.ivAiPreview.loadAny(
                            ImageStore.original(this@AiBeautifyActivity, cardId) ?: fresh.imageUrl
                        )
                    }
                }
            }

            render()
            renderQuota()
        }
    }

    // ==================== 载入 ====================

    private fun load() {
        lifecycleScope.launch {
            val c = repo.findCard(cardId) ?: run {
                toast(getString(R.string.detail_card_missing)); finish(); return@launch
            }
            card = c

            // 打开即落盘原图，后面生成直接用本地文件
            val original = ImageStore.ensureOriginal(this@AiBeautifyActivity, c.id, c.imageUrl)

            // 「已生成过且文件还在」才进结果态。只信 aiStyle 不够：
            // 换机恢复可能只有数据库没有文件，那种情况要退回原图重新生成。
            val existingStyle = AiStyle.fromCode(c.aiStyle)?.takeIf {
                ImageStore.ai(this@AiBeautifyActivity, c.id, it) != null
            }

            // 入口点名要另一档（预览页的「换个风格」）时，不要把已有的那档摆成结果：
            // 否则用户以为刚生成了一张，点「存到相册」存的还是老图。
            val showExisting = existingStyle != null &&
                (preferStyle == null || preferStyle == existingStyle)

            if (showExisting && existingStyle != null) {
                val existingFile = ImageStore.ai(this@AiBeautifyActivity, c.id, existingStyle)
                resultFile = existingFile
                resultStyle = existingStyle
                selectedStyle = existingStyle
                binding.ivAiPreview.loadAny(existingFile)
                showStatus(
                    getString(R.string.ai_status_existing, getString(existingStyle.badgeRes))
                )
            } else {
                binding.ivAiPreview.loadAny(original ?: c.imageUrl)
                if (existingStyle != null) {
                    showStatus(
                        getString(R.string.ai_status_switch_hint, getString(existingStyle.badgeRes))
                    )
                }
            }

            // 页面被重建（转后台又回来）时任务可能还在跑：把「生成中」接上，
            // 否则按钮会显示成「开始生成」，用户以为失败了会再点一次
            if (!running) {
                val st = AiTaskCenter.state.value
                val queued = runCatching { AiTaskStore.load(this@AiBeautifyActivity)?.pending?.firstOrNull() }
                    .getOrNull()
                running = st.running && (st.currentId == cardId || queued == cardId)
            }

            render()
            renderQuota()

            // load 完成时如果是「服务被杀但队列还在」，上面那段接不上（state 已经是 Idle），
            // 交给对账逻辑把任务续起来 —— 否则用户看到的是一张永远不动的原图
            reconcile()
        }
    }

    // ==================== 生成 ====================

    private fun pick(style: AiStyle) {
        if (running) return
        selectedStyle = style
        render()
    }

    private fun startGenerate() {
        if (running) return
        val c = card ?: return

        if (!AiPolicy.isUnlimited(c) && !AiQuotaStore.canGenerate(this)) {
            // 先说明为什么不能点，再给出口
            toast(getString(R.string.ai_no_quota))
            AiPurchaseDialog.show(this) { count ->
                toast(getString(R.string.ai_buy_done, count))
                renderQuota()
            }
            return
        }

        // Android 13+ 没有通知权限 → 后台跑完用户收不到提醒，先要一次；
        // 拒绝也照跑（功能不能被权限卡死，只是少一条通知）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingGenerate = { doGenerate() }
            runCatching { notifyPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                .onFailure {
                    pendingGenerate = null
                    doGenerate()
                }
            return
        }
        doGenerate()
    }

    /**
     * 真正开跑：把这一张交给后台队列，自己只订阅结果。
     *
     * `redo = true` 很关键：单张页的按钮可能是「再生成一张」，
     * 而批量那条「同风格已生成就跳过」的省额度规则**不能**套在这里，
     * 否则用户点半天没反应。
     */
    private fun doGenerate() {
        val c = card ?: return
        if (running) return

        if (AiTaskCenter.isRunning()) {
            // 已有任务在跑（多半是从别处发起的批量）→ 别硬塞，说明白就行
            showStatus(getString(R.string.ai_busy))
            return
        }

        hideStatus()
        running = true
        render()

        if (!AiTaskCenter.enqueue(this, listOf(c.id), selectedStyle, redo = true)) {
            running = false
            render()
            showStatus(getString(R.string.batch_start_failed))
        }
    }

    private fun errorText(f: ArkImageClient.Fail): String = getString(
        when (f) {
            ArkImageClient.Fail.NETWORK -> R.string.ai_err_network
            ArkImageClient.Fail.TIMEOUT -> R.string.ai_err_timeout
            ArkImageClient.Fail.AUTH -> R.string.ai_err_auth
            ArkImageClient.Fail.RATE_LIMIT -> R.string.ai_err_rate
            ArkImageClient.Fail.CONTENT -> R.string.ai_err_content
            ArkImageClient.Fail.SERVER -> R.string.ai_err_server
        }
    )

    // ==================== 结果操作 ====================

    private fun saveResult() {
        val file = resultFile
        if (file == null || !file.isFile) {
            toast(getString(R.string.preview_save_wait)); return
        }
        if (GallerySaver.needsPermission() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSave = file
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        doSave(file)
    }

    private fun doSave(file: File) {
        lifecycleScope.launch {
            val c = card
            val base = (c?.nameEnglish?.takeIf { it.isNotBlank() }
                ?: c?.nameChinese?.takeIf { it.isNotBlank() }
                ?: getString(R.string.card_title)) +
                "_" + (resultStyle?.let { getString(it.badgeRes) } ?: "")
            val meta = resultStyle?.let { GalleryTag.Meta(cardId, it) }
            when (GallerySaver.save(this@AiBeautifyActivity, file, base, meta)) {
                is GallerySaver.Result.Ok -> toast(getString(R.string.preview_saved, base))
                is GallerySaver.Result.NeedPermission -> toast(getString(R.string.preview_need_permission))
                is GallerySaver.Result.Err -> toast(getString(R.string.preview_save_failed))
            }
        }
    }

    private fun openPreview() {
        startActivity(
            Intent(this, ImagePreviewActivity::class.java)
                .putExtra(ImagePreviewActivity.EXTRA_CARD_ID, cardId)
        )
    }

    /** 换回原图：清掉卡片的 aiStyle 标记，文件留着，随时能再切回来 */
    private fun revertToOriginal() {
        lifecycleScope.launch {
            aiRepo.switchTo(cardId, null)
            resultFile = null
            resultStyle = null
            card = card?.copy(aiStyle = null, aiAt = null)
            binding.ivAiPreview.loadAny(ImageStore.original(this@AiBeautifyActivity, cardId) ?: card?.imageUrl)
            showStatus(getString(R.string.ai_status_reverted))
            render()
        }
    }

    // ==================== 渲染 ====================

    private fun render() {
        binding.groupAiRunning.visibility = if (running) View.VISIBLE else View.GONE

        // 选项选中态
        binding.tileHd.setBackgroundResource(
            if (selectedStyle == AiStyle.HD) R.drawable.bg_style_tile_on else R.drawable.bg_style_tile
        )
        binding.tileCartoon.setBackgroundResource(
            if (selectedStyle == AiStyle.CARTOON) R.drawable.bg_style_tile_on else R.drawable.bg_style_tile
        )

        // 「已有」角标：这一档已经生成过，重生成会覆盖
        val c = card
        binding.badgeHd.visibility =
            if (c != null && ImageStore.ai(this, c.id, AiStyle.HD) != null) View.VISIBLE else View.GONE
        binding.badgeCartoon.visibility =
            if (c != null && ImageStore.ai(this, c.id, AiStyle.CARTOON) != null) View.VISIBLE else View.GONE

        // 主按钮：生成中 → 「正在生成…」；有结果 → 「再生成一张」；否则「开始生成」
        binding.btnAiGenerate.isEnabled = !running
        binding.btnAiGenerate.text = when {
            running -> getString(R.string.ai_generating_button)
            resultFile != null -> getString(R.string.ai_again)
            else -> getString(R.string.ai_generate)
        }

        binding.groupAiResultActions.visibility =
            if (resultFile != null && !running) View.VISIBLE else View.GONE

        // 额度没了就把主按钮压成「购买张数」，别让用户点了才被告知。
        // 不限额设备（白名单）永远不进这个分支。
        if (!running && !AiPolicy.isUnlimited(card) && !AiQuotaStore.canGenerate(this)) {
            binding.btnAiGenerate.text = getString(R.string.ai_buy_entry)
        }
    }

    private fun renderQuota() {
        val unlimited = AiPolicy.isUnlimited(card)
        binding.tvAiQuota.text = AiPolicy.quotaText(
            this,
            AiQuotaStore.freeRemaining(this),
            AiQuotaStore.creditsRemaining(this),
            unlimited
        )
        // 不限额设备上没有「买张数」这回事，入口直接收掉，少一个能点错的东西
        binding.btnAiBuy.visibility = if (unlimited) View.GONE else View.VISIBLE
    }

    private fun showStatus(text: String) {
        binding.tvAiStatus.text = text
        binding.tvAiStatus.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        binding.tvAiStatus.visibility = View.GONE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
