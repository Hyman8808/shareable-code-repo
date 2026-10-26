package com.turing.thumbcard.ui.card

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.turing.thumbcard.R
import com.turing.thumbcard.data.ai.AiPolicy
import com.turing.thumbcard.data.ai.AiQuotaStore
import com.turing.thumbcard.data.ai.AiRepository
import com.turing.thumbcard.data.ai.AiStyle
import com.turing.thumbcard.data.ai.AiTaskCenter
import com.turing.thumbcard.data.db.WordCardEntity
import com.turing.thumbcard.data.image.ImageStore
import com.turing.thumbcard.data.image.GalleryTag
import com.turing.thumbcard.data.i18n.LanguagePrefs
import com.turing.thumbcard.data.i18n.NameResolver
import com.turing.thumbcard.data.repo.RecordRepository
import com.turing.thumbcard.databinding.ActivityImagePreviewBinding
import com.turing.thumbcard.ui.ai.AiBeautifyActivity
import com.turing.thumbcard.util.GallerySaver
import com.turing.thumbcard.util.loadAny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 原图 / AI 图的大图预览，支持存进相册。
 *
 * 三条产品规则：
 *  1. **看到的就是能存的**：右上角下载按钮保存的是「当前显示的这一张」，
 *     不是「永远保存原图」。预览页底部切到「高清」再点下载，存的就是高清图。
 *  2. **原图永远可达**：只要有 AI 图，底部就一定有「原图」胶囊，
 *     用户随时能确认「相机当初拍的是什么」，这对家长是信任问题，不是功能。
 *  3. **打开即落盘**：进页面就把原图下载到本地（[ImageStore.ensureOriginal]），
 *     之后断网、OSS 链接过期都还能看、还能存。
 */
class ImagePreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CARD_ID = "extra_preview_card_id"

        /**
         * 可左右切换的卡片 id 顺序（来源列表的**当前顺序**），与详情页 `EXTRA_IDS` 同语义。
         *
         * 为什么不传「来源」进来再查一遍库：
         *  - 收藏页可能带着搜索关键词，重查就把过滤条件丢了；
         *  - 首页顺序是「今天 → 更早」，与 id 倒序不等价；
         *  - 「全部记录」页可能选了「全部设备」，跨设备顺序只有那一页知道。
         */
        const val EXTRA_IDS = "extra_preview_ids"

        /** 回传给调用方：用户最后停留的那张卡（详情页据此同步，避免「翻了5张退回去还是第1张」） */
        const val RESULT_CARD_ID = "result_preview_card_id"

        /**
         * 唯一的慢加载阈值：到这里旧图就开始「撒谎」了
         *（页码已经 4/12，画面还是第 3 张），spinner 与压暗同时发生。
         */
        private const val SLOW_LOAD_MS = 250L

        /** 原图落盘的等待上限：超时就用 URL 兜底，不让用户对着 spinner 干等 */
        private const val NET_TIMEOUT_MS = 3000L

        /** 边界提示：6–7 个字，1.5s 已留足阅读余量；超过 2s 会和「再滑一次」的节奏打架 */
        private const val BOUNDARY_HINT_MS = 1500L

        /** 操作结果（已保存 / 需要权限 / 等着落盘）：带文件名或整句解释，1.5s 读不完 */
        private const val ACTION_MS = 2500L

        /** 放大态被拦的提示：每条会话只出一次，短一些不挡看图 */
        private const val ZOOM_HINT_MS = 1200L

        /** 提示条优先级：操作反馈 > 边界 > 放大提示 */
        private const val PILL_ACTION = 3
        private const val PILL_BOUNDARY = 2
        private const val PILL_ZOOM = 1

        /** 到头回弹距离（dp） */
        private const val EDGE_BOUNCE_DP = 12

        /**
         * 统一入口。
         *
         * 列表行的缩略图、网格卡的封面、详情页的大图都从这里进——
         * 三处如果各自 `startActivity`，将来改传参就得改三个地方，
         * 而漏掉一处不会编译报错，只会「点了没反应」。
         *
         * @param ids 来源列表的顺序；传 null 表示这张图没有可滑动的兄弟（单张看图）
         */
        fun start(context: Context, cardId: Long, ids: LongArray? = null) {
            val intent = Intent(context, ImagePreviewActivity::class.java)
                .putExtra(EXTRA_CARD_ID, cardId)
                .putExtra(EXTRA_IDS, ids)
            // 从非 Activity context（如 Application）启动必须带 NEW_TASK
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityImagePreviewBinding
    private lateinit var repo: RecordRepository
    private lateinit var aiRepo: AiRepository

    private var cardId: Long = -1L
    private var card: WordCardEntity? = null

    /** 可左右切换的卡片序列（来源列表顺序）与当前位置 */
    private var ids: LongArray = longArrayOf()
    private var index: Int = 0

    /**
     * 每次「开始加载某张卡」自增，异步结果回来先对 token。
     *
     * 没有它，快速连滑会串图：先发出的请求后返回，把新卡的画面换成旧卡的。
     * 这个页面比详情页更容易踩到，因为原图要先 `ensureOriginal()` 下载。
     */
    private var loadToken: Int = 0

    /**
     * 列表里**是否至少有一张卡**做过 AI 美化。
     *
     * 底部胶囊行按「有没有 AI 图」显隐，若按当前卡判断，切到一张没做过美化的卡整行就 GONE，
     * 底部 CTA 和提示行会一起下跳 48dp —— 快速连滑时页面像在抖。
     * 所以改成「整批都没有才收起」，本卡缺档用 INVISIBLE 保住高度。
     */
    private var listHasAi: Boolean = false

    /** 放大态被拦的提示每次进页面只给一次（以「尝试失败」触发，不预教） */
    private var zoomHintShown: Boolean = false

    /** 提示条的自动淡出任务，重复触发时要先撤掉上一个 */
    private var pillRunnable: Runnable? = null

    /** 提示条被占用的截止时刻（uptimeMillis）与占用者优先级：操作反馈 > 边界 > 放大 */
    private var pillUntil: Long = 0L
    private var pillPriority: Int = 0

    /** 切卡的「250ms 还没好」反馈任务 */
    private var slowRunnable: Runnable? = null

    /** 当前显示的是哪种风格；null = 原图 */
    private var shownStyle: AiStyle? = null

    /** 当前显示的文件（下载按钮存的就是它） */
    private var shownFile: File? = null

    /** 待保存的目标：等权限回调后继续 */
    private var pendingSave: File? = null

    /**
     * 点「美化」时预选哪一档。
     *
     * 这里不做「二选一对话框」：多一次点击就多一次流失，直接替用户选**还没做过的那档**
     * （已有高清 → 预选卡通化，反之预选高清），进去之后仍可改。
     */
    private var preferStyle: AiStyle = AiStyle.HD

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val target = pendingSave
        pendingSave = null
        if (granted && target != null) doSave(target)
        else showAction(getString(R.string.preview_need_permission))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImagePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cardId = intent.getLongExtra(EXTRA_CARD_ID, -1L)
        if (cardId <= 0) {
            toast(getString(R.string.detail_missing_card_info)); finish(); return
        }

        // 来源列表顺序。列表刚刷新过、这张卡已不在里面时退化成「只有这一张」：
        // 至少保证当前这张能正常看，而不是把 index 定在 -1 上后面全部越界。
        ids = intent.getLongArrayExtra(EXTRA_IDS)?.takeIf { it.isNotEmpty() } ?: longArrayOf(cardId)
        if (cardId !in ids) ids = longArrayOf(cardId)
        index = ids.indexOf(cardId).coerceAtLeast(0)

        repo = RecordRepository(this)
        aiRepo = AiRepository(this)

        binding.btnPreviewBack.setOnClickListener { backOrReset() }
        binding.btnPreviewDownload.setOnClickListener { saveCurrent() }

        // 底部操作说明：有多张时才宣传「左右滑动」，单张时不承诺做不到的事
        binding.tvPreviewHint.setText(
            if (ids.size > 1) R.string.preview_hint_swipe else R.string.preview_hint
        )

        // 手势出口在控件内部（见 ZoomImageView.onSwipeOut 的注释），这里只接结果
        binding.ivPreview.onSwipeOut = { delta ->
            step(delta)
            true
        }
        binding.ivPreview.onSwipeBlocked = {
            if (!zoomHintShown) {
                zoomHintShown = true
                showPill(getString(R.string.preview_zoom_locked), ZOOM_HINT_MS, PILL_ZOOM)
            }
        }

        // 读屏用户的左右轻扫被触摸探索吃掉，必须另给入口；只在触摸探索开启时出现
        val a11y = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (a11y?.isTouchExplorationEnabled == true && ids.size > 1) {
            binding.btnPreviewPrev.visibility = View.VISIBLE
            binding.btnPreviewNext.visibility = View.VISIBLE
        }
        binding.btnPreviewPrev.setOnClickListener { step(-1) }
        binding.btnPreviewNext.setOnClickListener { step(1) }
        binding.btnPreviewRetry.setOnClickListener { retryLoad() }

        // 放大状态下按返回键先回到「完整显示」，再按一次才退出。
        // 这是看图类页面的通行手感：用户把图放大看局部之后，退出的意图通常是「先缩回去看全图」。
        onBackPressedDispatcher.addCallback(this) { backOrReset() }

        binding.chipPreviewOriginal.setOnClickListener { switchTo(null) }
        binding.chipPreviewHd.setOnClickListener { switchTo(AiStyle.HD) }
        binding.chipPreviewCartoon.setOnClickListener { switchTo(AiStyle.CARTOON) }

        binding.btnPreviewBeautify.setOnClickListener { openBeautify() }

        observeGeneration()
        // 首次进页不延迟：用户点了图，就该立刻有反馈（只有切卡才走 250ms 那条路）
        lifecycleScope.launch {
            if (ids.size > 1) computeListHasAi()
            val token = ++loadToken
            loadInto(token, animate = false)
        }
    }

    /** 顶栏返回按钮与系统返回键共用一个动作：放大中先复位，没放大才退出 */
    private fun backOrReset() {
        if (binding.ivPreview.isZoomed()) binding.ivPreview.resetToBase() else finish()
    }

    /**
     * 订阅生成事件。
     *
     * 用户可能不等结果就按返回回到这一页 —— 这时美化任务还在后台跑。
     * 这一页停在 STARTED 就能收到 [AiTaskCenter.Event.CardUpdated]，
     * 生成完成那一刻图自己换掉，不用用户退出去再进来。
     */
    private fun observeGeneration() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AiTaskCenter.events.collect { e ->
                    if (e is AiTaskCenter.Event.CardUpdated && e.cardId == cardId) refreshFromDb()
                }
            }
        }
    }

    // ==================== 左右滑动切卡 ====================

    /**
     * 切到相对位置的一张：`+1` = 下一张（左滑），`-1` = 上一张（右滑）。
     *
     * 边界不弹对话框 —— 这是浏览型操作，弹窗会把「继续看」打断。
     * 只回弹 + 一条轻提示。回弹是**全 App 唯一允许图片位移的地方**：
     * 它表达「顶住了」，不会和「左右切卡」混淆，因为切卡本身完全不移动图片。
     */
    private fun step(delta: Int) {
        if (ids.size <= 1) return
        val target = index + delta
        if (target !in ids.indices) {
            edgeBounce(delta)
            showPill(
                getString(if (delta > 0) R.string.detail_swipe_last else R.string.detail_swipe_first),
                BOUNDARY_HINT_MS,
                PILL_BOUNDARY
            )
            return
        }

        index = target
        cardId = ids[target]
        // 回传给调用方（详情页据此同步到最后停下的那张）
        setResult(RESULT_OK, Intent().putExtra(RESULT_CARD_ID, cardId))

        pendingSave = null   // 🔴 权限回调绝不能把上一张写进相册
        card = null
        shownStyle = null
        shownFile = null
        zoomHintShown = false
        hidePill()
        hideRetry()
        hideEmpty()

        // 页码同帧更新：迟 100ms 再更新，会让人以为「已经到位了，其实是旧的」
        renderPosition(slideFrom = delta)

        // 作废在途请求 → 用新 token 重开一轮（慢加载反馈也带这个 token，旧卡的定时器自动失效）
        val token = ++loadToken
        scheduleSlowFeedback(token)
        reload(token, animate = true)
    }

    private fun reload(token: Int, animate: Boolean) {
        lifecycleScope.launch { loadInto(token, animate) }
    }

    /**
     * 加载某一张卡。
     *
     * 三个阶段刻意分开，因为「看到旧图」和「看到黑屏」都是不可接受的：
     *  t0  页码已更新、旧图**原样留着**（绝不清空 —— 清空就是黑屏）；
     *  t1  到 250ms 还没解码完 → spinner 淡入 + 旧图压到 0.45；
     *  t2  新图真正解码完成 → 换图、复位缩放、α 0.85→1（140ms）。
     *
     * 为什么不学详情页「整页位移 18% 滑入」：这页的图是满屏的，一移就露纯黑边，
     * 看起来像图变小了；本页也没有任何东西可以放弃位移。
     */
    private suspend fun loadInto(token: Int, animate: Boolean) {
        // 首次进页立即给 spinner：用户点了图，就该立刻有反馈。
        // 切卡走 250ms 的慢反馈，由 step() 触发 —— 图在内存缓存时它根本不会出现。
        if (!animate) showSpinner()

        val c = runCatching { repo.findCard(cardId) }.getOrNull()
        if (token != loadToken) return

        if (c == null) {
            hideLoading()
            if (ids.size <= 1) {
                toast(getString(R.string.detail_card_missing)); finish(); return
            }
            // 多张时不退出：留着切卡能力，这张显示空态，用户可以继续滑走
            showEmpty(getString(R.string.detail_card_missing))
            return
        }
        card = c

        val lang = LanguagePrefs.current(this@ImagePreviewActivity)
        val n = NameResolver.resolve(this@ImagePreviewActivity, c.id, c.nameChinese, c.nameEnglish, lang)
        setTitle(n.secondary ?: n.primary, animate)

        // 默认显示「卡片当前用的那张」：有 AI 图就显示 AI 图，否则原图
        val style = AiStyle.fromCode(c.aiStyle)
        shownStyle = style
        renderChips(c)
        renderCta(c)

        // 打开即落盘：这次下完，后面都从本地读。切卡时这一句就是主要耗时。
        // 3s 封顶：CDN 抽风时不能让用户对着 spinner 干等，超时后交给 URL 兜底。
        val local = withTimeoutOrNull(NET_TIMEOUT_MS) {
            ImageStore.ensureOriginal(this@ImagePreviewActivity, c.id, c.imageUrl)
        }
        if (token != loadToken) return

        if (local == null && c.imageUrl.isNullOrBlank()) {
            // 这张本来就没有图：**不退出页面**，否则用户连「滑走看下一张」都做不到
            hideLoading()
            binding.ivPreview.setImageResource(R.drawable.ic_image_placeholder)
            binding.ivPreview.alpha = 1f
            shownStyle = null
            shownFile = null
            renderChipState()
            showEmpty(getString(R.string.preview_no_image))
            return
        }

        applyShown(style, local, animate, token)
        if (animate) announceCurrent()
    }

    /**
     * 本页缓存的「整批是否有人做过 AI」。
     *
     * 只算一次：ids 在页面生命周期内不变，AI 图只增不减；
     * 万一进页时还没有、之后在详情页生成了，[renderChips] 里会就地补上。
     */
    private suspend fun computeListHasAi() {
        if (listHasAi) return
        listHasAi = withContext(Dispatchers.IO) {
            ids.any { id ->
                ImageStore.ai(this@ImagePreviewActivity, id, AiStyle.HD) != null ||
                    ImageStore.ai(this@ImagePreviewActivity, id, AiStyle.CARTOON) != null
            }
        }
    }

    /** 到头了：图片往反方向顶 12dp 再弹回。先 cancel 再归零，否则连滑会累积位移 */
    private fun edgeBounce(delta: Int) {
        val v = binding.ivPreview
        val w = EDGE_BOUNCE_DP * resources.displayMetrics.density * delta
        v.animate().cancel()
        v.translationX = 0f
        v.animate().translationX(-w).setDuration(90)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { v.animate().translationX(0f).setDuration(120).start() }
            .start()
    }

    /** 第几张 / 共几张：只有一张时不显示（「1 / 1」是噪声） */
    private fun renderPosition(slideFrom: Int = 0) {
        val tv = binding.tvPreviewPos
        if (ids.size <= 1) {
            tv.visibility = View.GONE
            return
        }
        tv.visibility = View.VISIBLE
        tv.text = getString(R.string.detail_pos_fmt, index + 1, ids.size)
        // 页码动、图片不动：这是切卡唯一的方向线索，暗示「新的一张从哪边进来」
        if (slideFrom != 0) {
            tv.animate().cancel()
            tv.translationX = 6f * resources.displayMetrics.density * slideFrom
            tv.animate().translationX(0f).setDuration(80)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /**
     * 顶部悬浮提示条 —— 本页**所有操作反馈**的唯一出口（无图/失败除外，那两条是就地占位）。
     *
     * 为什么这页不用 Toast：Toast 是浅底深字的系统窗口，压在黑底满屏照片上像一块补丁，
     * 位置也不受控，会盖住底部胶囊。只有「提示后页面立即退出」的路径仍用 Toast，
     * 因为 pill 是 Activity 的 View，Activity 一结束就没了，用户被弹回列表时看不到任何解释。
     *
     * 优先级靠「占用窗口」实现：高优先级现场接管，低优先级既不插队、也不排队。
     * 操作反馈 > 边界 > 放大提示。
     */
    private fun showPill(text: String, durationMs: Long, priority: Int) {
        val v = binding.tvPreviewBoundary
        val now = SystemClock.uptimeMillis()
        if (now < pillUntil && priority < pillPriority) return

        pillRunnable?.let { v.removeCallbacks(it) }
        v.animate().cancel()
        v.text = text
        v.alpha = 1f
        v.visibility = View.VISIBLE
        pillUntil = now + durationMs
        pillPriority = priority

        val r = Runnable {
            v.animate().alpha(0f).setDuration(180).withEndAction {
                v.visibility = View.GONE
                v.alpha = 1f
            }.start()
            pillUntil = 0L
            pillPriority = 0
        }
        pillRunnable = r
        v.postDelayed(r, durationMs)
        // 提示条本身对读屏不可见（见 XML），改由这里念原文
        v.announceForAccessibility(text)
    }

    /** 操作结果（已保存 / 需要权限 / 等待落盘）：给足 2.5s —— 带文件名或整句解释，1.5s 读不完 */
    private fun showAction(text: String) = showPill(text, ACTION_MS, PILL_ACTION)

    private fun hidePill() {
        val v = binding.tvPreviewBoundary
        pillRunnable?.let { v.removeCallbacks(it) }
        pillRunnable = null
        v.visibility = View.GONE
        v.alpha = 1f
        pillUntil = 0L
        pillPriority = 0
    }

    /**
     * 切卡的「慢加载」反馈：**单一 250ms 阈值**，spinner 与压暗旧图同时发生。
     *
     * 为什么不是「80ms 弹 spinner、250ms 再压暗」：spinner 在屏幕正中、又是满屏图的
     * 唯一焦点，80ms 就让它淡入，缓存命中时它刚显示到一半就被新图盖掉，
     * 留下一个半透明的环 —— 比晚出现更难受。两个阈值只差 170ms，也验证不出价值。
     */
    private fun scheduleSlowFeedback(token: Int) {
        cancelSlowFeedback()
        val r = Runnable {
            if (token != loadToken) return@Runnable
            showSpinner()
            binding.ivPreview.animate().alpha(0.45f).setDuration(150).start()
        }
        slowRunnable = r
        binding.pbPreview.postDelayed(r, SLOW_LOAD_MS)
    }

    private fun cancelSlowFeedback() {
        slowRunnable?.let { binding.pbPreview.removeCallbacks(it) }
        slowRunnable = null
    }

    private fun showSpinner() {
        val pb = binding.pbPreview
        pb.animate().cancel()
        pb.visibility = View.VISIBLE
        pb.alpha = 0f
        pb.animate().alpha(1f).setDuration(120).start()
    }

    private fun hideLoading() {
        val pb = binding.pbPreview
        cancelSlowFeedback()
        pb.animate().cancel()
        if (pb.visibility != View.VISIBLE) return
        pb.animate().alpha(0f).setDuration(100).withEndAction {
            pb.visibility = View.GONE
            pb.alpha = 1f
        }.start()
    }

    private fun setTitle(text: String?, animate: Boolean) {
        val tv = binding.tvPreviewTitle
        if (!animate) {
            tv.text = text
            return
        }
        tv.animate().cancel()
        tv.animate().alpha(0f).setDuration(80).withEndAction {
            tv.text = text
            tv.animate().alpha(1f).setDuration(120).start()
        }.start()
    }

    /** 底部胶囊当前哪一档，用于读屏 —— 不能只说「第 3 张」，得说清看的是原图还是高清 */
    private fun currentChipLabel(): String = when (shownStyle) {
        null -> getString(R.string.preview_style_original)
        AiStyle.HD -> getString(R.string.ai_badge_hd)
        AiStyle.CARTOON -> getString(R.string.ai_badge_cartoon)
    }

    /**
     * 读屏标签。
     *
     * 成功与失败用**两条完整格式串**，不做「前缀 + 后缀」拼接：
     * 日/德/俄的语序不同，靠拼接早晚会出病句；整句交给翻译，只改一条。
     */
    private fun updateA11yLabel(failed: Boolean) {
        binding.ivPreview.contentDescription = when {
            ids.size <= 1 -> getString(R.string.preview_title)
            failed -> getString(
                R.string.preview_a11y_pos_fail_fmt, index + 1, ids.size, currentChipLabel()
            )
            else -> getString(
                R.string.preview_a11y_pos_fmt, index + 1, ids.size, currentChipLabel()
            )
        }
    }

    /** 加载失败：撤掉旧图（不能让「第 4/12 张」下面是第 3 张的照片），给可点的重试占位 */
    private fun showRetry() {
        hideLoading()
        hidePill()
        hideEmpty()
        binding.ivPreview.animate().alpha(0f).setDuration(120).start()

        val v = binding.btnPreviewRetry
        v.animate().cancel()
        v.alpha = 0f
        v.visibility = View.VISIBLE
        v.animate().alpha(1f).setDuration(120).start()

        updateA11yLabel(failed = true)
        // 失败只说一次：由占位本身朗读（它同时就是按钮）。**不再额外弹 pill** ——
        // 占位已在视觉中心且带动作，再弹一条等于同一件事说两遍，还会把注意力从「重试」引开。
        v.announceForAccessibility(
            if (ids.size > 1) {
                getString(
                    R.string.preview_a11y_pos_fail_fmt, index + 1, ids.size, currentChipLabel()
                )
            } else {
                getString(R.string.preview_load_failed)
            }
        )
    }

    private fun hideRetry() {
        val v = binding.btnPreviewRetry
        v.animate().cancel()
        v.visibility = View.GONE
        v.alpha = 1f
    }

    /** 无图态：纯文字、无底无边、不可点 —— 和「加载失败」必须一眼能分开 */
    private fun showEmpty(text: String) {
        hideLoading()
        hideRetry()
        binding.ivPreview.animate().alpha(0f).setDuration(120).start()

        val v = binding.tvPreviewEmpty
        v.text = text
        v.animate().cancel()
        v.alpha = 0f
        v.visibility = View.VISIBLE
        v.animate().alpha(1f).setDuration(120).start()
        updateA11yLabel(failed = true)
    }

    private fun hideEmpty() {
        val v = binding.tvPreviewEmpty
        v.animate().cancel()
        v.visibility = View.GONE
        v.alpha = 1f
    }

    /** 用户点重试：立刻给 spinner（他已经等过一次了，不能再让他猜有没有反应） */
    private fun retryLoad() {
        hideRetry()
        hideEmpty()
        showSpinner()
        binding.ivPreview.alpha = 0f
        val token = ++loadToken
        reload(token, animate = true)
    }

    private fun announceCurrent() {
        if (ids.size <= 1) return
        binding.ivPreview.announceForAccessibility(
            getString(R.string.preview_a11y_pos_fmt, index + 1, ids.size, currentChipLabel())
        )
    }

    /**
     * 底部胶囊：整批里有没有人做过 AI 图，决定整行显隐。
     *
     * 按**整批**判而不是按当前卡判：按当前卡判的话，切到一张没做过美化的卡整行就 GONE，
     * 底部 CTA 和提示行会一起下跳 48dp —— 快速连滑时页面像在抖。
     * 本卡缺档用 INVISIBLE 保住测量尺寸，绝不画灰态（灰态会被读成「有能力但暂不可用」）。
     */
    private fun renderChips(c: WordCardEntity) {
        val hd = ImageStore.ai(this, c.id, AiStyle.HD) != null
        val cartoon = ImageStore.ai(this, c.id, AiStyle.CARTOON) != null
        val thisHasAi = hd || cartoon
        // 进页时整批没有、之后在详情页生成了：就地补上，不必重进页面
        if (thisHasAi) listHasAi = true

        binding.groupPreviewStyle.visibility = if (listHasAi) View.VISIBLE else View.GONE
        // 「原图」在本卡没有任何 AI 图时仍然显示（它就是当前选中项）：
        // 留一个有意义的选中态，比留一条全空的行好读
        binding.chipPreviewOriginal.visibility = if (thisHasAi) View.VISIBLE else View.INVISIBLE
        binding.chipPreviewHd.visibility = if (hd) View.VISIBLE else View.INVISIBLE
        binding.chipPreviewCartoon.visibility = if (cartoon) View.VISIBLE else View.INVISIBLE
    }

    /**
     * 底部美化入口。
     *
     * 文案按「这张卡已经有什么」分流，而不是固定一句：
     *  · 一张 AI 图都没有 → 「AI 美化这张」，首次转化，把动作说清楚；
     *  · 已经有其中一档 → 「换个风格」，不重复卖同一件东西，直接指向另一档。
     *
     * 额度小字跟着走：不限额设备显示「不限」，否则演示机在客户面前会显示「剩 0 张」。
     */
    private fun renderCta(c: WordCardEntity) {
        val hasHd = ImageStore.ai(this, c.id, AiStyle.HD) != null
        val hasCartoon = ImageStore.ai(this, c.id, AiStyle.CARTOON) != null

        // 已有高清就预选卡通化，反之预选高清：点一次就到了「没做过的那档」
        preferStyle = if (hasHd && !hasCartoon) AiStyle.CARTOON else AiStyle.HD

        // 这张正在后台生成：按钮改成「正在生成…」并禁用，
        // 否则用户会以为刚才那一下没生效，再点一次等于重复扣额度
        val st = AiTaskCenter.state.value
        val busy = st.running && st.currentId == c.id

        binding.btnPreviewBeautify.isEnabled = !busy
        binding.btnPreviewBeautify.setText(
            when {
                busy -> R.string.ai_generating_button
                hasHd || hasCartoon -> R.string.preview_beautify_switch
                else -> R.string.preview_beautify
            }
        )
        binding.tvPreviewQuota.text = AiPolicy.quotaShort(
            this,
            AiQuotaStore.freeRemaining(this),
            AiQuotaStore.creditsRemaining(this),
            AiPolicy.isUnlimited(c)
        )
    }

    private fun openBeautify() {
        val c = card ?: return
        startActivity(
            Intent(this, AiBeautifyActivity::class.java)
                .putExtra(AiBeautifyActivity.EXTRA_CARD_ID, c.id)
                .putExtra(AiBeautifyActivity.EXTRA_STYLE, preferStyle.code)
        )
    }

    /**
     * 从美化页回来：可能刚生成了一张，也可能把同一档重做覆盖了原文件。
     *
     * 两条都要能切过去：
     *  · 生成了另一档 → 库里 aiStyle 变了；
     *  · 重做同一档   → aiStyle 没变，但文件被覆盖（lastModified 变了）。
     * 只比 aiStyle 会漏掉后者，用户会觉得「我明明重做了，怎么还是老图」。
     */
    override fun onResume() {
        super.onResume()
        if (card != null) refreshFromDb()
    }

    /** 按「库 + 文件」两处事实重画，不看任何请求状态 —— 事件可能丢，事实不会 */
    private fun refreshFromDb() {
        val prevStyle = shownStyle
        val prevFile = shownFile
        // 捕获发起时的卡与 token：这个协程回来时可能已经切到别的卡了
        val targetId = cardId
        val myToken = loadToken

        lifecycleScope.launch {
            val fresh = runCatching { repo.findCard(targetId) }.getOrNull() ?: return@launch
            if (myToken != loadToken) return@launch
            card = fresh
            renderChips(fresh)
            renderCta(fresh)

            val nowStyle = AiStyle.fromCode(fresh.aiStyle)
            val original = {
                ImageStore.original(this@ImagePreviewActivity, targetId)
            }

            if (nowStyle == null) {
                // 在美化页点了「还原原图」→ 这边也得跟着退回去
                if (prevStyle != null) applyShown(null, original())
                return@launch
            }

            val nowFile = ImageStore.ai(this@ImagePreviewActivity, targetId, nowStyle)
            val overwritten = nowFile != null && prevFile != null &&
                nowFile.absolutePath == prevFile.absolutePath &&
                nowFile.lastModified() != prevFile.lastModified()

            if (nowStyle != prevStyle || overwritten) applyShown(nowStyle, original())
        }
    }

    /**
     * 把 [style] 对应的图放上去。
     *
     * 这一页用 `fullSize = true`：放大的意义就是看细节，
     * 按控件尺寸降采样后再放大 5× 只会看到被拉伸的像素，
     * 用户会以为「AI 高清图也就这样」。关掉 crossfade 是因为
     * 淡入过程中 drawable 会换两次，缩放矩阵会被复位两次，
     * 手快的人在 100ms 内开始缩放会看到画面回弹。
     *
     * @param animate 跨卡切换时 true：旧图先原地不动，新图**解码完成**后才做 alpha 过渡。
     *   同卡内切风格（原图 ↔ 高清）传 false —— 那是同一张照片的两个版本，不需要过渡。
     * @param originalFile 已经落盘的原图；为 null 时会让 Coil 直接走 URL 兜底
     * @param token 发起时的 [loadToken]；回来时若已切卡就整体丢弃
     */
    private fun applyShown(
        style: AiStyle?,
        originalFile: File?,
        animate: Boolean = false,
        token: Int = loadToken
    ) {
        val c = card ?: return
        val aiFile = style?.let { ImageStore.ai(this, c.id, it) }

        shownStyle = style
        shownFile = aiFile ?: originalFile
        updateA11yLabel(failed = false)
        renderChipState()

        val model = shownFile ?: c.imageUrl

        if (!animate) {
            // 从失败/无图态点胶囊切图时，占位必须跟着收起来，否则会叠在新图上
            hideRetry()
            hideEmpty()
            binding.ivPreview.alpha = 1f
            binding.ivPreview.loadAny(model, crossfade = false, fullSize = true) { ok ->
                // spinner 必须收在回调里：提前收等于「加载期间没有任何反馈」
                hideLoading()
                if (ok) logShown() else showRetry()
            }
            return
        }

        val myToken = token

        // 唯一阈值 250ms：到这里旧图就开始「撒谎」了
        //（页码已经 4/12，画面还是第 3 张）—— spinner 与压暗同时发生。
        // 为什么不拆成「80ms 弹 spinner、250ms 再压暗」：spinner 在屏幕正中、是满屏图
        // 唯一的焦点，缓存命中时它刚淡入一半就被新图盖掉，留下一个半透明的环；
        // 两个阈值只差 170ms，也验证不出价值。
        val dim = Runnable {
            if (myToken == loadToken) {
                binding.ivPreview.animate().alpha(0.45f).setDuration(150).start()
            }
        }
        binding.ivPreview.postDelayed(dim, SLOW_LOAD_MS)

        binding.ivPreview.loadAny(model, crossfade = false, fullSize = true) { ok ->
            binding.ivPreview.removeCallbacks(dim)
            if (myToken == loadToken) {
                if (!ok) {
                    showRetry()
                    return@loadAny
                }
                hideLoading()
                hideRetry()
                // 新图的缩放必须复位：上一张放到 3×，新图若继承 3× 会直接是花的
                binding.ivPreview.resetToBase()
                binding.ivPreview.alpha = 0.85f
                binding.ivPreview.animate().alpha(1f).setDuration(140)
                    .setInterpolator(DecelerateInterpolator()).start()
                logShown()
            }
        }
    }

    /**
     * 把「这一页现在到底在显示哪张图」打进日志。
     *
     * 为什么值得单独写一条：这一页出过「看起来像缩略图」的争议，
     * 而「显示的是哪张、多大、多少 KB」是唯一能当场定案的事实 ——
     * 文件名（`_hd.jpg` 还是 `_hd_card.jpg`）+ 像素 + 体积，一眼就能分辨是裁切图、
     * 原图（160×80）还是真正的全尺寸 AI 图。
     */
    private fun logShown() {
        val f = shownFile ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeFile(f.absolutePath, o) }
            Log.d(
                "ImagePreview",
                "shown ${f.name} ${o.outWidth}x${o.outHeight} ${f.length() / 1024}KB style=$shownStyle"
            )
        }
    }

    private fun renderChipState() {
        val onText = ContextCompat.getColor(this, R.color.text_on_accent)
        val offText = ContextCompat.getColor(this, R.color.viewer_icon)
        val onBg = R.drawable.bg_chip_on
        val offBg = R.drawable.bg_chip

        fun apply(v: android.widget.TextView, on: Boolean) {
            v.setBackgroundResource(if (on) onBg else offBg)
            v.setTextColor(if (on) onText else offText)
        }
        apply(binding.chipPreviewOriginal, shownStyle == null)
        apply(binding.chipPreviewHd, shownStyle == AiStyle.HD)
        apply(binding.chipPreviewCartoon, shownStyle == AiStyle.CARTOON)
    }

    /** 切风格。这里只改「看哪张」，不写数据库——预览是浏览，不该改变卡片设置 */
    private fun switchTo(style: AiStyle?) {
        val c = card ?: return
        if (style != null && ImageStore.ai(this, c.id, style) == null) return
        applyShown(style, ImageStore.original(this, c.id))
    }

    /**
     * 下载目标的**唯一口径**：当前风格的全尺寸图。
     *
     * 为什么要单独取一次、而不是直接用 [shownFile]：
     * `shownFile` 只是「屏幕上正在显示的那张」。历史上列表封面与详情页主图都用 4:3 裁切图，
     * 一旦哪天这里显示的落到了裁切图上，下载就会把缩略图存进相册 —— 用户明确不接受。
     * 把「下载 = 全尺寸」做成硬规则：显示什么都不能影响它。
     */
    private fun saveTarget(): File? {
        val style = shownStyle
        if (style != null) {
            ImageStore.ai(this, cardId, style)?.let { return it }
        }
        return shownFile
    }

    private fun saveCurrent() {
        val file = saveTarget()
        if (file == null || !file.isFile) {
            // 还没落盘（例如原图下载失败，界面靠 URL 显示）→ 先落盘再存
            showAction(getString(R.string.preview_save_wait)); return
        }
        Log.d("ImagePreview", "save ${file.name} ${file.length() / 1024}KB style=$shownStyle")

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
            val name = buildSaveName()
            // 只有 AI 图才打「身份标识」：相机原图没有生成成本，
            // 给它打标会让人误以为「相册里已经有高清图了」，从而挑错恢复对象。
            val meta = shownStyle?.let { GalleryTag.Meta(cardId, it) }
            when (GallerySaver.save(this@ImagePreviewActivity, file, name, meta)) {
                is GallerySaver.Result.Ok ->
                    showAction(getString(R.string.preview_saved, name))
                is GallerySaver.Result.NeedPermission ->
                    showAction(getString(R.string.preview_need_permission))
                is GallerySaver.Result.Err ->
                    showAction(getString(R.string.preview_save_failed))
            }
        }
    }

    /** 文件名用「词 + 风格」，在相册里能直接认出来是哪张卡 */
    private fun buildSaveName(): String {
        val c = card
        val base = c?.nameEnglish?.takeIf { it.isNotBlank() }
            ?: c?.nameChinese?.takeIf { it.isNotBlank() }
            ?: getString(R.string.card_title)
        val suffix = when (shownStyle) {
            AiStyle.HD -> "_" + getString(R.string.ai_badge_hd)
            AiStyle.CARTOON -> "_" + getString(R.string.ai_badge_cartoon)
            null -> ""
        }
        return base + suffix
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
