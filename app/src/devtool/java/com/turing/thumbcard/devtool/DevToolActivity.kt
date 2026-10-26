package com.turing.thumbcard.devtool

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.turing.thumbcard.R
import com.turing.thumbcard.ble.BleWiFiClient
import com.turing.thumbcard.databinding.ActivityDevtoolBinding

/**
 * 联调工具主界面。
 *
 * 三个页签：配网 / 报文 / 记录。BLE 客户端在 Activity 层持有，
 * 配网页通过 [bleClient] 拿，页面切换不会把连接断掉。
 */
class DevToolActivity : AppCompatActivity() {

    private lateinit var b: ActivityDevtoolBinding

    lateinit var bleClient: BleWiFiClient
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDevtoolBinding.inflate(layoutInflater)
        setContentView(b.root)

        bleClient = BleWiFiClient(this)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().apply {
                add(R.id.container, NetFragment(), TAG_NET)
                add(R.id.container, LogFragment(), TAG_LOG)
                add(R.id.container, CardsFragment(), TAG_CARD)
            }.commitNow()
        }

        b.tabNet.setOnClickListener { select(0) }
        b.tabLog.setOnClickListener { select(1) }
        b.tabCard.setOnClickListener { select(2) }

        select(if (savedInstanceState == null) 0 else savedInstanceState.getInt(KEY_TAB, 0))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    private var currentTab = 0

    private fun select(index: Int) {
        currentTab = index
        val fm = supportFragmentManager
        fm.beginTransaction().apply {
            for (i in 0..2) {
                val f = fm.findFragmentByTag(tagOf(i)) ?: continue
                if (i == index) show(f) else hide(f)
            }
        }.commit()

        val tabs = listOf(b.tabNet, b.tabLog, b.tabCard)
        tabs.forEachIndexed { i, tv -> tv.isSelected = i == index }
    }

    private fun tagOf(i: Int): String = when (i) {
        0 -> TAG_NET
        1 -> TAG_LOG
        else -> TAG_CARD
    }

    override fun onDestroy() {
        runCatching { bleClient.stopScan() }
        runCatching { bleClient.disconnect() }
        super.onDestroy()
    }

    private companion object {
        const val TAG_NET = "p0"
        const val TAG_LOG = "p1"
        const val TAG_CARD = "p2"
        const val KEY_TAB = "dev_tab"
    }
}
