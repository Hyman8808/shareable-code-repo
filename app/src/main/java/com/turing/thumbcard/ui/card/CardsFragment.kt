package com.turing.thumbcard.ui.card

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.turing.thumbcard.R
import com.turing.thumbcard.data.i18n.HantConverter
import com.turing.thumbcard.data.i18n.LanguagePrefs
import com.turing.thumbcard.data.i18n.NameLanguage
import com.turing.thumbcard.data.i18n.NameResolver
import com.turing.thumbcard.data.ai.AiPolicy
import com.turing.thumbcard.data.ai.AiQuotaStore
import com.turing.thumbcard.data.ai.AiStyle
import com.turing.thumbcard.data.ai.AiTaskCenter
import com.turing.thumbcard.data.device.CurrentDeviceStore
import com.turing.thumbcard.data.repo.BindRepository
import com.turing.thumbcard.data.repo.RecordRepository
import com.turing.thumbcard.data.repo.SyncResult
import com.turing.thumbcard.data.repo.SyncScope
import com.turing.thumbcard.databinding.FragmentCardsBinding
import com.turing.thumbcard.ui.MainActivity
import com.turing.thumbcard.ui.ai.AiBatchActivity
import kotlinx.coroutines.launch

/**
 * 单词卡页：**只显示已收藏**的卡片。
 *
 * 收藏入口在首页（卡片右上角 ♡），本页不显示未收藏的照片。
 * 这样「单词卡」是用户主动挑选过的精华，而不是照片流水账。
 * ⚠️ 云端接口无搜索能力，搜索为本地过滤（已拉取的记录）。
 */
class CardsFragment : Fragment() {

    private var _binding: FragmentCardsBinding? = null
    private val binding get() = _binding!!

    private lateinit var bindRepo: BindRepository
    private lateinit var recordRepo: RecordRepository
    private val adapter = WordCardGridAdapter(
        onClick = { card -> openDetail(card) },
        onToggleFavorite = { card -> unFavorite(card) }
    )

    /**
     * 详情页左右滑动的范围 = **本页当前显示的这些卡**（含搜索过滤后的结果）。
     *
     * 用户从收藏进来，左右滑就该一直在收藏里滑；滑到底不该出现「一张没收藏的照片」。
     * 搜索时同理：在「tea」的结果里左右滑，不该跳到不匹配的词上。
     */
    private var shownCards: List<com.turing.thumbcard.data.db.WordCardEntity> = emptyList()

    private fun openDetail(card: com.turing.thumbcard.data.db.WordCardEntity) {
        startActivity(
            Intent(requireContext(), CardDetailActivity::class.java)
                .putExtra(CardDetailActivity.EXTRA_CARD_ID, card.id)
                .putExtra(CardDetailActivity.EXTRA_IDS, shownCards.map { it.id }.toLongArray())
                .putExtra(CardDetailActivity.EXTRA_SOURCE, CardDetailActivity.SOURCE_FAVORITES)
        )
    }

    private var allCards: List<com.turing.thumbcard.data.db.WordCardEntity> = emptyList()
    private var keyword: String = ""

    /**
     * cardId → 第二行文字（音标优先，退到拼音）。
     *
     * 在 IO 线程一次性读好再交给列表，**不在 onBind 里做** ——
     * onBind 每滚动一次就调一次，那里读文件会掉帧、读网络会成流量黑洞。
     */
    private var glossMap: Map<Long, WordCardGridAdapter.Gloss> = emptyMap()

    /** 名称语言变了 → 重绘（数据不变，只换名称；不重新请求网络） */
    private val onLanguageChanged: (NameLanguage) -> Unit = { applyFilter() }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCardsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindRepo = BindRepository(requireContext())
        recordRepo = RecordRepository(requireContext())

        // 收藏页 = 卡片流（2 列），与首页的「词典行」刻意不同：
        // 首页是效率场景（越多越好），收藏是挑选场景（图要大、能认出这张卡）。
        binding.rvCards.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvCards.adapter = adapter
        // 网格不加行分割线：卡片自带 12dp 外边距和阴影，再画 hairline 会变成表格
        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.brand_primary)
        )
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(requireContext(), R.color.bg_card)
        )
        binding.swipeRefresh.setOnRefreshListener { sync() }
        binding.etSearch.doAfterTextChanged { text ->
            keyword = text?.toString()?.trim().orEmpty()
            applyFilter()
        }
        binding.btnGoAdd.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_HOME)
        }

        // ===== 多选 / 批量修复 =====
        adapter.onSelectionChanged = { renderSelection() }
        binding.btnSelect.setOnClickListener {
            if (adapter.isPicking()) adapter.exitSelection()
            else adapter.enterSelection()
        }
        binding.btnBatchHd.setOnClickListener { startBatch(AiStyle.HD) }
        binding.btnBatchCartoon.setOnClickListener { startBatch(AiStyle.CARTOON) }

        // 后台服务生成好一张 → 立刻换掉这一格的封面。
        // 没有这条通道，用户得下拉刷新才看得到新图，表现就是「生成了但封面没换」。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AiTaskCenter.events.collect { e ->
                    if (e is AiTaskCenter.Event.CardUpdated) refreshOne(e.cardId)
                }
            }
        }

        loadLocalThenSync()
        LanguagePrefs.addListener(onLanguageChanged)
    }

    override fun onResume() {
        super.onResume()
        // 从批量修复页回来：选择态已经没有意义，先退出再刷新，
        // 否则列表重绘后仍停在「已选 N 张」，而 N 张图其实早就换好了。
        if (adapter.isPicking()) adapter.exitSelection()
        loadLocalThenSync()
    }

    /**
     * 切 Tab 走的是 hide/show，**不会触发 onResume**，
     * 因此在首页刚点的收藏必须在这里补一次本地读取，否则本页会漏卡。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshFavoritesLocal()
    }

    /** 只读本地（不打网络）：切 Tab 回来时用，够快也不费流量 */
    private fun refreshFavoritesLocal() {
        lifecycleScope.launch {
            val devices = runCatching { bindRepo.listBound() }.getOrNull().orEmpty()
            val d = CurrentDeviceStore.resolve(requireContext(), devices) ?: return@launch
            allCards = recordRepo.loadFavorites(d.deviceId)
            glossMap = buildGlosses(allCards)
            applyFilter()
        }
    }

    private fun loadLocalThenSync() {
        lifecycleScope.launch {
            val devices = runCatching { bindRepo.listBound() }.getOrNull().orEmpty()
            val d = CurrentDeviceStore.resolve(requireContext(), devices)
            if (d == null) {
                allCards = emptyList()
                applyFilter()
                binding.tvEmpty.setText(R.string.cards_empty_unbound)
                binding.groupEmptyCards.visibility = View.VISIBLE
                binding.btnGoAdd.visibility = View.VISIBLE
                binding.grpSearch.visibility = View.GONE
                return@launch
            }
            binding.btnGoAdd.visibility = View.GONE
            binding.grpSearch.visibility = View.VISIBLE

            val deviceId = d.deviceId
            allCards = recordRepo.loadFavorites(deviceId)
            glossMap = buildGlosses(allCards)
            applyFilter()
            sync()
        }
    }

    private fun sync() {
        lifecycleScope.launch {
            val devices = runCatching { bindRepo.listBound() }.getOrNull().orEmpty()
            val d = CurrentDeviceStore.resolve(requireContext(), devices)
            if (d == null) {
                binding.swipeRefresh.isRefreshing = false
                return@launch
            }
            val deviceId = d.deviceId
            // 只拉最新一页：本页只展示收藏，而收藏都是在首页（最新数据）点的，
            // 不需要为「单词卡」再翻十几页历史。
            val lang = NameResolver.requestLangParam(requireContext())
            when (val r = recordRepo.sync(deviceId, lang, SyncScope.LATEST)) {
                is SyncResult.Success -> {
                    bindRepo.markSynced(deviceId)
                    allCards = recordRepo.loadFavorites(deviceId)
                    glossMap = buildGlosses(allCards)
                    applyFilter()
                }
                is SyncResult.InvalidCredential -> toast(r.message)
                is SyncResult.Failure -> toast(getString(R.string.common_sync_failed_fmt, r.message))
            }
            binding.swipeRefresh.isRefreshing = false
        }
    }

    /**
     * 给每张卡算第二行：音标优先、退到拼音。
     *
     * 逻辑与首页「今天拍的」卡片流共用 [buildCardGlosses]（同一个缓存键，
     * 否则同一个词在两页会命中两份缓存、其中一份永远空）。
     */
    private suspend fun buildGlosses(
        cards: List<com.turing.thumbcard.data.db.WordCardEntity>
    ): Map<Long, WordCardGridAdapter.Gloss> = buildCardGlosses(cards)

    private fun applyFilter() {
        val list = if (keyword.isBlank()) {
            allCards
        } else {
            allCards.filter {
                // 库里存的是简体原文；用户可能按繁体输入（紙箱），
                // 所以两边都试一次：原值命中，或「转成繁体后」命中
                it.nameChinese?.contains(keyword, true) == true ||
                    HantConverter.convert(it.nameChinese)?.contains(keyword, true) == true ||
                    it.nameEnglish?.contains(keyword, true) == true
            }
        }
        adapter.submit(list, glossMap)
        shownCards = list
        binding.tvCount.text =
            if (allCards.isEmpty()) "" else getString(R.string.cards_count_fmt, list.size)
        binding.groupEmptyCards.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) {
            if (keyword.isBlank()) {
                binding.tvEmpty.setText(R.string.cards_empty_fav)
            } else {
                binding.tvEmpty.text = getString(R.string.cards_no_result_fmt, keyword)
            }
        }
    }

    // ==================== 多选 / 批量修复 ====================

    /**
     * 选择态的界面同步：底栏、入口文案、下拉刷新、列表底部留白。
     *
     * 全部集中在这一个函数里，是因为它由三处触发（点「选择」、长按卡片、勾选变化）。
     * 分散写必然漏掉某一个入口，表现就是「长按进来了但底栏没出来」。
     */
    private fun renderSelection() {
        // adapter 的生命周期比 view 长（切 Tab 走 hide/show，view 会留着，
        // 但真正销毁时这个回调仍可能被触发一次）→ 没有 binding 就直接返回，
        // 否则「离开页面时正好列表刷新」会变成一个必现的空指针崩溃。
        if (_binding == null) return
        val picking = adapter.isPicking()
        binding.barSelection.visibility = if (picking) View.VISIBLE else View.GONE
        binding.btnSelect.setText(
            if (picking) R.string.cards_select_done else R.string.cards_select
        )
        // 选择态下禁掉下拉刷新：勾东西时手一滑就整页刷新、勾选全丢
        binding.swipeRefresh.isEnabled = !picking

        val n = adapter.selectedCount()
        binding.tvSelectionCount.text = getString(R.string.cards_selected_fmt, n)
        // 一张都没选时按钮置灰，而不是让它点下去弹「请先选择」
        binding.btnBatchHd.isEnabled = n > 0
        binding.btnBatchCartoon.isEnabled = n > 0

        // 底栏是浮层，不给列表补底部留白的话，最后一行会被它盖住一半
        val bottom = resources.getDimensionPixelSize(
            if (picking) R.dimen.selection_bar_space else R.dimen.space_24
        )
        binding.rvCards.setPadding(
            binding.rvCards.paddingStart,
            binding.rvCards.paddingTop,
            binding.rvCards.paddingEnd,
            bottom
        )

        refreshSelectionQuota()
    }

    /**
     * 底栏那行额度提示。
     *
     * 必须**在批量花钱之前**让人看到还剩几张 —— 选完 10 张才发现只有 2 张额度，
     * 那一刻的挫败感最强。不限额设备直接写「不限」，不显示数字。
     */
    private fun refreshSelectionQuota() {
        if (!adapter.isPicking()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            val unlimited = AiPolicy.currentUnlimited(ctx)
            binding.tvSelectionQuota.text = AiPolicy.quotaShort(
                ctx,
                AiQuotaStore.freeRemaining(ctx),
                AiQuotaStore.creditsRemaining(ctx),
                unlimited
            )
        }
    }

    private fun startBatch(style: AiStyle) {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        AiBatchActivity.start(requireContext(), ids.toLongArray(), style)
    }

    /** 单张卡的 AI 图刚生成好：只重读这一条，只重绘这一格 */
    private fun refreshOne(cardId: Long) {
        lifecycleScope.launch {
            val fresh = runCatching { recordRepo.findCard(cardId) }.getOrNull() ?: return@launch
            allCards = allCards.map { if (it.id == cardId) fresh else it }
            adapter.applyCard(fresh)
        }
    }

    /**
     * 在单词卡页取消收藏 → 这张卡就该从本页消失。
     *
     * 首页仍保留该照片（拍过就是拍过），只是不再算「单词卡」。
     */
    private fun unFavorite(card: com.turing.thumbcard.data.db.WordCardEntity) {
        lifecycleScope.launch {
            val next = recordRepo.toggleFavorite(card.id)
            if (!next) {
                allCards = allCards.filterNot { it.id == card.id }
                applyFilter()
                toast(getString(R.string.cards_fav_removed))
            } else {
                adapter.applyFavorite(card.id, true)
            }
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        LanguagePrefs.removeListener(onLanguageChanged)
        _binding = null
    }
}
