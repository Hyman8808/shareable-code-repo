package com.turing.thumbcard.ui.card

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.turing.thumbcard.R
import com.turing.thumbcard.data.ai.AiPolicy
import com.turing.thumbcard.data.ai.AiQuotaStore
import com.turing.thumbcard.data.ai.AiStyle
import com.turing.thumbcard.data.ai.AiTaskCenter
import com.turing.thumbcard.data.db.BoundDeviceEntity
import com.turing.thumbcard.data.db.WordCardEntity
import com.turing.thumbcard.data.device.CurrentDeviceStore
import com.turing.thumbcard.data.i18n.LanguagePrefs
import com.turing.thumbcard.data.i18n.NameResolver
import com.turing.thumbcard.data.repo.BindRepository
import com.turing.thumbcard.data.repo.RecordRepository
import com.turing.thumbcard.data.repo.SyncResult
import com.turing.thumbcard.data.repo.SyncScope
import com.turing.thumbcard.databinding.ActivityCardListBinding
import com.turing.thumbcard.databinding.ItemWordCardBinding
import com.turing.thumbcard.ui.ai.AiBatchActivity
import com.turing.thumbcard.ui.widget.RowDividerDecoration
import com.turing.thumbcard.util.loadCardImage
import kotlinx.coroutines.launch

/**
 * 全部照片页（首页「全部 ›」/ 配网成功后 / 设备管理进入）。
 *
 * 与「单词卡」页的区别：本页显示**所有**拍过的照片（含未收藏），
 * 用于核对设备是否真的在上传；收藏与否在这里也能改。
 *
 * 数据口径（v1.4.0）：
 *  - 首页只拉最新一页（[SyncScope.LATEST]），**历史照片只有进本页才拉**
 *  - 本页走 [SyncScope.ALL]：本地已有的记录不再重复保存；
 *    翻到「上次拉取边界」即停，不会每次都把几十页历史重下一遍
 *  - 拉到的全部落本地库，下次进来先秒出本地、再后台补增量
 */
class CardListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"

        /** true 表示打开时选中「全部设备」（设备页的「全部单词记录」入口用） */
        const val EXTRA_ALL_DEVICES = "extra_all_devices"

        /** true 表示存储不可用时的临时展示模式（不落库） */
        const val EXTRA_TEMP_MODE = "extra_temp_mode"

        /** 临时模式下用于拉取记录的 apikey */
        const val EXTRA_API_KEY_OVERRIDE = "extra_api_key_override"

        /** true 表示进页面就进多选态（首页「批量修复」入口用，省掉一次「选择」点击） */
        const val EXTRA_PICK_MODE = "extra_pick_mode"
    }

    private lateinit var binding: ActivityCardListBinding
    private lateinit var recordRepo: RecordRepository
    private lateinit var bindRepo: BindRepository

    /**
     * 当前筛选的设备 id，或 [CurrentDeviceStore.ALL]（全部设备）。
     *
     * 🔴 这一页是「设备隔离」的唯一例外：只有这里允许把多台设备合起来看，
     *    并且必须由一个**显式的胶囊**选中，不能默认合并 ——
     *    默认合并会让用户以为「另一台相机的照片也是这台拍的」。
     */
    private var deviceId: String = ""

    private lateinit var devices: List<BoundDeviceEntity>

    /** 本页当前显示的列表 → 详情页左右滑动的范围 */
    private var shownCards: List<WordCardEntity> = emptyList()

    /**
     * 是否已经跑过一轮同步。
     *
     * 「从首页批量修图进来」时，本地缓存可能还是空的（首次进这个设备），
     * 这时候不能因为「列表空」就把多选态退掉 —— 等同步真的回来再判断。
     */
    private var syncedOnce = false

    private val adapter = CardAdapter(
        onClick = { card -> openDetail(card) },
        onToggleFavorite = { card -> toggleFavorite(card) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCardListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recordRepo = RecordRepository(this)
        bindRepo = BindRepository(this)

        // 临时模式：存储不可用，用调用方传入的 apikey 直接拉云端
        if (intent.getBooleanExtra(EXTRA_TEMP_MODE, false)) {
            intent.getStringExtra(EXTRA_API_KEY_OVERRIDE)?.let {
                recordRepo.setApiKeyOverride(it)
            }
            toast(getString(R.string.cardlist_temp_mode))
        }

        // 设备筛选的初值：调用方指定 > 全部 > 当前设备。
        // 没有传入时不再直接报错退出 —— 这里已经能自己解析「当前设备」，
        // 一个必填 extra 缺失就把人踢出去，属于把内部约定当成了用户错误。
        //
        // ⚠️ listBound() 是挂起函数：设备列表只能进协程后拿，
        //    拿不到就什么也读不出来（这里改成协程后，页面其余部分照常先建好）。
        val requested = intent.getStringExtra(EXTRA_DEVICE_ID)
        val wantAll = intent.getBooleanExtra(EXTRA_ALL_DEVICES, false)

        // item_word_card 是「行」布局，网格管理器会把行压成两列
        binding.rvCards.layoutManager = LinearLayoutManager(this)
        binding.rvCards.adapter = adapter
        // 行式列表的 1dp hairline（与首页 / 收藏 Tab 同一个类，风格三处一致）
        if (binding.rvCards.itemDecorationCount == 0) {
            binding.rvCards.addItemDecoration(RowDividerDecoration(this))
        }
        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.brand_primary)
        )
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.bg_card)
        )
        binding.swipeRefresh.setOnRefreshListener { sync() }

        // ==================== 多选批量（v2.4.1） ====================
        // 批量修复的对象是「拍过的照片」，所以入口必须在本页（全部记录），
        // 而不是只在收藏页 —— 不先收藏就没法批量，这条路径本身就是错的。
        adapter.onSelectionChanged = { renderSelection() }
        binding.btnSelect.setOnClickListener {
            if (adapter.isPicking()) adapter.exitSelection() else adapter.enterSelection()
        }
        binding.btnBatchHd.setOnClickListener { startBatch(AiStyle.HD) }
        binding.btnBatchCartoon.setOnClickListener { startBatch(AiStyle.CARTOON) }

        // 多选态下按返回 = 退出多选，而不是退出页面（否则勾了 8 张一按返回全丢）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.isPicking()) adapter.exitSelection() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        if (intent.getBooleanExtra(EXTRA_PICK_MODE, false)) adapter.enterSelection()

        // 后台生成好一张 AI 图 → 本行封面跟着换（同一进程内，不需要广播）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AiTaskCenter.events.collect { e ->
                    if (e is AiTaskCenter.Event.CardUpdated) {
                        runCatching { recordRepo.findCard(e.cardId) }.getOrNull()
                            ?.let { adapter.applyCard(it) }
                    }
                }
            }
        }

        // 先读出设备列表，再决定筛选、建胶囊、读本地、后台同步
        lifecycleScope.launch {
            devices = runCatching { bindRepo.listBound() }.getOrNull().orEmpty()
            deviceId = requested
                ?: if (wantAll) CurrentDeviceStore.ALL
                else CurrentDeviceStore.resolve(this@CardListActivity, devices)?.deviceId.orEmpty()
            if (deviceId.isBlank()) {
                toast(getString(R.string.cardlist_missing_device))
                finish()
                return@launch
            }
            buildChips()
            loadLocal()
            sync()
        }
    }

    // ==================== 设备切换（多设备） ====================

    /**
     * 顶部设备胶囊：每台设备一个 + 「全部」。
     *
     * 「全部」只在本页有效，**不会改全局当前设备** ——
     * 全局当前设备语义是「我默认在玩哪台相机」，与「这一页我想看并集」不是一回事。
     * 反过来，点某台设备会顺带把它设为当前设备：用户在这里点了一下设备 2，
     * 退回首页却还在看设备 1，是这版之前最让人困惑的行为。
     */
    private fun buildChips() {
        if (devices.size < 2) {
            binding.chipScroll.visibility = View.GONE
            return
        }
        binding.chipScroll.visibility = View.VISIBLE
        binding.chipRow.removeAllViews()
        devices.forEach { d ->
            addChip(
                CurrentDeviceStore.displayName(d, getString(R.string.device_default_name)),
                d.deviceId,
                isDevice = true
            )
        }
        addChip(getString(R.string.cardlist_chip_all), CurrentDeviceStore.ALL, isDevice = false)
        renderChips()
    }

    private fun addChip(label: String, id: String, isDevice: Boolean) {
        val chip = TextView(this).apply {
            text = label
            // id 放 tag：不能靠文字反查 —— 两台设备同名（都叫「拇指相机」）时会一起高亮
            tag = id
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (resources.displayMetrics.widthPixels * 0.5f).toInt()
            gravity = android.view.Gravity.CENTER
            val p = dp(14)
            val pv = dp(7)
            setPadding(p, pv, p, pv)
            isClickable = true
            isFocusable = true
            setOnClickListener { onChipClick(id, isDevice) }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) }
        binding.chipRow.addView(chip, lp)
    }

    private fun onChipClick(id: String, isDevice: Boolean) {
        if (id == deviceId) return
        deviceId = id
        // 点具体设备 → 全 App 跟着换；点「全部」→ 只换本页
        if (isDevice) CurrentDeviceStore.setCurrent(this, id)
        renderChips()
        if (adapter.isPicking()) adapter.exitSelection()
        loadLocal()
        sync()
    }

    /** 只重绘胶囊配色，不重建视图（重建会让手指底下的 View 被换掉，点按反馈会断） */
    private fun renderChips() {
        for (i in 0 until binding.chipRow.childCount) {
            val chip = binding.chipRow.getChildAt(i) as? TextView ?: continue
            val on = chip.tag == deviceId
            chip.setBackgroundResource(
                if (on) R.drawable.bg_switch_chip_on else R.drawable.bg_switch_chip
            )
            chip.setTextColor(
                ContextCompat.getColor(this, if (on) R.color.text_on_brand else R.color.text_secondary)
            )
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ==================== 数据 ====================

    /** 按当前筛选读本地：单设备 or 全部设备 */
    private suspend fun loadSource(): List<WordCardEntity> =
        if (CurrentDeviceStore.isAll(deviceId)) recordRepo.loadLocalAll()
        else recordRepo.loadLocal(deviceId)

    private fun loadLocal() {
        lifecycleScope.launch {
            applyList(loadSource())
        }
    }

    private fun sync() {
        binding.pbSync.visibility = View.VISIBLE
        lifecycleScope.launch {
            val lang = NameResolver.requestLangParam(this@CardListActivity)
            if (CurrentDeviceStore.isAll(deviceId)) syncAllDevices(lang) else syncOne(deviceId, lang)
            applyList(loadSource())
            binding.pbSync.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
            syncedOnce = true
        }
    }

    private suspend fun syncOne(id: String, lang: String?) {
        when (val r = recordRepo.sync(id, lang, SyncScope.ALL)) {
            is SyncResult.Success -> {
                bindRepo.markSynced(id)
                when {
                    r.added > 0 -> toast(getString(R.string.cards_synced_fmt, r.added, r.skipped))
                    r.cloudTotal == 0 -> showEmptyReason(
                        getString(R.string.cardlist_empty_reason_fmt, r.deviceId)
                    )
                    else -> toast(getString(R.string.cards_up_to_date_fmt, r.total))
                }
            }
            is SyncResult.InvalidCredential -> toast(r.message)
            is SyncResult.Failure -> toast(getString(R.string.common_sync_failed_fmt, r.message))
        }
    }

    /** 「全部」模式：逐台串行同步，汇总一句提示，不逐台弹 toast */
    private suspend fun syncAllDevices(lang: String?) {
        val ids = devices.map { it.deviceId }
        if (ids.isEmpty()) return
        val results = recordRepo.syncDevices(ids, lang, SyncScope.ALL)
        var added = 0
        var failed = 0
        results.forEach { (id, r) ->
            when (r) {
                is SyncResult.Success -> {
                    added += r.added
                    bindRepo.markSynced(id)
                }
                else -> failed++
            }
        }
        if (failed > 0) {
            toast(getString(R.string.cardlist_all_partial_fmt, ids.size - failed, failed))
        } else if (added > 0) {
            toast(getString(R.string.cardlist_all_synced_fmt, ids.size, added))
        }
    }

    private fun showEmptyReason(text: String) {
        binding.tvCardEmpty.text = text
    }

    private fun applyList(list: List<WordCardEntity>) {
        shownCards = list
        adapter.submit(list)
        binding.tvCardCount.text =
            if (list.isEmpty()) "" else getString(R.string.cardlist_count_fmt, list.size)
        // 空状态是「虚线环 + 图标 + 文案」的整组，必须整组显隐，
        // 只切文案会留下一个孤零零的环。
        binding.groupEmptyList.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        // 一条记录都没有时，「选择」是个死按钮 → 直接收起来
        binding.btnSelect.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        // 从首页「批量修图」带 pick 模式进来时列表可能还没同步回来：
        // 同步完发现是空的，就别留着一个「已选 0 张」的浮层
        if (syncedOnce && list.isEmpty() && adapter.isPicking()) adapter.exitSelection()
    }

    override fun onResume() {
        super.onResume()
        // 从批量页/购买弹窗回来，额度可能已经变了
        if (adapter.isPicking()) refreshSelectionQuota()
    }

    private fun openDetail(card: WordCardEntity) {
        startActivity(
            Intent(this, CardDetailActivity::class.java)
                .putExtra(CardDetailActivity.EXTRA_CARD_ID, card.id)
                // 从「全部记录」进来 → 左右滑动的范围就是本页这份列表
                //（单设备筛选时只有那台；选「全部」时跨设备，顺序与屏幕一致）
                .putExtra(CardDetailActivity.EXTRA_IDS, shownCards.map { it.id }.toLongArray())
                .putExtra(CardDetailActivity.EXTRA_SOURCE, CardDetailActivity.SOURCE_ALL)
        )
    }

    // ==================== 多选批量 ====================

    /**
     * 选择态的界面同步：底栏、入口文案、下拉刷新、列表底部留白。
     *
     * 全部收在一个函数里，因为它由三处触发（点「选择」、长按某行、勾选变化）。
     * 分散写必然漏掉一个入口，表现就是「长按进来了但底栏没出来」。
     */
    private fun renderSelection() {
        val picking = adapter.isPicking()
        binding.barSelection.visibility = if (picking) View.VISIBLE else View.GONE
        binding.btnSelect.setText(
            if (picking) R.string.cards_select_done else R.string.cards_select
        )
        // 勾东西时手一滑就整页刷新、勾选全丢
        binding.swipeRefresh.isEnabled = !picking

        val n = adapter.selectedCount()
        binding.tvSelectionCount.text = getString(R.string.cards_selected_fmt, n)
        // 一张都没选时置灰，而不是点下去弹「请先选择」
        binding.btnBatchHd.isEnabled = n > 0
        binding.btnBatchCartoon.isEnabled = n > 0

        // 底栏是浮层，不给列表补底部留白，最后一行会被盖住一半
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
     * 必须**在花钱之前**让人看到还剩几张 —— 选完 10 张才发现只有 2 张额度，
     * 那一刻的挫败感最强。不限额设备直接写「不限」，不显示数字。
     */
    private fun refreshSelectionQuota() {
        if (!adapter.isPicking()) return
        lifecycleScope.launch {
            val unlimited = AiPolicy.currentUnlimited(this@CardListActivity)
            binding.tvSelectionQuota.text = AiPolicy.quotaShort(
                this@CardListActivity,
                AiQuotaStore.freeRemaining(this@CardListActivity),
                AiQuotaStore.creditsRemaining(this@CardListActivity),
                unlimited
            )
        }
    }

    private fun startBatch(style: AiStyle) {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        AiBatchActivity.start(this, ids.toLongArray(), style)
    }

    private fun toggleFavorite(card: WordCardEntity) {
        lifecycleScope.launch {
            val next = recordRepo.toggleFavorite(card.id)
            adapter.applyFavorite(card.id, next)
            toast(getString(if (next) R.string.home_fav_added else R.string.home_fav_removed))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ==================== 适配器 ====================

    private class CardAdapter(
        private val onClick: (WordCardEntity) -> Unit,
        private val onToggleFavorite: (WordCardEntity) -> Unit
    ) : RecyclerView.Adapter<CardAdapter.VH>() {

        private val items = ArrayList<WordCardEntity>()

        // ==================== 多选态 ====================
        //
        // 选择集放在 adapter 内部而不是 Activity：onBind 每次都要判断「这行勾没勾」，
        // 放外面就变成每次绑定一次跨对象查询。Activity 只订阅 [onSelectionChanged]。

        /** 是否处于多选态 */
        private var picking = false

        /** 已选 id。LinkedHashSet 保持勾选顺序，批量任务按勾选顺序跑，进度更好理解 */
        private val selected = LinkedHashSet<Long>()

        var onSelectionChanged: (() -> Unit)? = null

        fun isPicking(): Boolean = picking

        fun selectedIds(): List<Long> = selected.toList()

        fun selectedCount(): Int = selected.size

        /** 进入多选态；[firstId] 非空时顺手选上（长按直接选那张，少一次点击） */
        fun enterSelection(firstId: Long? = null) {
            picking = true
            selected.clear()
            if (firstId != null) selected.add(firstId)
            notifyDataSetChanged()
            onSelectionChanged?.invoke()
        }

        fun exitSelection() {
            if (!picking && selected.isEmpty()) return
            picking = false
            selected.clear()
            notifyDataSetChanged()
            onSelectionChanged?.invoke()
        }

        fun toggleSelect(id: Long) {
            if (!selected.add(id)) selected.remove(id)
            // 只刷新这一行：整表重绘会让所有缩略图闪一下
            val i = indexOf(id)
            if (i >= 0) notifyItemChanged(i)
            onSelectionChanged?.invoke()
        }

        private fun indexOf(id: Long): Int = items.indexOfFirst { it.id == id }

        fun submit(list: List<WordCardEntity>) {
            items.clear()
            items.addAll(list)
            // 列表变了（同步 / 取消收藏）→ 把已经不存在的 id 从选择集里剔掉，
            // 否则底栏显示「已选 5 张」但屏幕上只剩 3 行能点
            selected.retainAll(items.map { it.id }.toSet())
            notifyDataSetChanged()
            if (picking) onSelectionChanged?.invoke()
        }

        fun applyFavorite(id: Long, favorite: Boolean) {
            val i = indexOf(id)
            if (i < 0) return
            items[i] = items[i].copy(favorite = favorite)
            notifyItemChanged(i)
        }

        /** AI 图生成好了 → 只重绘这一行（批量时可能连续来好几次，整表重绘会闪） */
        fun applyCard(card: WordCardEntity) {
            val i = indexOf(card.id)
            if (i < 0) return
            items[i] = card
            notifyItemChanged(i)
        }

        class VH(val binding: ItemWordCardBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemWordCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = items[position]
            val b = holder.binding
            val ctx = b.root.context
            val lang = LanguagePrefs.current(ctx)
            val name = NameResolver.resolve(ctx, c.id, c.nameChinese, c.nameEnglish, lang)

            b.tvCardChinese.text = name.primary
            if (name.secondary == null) {
                b.tvCardEnglish.visibility = View.GONE
            } else {
                b.tvCardEnglish.text = name.secondary
                b.tvCardEnglish.visibility = View.VISIBLE
            }
            b.tvCardMeta.text = buildList {
                when {
                    lang.isNone -> {}
                    // 繁体也是中文：拼音照样给（拼音本身不分简繁）
                    lang.isChinese -> {
                        c.pinyin?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                    else -> {
                        add(if (name.translated) lang.label else ctx.getString(R.string.card_meta_pending_fmt, lang.label))
                    }
                }
                c.createTime?.takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString("  ·  ")

            b.ivCardImage.loadCardImage(c)

            val checked = c.id in selected

            // 选择态下整行（含缩略图）都是勾选热区：这时候用户意图只有一个「多选」，
            // 任何局部还保留「看大图」都会打断他
            b.ivCardImage.setOnClickListener {
                if (picking) toggleSelect(c.id)
                else ImagePreviewActivity.start(ctx, c.id, items.map { it.id }.toLongArray())
            }
            b.root.setOnClickListener {
                if (picking) toggleSelect(c.id) else onClick(c)
            }
            // 长按进入多选并选中这一行 —— 列表里最省事的批量入口
            b.root.setOnLongClickListener {
                if (picking) toggleSelect(c.id) else enterSelection(c.id)
                true
            }

            // 心 与 勾 同位置互斥：选择态下收藏心收起，避免「想勾却点成取消收藏」
            b.btnFavorite.visibility = if (picking) View.GONE else View.VISIBLE
            b.ivPick.visibility = if (picking) View.VISIBLE else View.GONE
            if (picking) {
                b.ivPick.setBackgroundResource(
                    if (checked) R.drawable.bg_pick_on else R.drawable.bg_pick_off
                )
                // 未选中时勾本身透明（圆圈留着），选中才画出来
                b.ivPick.imageTintList = ContextCompat.getColorStateList(
                    ctx, if (checked) R.color.text_on_accent else android.R.color.transparent
                )
            }

            b.btnFavorite.setImageResource(
                if (c.favorite) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
            )
            b.btnFavorite.setBackgroundResource(
                if (c.favorite) R.drawable.bg_heart_fav else R.drawable.bg_heart_unfav
            )
            // 与首页列表行、详情页顶栏共用同一对收藏配色：
            // 未收藏 = tertiary 描边心，已收藏 = text_on_accent 实心心（压在荧光底上）。
            b.btnFavorite.imageTintList = ContextCompat.getColorStateList(
                ctx, if (c.favorite) R.color.text_on_accent else R.color.text_tertiary
            )
            b.btnFavorite.setOnClickListener { onToggleFavorite(c) }
        }

        override fun getItemCount(): Int = items.size
    }
}
