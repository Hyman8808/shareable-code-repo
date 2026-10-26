package com.turing.thumbcard.devtool

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 联调日志缓冲（进程内单例）。
 *
 * 配网面板负责往里写（步骤 + 每一帧收发），报文面板负责读。
 * 两边共享同一份数据，不需要跨 Fragment 传引用。
 */
object DevLog {

    const val KIND_FRAME = 0
    const val KIND_STEP = 1
    const val KIND_ERROR = 2

    data class Entry(
        val at: Long,
        val kind: Int,
        /** 仅帧有效：`TX` / `RX` */
        val dir: String,
        val text: String
    ) {
        val timeText: String
            get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(at))
    }

    /** 超过这个条数从头丢弃，避免长时间联调把内存吃满 */
    private const val MAX = 800

    private val items = ArrayList<Entry>(MAX)
    private val listeners = CopyOnWriteArrayList<(Entry) -> Unit>()

    fun snapshot(): List<Entry> = synchronized(items) { ArrayList(items) }

    fun size(): Int = synchronized(items) { items.size }

    fun frame(dir: String, hex: String) =
        add(Entry(System.currentTimeMillis(), KIND_FRAME, dir, hex))

    fun step(text: String) =
        add(Entry(System.currentTimeMillis(), KIND_STEP, "", text))

    fun error(text: String) =
        add(Entry(System.currentTimeMillis(), KIND_ERROR, "", text))

    private fun add(e: Entry) {
        synchronized(items) {
            items.add(e)
            if (items.size > MAX) items.subList(0, items.size - MAX).clear()
        }
        listeners.forEach { it(e) }
    }

    fun clear() {
        synchronized(items) { items.clear() }
    }

    fun observe(l: (Entry) -> Unit) {
        listeners.add(l)
    }

    fun forget(l: (Entry) -> Unit) {
        listeners.remove(l)
    }

    /** 复制到剪贴板用的纯文本 */
    fun dump(deviceMac: String? = null): String = buildString {
        append("=== 拍学机联调日志 ===\n")
        if (!deviceMac.isNullOrBlank()) append("设备 MAC: ").append(deviceMac).append('\n')
        append('\n')
        snapshot().forEach { e ->
            append(e.timeText).append(' ')
            when (e.kind) {
                KIND_FRAME -> append(e.dir).append(' ').append(e.text)
                else -> append(e.text)
            }
            append('\n')
        }
    }
}
