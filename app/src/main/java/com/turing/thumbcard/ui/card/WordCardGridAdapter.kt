package com.turing.thumbcard.ui.card

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.turing.thumbcard.R
import com.turing.thumbcard.data.db.WordCardEntity
import com.turing.thumbcard.data.i18n.LanguagePrefs
import com.turing.thumbcard.data.i18n.NameResolver
import com.turing.thumbcard.databinding.ItemWordCardGridBinding
import com.turing.thumbcard.util.loadCardImage

/**
 * 收藏页的网格卡适配器（2 列）。
 *
 * 与 [WordCardAdapter]（首页/全部记录的「词典行」）分开写而不是加个 layoutRes 参数：
 * 两者的信息优先级根本不同 —— 行只看「这词是什么」，卡要能「认出这张卡」。
 * 塞进一个类会变成满屏 if。
 *
 * 音标不走网络：由 Fragment 侧一次性从 [com.turing.thumbcard.data.dict.DictCache]
 * 读好整表传进来（[submit] 的 phoneticMap），onBind 里零 IO。
 * 列表滚动触发网络查询会变成流量黑洞，这条不能让步。
 */
class WordCardGridAdapter(
    private val onClick: (WordCardEntity) -> Unit,
    private val onToggleFavorite: ((WordCardEntity) -> Unit)? = null
) : RecyclerView.Adapter<WordCardGridAdapter.VH>() {

    /**
     * 卡片上的第二行：音标优先，退到拼音。
     *
     * 两者同色（text_tertiary）—— 音标带斜杠、拼音不带，用户一眼能分清，
     * 不需要再用颜色区分。用墨色反而会和 19sp 词头抢层级：
     * 154dp 宽的卡上，同色同族的 14sp 会被读成「第二行正文」而不是读音。
     */
    data class Gloss(val text: String)

    private val items = ArrayList<WordCardEntity>()
    private var glosses: Map<Long, Gloss> = emptyMap()

    // ==================== 多选态 ====================
    //
    // 选择态放在 adapter 内部而不是 Fragment，是因为「点了哪张」这件事
    // 每滚一次都要读一次（onBind 里判断勾选圈），放外面会变成每帧跨对象查询。
    // Fragment 只订阅 [onSelectionChanged] 来更新标题/底栏。

    /** 是否处于多选态 */
    private var picking = false

    /** 已选 id。用 LinkedHashSet：保持勾选顺序，批量任务按用户勾的顺序跑，进度更好理解 */
    private val selected = LinkedHashSet<Long>()

    /** 选择集或选择态变化时回调（Fragment 用它刷新「已选 N 张」与底栏） */
    var onSelectionChanged: (() -> Unit)? = null

    fun isPicking(): Boolean = picking

    fun selectedIds(): List<Long> = selected.toList()

    fun selectedCount(): Int = selected.size

    /** 进入多选态，并把 [firstId] 一起选上（长按直接选中那一张，少一次点击） */
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
        // 只刷新这一格：整表重绘会让所有图片闪一下
        val i = indexOf(id)
        if (i >= 0) notifyItemChanged(i)
        onSelectionChanged?.invoke()
    }

    /** 全选当前**筛选后**可见的卡（不是全部收藏）—— 搜索结果里点全选，用户要的是这批 */
    fun selectAll() {
        items.forEach { selected.add(it.id) }
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    fun submit(list: List<WordCardEntity>, glossMap: Map<Long, Gloss>) {
        items.clear()
        items.addAll(list)
        glosses = glossMap
        // 列表变了（同步/取消收藏/搜索）→ 把已经不存在的 id 从选择集里剔掉，
        // 否则底栏会显示「已选 5 张」但屏幕上只剩 3 张能点。
        selected.retainAll(items.map { it.id }.toSet())
        notifyDataSetChanged()
        if (picking) onSelectionChanged?.invoke()
    }

    /** 语言切换后重绘（数据没变，只换名称） */
    fun refreshNames() = notifyDataSetChanged()

    fun indexOf(id: Long): Int = items.indexOfFirst { it.id == id }

    /** 只刷新单条的收藏态，避免整表重绘造成图片闪动 */
    fun applyFavorite(id: Long, favorite: Boolean) {
        val i = indexOf(id)
        if (i < 0) return
        items[i] = items[i].copy(favorite = favorite)
        notifyItemChanged(i)
    }

    /**
     * 单条卡片的其它字段变了 —— 目前只有「AI 图生成好了」（`aiStyle` 变化）。
     *
     * 为什么不用 `submit()` 整表重绘：AI 生成是**一张一张**完成的，
     * 批量 10 张就会重绘 10 次整表，每次的 `notifyDataSetChanged` 会让
     * 所有图片重新绑定、闪一下。只刷变的那一格，画面是静的。
     */
    fun applyCard(card: WordCardEntity) {
        val i = indexOf(card.id)
        if (i < 0) return
        items[i] = card
        notifyItemChanged(i)
    }

    class VH(val binding: ItemWordCardGridBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemWordCardGridBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        val b = holder.binding
        val ctx = b.root.context
        val lang = LanguagePrefs.current(ctx)

        val name = NameResolver.resolve(ctx, c.id, c.nameChinese, c.nameEnglish, lang)

        // 第一行固定是英文：这套视觉里英文是主角（公理 B）。
        // 「无（仅英文）」模式下 secondary 为 null，此时主名称本身就是英文。
        b.tvCardEnglish.text = name.secondary ?: name.primary

        if (name.secondary == null) {
            // 没有独立的英文名 → 第一行已经写了它，第三行整行收起（不留空行）
            b.tvCardChinese.visibility = View.GONE
        } else {
            b.tvCardChinese.visibility = View.VISIBLE
            b.tvCardChinese.text = name.primary
            // 没拿到真译名时弱化，视觉上就不像「最终结果」（与行式同一判据）
            b.tvCardChinese.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (name.translated || lang.isChinese) R.color.text_primary else R.color.text_secondary
                )
            )
        }

        // 第二行：音标 → 拼音 → 占位
        // ⚠️ 必须 INVISIBLE 而不是 GONE：同一行的两张卡若一张有音标一张没有，
        // GONE 会让后者矮 24dp，网格立刻参差。
        val gloss = glosses[c.id]
        if (gloss == null) {
            b.tvCardPhonetic.text = ""
            b.tvCardPhonetic.visibility = View.INVISIBLE
        } else {
            b.tvCardPhonetic.text = gloss.text
            b.tvCardPhonetic.visibility = View.VISIBLE
        }

        b.ivCardImage.loadCardImage(c)

        val checked = c.id in selected

        // 选择态下，整张卡（含图片）都是勾选热区：
        // 这时候用户的意图只有一个「多选」，任何局部还是「看大图」都会打断他。
        b.ivCardImage.setOnClickListener {
            if (picking) toggleSelect(c.id)
            else ImagePreviewActivity.start(ctx, c.id, items.map { it.id }.toLongArray())
        }
        b.root.setOnClickListener {
            if (picking) toggleSelect(c.id) else onClick(c)
        }
        // 长按进入多选（并顺手选中这一张）—— 这是列表里最省事的批量入口，
        // 比先点右上角「选择」再逐张勾少两次操作。
        b.root.setOnLongClickListener {
            if (picking) toggleSelect(c.id) else enterSelection(c.id)
            true
        }

        // 心 与 勾 同位置互换：选择态下收藏心收起，避免「勾了却点成取消收藏」
        b.boxFavorite.visibility =
            if (!picking && onToggleFavorite != null) View.VISIBLE else View.GONE
        b.boxPick.visibility = if (picking) View.VISIBLE else View.GONE
        if (picking) {
            b.ivPick.setBackgroundResource(
                if (checked) R.drawable.bg_pick_on else R.drawable.bg_pick_off
            )
            // 未选中时勾本身透明（圆圈留着），选中才画出来
            b.ivPick.imageTintList = ContextCompat.getColorStateList(
                ctx, if (checked) R.color.text_on_accent else android.R.color.transparent
            )
        }

        val heart = b.btnFavorite
        if (onToggleFavorite == null) {
            heart.visibility = View.GONE
        } else {
            heart.visibility = View.VISIBLE
            heart.setImageResource(
                if (c.favorite) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
            )
            heart.setBackgroundResource(
                if (c.favorite) R.drawable.bg_heart_fav else R.drawable.bg_heart_unfav
            )
            // 矢量图的 fillColor 只是基色，最终色由 tint 决定：
            // 已收藏压在荧光圆上必须是墨色（11.93:1），白色会糊成一片。
            heart.imageTintList = ContextCompat.getColorStateList(
                ctx, if (c.favorite) R.color.text_on_accent else R.color.text_tertiary
            )
            heart.setOnClickListener { onToggleFavorite.invoke(c) }
        }
    }

    override fun getItemCount(): Int = items.size
}
