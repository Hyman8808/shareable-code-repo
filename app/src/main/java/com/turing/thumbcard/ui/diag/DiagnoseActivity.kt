package com.turing.thumbcard.ui.diag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.turing.thumbcard.BuildConfig
import com.turing.thumbcard.R
import com.turing.thumbcard.ThumbCardApp
import com.turing.thumbcard.data.db.AppDatabase
import com.turing.thumbcard.data.device.DeviceLangStore
import com.turing.thumbcard.data.repo.BindRepository
import com.turing.thumbcard.data.repo.LatencyStore
import com.turing.thumbcard.data.repo.RecordRepository
import com.turing.thumbcard.databinding.ActivityDiagnoseBinding
import com.turing.thumbcard.util.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 诊断页。
 *
 * 用于在真机（尤其是华为鸿蒙 / 卓易通等容器环境）上快速定位问题：
 *  - 运行环境信息
 *  - 各项能力可用性（加密存储 / 数据库 / BLE）
 *  - 上次崩溃堆栈
 *  - 启动期异常记录
 *
 * 入口：首页底部版本号可点击。
 */
class DiagnoseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnoseBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnoseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvDiagContent.text = buildReport()

        binding.btnCopy.setOnClickListener { copyReport() }
        binding.btnClear.setOnClickListener {
            ThumbCardApp.clearDiag()
            Toast.makeText(this, getString(R.string.diag_cleared), Toast.LENGTH_SHORT).show()
            binding.tvDiagContent.text = buildReport()
        }

        binding.btnCloudCheck.setOnClickListener { runCloudCheck() }
        binding.btnBack.setOnClickListener { finish() }
    }

    /**
     * 云端连通性自检。
     *
     * 分别查询「本机已绑定设备的 deviceId」与「已知有记录的参考 deviceId」，
     * 用 `data.total` 判断问题出在 App 查询链路还是设备上传链路。
     */
    private fun runCloudCheck() {
        binding.btnCloudCheck.isEnabled = false
        binding.tvCloudCheckResult.setText(R.string.diag_checking)

        lifecycleScope.launch {
            val repo = RecordRepository(this@DiagnoseActivity)
            val apiKey = CredentialStore.getApiKey() ?: BuildConfig.DEFAULT_API_KEY
            repo.setApiKeyOverride(apiKey)

            val boundIds = withContext(Dispatchers.IO) {
                runCatching { BindRepository(this@DiagnoseActivity).listBound() }
                    .getOrElse { emptyList() }
                    .map { it.deviceId }
            }

            val report = StringBuilder()
            report.append("apikey: ${maskKey(apiKey)}\n\n")

            if (boundIds.isEmpty()) {
                report.append("（本机未找到已绑定设备）\n\n")
            } else {
                report.append("【本机绑定设备】\n")
                boundIds.forEach { id ->
                    val n = queryTotal(repo, id)
                    report.append("  $id  → ${if (n < 0) "查询失败" else "云端 $n 条"}\n")
                }
                report.append("\n")
            }

            // 参考值：联盛德测试设备（已知有记录）
            report.append("【参考设备（联盛德测试机）】\n")
            val ref = "c8586a630100"
            val refN = queryTotal(repo, ref)
            report.append("  $ref  → ${if (refN < 0) "查询失败" else "云端 $refN 条"}\n\n")

            report.append(
                when {
                    refN > 0 -> getString(R.string.diag_ok) + "\n" +
                            getString(R.string.diag_ok_detail)
                    refN == 0 -> getString(R.string.diag_ref_zero)
                    else -> getString(R.string.diag_unreachable)
                }
            )

            binding.tvCloudCheckResult.text = report.toString()
            binding.btnCloudCheck.isEnabled = true
        }
    }

    private suspend fun queryTotal(repo: RecordRepository, deviceId: String): Int =
        withContext(Dispatchers.IO) {
            when (val r = repo.sync(deviceId)) {
                is com.turing.thumbcard.data.repo.SyncResult.Success -> r.cloudTotal
                else -> -1
            }
        }

    private fun maskKey(key: String): String =
        if (key.length <= 8) "****" else "${key.take(4)}****${key.takeLast(4)}"

    private fun buildReport(): String {
        val sb = StringBuilder()

        // ---------- 1. 应用信息 ----------
        sb.append("═══ 应用信息 ═══\n")
        sb.append("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        sb.append("包名: $packageName\n")
        sb.append("目标模式: 单词卡相机\n\n")

        // ---------- 2. 运行环境 ----------
        sb.append("═══ 运行环境 ═══\n")
        sb.append(ThumbCardApp.envSummary()).append("\n")
        // 容器环境识别
        val container = detectContainer()
        sb.append("兼容容器: $container\n\n")

        // ---------- 3. 能力可用性 ----------
        sb.append("═══ 能力检测 ═══\n")

        // 加密存储
        sb.append("凭据存储: ${if (CredentialStore.isEncrypted) "✅" else "⚠️"} ")
        sb.append(CredentialStore.storageDescription).append("\n")

        // 数据库
        val dbOk = runCatching { AppDatabase.get(this) != null }.getOrDefault(false)
        sb.append("本地数据库: ${if (dbOk) "✅ 可用" else "❌ 不可用"}")
        AppDatabase.lastInitError?.let { sb.append("\n    → $it") }
        sb.append("\n")
        if (!dbOk) {
            // 给出最常见原因的排查提示
            val err = AppDatabase.lastInitError ?: ""
            when {
                err.contains("AppDatabase_Impl") || err.contains("Cannot find implementation") ->
                    sb.append("    提示: Room 注解处理器未生效（检查 build.gradle 的 ksp/room-compiler）\n")
                err.contains("SQLite") || err.contains("unable to open") ->
                    sb.append("    提示: 文件系统受限，可能是容器环境（卓易通等）的存储隔离\n")
                else ->
                    sb.append("    提示: 本地库不可用，App 会自动降级为内存模式\n")
            }
        }

        // 仓库层
        val bindOk = runCatching { BindRepository(this).isStorageAvailable }.getOrDefault(false)
        val recOk = runCatching { RecordRepository(this).isStorageAvailable }.getOrDefault(false)
        sb.append("绑定仓库: ${if (bindOk) "✅" else "⚠️ 降级"}\n")
        sb.append("记录仓库: ${if (recOk) "✅" else "⚠️ 降级（仅内存）"}\n")

        // BLE
        val bleOk = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)
        sb.append("蓝牙 LE: ${if (bleOk) "✅ 支持" else "❌ 不支持"}\n")

        // 权限
        val perm = checkBlePermissions()
        sb.append("BLE 权限: $perm\n\n")

        // ---------- 3.5 网络时延与全球化配置 ----------
        // 产品要求「全球访问 2 秒内」。云端达标不等于用户达标，
        // 这里量的是 App 侧「发请求到解析完成」，可直接截图作为验收证据。
        sb.append("═══ 网络时延 ═══\n")
        val st = LatencyStore.listStats(this)
        if (st.isEmpty) {
            sb.append("记录接口: 暂无样本（刷新一次首页后复测）\n")
        } else {
            sb.append("记录接口(近${st.count}次): 平均 ${st.avgMs}ms / P50 ${st.p50Ms}ms / 最大 ${st.maxMs}ms")
            sb.append(if (st.p50Ms <= 2000) "  ✅ 达标 ≤2000ms" else "  ⚠️ 超过 2000ms")
            sb.append("\n")
        }
        val dl = DeviceLangStore.current(this)
        sb.append("设备语言: ").append(DeviceLangStore.displayNameOf(dl.itemLang))
        if (dl.pending) sb.append("（待下发）")
        sb.append("；界面语言: ").append(DeviceLangStore.displayNameOf(dl.uiLang))
        sb.append("；云端保留: ${dl.cloudDays} 天\n\n")

        // ---------- 4. 崩溃记录 ----------
        sb.append("═══ 上次崩溃 ═══\n")
        val crash = ThumbCardApp.readLastCrash()
        if (crash.isNullOrBlank()) {
            sb.append("无记录 ✅\n")
        } else {
            sb.append(crash.take(4000)).append("\n")
        }
        sb.append("\n")

        // ---------- 5. 启动期问题 ----------
        sb.append("═══ 启动期问题 ═══\n")
        val issues = ThumbCardApp.readStartupIssues()
        if (issues.isNullOrBlank()) {
            sb.append("无记录 ✅\n")
        } else {
            sb.append(issues.take(2000)).append("\n")
        }

        return sb.toString()
    }

    /** 粗略识别常见的 Android 兼容容器 */
    private fun detectContainer(): String {
        val fp = "${Build.FINGERPRINT} ${Build.BRAND} ${Build.MANUFACTURER} ${Build.DISPLAY}".lowercase()
        return when {
            fp.contains("harmony") || fp.contains("hongmeng") -> "HarmonyOS 检测到"
            fp.contains("droitong") || fp.contains("卓易通") -> "卓易通容器"
            fp.contains("isulad") -> "iSulad 容器（华为鸿蒙兼容层）"
            Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) -> "华为设备（可能运行兼容层）"
            else -> "未识别（原生 Android 环境）"
        }
    }

    private fun checkBlePermissions(): String {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val result = needed.map { p ->
            val ok = checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
            "${p.substringAfterLast('.')}=${if (ok) "已授予" else "未授予"}"
        }
        return result.joinToString(", ")
    }

    private fun copyReport() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.diag_clip_label), binding.tvDiagContent.text)
            )
        Toast.makeText(this, getString(R.string.diag_copied_toast), Toast.LENGTH_SHORT).show()
    }
}
