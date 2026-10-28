package com.turing.thumbcard.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.turing.thumbcard.R
import com.turing.thumbcard.ThumbCardApp
import com.turing.thumbcard.data.ai.AiTaskCenter
import com.turing.thumbcard.data.ai.AiTaskStore
import com.turing.thumbcard.data.device.DeviceLangSync
import com.turing.thumbcard.data.repo.BindRepository
import com.turing.thumbcard.databinding.ActivityMainBinding
import com.turing.thumbcard.ui.card.CardsFragment
import com.turing.thumbcard.ui.diag.DiagnoseActivity
import com.turing.thumbcard.ui.home.HomeFragment
import com.turing.thumbcard.ui.profile.ProfileFragment

/**
 * 主容器：底部 3 个 Tab（今天 / 收藏 / 我的）。
 *
 * 首页语义按「是否已绑定设备」自适应：
 *  - 未绑定 → 引导页（添加设备）
 *  - 已绑定 → 直接呈现单词卡内容（单词卡即主页）
 *
 * 设备管理不再是常驻 Tab，收敛到「我的 › 我的相机」（[com.turing.thumbcard.ui.device.DeviceListActivity]）。
 *
 * ⚠️ 兼容性：仓库初始化容错，任何异常不闪退。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /**
     * ⚠️ 必须优先复用 FragmentManager 里已恢复的实例。
     *
     * 切换界面语言会让 AppCompat 重建 Activity，此时系统已按 tag 恢复了
     * 四个 Fragment；如果这里再 new 一个，`add` 就会产生**同 tag 的重复实例**
     * （表现为切 Tab 时叠了两层界面）。所以按 tag 查找，找不到才新建。
     */
    private val homeFragment by lazy { findOrCreate("HomeFragment") { HomeFragment() } }
    private val cardsFragment by lazy { findOrCreate("CardsFragment") { CardsFragment() } }
    private val profileFragment by lazy { findOrCreate("ProfileFragment") { ProfileFragment() } }

    private var current: Fragment? = null

    private fun <T : Fragment> findOrCreate(tag: String, factory: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return (supportFragmentManager.findFragmentByTag(tag) as? T) ?: factory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 上次没上报成功的语言变更（断网 / 当时还没配网）在这里补一次。
        // 没有 pending 时内部直接返回，不发请求。
        DeviceLangSync.flushPendingAsync(this)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { switchTo(homeFragment); true }
                R.id.nav_cards -> { switchTo(cardsFragment); true }
                R.id.nav_profile -> { switchTo(profileFragment); true }
                else -> false
            }
        }

        // 重建（切语言/深色模式）后要回到原来的 Tab，而不是回首页；
        // 首次创建时若有外部指定 Tab（如配网成功后回首页），按指定的来。
        //
        // ⚠️ 选中的 id 必须是**当前菜单里真实存在**的 id：
        // 否则 setOnItemSelectedListener 会返回 false，界面停在空白页。
        // 典型场景是用「4 Tab 版本」升级到「3 Tab 版本」——恢复出来的
        // nav_devices 已经不在菜单里，必须回落首页。
        val restored = savedInstanceState?.getInt(KEY_TAB)
            ?: intent?.getIntExtra(EXTRA_SELECT_TAB, 0)?.takeIf { it != 0 }
            ?: R.id.nav_home
        val safeTab = if (binding.bottomNav.menu.findItem(restored) == null) {
            R.id.nav_home
        } else {
            restored
        }
        binding.bottomNav.selectedItemId = safeTab

        showStartupIssueIfAny()
    }

    /**
     * 复用已有实例时（配网成功 → 回到首页），Activity 不会重建，
     * 需要在这里把 Tab 切过去，否则用户停在配网前那个页面。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getIntExtra(EXTRA_SELECT_TAB, 0).takeIf { it != 0 }?.let { selectTab(it) }
    }

    /** 供 Fragment 内部跳转 Tab 使用 */
    fun selectTab(itemId: Int) {
        // 同样要防「传进来一个菜单里没有的 id」，否则会停在空白页
        if (binding.bottomNav.menu.findItem(itemId) == null) {
            binding.bottomNav.selectedItemId = R.id.nav_home
            return
        }
        binding.bottomNav.selectedItemId = itemId
    }

    /**
     * 切换 Tab。
     *
     * ⚠️ 隐藏的是**所有**其它 Fragment，而不是只藏上一个：
     * Activity 重建后 `current` 为 null，只藏「上一个」会让两个页面叠在一起。
     */
    private fun switchTo(target: Fragment) {
        if (current === target && target.isAdded) return
        val tx = supportFragmentManager.beginTransaction()
        supportFragmentManager.fragments.forEach { f ->
            if (f !== target && !f.isHidden) tx.hide(f)
        }
        if (!target.isAdded) {
            tx.add(R.id.fragmentContainer, target, target.javaClass.simpleName)
        } else {
            tx.show(target)
        }
        tx.commitAllowingStateLoss()
        current = target
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, binding.bottomNav.selectedItemId)
    }

    /**
     * 每次回到前台都检查一次「有没有没跑完的 AI 批量任务」。
     *
     * 三种情况会走到这里：
     *  1. 进程被 ROM 杀掉（前台服务也没了）→ 队列还在 SP 里 → 问用户要不要继续；
     *  2. 用户上次选了「稍后再说」→ 下次进 App 再问一次（同一个任务只问一次，
     *     用 `startedAt` 做去重，否则每次切回来都弹窗）；
     *  3. 服务自己 `START_STICKY` 重启了 → `isRunning()` 为真，不问，直接接着跑。
     */
    override fun onStart() {
        super.onStart()
        maybeResumeAiTask()
    }

    private fun maybeResumeAiTask() {
        if (AiTaskCenter.isRunning()) return
        val run = runCatching { AiTaskStore.load(this) }.getOrNull() ?: return
        if (run.pending.isEmpty()) return
        // 已经为这次任务问过一次就别再弹（切 Tab / 回前台都会触发 onStart）
        if (lastAskedRunAt == run.startedAt) return
        lastAskedRunAt = run.startedAt

        AlertDialog.Builder(this)
            .setTitle(R.string.ai_resume_title)
            .setMessage(getString(R.string.ai_resume_msg, run.pending.size))
            .setPositiveButton(R.string.ai_resume_continue) { _, _ ->
                AiTaskCenter.resume(this)
            }
            .setNeutralButton(R.string.ai_resume_discard) { _, _ ->
                AiTaskCenter.discard(this)
                Toast.makeText(this, R.string.ai_resume_discarded, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.ai_resume_later, null)
            .show()
    }

    private fun openDiagnose() {
        startActivity(Intent(this, DiagnoseActivity::class.java))
    }

    /** 若上次运行存在崩溃/启动异常，弹窗提示并提供诊断入口 */
    private fun showStartupIssueIfAny() {
        val crash = runCatching { ThumbCardApp.readLastCrash() }.getOrNull()
        val issues = runCatching { ThumbCardApp.readStartupIssues() }.getOrNull()
        if (crash.isNullOrBlank() && issues.isNullOrBlank()) return

        val detail = buildString {
            if (!crash.isNullOrBlank()) {
                append(getString(R.string.main_crash_prefix)).append("\n")
                append(crash.lineSequence().take(6).joinToString("\n"))
            } else {
                append(getString(R.string.main_startup_prefix)).append("\n")
                append(issues?.lineSequence()?.take(6)?.joinToString("\n"))
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.main_compat_title)
            .setMessage(getString(R.string.main_compat_msg, detail))
            .setPositiveButton(R.string.main_view_diag) { _, _ -> openDiagnose() }
            .setNegativeButton(R.string.main_got_it) { _, _ ->
                runCatching { ThumbCardApp.clearDiag() }
            }
            .show()
    }

    companion object {
        // R.id.* 在 app 模块中不是编译期常量，故用 val 而非 const val
        val TAB_HOME = R.id.nav_home
        val TAB_CARDS = R.id.nav_cards
        val TAB_PROFILE = R.id.nav_profile

        /** 记住当前 Tab（切界面语言会重建 Activity） */
        private const val KEY_TAB = "main_selected_tab"

        /** 启动/复用本页时指定要显示的 Tab（配网成功后回首页用） */
        const val EXTRA_SELECT_TAB = "extra_select_tab"

        /**
         * 已经为哪一次任务弹过「继续修复」。
         *
         * 放 companion（而非成员）是为了配置变更重建 Activity 后不再问第二遍；
         * 用任务的 `startedAt` 而不是布尔值，这样「新的一次任务」仍会正常提示。
         */
        private var lastAskedRunAt: Long = 0L
    }
}
