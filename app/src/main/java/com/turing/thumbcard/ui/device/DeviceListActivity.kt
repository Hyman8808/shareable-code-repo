package com.turing.thumbcard.ui.device

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.turing.thumbcard.R
import com.turing.thumbcard.data.db.BoundDeviceEntity
import com.turing.thumbcard.data.device.CurrentDeviceStore
import com.turing.thumbcard.data.repo.BindRepository
import com.turing.thumbcard.databinding.ActivityDeviceListBinding
import com.turing.thumbcard.databinding.ItemDeviceBinding
import com.turing.thumbcard.ui.card.CardListActivity
import com.turing.thumbcard.ui.provision.ScanDeviceActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 已绑定设备管理页。
 *
 * 交互口径（v2.8.0 调整）：
 *  - **点一下 = 切换当前设备**，然后回到上一页。
 *    这一页是「我的相机」清单，用户点某一台的本意就是「我要看这台」；
 *    旧实现在这里直接跳「全部单词记录」，于是只有那一页换了设备，
 *    首页 / 收藏仍然停在第一台 —— 表现就是「切了但没完全切」。
 *  - **长按 = 更多操作**（查看全部记录 / 设为当前 / 重命名 / 解绑）。
 */
class DeviceListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceListBinding
    private lateinit var repo: BindRepository
    private var currentId: String? = null

    private val adapter = DeviceAdapter(
        onClick = { d -> selectDevice(d) },
        onLongClick = { d -> showActions(d) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = BindRepository(this)
        binding.rvBoundDevices.layoutManager = LinearLayoutManager(this)
        binding.rvBoundDevices.adapter = adapter
        binding.btnAddMore.setOnClickListener {
            startActivity(Intent(this, ScanDeviceActivity::class.java))
        }
        binding.tvDeviceHint.setText(R.string.device_switch_hint)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val list = repo.listBound()
            // 先校正一次当前设备：可能刚被解绑，也可能一次都没选过
            currentId = CurrentDeviceStore.resolve(this@DeviceListActivity, list)?.deviceId
            adapter.submit(list, currentId)
            binding.tvBoundEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            // 一台都没有时，「点按切换」这句说明无从谈起
            binding.tvDeviceHint.visibility = if (list.size > 1) View.VISIBLE else View.GONE
        }
    }

    /** 点一下 = 设为当前设备。所有页面（首页/收藏/全部记录）都读这一个指针 */
    private fun selectDevice(d: BoundDeviceEntity) {
        if (d.deviceId == currentId) {
            toast(getString(R.string.device_already_current))
            return
        }
        CurrentDeviceStore.setCurrent(this, d.deviceId)
        val name = CurrentDeviceStore.displayName(d, getString(R.string.device_default_name))
        toast(getString(R.string.device_switched_fmt, name))
        // 回上一页：首页 / 收藏 / 我的 都会在 onResume 重读当前设备
        finish()
    }

    private fun openCards(d: BoundDeviceEntity) {
        startActivity(
            Intent(this, CardListActivity::class.java)
                .putExtra(CardListActivity.EXTRA_DEVICE_ID, d.deviceId)
        )
    }

    private fun showActions(d: BoundDeviceEntity) {
        val isCurrent = d.deviceId == currentId
        val items = buildList {
            add(getString(R.string.device_menu_cards))
            if (!isCurrent) add(getString(R.string.device_menu_set_current))
            add(getString(R.string.device_menu_rename))
            add(getString(R.string.device_menu_unbind))
        }
        AlertDialog.Builder(this)
            .setTitle(CurrentDeviceStore.displayName(d, getString(R.string.device_default_name)))
            .setItems(items.toTypedArray()) { _, which ->
                if (isCurrent) {
                    when (which) {
                        0 -> openCards(d)
                        1 -> renameDialog(d)
                        2 -> unbindDialog(d)
                    }
                } else {
                    when (which) {
                        0 -> openCards(d)
                        1 -> selectDevice(d)
                        2 -> renameDialog(d)
                        3 -> unbindDialog(d)
                    }
                }
            }
            .show()
    }

    private fun renameDialog(d: BoundDeviceEntity) {
        val input = android.widget.EditText(this).apply {
            setText(d.deviceName ?: "")
            hint = getString(R.string.device_rename_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.device_rename_title)
            .setView(input)
            .setNegativeButton(com.turing.thumbcard.R.string.common_cancel, null)
            .setPositiveButton(com.turing.thumbcard.R.string.common_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    repo.rename(d.deviceId, name)
                    refresh()
                }
            }
            .show()
    }

    private fun unbindDialog(d: BoundDeviceEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.device_unbind_title)
            .setMessage(R.string.device_unbind_msg)
            .setNegativeButton(com.turing.thumbcard.R.string.common_cancel, null)
            .setPositiveButton(R.string.device_unbind_ok) { _, _ ->
                lifecycleScope.launch {
                    repo.unbind(d.deviceId)
                    // 🔴 解绑的正好是当前设备 → 必须清掉指针，
                    //    否则下次启动会拿着一个已经不存在的 deviceId 去同步
                    CurrentDeviceStore.clearIfMatches(this@DeviceListActivity, d.deviceId)
                    Toast.makeText(this@DeviceListActivity, getString(R.string.device_unbound), Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .show()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ==================== 适配器 ====================

    private class DeviceAdapter(
        private val onClick: (BoundDeviceEntity) -> Unit,
        private val onLongClick: (BoundDeviceEntity) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.VH>() {

        private val items = ArrayList<BoundDeviceEntity>()
        private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        /** 当前设备 id：行右侧那颗 pill 显示「当前」而不是「切换」 */
        private var currentId: String? = null

        fun submit(list: List<BoundDeviceEntity>, current: String?) {
            items.clear()
            items.addAll(list)
            currentId = current
            notifyDataSetChanged()
        }

        class VH(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val d = items[position]
            val b = holder.binding
            val ctx = b.root.context
            b.tvDeviceName.text = CurrentDeviceStore.displayName(
                d, ctx.getString(R.string.device_default_name)
            )
            b.tvDeviceMac.text = buildString {
                append(d.deviceId)
                d.lastSyncTime?.let {
                    append(ctx.getString(R.string.device_sync_at_fmt, fmt.format(Date(it))))
                } ?: append(ctx.getString(R.string.device_not_synced))
            }

            // 当前设备用「实心墨底 pill」，其余是「淡底 pill」：
            // 只靠一行小字区分「当前」会在列表里看不清，实心块一眼就能扫到
            val isCurrent = d.deviceId == currentId
            b.tvDeviceAction.setText(
                if (isCurrent) R.string.device_state_current else R.string.device_state_switch
            )
            b.tvDeviceAction.setBackgroundResource(
                if (isCurrent) R.drawable.bg_pill_ink else R.drawable.bg_pill_soft
            )
            b.tvDeviceAction.setTextColor(
                ContextCompat.getColor(
                    ctx, if (isCurrent) R.color.text_on_accent else R.color.brand_primary
                )
            )

            b.root.setOnClickListener { onClick(d) }
            b.root.setOnLongClickListener { onLongClick(d); true }
        }

        override fun getItemCount(): Int = items.size
    }
}
