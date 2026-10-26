package com.turing.thumbcard.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 【仅 debug】模拟设备广播器的开关。
 *
 * ```bash
 * # 开（纯名字广播，验证 AIPicCamera 兜底通道）
 * adb shell am broadcast -a com.turing.thumbcard.DEBUG_MOCK_BLE --ez on true
 * # 开（带 ORG-ID，验证主通道）
 * adb shell am broadcast -a com.turing.thumbcard.DEBUG_MOCK_BLE --ez on true --ez orgid true
 * # 关
 * adb shell am broadcast -a com.turing.thumbcard.DEBUG_MOCK_BLE --ez on false
 * ```
 */
class MockBleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val on = intent.getBooleanExtra("on", false)
        val orgId = intent.getBooleanExtra("orgid", false)
        val mock = instance ?: MockPeripheral(context.applicationContext).also { instance = it }

        if (on) {
            mock.start(withOrgId = orgId)
            Log.i(TAG, "模拟广播已请求启动（orgId=$orgId）")
        } else {
            mock.stop()
            mock.restoreName()
            Log.i(TAG, "模拟广播已停止，蓝牙名已恢复")
        }
    }

    private companion object {
        const val TAG = "MockBleReceiver"
        var instance: MockPeripheral? = null
    }
}
