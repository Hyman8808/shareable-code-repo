package com.turing.thumbcard.ui.card

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.turing.thumbcard.R
import com.turing.thumbcard.data.db.WordCardEntity
import com.turing.thumbcard.data.i18n.LanguagePrefs
import com.turing.thumbcard.data.i18n.NameResolver
import com.turing.thumbcard.data.i18n.UiLanguage
import com.turing.thumbcard.databinding.ItemWordCardBinding
import com.turing.thumbcard.util.loadCardImage

/**
 * 单词卡网格适配器（首页与单词卡页共用）。
 *
 * 名称展示统一走 [NameResolver]：
 *  - 主名称 = 当前所选语言的名称（中文时即识别原文）
 *  - 副名称 = 英文名（固定第二行，不随语言变化）
 *  - meta  = 中文时显示拼音；外语时显示「语言 · 待翻译」，让用户知道这是回退显示
 *
 * 收藏按钮：传 [onToggleFavorite] 才显示；不传（如只读场景）则整颗心不出现。
 */
class WordCardAdapter(
    private val onClick: (WordCardEntity) -> Unit,
    private val onToggleFavorite: ((WordCardEntity) -> Unit)? = null
) : RecyclerView.Adapter<WordCardAdapter.VH>() {

    private val items = ArrayList<WordCardEntity>()

    fun submit(list: List<WordCardEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** 语言切换后重绘（数据没变，但名称要换） */
    fun refreshNames() = notifyDataSetChanged()

    /** 找到某张卡片当前所在位置（收藏切换后局部刷新用，找不到返回 -1） */
    fun indexOf(id: Long): Int = items.indexOfFirst { it.id == id }

    /** 只用「切换后的收藏态」刷新单条，避免整表重绘造成图片闪动 */
    fun applyFavorite(id: Long, favorite: Boolean) {
        val i = indexOf(id)
        if (i < 0) return
        items[i] = items[i].copy(favorite = favorite)
        notifyItemChanged(i)
    }

    /** 单条卡片的其它字段变了（目前只有 AI 图换好了）→ 只重绘这一行 */
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
        // 没有真译名时弱化主名称，视觉上就不像「最终结果」
        b.tvCardChinese.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (name.translated || lang.isChinese) R.color.text_primary else R.color.text_secondary
            )
        )

        // 「无（仅英文）」模式：英文名就是主角，写进大字号行，中文行整行隐藏
        //（显示 "-" 反而像坏数据；把英文塞进 15sp 的小灰字行则是层级错误）
        if (name.secondary == null) {
            b.tvCardEnglish.text = name.primary
            b.tvCardEnglish.visibility = android.view.View.VISIBLE
            b.tvCardChinese.visibility = android.view.View.GONE
        } else {
            b.tvCardEnglish.text = name.secondary
            b.tvCardEnglish.visibility = android.view.View.VISIBLE
            b.tvCardChinese.visibility = android.view.View.VISIBLE
        }

        b.tvCardMeta.text = buildList {
            when {
                // 仅英文：不放语言标注，只留时间（界面全英文，中文标签会穿帮）
                lang.isNone -> {}
                // 繁体也是中文：拼音照样给（拼音本身不分简繁）
                lang.isChinese -> {
                    c.pinyin?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                else -> {
                    val langName = lang.labelFor(UiLanguage.storedTag(ctx))
                    add(
                        if (name.translated) langName
                        else ctx.getString(R.string.card_meta_pending_fmt, langName)
                    )
                }
            }
            c.createTime?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString("  ·  ")

        b.ivCardImage.loadCardImage(c)
        // 点图片 = 直接看原图 / 存相册，不经过卡片详情。
        // 「卡片详情」回答的是「这词怎么读」，而点图片的人要的是「这张照片放大看看」，
        // 两件事分开，各自一步到位。
        // 传当前列表顺序：预览页据此左右滑上一张/下一张（与用户点进来时看到的逐项一致）
        b.ivCardImage.setOnClickListener {
            ImagePreviewActivity.start(ctx, c.id, items.map { it.id }.toLongArray())
        }
        b.root.setOnClickListener { onClick(c) }

        // 长按整行 = 收藏 / 取消收藏。
        // 为什么必须有：行内那颗心只有 30dp，通勤单手（拇指+列车晃动）点不中；
        // 整行是 88dp 高的天然大热区，长按不需要精度。震动反馈确认「收到了」。
        if (onToggleFavorite != null) {
            b.root.setOnLongClickListener {
                onToggleFavorite.invoke(c)
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                true
            }
        } else {
            b.root.setOnLongClickListener(null)
        }

        val heart = b.btnFavorite
        if (onToggleFavorite == null) {
            heart.visibility = android.view.View.GONE
        } else {
            heart.visibility = android.view.View.VISIBLE
            heart.setImageResource(
                if (c.favorite) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
            )
            heart.setBackgroundResource(
                if (c.favorite) R.drawable.bg_heart_fav else R.drawable.bg_heart_unfav
            )
            // 矢量图的 fillColor 只是「基色」，最终颜色由 tint 决定：
            // 未收藏 = text_tertiary 描边心（暖白圆上可读），
            // 已收藏 = text_on_accent 深墨实心（压在荧光圆底上 11.93:1）。
            heart.imageTintList = ContextCompat.getColorStateList(
                ctx, if (c.favorite) R.color.text_on_accent else R.color.text_tertiary
            )
            heart.setOnClickListener { onToggleFavorite.invoke(c) }
        }
    }

    override fun getItemCount(): Int = items.size
}
