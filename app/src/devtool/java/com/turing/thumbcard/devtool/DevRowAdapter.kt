package com.turing.thumbcard.devtool

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.turing.thumbcard.databinding.ItemDevRowBinding

/**
 * 三个列表（设备 / 热点 / 卡片）共用的一行数据。
 *
 * 联调工具不需要各列表长得不一样，统一成「主标题 + 副标题 + 右侧小字」，
 * 省掉三套 adapter 与三套布局。
 */
data class DevRow(
    val title: String,
    val sub: String = "",
    val trail: String = "",
    /** 行高亮（比如当前连接的那台） */
    val active: Boolean = false,
    val tag: Any? = null
)

class DevRowAdapter(
    private val onClick: (DevRow) -> Unit = {}
) : RecyclerView.Adapter<DevRowAdapter.VH>() {

    private val data = ArrayList<DevRow>()

    fun submit(list: List<DevRow>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDevRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(data[position])
    }

    inner class VH(private val b: ItemDevRowBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: DevRow) {
            b.tvTitle.text = row.title
            b.tvSub.text = row.sub
            b.tvSub.visibility = if (row.sub.isBlank()) View.GONE else View.VISIBLE
            b.tvTrail.text = row.trail
            b.tvTrail.visibility = if (row.trail.isBlank()) View.GONE else View.VISIBLE
            b.root.isSelected = row.active
            b.root.alpha = if (row.active) 1f else 0.92f
            b.root.setOnClickListener { onClick(row) }
        }
    }
}
