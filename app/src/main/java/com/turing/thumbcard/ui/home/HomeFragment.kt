package com.turing.thumbcard.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.turing.thumbcard.R
import com.turing.thumbcard.data.ai.AiTaskCenter
import com.turing.thumbcard.data.db.BoundDeviceEntity
import com.turing.thumbcard.data.db.WordCardEntity
import com.turing.thumbcard.data.device.CurrentDeviceStore
import com.turing.thumbcard.data.i18n.LanguagePrefs
import com.turing.thumbcard.data.i18n.NameLanguage
import com.turing.thumbcard.data.i18n.NameResolver
import com.turing.thumbcard.data.repo.BindRepository
import com.turing.thumbcard.data.repo.BindResult
import com.turing.thumbcard.data.repo.RecordRepository
import com.turing.thumbcard.data.repo.StudyStats
import com.turing.thumbcard.data.repo.SyncResult
import com.turing.thumbcard.data.repo.SyncScope
import com.turing.thumbcard.databinding.FragmentHomeBinding
import com.turing.thumbcard.ui.card.CardDetailActivity
import com.turing.thumbcard.ui.card.CardListActivity
import com.turing.thumbcard.ui.card.WordCardAdapter
import com.turing.thumbcard.ui.card.WordCardGridAdapter
import com.turing.thumbcard.ui.card.buildCardGlosses
import com.turing.thumbcard.ui.device.DeviceListActivity
import com.turing.thumbcard.ui.provision.ScanDeviceActivity
import com.turing.thumbcard.ui.widget.RowDividerDecoration
import kotlinx.coroutines.launch

/**
 * 首页：内容随「是否已绑定设备」自适应。
 *
 *  - 未绑定 → 引导页（大卡片 + 添加设备按钮）
 *  - 已绑定 → 成就卡 + **今天拍的（卡片流）** + **全部记录（行式列表）**
 *
 * 产品口径（v1.3.0 起，v2.1.0 调整首页分区）：
 *  - **拍 ≠ 收藏**：拍下的照片全部进首页，只有点了 ♡ 的才进「收藏」Tab
 *  - 学习报告按「照片张数」与「去重单词数」两个口径分别统计，见 [StudyStats]
 *
 * v2.1.0 的两个关键判断（用户反馈「家长看到没有成就感」）：
 *  1. **0 不能当主角**：今天没拍时，成就卡的主指标从「今天」切到「累计」，
 *     而不是把 30sp 的「0 张照片」摆在首屏正中。
 *  2. **今天没拍不是空态**：只要历史有内容，今日分组整组消失、直接接「全部记录」，
 *     页面呈现的是「你积累了多少」，不是「你今天缺了什么」。
 */
class HomeFragment : Fragment() {

    companion object {
        /**
         * 今日卡片流最多显示几张（2 列 × 2 行）。
         *
         * 为什么设上限：卡片单张 ≈214dp 高，全部今日照片都铺开的话，
         * 拍 30 张的用户首页会变成 15 行卡，历史永远看不到；而且首页是
         * NestedScrollView + wrap_content RecyclerView，全量 measure 会卡。
         * 超出部分由「今天还有 N 张 ›」给出口。
         */
        private const val TODAY_CARD_LIMIT = 4

        /**
         * 历史行最多显示几行。
         *
         * 首页的历史列表是「我一直在坚持」的证据，不是完整档案 ——
         * 完整档案在 [CardListActivity]。10 行 ≈ 2 屏，够看出积累量，
         * 又不至于让用户滑到手指酸。
         */
        private const val HISTORY_ROW_LIMIT = 10
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var bindRepo: BindRepository
    private lateinit var recordRepo: RecordRepository

    /** 历史分组：**行**（档案，密度优先） */
    private val adapter = WordCardAdapter(
        onClick = { card -> openDetail(card) },
        onToggleFavorite = { card -> toggleFavorite(card) }
    )

    /** 今天拍的：**卡**（今天值得看大图） */
    private val gridAdapter = WordCardGridAdapter(
        onClick = { card -> openDetail(card) },
        onToggleFavorite = { card -> toggleFavorite(card) }
    )

    /** 当前展示的设备（**由 [CurrentDeviceStore] 决定**，不再固定取第一台） */
    private var device: BoundDeviceEntity? = null

    /**
     * 当前屏幕上的卡片顺序（今天 → 更早），详情页左右滑动用的就是它。
     *
     * 为什么按「视觉顺序」而不是按 id 排：用户在首页看到的第一张是今天的，
     * 往右滑就应该到第二张今天的，而不是跳到历史里 id 最大的那张。
     */
    private var visibleCards: List<WordCardEntity> = emptyList()

    /** 名称语言变了 → 仅重绘名称，不重新同步 */
    private val onLanguageChanged: (NameLanguage) -> Unit = {
        adapter.refreshNames()
        gridAdapter.refreshNames()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindRepo = BindRepository(requireContext())
        recordRepo = RecordRepository(requireContext())

        // 今天拍的 = 卡片流（2 列）。与收藏页同一个 adapter / 同一个 item 布局，
        // 两处的卡必须长得一模一样，否则「今天的卡」和「收藏的卡」会像两个产品。
        binding.rvTodayCards.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvTodayCards.adapter = gridAdapter

        // 历史 = 行式列表。行没有卡底，行与行之间必须有一道 1dp hairline，
        // 否则两行会读成「一个两行高的块」。
        // 判空是防重复：Fragment 视图重建时若同一 RV 被多次 add，会叠成 2dp/3dp 粗线。
        binding.rvRecent.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecent.adapter = adapter
        if (binding.rvRecent.itemDecorationCount == 0) {
            binding.rvRecent.addItemDecoration(RowDividerDecoration(requireContext()))
        }

        binding.cardAddDevice.setOnClickListener {
            startActivity(Intent(requireContext(), ScanDeviceActivity::class.java))
        }
        binding.btnAllCards.setOnClickListener { openAllPhotos() }
        binding.btnAllRecords.setOnClickListener { openAllPhotos() }
        binding.btnTodayMore.setOnClickListener { openAllPhotos() }
        // 「批量修图」直接跳全部记录页的多选态 —— 批量选图的对象是拍过的照片，
        // 不是收藏，所以这个入口不该藏在收藏 Tab 里。
        // 两处各一个（「今天」与「更早」区块头），保证任何状态下首页都能一步进多选。
        binding.btnHomeBatch.setOnClickListener { openAllPhotos(pickMode = true) }
        binding.btnTodayBatch.setOnClickListener { openAllPhotos(pickMode = true) }

        // AI 图在后台生成好 → 首页封面立刻跟着换。
        // 首页只有「今天拍的 ≤4 张 + 历史 ≤10 行」，整段重渲染（读本地库）比逐条 patch 简单可靠。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AiTaskCenter.events.collect { e ->
                    val d = device
                    if (e is AiTaskCenter.Event.CardUpdated && d != null) {
                        renderLocal(d.deviceId)
                    }
                }
            }
        }
        // 设备入口直接进设备管理页（不再切 Tab：TAB_DEVICES 只是「设备」这个 tab 的 id，
        // 而用户点这颗蓝牙图标要的是「管理已绑定的设备」，那是独立一页）
        binding.btnDevicesEntry.setOnClickListener {
            startActivity(Intent(requireContext(), DeviceListActivity::class.java))
        }

        // 下拉指示器默认是黑色箭头，和主色系统一（不设的话每次下拉都跳出来一个黑圈）
        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.brand_primary)
        )
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(requireContext(), R.color.bg_card)
        )
        binding.swipeRefresh.setOnRefreshListener { refresh() }

        refresh()
        LanguagePrefs.addListener(onLanguageChanged)
    }

    private fun openDetail(card: WordCardEntity) {
        startActivity(
            Intent(requireContext(), CardDetailActivity::class.java)
                .putExtra(CardDetailActivity.EXTRA_CARD_ID, card.id)
                // 从首页进来 → 左右滑动的范围就是首页这个顺序
                .putExtra(CardDetailActivity.EXTRA_IDS, visibleCards.map { it.id }.toLongArray())
                .putExtra(CardDetailActivity.EXTRA_SOURCE, CardDetailActivity.SOURCE_HOME)
        )
    }

    /**
     * 「全部 ›」→ **全部照片页**（不是单词卡 Tab）。
     *
     * ⚠️ v1.3.0 这里错跳到了单词卡 Tab，用户看到的是「已收藏」的卡片，
     *    与「全部」语义完全不符。全部照片应走 [CardListActivity]。
     */
    private fun openAllPhotos(pickMode: Boolean = false) {
        val d = device
        if (d == null) {
            toast(getString(R.string.device_empty))
            return
        }
        startActivity(
            Intent(requireContext(), CardListActivity::class.java)
                .putExtra(CardListActivity.EXTRA_DEVICE_ID, d.deviceId)
                .putExtra(CardListActivity.EXTRA_PICK_MODE, pickMode)
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** 切 Tab 是 hide/show，不走 onResume；回来时重新读一次本地数据 + 统计 */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val devices = runCatching { bindRepo.listBound() }.getOrNull().orEmpty()
            // 多设备：当前是哪台由 CurrentDeviceStore 决定（设备页切换后所有页面跟着换）
            device = CurrentDeviceStore.resolve(requireContext(), devices)

            if (devices.isEmpty()) {
                showUnbound()
            } else {
                showBound(devices)
            }
            binding.swipeRefresh.isRefreshing = false
        }
    }

    /** 未绑定：显示引导（即原首页形态） */
    private fun showUnbound() {
        binding.groupUnbound.visibility = View.VISIBLE
        binding.groupBound.visibility = View.GONE
    }

    /** 已绑定：单词卡为主页 */
    private fun showBound(devices: List<BoundDeviceEntity>) {
        binding.groupUnbound.visibility = View.GONE
        binding.groupBound.visibility = View.VISIBLE

        val d = device ?: devices.first()
        binding.tvDeviceName.text =
            d.deviceName?.takeIf { it.isNotBlank() } ?: getString(R.string.home_default_device)

        // 设备入口只在「有得选」时出现：单设备没有任何可切换目标，那个图标只会造成
        // 「点了跳到一个我已经知道的页面」的空转；多设备时才需要「换个相机看」。
        // 点击行为固定为进 DeviceListActivity —— 不在这里做轮换式切换
        // （用户不知道下一台是哪台，且需要引入 deviceIndex 状态，为一个低频场景不值）。
        binding.btnDevicesEntry.visibility = if (devices.size > 1) View.VISIBLE else View.GONE

        binding.tvDeviceMeta.text = buildString {
            append(d.deviceId)
            if (devices.size > 1) {
                append(getString(R.string.home_multi_device_fmt, devices.size))
            }
        }

        // 先读本地缓存，再同步（首页只拉最新一页，历史走「全部」页）
        lifecycleScope.launch {
            renderLocal(d.deviceId)

            val lang = NameResolver.requestLangParam(requireContext())
            when (val r = recordRepo.sync(d.deviceId, lang, SyncScope.LATEST)) {
                is SyncResult.Success -> {
                    renderLocal(d.deviceId)
                }
                is SyncResult.InvalidCredential -> {
                    toast(r.message)
                }
                is SyncResult.Failure -> {
                    toast(getString(R.string.common_sync_failed_fmt, r.message))
                }
            }
        }
    }

    /**
     * 用本地库渲染首页：成就卡 + 今天拍的（卡片流）+ 全部记录（行式列表）。
     *
     * 两段的边界是**日期**，不是"数量"：今天的用卡，今天以外的用行。
     * 历史段**排除今天已展示的条目**，所以同一张照片在首页只出现一次。
     */
    private suspend fun renderLocal(deviceId: String) {
        val all = recordRepo.loadLocal(deviceId)
        val today = StudyStats.todayOf(all)
        // 视觉顺序 = 今天 → 更早。详情页左右滑动严格按这个顺序走
        val todayIds = HashSet<Long>(today.size)
        today.forEach { todayIds.add(it.id) }
        val history = all.filterNot { it.id in todayIds }
        visibleCards = today + history

        renderToday(today)
        renderHistory(history, hasToday = today.isNotEmpty())

        renderStats(StudyStats.of(all))

        // 空态只在「一张都没拍过」时出现。
        // ⚠️ 「今天没拍」**不算**空态 —— 那是用户反馈的痛点：把「今天没拍」做成
        //    88dp 虚线环 + 一句话，等于每天打开都在提醒「你什么都没做」。
        //    今天没拍但历史有内容时，页面直接呈现成就卡（累计）+ 全部记录。
        val totallyEmpty = all.isEmpty()
        binding.groupEmpty.visibility = if (totallyEmpty) View.VISIBLE else View.GONE
        if (totallyEmpty) binding.tvEmptyHint.setText(R.string.home_empty)

        // 拍摄提示同理：只在真正一张都没有时出现（有积累的人不该被催）
        binding.groupShootHint.visibility = if (totallyEmpty) View.VISIBLE else View.GONE
    }

    /** 今天拍的：卡片流，最多 [TODAY_CARD_LIMIT] 张，超出部分给「今天还有 N 张」出口 */
    private suspend fun renderToday(today: List<WordCardEntity>) {
        if (today.isEmpty()) {
            // 整组收起：没有内容的分组标题是噪声
            binding.groupToday.visibility = View.GONE
            // 必须清空：否则下次「今天有拍」时会先闪一帧上一次的旧卡
            gridAdapter.submit(emptyList(), emptyMap())
            return
        }

        binding.groupToday.visibility = View.VISIBLE
        binding.tvTodayCount.text = getString(R.string.home_count_fmt, today.size)

        val shown = today.take(TODAY_CARD_LIMIT)
        gridAdapter.submit(shown, buildCardGlosses(shown))

        val rest = today.size - shown.size
        binding.btnTodayMore.visibility = if (rest > 0) View.VISIBLE else View.GONE
        if (rest > 0) binding.btnTodayMore.text = getString(R.string.home_today_more_fmt, rest)
    }

    /**
     * 全部记录：行式列表，最多 [HISTORY_ROW_LIMIT] 行。
     *
     * @param hasToday 今天有拍 → 标题写「更早的记录」（下面确实排除了今天）；
     *                 今天没拍 → 标题写「全部记录」（下面就是全部，没有重复）
     */
    private fun renderHistory(history: List<WordCardEntity>, hasToday: Boolean) {
        if (history.isEmpty()) {
            binding.groupHistory.visibility = View.GONE
            // 底部出口在 groupHistory 外面，必须单独收起（否则留一个指向空列表的按钮）
            binding.btnAllRecords.visibility = View.GONE
            adapter.submit(emptyList())
            return
        }

        binding.groupHistory.visibility = View.VISIBLE
        binding.tvHistoryTitle.setText(
            if (hasToday) R.string.home_section_earlier else R.string.home_section_all
        )
        binding.tvHistoryCount.text = getString(R.string.home_count_fmt, history.size)

        val shown = history.take(HISTORY_ROW_LIMIT)
        adapter.submit(shown)

        // 长按收藏是不可发现的手势：一条收藏都没有时才提示；收藏过就不再占那一行
        binding.tvFavoriteHint.visibility =
            if (history.none { it.favorite }) View.VISIBLE else View.GONE

        // 列表被截断时，底部再给一个出口 —— 滑到底的人才正要找剩下的
        val more = history.size > shown.size
        binding.btnAllRecords.visibility = if (more) View.VISIBLE else View.GONE
        if (more) binding.btnAllRecords.text = getString(R.string.home_all_records_fmt, history.size)
    }

    /**
     * 成就卡：今日 / 累计 两个区间 × 照片张数 / 去重单词数。
     *
     * 🔴 **0 绝不能当主角**：今天没拍时主指标切到「累计」。
     * 原来无论有没有拍，30sp 的主指标都写今天——于是没拍照的日子，
     * 家长打开首页第一眼是一个巨大的「0 张照片」，累计被压成 11sp 灰字挤在右角。
     */
    private fun renderStats(s: StudyStats) {
        val todayIsMain = s.todayPhotos > 0

        binding.tvPrimaryLabel.setText(
            if (todayIsMain) R.string.home_today else R.string.home_total_label
        )
        binding.tvPrimaryPhotos.text =
            (if (todayIsMain) s.todayPhotos else s.totalPhotos).toString()
        binding.tvPrimaryWords.text =
            (if (todayIsMain) s.todayWords else s.totalWords).toString()

        binding.tvWeekPhotos.text = s.weekPhotos.toString()
        binding.tvWeekWords.text = s.weekWords.toString()
        // 累计已经在主位时不再重复写小字（否则同屏出现两个「累计」）
        binding.tvStatsTotal.text =
            if (todayIsMain && s.hasAny) {
                getString(R.string.home_total_fmt, s.totalPhotos, s.totalWords)
            } else {
                ""
            }
    }

    /** 收藏 / 取消收藏：只改收藏态，不重新拉云端 */
    /** 收藏 / 取消收藏：只改收藏态，不重新拉云端。两个列表都要刷（同一条只会在其中一个里） */
    private fun toggleFavorite(card: WordCardEntity) {
        lifecycleScope.launch {
            val next = recordRepo.toggleFavorite(card.id)
            adapter.applyFavorite(card.id, next)
            gridAdapter.applyFavorite(card.id, next)
            toast(getString(if (next) R.string.home_fav_added else R.string.home_fav_removed))
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
