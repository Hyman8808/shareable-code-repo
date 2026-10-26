package com.turing.thumbcard.devtool

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.turing.thumbcard.databinding.ItemDevLogBinding

/** 报文列表：每行一条，帧用等宽字体，步骤用普通字体。 */
class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {

    private val data = ArrayList<DevLog.Entry>()

    fun submit(list: List<DevLog.Entry>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    fun append(e: DevLog.Entry) {
        data.add(e)
        notifyItemInserted(data.size - 1)
    }

    override fun getItemCount(): Int = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDevLogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(data[position])
    }

    inner class VH(private val b: ItemDevLogBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(e: DevLog.Entry) {
            when (e.kind) {
                DevLog.KIND_FRAME -> {
                    b.tvHead.text = "${e.timeText}  ${e.dir}"
                    b.tvHead.setTextColor(0xFF6E695E.toInt())
                    b.tvHex.text = e.text
                    b.tvHex.setTextColor(0xFF1A1917.toInt())
                }
                DevLog.KIND_ERROR -> {
                    b.tvHead.text = e.timeText
                    b.tvHead.setTextColor(0xFF8E3450.toInt())
                    b.tvHex.text = e.text
                    b.tvHex.setTextColor(0xFF8E3450.toInt())
                }
                else -> {
                    b.tvHead.text = e.timeText
                    b.tvHead.setTextColor(0xFF9A9484.toInt())
                    b.tvHex.text = e.text
                    b.tvHex.setTextColor(0xFF3D3A33.toInt())
                }
            }
        }
    }
}
