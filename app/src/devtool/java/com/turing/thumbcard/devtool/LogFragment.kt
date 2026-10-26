package com.turing.thumbcard.devtool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.turing.thumbcard.R
import com.turing.thumbcard.databinding.DevFragmentLogBinding

/**
 * 报文面板：BLE 收发的每一帧十六进制 + 各阶段步骤，合在一起按时间排。
 *
 * 联调时固件工程师只需要看这一页：发的什么、收的什么、卡在哪一步。
 */
class LogFragment : Fragment() {

    private var _b: DevFragmentLogBinding? = null
    private val b get() = _b!!

    private val adapter = LogAdapter()
    private var autoScroll = true

    private val observer: (DevLog.Entry) -> Unit = { entry ->
        val binding = _b
        if (binding != null) {
            adapter.append(entry)
            binding.tvCount.text = "${adapter.itemCount} 条"
            binding.tvEmpty.visibility = View.GONE
            if (autoScroll) binding.rvLog.scrollToPosition(adapter.itemCount - 1)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = DevFragmentLogBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.rvLog.layoutManager = LinearLayoutManager(requireContext())
        b.rvLog.adapter = adapter
        adapter.submit(DevLog.snapshot())
        b.tvCount.text = "${adapter.itemCount} 条"
        b.tvEmpty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE

        // 用户往上翻的时候不要把他拽回底部
        b.rvLog.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                autoScroll = !rv.canScrollVertically(1)
            }
        })

        b.btnCopy.setOnClickListener { copyAll() }
        b.btnClear.setOnClickListener {
            DevLog.clear()
            adapter.submit(emptyList())
            b.tvCount.text = "0 条"
            b.tvEmpty.visibility = View.VISIBLE
        }

        DevLog.observe(observer)
    }

    override fun onDestroyView() {
        DevLog.forget(observer)
        _b = null
        super.onDestroyView()
    }

    private fun copyAll() {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("thumbcard-devtool-log", DevLog.dump()))
        Toast.makeText(requireContext(), R.string.dev_copied, Toast.LENGTH_SHORT).show()
    }
}
