package com.turing.thumbcard.ui.provision

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.turing.thumbcard.ble.model.WifiAp
import com.turing.thumbcard.databinding.ItemWifiApBinding

/**
 * WiFi 列表适配器（配网 V1.2：设备扫、用户选）。
 *
 * 只做展示，不做任何蓝牙交互 —— 点选回调交给 Activity，
 * 因为「开放网络直接下发 / 加密网络先弹密码框」属于流程决策，不属于列表职责。
 */
class WifiApAdapter(
    private val onPick: (WifiAp) -> Unit
) : RecyclerView.Adapter<WifiApAdapter.VH>() {

    private val items = ArrayList<WifiAp>()

    fun submit(list: List<WifiAp>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemWifiApBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val b: ItemWifiApBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(ap: WifiAp) {
            b.tvSsid.text = ap.ssid

            // 开放网络不显示锁：用户看到锁就会去找密码，多一个误解点
            b.ivLock.visibility = if (ap.isOpen) View.GONE else View.VISIBLE

            // 信号格：4 根柱子固定存在，用 alpha 区分「亮/暗」，
            // 避免为 4 档各做一张图，也让高度差不随主题变化
            val bars = arrayOf(b.bar1, b.bar2, b.bar3, b.bar4)
            bars.forEachIndexed { index, v ->
                v.alpha = if (index < ap.signalBars) ACTIVE_ALPHA else INACTIVE_ALPHA
            }

            // 开放网络点一下就能连，加一句说明，避免用户以为「点了没反应」
            b.root.setOnClickListener { onPick(ap) }
        }
    }

    private companion object {
        const val ACTIVE_ALPHA = 1f
        const val INACTIVE_ALPHA = 0.18f
    }
}
