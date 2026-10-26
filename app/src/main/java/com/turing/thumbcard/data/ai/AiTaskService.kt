package com.turing.thumbcard.data.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.turing.thumbcard.R
import com.turing.thumbcard.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AI 出图的后台执行器（前台服务）。
 *
 * 为什么必须是前台服务：
 *  - 一张 2K 图要 60–90 秒，批量 8 张就是十几分钟。普通 `lifecycleScope` 的协程
 *    跟着 Activity 走，用户一按返回就取消 —— 钱已经花了，图没了；
 *  - Android 8 起后台服务会被限流/回收，只有前台服务（带常驻通知）
 *    能稳定跑完十几分钟的活，用户也能在通知栏看到进度、随时停。
 *
 * 四种进入方式：
 *  1. 新任务：`ACTION_START`（[AiTaskCenter.enqueue] 发出）
 *  2. 用户按通知里的「停止」：`ACTION_CANCEL`（只置标志，当前这张跑完再停）
 *  3. 被系统杀掉后重启（`START_STICKY` + 空 Intent）：从 [AiTaskStore] 接着跑
 *  4. 用户手动点「继续修复」：同样走 `ACTION_START`
 *
 * ⚠️ 华为/鸿蒙上仍有被杀的可能，所以「被杀后能续跑」不能省：
 *    队列落盘在 [AiTaskStore]，重启时剩下的 pending 就是要补的活。
 */
class AiTaskService : Service() {

    companion object {
        private const val TAG = "AiTaskService"

        const val ACTION_START = "com.turing.thumbcard.ai.action.START"
        const val ACTION_CANCEL = "com.turing.thumbcard.ai.action.CANCEL"

        private const val CHANNEL_ID = "ai_task"
        private const val NOTIF_PROGRESS = 1001
        private const val NOTIF_DONE = 1002

        /** 唤醒锁兜底 60 分钟：批量再长也不该超过这个数，超了说明卡死，别一直耗电 */
        private const val WAKE_LOCK_MS = 60L * 60 * 1000

        /**
         * 启动服务。
         *
         * @return false 表示系统拒绝了前台服务启动（Android 12+ 后台启动限制）。
         *         调用方要把状态回滚成「未开始」，否则界面会一直显示「正在生成」。
         */
        fun start(ctx: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(
                ctx.applicationContext,
                Intent(ctx.applicationContext, AiTaskService::class.java).setAction(ACTION_START)
            )
            true
        }.getOrElse {
            Log.w(TAG, "startForegroundService rejected", it)
            false
        }

        /** 通知栏里的「停止」按钮 */
        fun cancel(ctx: Context) {
            runCatching {
                ctx.applicationContext.startService(
                    Intent(ctx.applicationContext, AiTaskService::class.java).setAction(ACTION_CANCEL)
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** 前台通知是否已经挂上（onStartCommand 里 null intent 的分支要用） */
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        AiTaskCenter.markServiceAlive(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                AiTaskCenter.requestCancel()
                // 已经在跑：把标题改成「正在停止」，别让用户以为没点上
                if (job != null) notifyProgress(AiTaskCenter.state.value)
                return START_STICKY
            }

            else -> {
                if (job != null) return START_STICKY

                // 空 Intent = 系统重启（START_STICKY）。没有剩余任务就别占着前台
                if (intent == null && !AiTaskStore.hasPending(this)) return stopNow()

                // 没有待办（例如用户已经取消）→ 立刻退出，不留一条「0/0」的通知
                if (!AiTaskStore.hasPending(this)) return stopNow()

                startForegroundWith(AiTaskCenter.state.value)
                acquireWakeLock()
                launchRunner()
                return START_STICKY
            }
        }
    }

    private fun launchRunner() {
        job = scope.launch {
            // runQueue 的返回值就是收尾统计（它在复位状态之前算好），
            // 比再订阅一次事件可靠 —— 事件是给页面用的，服务不该跟页面抢
            var end: AiTaskCenter.State? = null
            try {
                end = AiTaskCenter.runQueue(this@AiTaskService) { s -> notifyProgress(s) }
            } catch (t: Throwable) {
                Log.e(TAG, "queue crashed", t)
            } finally {
                job = null
                finishUp(end ?: AiTaskCenter.state.value)
            }
        }
    }

    private fun finishUp(state: AiTaskCenter.State) {
        showDoneNotification(state)
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun stopNow(): Int {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AiTaskCenter.markServiceAlive(false)
        // 没有正常走完就被销毁 → 复位状态，否则界面永远停在「正在生成」，
        // 用户点不动生成、也等不到「继续修复」的弹窗
        AiTaskCenter.resetIfRunning()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ==================== 通知 ====================

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ai_notif_channel),
            // DEFAULT 而不是 LOW：跑完的那条要能被看见（LOW 只在下拉里，不出横幅）
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.ai_notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun startForegroundWith(s: AiTaskCenter.State) {
        val n = buildProgress(s)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_PROGRESS, n)
        }
        foregroundStarted = true
    }

    private fun notifyProgress(s: AiTaskCenter.State) {
        if (!foregroundStarted) return
        val n = buildProgress(s)
        // 用 startForeground 更新而不是 notify：部分 ROM 上「只 notify 不重新 startForeground」
        // 会让进度条停在第一帧
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_PROGRESS, n)
        }
    }

    private fun buildProgress(s: AiTaskCenter.State): Notification {
        val title = when {
            s.canceling -> getString(R.string.ai_notif_stopping)
            s.total > 0 -> getString(R.string.ai_notif_progress_title, s.done, s.total)
            else -> getString(R.string.ai_notif_preparing)
        }
        val text = s.currentWord?.takeIf { it.isNotBlank() }
            ?.let { getString(R.string.ai_notif_progress_text, it) }
            ?: getString(R.string.ai_notif_progress_idle)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ai)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(s.total.coerceAtLeast(1), s.done.coerceIn(0, s.total.coerceAtLeast(1)), false)
            .setOngoing(true)
            // 只在文案真的变了才响 —— 每张图都叮一下会让人想直接关通知
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.ai_notif_cancel), cancelIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * 跑完的通知。
     *
     * 单独一个 id，不带 `setAutoCancel(false)` → 用户点开就消失，
     * 不留一条「已完成」长期占着通知栏。
     */
    private fun showDoneNotification(s: AiTaskCenter.State) {
        // 一张都没做（例如全部跳过、或队列为空）就不打扰了：页面里已经说清楚了
        if (s.ok <= 0 && s.failed <= 0) return

        val text = when {
            s.quotaOut -> getString(R.string.ai_notif_done_quota, s.ok)
            s.canceled -> getString(R.string.ai_notif_done_cancel, s.ok)
            s.failed > 0 || s.skipped > 0 ->
                getString(R.string.ai_notif_done_mixed, s.ok, s.skipped, s.failed)
            else -> getString(R.string.ai_notif_done, s.ok)
        }

        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ai)
            .setContentTitle(getString(R.string.ai_notif_done_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent())
            .build()

        runCatching {
            // 通知权限被拒（Android 13+）时这里什么都不做，但任务本身已经完成
            NotificationManagerCompat.from(this).notify(NOTIF_DONE, n)
        }.onFailure { Log.w(TAG, "notify done failed", it) }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SELECT_TAB, R.id.nav_cards)
        return PendingIntent.getActivity(
            this, 10, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelIntent(): PendingIntent {
        val intent = Intent(this, AiTaskService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(
            this, 11, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ==================== 唤醒锁 ====================

    /**
     * 生成期间亮不亮屏无所谓，但 CPU 不能睡 —— 锁屏后 Doze 会掐掉网络，
     * 一次 90 秒的请求很容易卡到超时。跑完立刻释放，不留后台耗电的把柄。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "thumbcard:ai-task").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MS)
            }
        }.onFailure { Log.w(TAG, "acquire wakelock failed", it) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }
}
