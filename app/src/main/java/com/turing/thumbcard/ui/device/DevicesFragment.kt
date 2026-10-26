package com.turing.thumbcard.ui.device

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.turing.thumbcard.data.db.BoundDeviceEntity
import com.turing.thumbcard.R
import com.turing.thumbcard.data.repo.BindRepository
import com.turing.thumbcard.databinding.FragmentDevicesBinding
import com.turing.thumbcard.databinding.ItemDeviceBinding
import com.turing.thumbcard.ui.MainActivity
import com.turing.thumbcard.ui.provision.ScanDeviceActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设备管理页（底部 Tab 版）。
 */
class DevicesFragment : Fragment() {

    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: BindRepository
    private val adapter = DeviceAdapter(
        onClick = { switchToCards() },
        onLongClick = { d -> showActions(d) }
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = BindRepository(requireContext())
        binding.rvBoundDevices.layoutManager = LinearLayoutManager(requireContext())
        binding.rvBoundDevices.adapter = adapter
        binding.btnAddMore.setOnClickListener {
            startActivity(Intent(requireContext(), ScanDeviceActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun switchToCards() {
        (activity as? MainActivity)?.selectTab(MainActivity.TAB_CARDS)
    }

    private fun refresh() {
        lifecycleScope.launch {
            val list = runCatching { repo.listBound() }.getOrNull().orEmpty()
            adapter.submit(list)
            binding.tvBoundEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            binding.tvDeviceCount.text =
                if (list.isEmpty()) "" else getString(R.string.device_count_fmt, list.size)
        }
    }

    private fun showActions(d: BoundDeviceEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle(d.deviceName ?: d.deviceId)
            .setItems(
                arrayOf(
                    getString(R.string.device_menu_cards),
                    getString(R.string.device_menu_rename),
                    getString(R.string.device_menu_unbind)
                )
            ) { _, which ->
                when (which) {
                    0 -> switchToCards()
                    1 -> renameDialog(d)
                    2 -> unbindDialog(d)
                }
            }
            .show()
    }

    private fun renameDialog(d: BoundDeviceEntity) {
        val input = android.widget.EditText(requireContext()).apply {
            setText(d.deviceName ?: "")
            hint = getString(R.string.device_rename_hint)
        }
        AlertDialog.Builder(requireContext())
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
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.device_unbind_title_alt)
            .setMessage(R.string.device_unbind_msg_alt)
            .setNegativeButton(com.turing.thumbcard.R.string.common_cancel, null)
            .setPositiveButton(R.string.device_unbind_ok) { _, _ ->
                lifecycleScope.launch {
                    repo.unbind(d.deviceId)
                    Toast.makeText(requireContext(), getString(R.string.device_unbound), Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .show()
    }

    // ==================== 适配器 ====================

    private class DeviceAdapter(
        private val onClick: (BoundDeviceEntity) -> Unit,
        private val onLongClick: (BoundDeviceEntity) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<DeviceAdapter.VH>() {

        private val items = ArrayList<BoundDeviceEntity>()
        private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        fun submit(list: List<BoundDeviceEntity>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        class VH(val binding: ItemDeviceBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val d = items[position]
            val b = holder.binding
            val ctx = b.root.context
            b.tvDeviceName.text = d.deviceName ?: ctx.getString(R.string.device_default_name)
            b.tvDeviceMac.text = buildString {
                append(d.deviceId)
                d.lastSyncTime?.let { append(ctx.getString(R.string.device_sync_at_fmt, fmt.format(Date(it)))) }
                    ?: append(ctx.getString(R.string.device_not_synced))
            }
            b.root.setOnClickListener { onClick(d) }
            b.root.setOnLongClickListener { onLongClick(d); true }
        }

        override fun getItemCount(): Int = items.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
