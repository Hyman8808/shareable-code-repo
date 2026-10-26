package com.turing.thumbcard

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.turing.thumbcard.util.CredentialStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用入口。
 *
 * ⚠️ 兼容性目标：在**华为鸿蒙 NEXT + 卓易通**等 Android 兼容容器中，
 * Keystore、数据库、文件系统等能力可能受限或不完整。
 * 因此所有初始化步骤都必须**容错**，任何一步失败都不能导致 App 启动失败。
 */
class ThumbCardApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 先建立诊断目录（后续所有记录都依赖它）
        initDir(this)

        // App 界面语言（默认中文）：必须在任何 Activity 创建之前应用，
        // 否则首屏会用系统语言显示，切换后闪一下才变。
        safe("UiLanguage.applyStored") {
            com.turing.thumbcard.data.i18n.UiLanguage.applyStored(this)
        }

        // 安装全局未捕获异常处理器：记录日志并在下次启动时提示，避免"无声闪退"
        installCrashHandler()

        // 初始化存储（内部已三级降级，不会抛异常）
        safe("CredentialStore.init") { CredentialStore.init(this) }

        // 词典结果本地缓存（查过的词不再连云端；失败只是不启用缓存）
        safe("DictCache.init") { com.turing.thumbcard.data.dict.DictCache.init(this) }

        // 物品名称多语言译名缓存（接口未接入也不会失败，只是恒回退中文原词）
        safe("NameStore.init") { com.turing.thumbcard.data.i18n.NameStore.init(this) }

        // 预先触发数据库初始化，若不可用则记录（不阻塞启动）
        safe("AppDatabase.get") {
            com.turing.thumbcard.data.db.AppDatabase.get(this)
        }
    }

    /** 执行一个初始化步骤，捕获任何 Throwable 并记录，绝不让启动失败 */
    private inline fun safe(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "init step failed: $tag (${t.javaClass.simpleName})")
            recordStartupIssue("$tag: ${t.javaClass.simpleName} - ${t.message}")
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                Log.e(TAG, "UNCAUGHT on ${thread.name}:\n${sw}")
                recordCrash(sw.toString())
            } catch (_: Throwable) {
                // 崩溃处理器自身绝不能抛异常
            }
            // 交回系统默认处理（弹"应用已停止"），便于定位
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "ThumbCardApp"
        private const val CRASH_FILE = "last_crash.txt"
        private const val ISSUE_FILE = "startup_issues.txt"

        /** 记录启动期问题（供诊断页展示） */
        fun recordStartupIssue(text: String) {
            try {
                val f = File(APP_DIR, ISSUE_FILE)
                val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
                f.appendText("[$ts] $text\n")
            } catch (_: Throwable) {
            }
        }

        private fun recordCrash(stack: String) {
            try {
                val f = File(APP_DIR, CRASH_FILE)
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                f.writeText("[$ts]\n$stack")
            } catch (_: Throwable) {
            }
        }

        private lateinit var APP_DIR: File

        /** 由 Application 在首行调用，建立诊断目录 */
        fun initDir(context: Context) {
            APP_DIR = File(context.filesDir, "diag").apply { mkdirs() }
        }

        /** 读取上次崩溃信息；无则返回 null */
        fun readLastCrash(): String? {
            return try {
                val f = File(APP_DIR, CRASH_FILE)
                if (f.exists()) f.readText() else null
            } catch (_: Throwable) {
                null
            }
        }

        /** 读取启动期问题记录 */
        fun readStartupIssues(): String? {
            return try {
                val f = File(APP_DIR, ISSUE_FILE)
                if (f.exists()) f.readText() else null
            } catch (_: Throwable) {
                null
            }
        }

        fun clearDiag() {
            try {
                File(APP_DIR, CRASH_FILE).delete()
                File(APP_DIR, ISSUE_FILE).delete()
            } catch (_: Throwable) {
            }
        }

        /** 环境信息摘要（诊断用） */
        fun envSummary(): String = buildString {
            append("厂商: ${Build.MANUFACTURER}\n")
            append("型号: ${Build.MODEL}\n")
            append("品牌: ${Build.BRAND}\n")
            append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("ROM: ${Build.DISPLAY}\n")
            append("存储模式: ${CredentialStore.storageDescription}")
        }
    }
}
