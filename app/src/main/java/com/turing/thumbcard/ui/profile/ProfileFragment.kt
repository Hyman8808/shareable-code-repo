package com.turing.thumbcard.ui.profile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.turing.thumbcard.BuildConfig
import com.turing.thumbcard.R
import com.turing.thumbcard.data.ai.AiPolicy
import com.turing.thumbcard.data.ai.AiQuotaStore
import com.turing.thumbcard.data.ai.AiTaskCenter
import com.turing.thumbcard.data.device.CurrentDeviceStore
import com.turing.thumbcard.data.device.DeviceLangStore
import com.turing.thumbcard.data.i18n.LanguagePrefs
import com.turing.thumbcard.data.i18n.NameLanguage
import com.turing.thumbcard.data.i18n.UiLanguage
import com.turing.thumbcard.data.image.GalleryRestore
import com.turing.thumbcard.data.repo.BindRepository
import com.turing.thumbcard.data.repo.RecordRepository
import com.turing.thumbcard.databinding.FragmentProfileBinding
import com.turing.thumbcard.ui.ai.AiPurchaseDialog
import com.turing.thumbcard.ui.auth.AccountStore
import com.turing.thumbcard.ui.auth.LoginActivity
import com.turing.thumbcard.ui.diag.DiagnoseActivity
import com.turing.thumbcard.ui.device.DeviceListActivity
import com.turing.thumbcard.ui.settings.LanguageActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 「我的」页：账号、额度入口、应用信息。
 *
 * 登录/注册依赖服务端，当前服务端未就绪 → 页面保留，点击进入 [LoginActivity]，
 * 其内部对三个渠道统一提示「开发中」，不做假登录（避免用户误以为已注册成功）。
 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private companion object {
        const val TAG = "Profile"
    }

    /** 找回进行中：防止连点重复扫描（一次扫描要读一堆 EXIF，重复跑会明显掉帧） */
    private var restoring = false

    /** 懒建，只有用到「找回」时才初始化（主线版本永远用不到） */
    private var repo: RecordRepository? = null

    /** 设备汇总（「我的相机」行状态）用；在 onCreateView 里初始化 */
    private lateinit var bindRepo: BindRepository

    /**
     * 相册读权限。
     *
     * 拿不到也要能走：分区存储下本应用自己写入的图有隐式读权限，
     * 拒绝授权只是读不到「别人转存进相册目录」的那批，不该把功能整个锁死。
     */
    private val restorePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRestore()
            } else {
                toast(getString(R.string.restore_need_permission))
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        bindRepo = BindRepository(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvVersion.text =
            getString(R.string.profile_version_fmt, BuildConfig.VERSION_NAME)

        binding.rowAccount.setOnClickListener {
            startActivity(Intent(requireContext(), LoginActivity::class.java))
        }
        binding.rowLanguage.setOnClickListener {
            startActivity(Intent(requireContext(), LanguageActivity::class.java))
        }
        // 设备管理唯一的常驻入口（底部「设备」Tab 已移除）
        binding.rowMyCamera.setOnClickListener {
            startActivity(Intent(requireContext(), DeviceListActivity::class.java))
        }
        binding.rowUiLang.setOnClickListener { pickUiLanguage() }
        // 设备语言：写入固件，决定设备提示语/开机字幕（固件需支持 0x12）
        binding.rowDeviceLang.setOnClickListener { pickDeviceLang() }
        binding.rowCloudStorage.setOnClickListener { showCloudStorage() }
        binding.rowQuota.setOnClickListener {
            // 额度入口：先弹购买。这里不再是一句「开发中」——
            // 用户点进来是想补充额度，弹一个「开发中」等于把需求堵在原地。
            AiPurchaseDialog.show(requireContext()) { count ->
                toast(getString(R.string.ai_buy_done, count))
                refreshQuota()
            }
        }
        binding.rowDiag.setOnClickListener {
            startActivity(Intent(requireContext(), DiagnoseActivity::class.java))
        }

        // 相册找回只出现在 AI 版：主线不生成 AI 图，也就没有「省额度」这回事，
        // 摆一行点了没用的入口只会让人以为主线少了个功能。
        if (BuildConfig.IS_AI_EDITION) {
            binding.rowRestoreGallery.setOnClickListener { onRestoreClicked() }
        } else {
            binding.rowRestoreGallery.visibility = View.GONE
            // 分隔线一起收：否则主线版「其他」分组顶部会多一条没有内容的横线
            binding.dividerRestore.visibility = View.GONE
        }
        binding.rowPrivacy.setOnClickListener {
            toast(getString(R.string.profile_privacy_toast))
        }
        binding.rowAbout.setOnClickListener {
            toast(getString(R.string.profile_about_toast))
        }

        refreshAccount()
        refreshCameraSummary()
        refreshLanguage()
        refreshDeviceLang()
        refreshCloudStorage()
        refreshUiLanguage()
        refreshQuota()
    }

    // ==================== 「我的相机」行 ====================

    /**
     * 「我的相机」行的右侧状态。
     *
     * 🔴 旧实现这一行**从来没人写过**，`tvCameraState` 一直是 XML 里的默认值
     *    「未配对」——于是无论绑了 1 台还是 5 台，用户看到的都是「未配对」，
     *    以为绑定失败了，实际后台早就在取数。
     *
     * 文案口径：
     *  - 0 台 → 「未配对」
     *  - 1 台 → 「1 台已绑定」
     *  - N 台 → 「N 台已绑定」
     * 多设备时把「当前是哪台」放进括号，否则用户不知道首页正在看哪一台。
     */
    private fun refreshCameraSummary() {
        viewLifecycleOwner.lifecycleScope.launch {
            val devices = runCatching { bindRepo.listBound() }.getOrNull().orEmpty()
            if (_binding == null) return@launch

            binding.tvCameraState.text = when {
                devices.isEmpty() -> getString(R.string.profile_camera_unbound)
                devices.size == 1 -> getString(R.string.profile_camera_one_bound)
                else -> getString(R.string.profile_camera_bound_fmt, devices.size)
            }

            // 多设备时把「当前是哪台」点出来：否则用户不知道首页正在看哪台相机。
            // 只在能放下时拼接（超过 8 个字符的名字交给省略号，状态数字必须完整可见）
            if (devices.size > 1) {
                val cur = CurrentDeviceStore.resolve(requireContext(), devices)
                val name = cur?.deviceName?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8 }
                if (name != null) {
                    binding.tvCameraState.text =
                        getString(R.string.profile_camera_bound_current_fmt, devices.size, name)
                }
            }
        }
    }

    // ==================== 设备语言 ====================

    /**
     * 设备语言：一次设置同时决定「物品语言」与「设备界面语言」。
     *
     * 为什么把两个语言绑在一起给用户选：产品要求「物品语言选哪个，设备界面就变哪个」，
     * 拆成两个开关只会让用户配错（界面日语、物品英语，还以为设备坏了）。
     * 协议上仍是两个 TLV，后续要支持「读日语、界面英语」只需在此加一步二次选择。
     *
     * ⚠️ 用 `setSingleChoiceItems`（自带滚动）而不是自定义布局：
     * 17 种语言塞进 `setMessage` + 自定义 View 的弹窗会被部分机型折叠，
     * 只剩说明文字、看不到选项（界面语言弹窗踩过这个坑）。
     */
    private fun pickDeviceLang() {
        val options = deviceLangOptions()
        val current = DeviceLangStore.current(requireContext()).itemLang
        val checked = options.indexOfFirst { it.first == current }.coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.device_lang_title)
            .setSingleChoiceItems(
                options.map { it.second }.toTypedArray(),
                checked
            ) { dialog, which ->
                dialog.dismiss()
                val (code, label) = options[which]
                DeviceLangStore.setItemLang(requireContext(), code)
                refreshDeviceLang()
                toast(getString(R.string.device_lang_saved_fmt, label))
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /**
     * 可选语言：英语置首（海外默认），其余按枚举顺序。
     *
     * `NONE`（仅英文模式）不出现在这里 —— 它是"不显示中文内容"的开关，不是一种语言，
     * 下发给设备会让固件找不到对应的提示语资源。
     *
     * 中文两项只写本地名（简体中文 / 繁體中文）：再拼「· 中文（简体）」是同义反复。
     */
    private fun deviceLangOptions(): List<Pair<String, String>> {
        val list = mutableListOf("en" to "English")
        NameLanguage.entries.forEach { lang ->
            if (lang.code == NameLanguage.NONE.code) return@forEach
            val label = if (lang.isChinese) lang.native else "${lang.native}  ${lang.label}"
            list += lang.code to label
        }
        return list
    }

    /**
     * 设备语言当前值。
     *
     * `pending` 要显示出来：设置后设备通常不在连接状态，下次配网才下发。
     * 不显示的话，用户改完回到设备发现没变，会认为设置无效。
     */
    private fun refreshDeviceLang() {
        val dl = DeviceLangStore.current(requireContext())
        val name = DeviceLangStore.displayNameOf(dl.itemLang)
        binding.tvDeviceLangValue.text = if (dl.pending) {
            getString(R.string.device_lang_pending_fmt, name)
        } else {
            name
        }
    }

    // ==================== 云端存储 ====================

    private fun refreshCloudStorage() {
        val days = DeviceLangStore.current(requireContext()).cloudDays
        binding.tvCloudStorageValue.text =
            if (days == DeviceLangStore.DEFAULT_CLOUD_DAYS) {
                getString(R.string.cloud_storage_default)
            } else {
                getString(R.string.cloud_storage_days_fmt, days)
            }
    }

    /**
     * 云端存储说明。
     *
     * 当前**只有默认 7 天**，订阅与付费依赖云端（账号、订单、容量计费）。
     * 因此这里不摆一个假的「立即开通」，而是把规则讲清楚：
     * 免费 7 天、超期记录不再保留、开通后按月/年延展。
     * 云端上线后，这个弹窗换成套餐列表即可，入口与文案不用改。
     */
    private fun showCloudStorage() {
        val days = DeviceLangStore.current(requireContext()).cloudDays
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.cloud_storage_title)
            .setMessage(getString(R.string.cloud_storage_body, days))
            .setPositiveButton(R.string.cloud_storage_plan_btn) { _, _ ->
                toast(getString(R.string.cloud_storage_soon))
            }
            .setNegativeButton(R.string.common_close, null)
            .show()
    }

    /**
     * 额度角标：显示「总剩余张数」而不是「免费/已购」两行。
     *
     * 用户只关心「我还能做几张」，拆成两行反而要他自己加。
     * 余额为 0 时切 warning 底 + warning 字，这是本页唯一需要被推一把的状态。
     */
    private fun refreshQuota() {
        // 不限额设备要读「当前绑定的是哪台」，是数据库查询 → 必须离开主线程。
        // 这里用 lifecycleScope 而不是 runBlocking：本页是 Tab 页，反复进出频繁，
        // 在主线程上等一次 DB 就会掉一帧。
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            val remaining = AiQuotaStore.remaining(ctx)
            // 白名单设备（自研机/演示机）不限额：角标写「不限」，
            // 而不是「剩 0 张」——后者会让人以为要付款才能演示。
            val unlimited = AiPolicy.currentUnlimited(ctx)
            val hasQuota = unlimited || remaining > 0

            binding.tvQuotaBadge.text = if (unlimited) {
                getString(R.string.ai_quota_unlimited_short)
            } else {
                getString(R.string.profile_quota_remaining, remaining)
            }
            binding.tvQuotaBadge.setBackgroundResource(
                if (hasQuota) R.drawable.bg_pill_soft else R.drawable.bg_hint_warning
            )
            binding.tvQuotaBadge.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (hasQuota) R.color.brand_primary else R.color.warning
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccount()
        // 设备可能在设备管理页被切换 / 解绑 / 新增 → 每次回到本页重算
        refreshCameraSummary()
        refreshLanguage()
        refreshDeviceLang()
        refreshCloudStorage()
        refreshUiLanguage()
        // 从 AI 页回来额度可能被扣了
        refreshQuota()
    }

    // ==================== 从相册找回已生成的 AI 图 ====================

    /**
     * 入口。
     *
     * 这个功能存在的唯一理由是**省钱**：一次生成 60–110 秒 + 扣 1 张额度，
     * 而重装 / 清数据 / 换机之后本地那份就没了，相册里那份还在。
     * 找回不调模型、不扣额度，只是把字节搬回来。
     */
    private fun onRestoreClicked() {
        if (restoring) return

        // 生成中不允许找回：两条路径会同时写同一张卡的 aiStyle，
        // 而且队列里还排着的 id 会与刚找回的图互相覆盖。
        if (AiTaskCenter.isRunning()) {
            toast(getString(R.string.restore_busy))
            return
        }

        val perm = galleryReadPermission()
        if (perm != null &&
            ContextCompat.checkSelfPermission(requireContext(), perm) != PackageManager.PERMISSION_GRANTED
        ) {
            restorePermLauncher.launch(perm)
            return
        }
        startRestore()
    }

    /**
     * 读相册该申请的权限。
     *
     * - API 33+：`READ_MEDIA_IMAGES`
     * - API 29–32：分区存储下本应用自己写入的图有隐式读权限，不申请
     * - API ≤ 28：`READ_EXTERNAL_STORAGE`
     */
    private fun galleryReadPermission(): String? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_IMAGES
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> Manifest.permission.READ_EXTERNAL_STORAGE
        else -> null
    }

    private fun startRestore() {
        if (restoring) return
        restoring = true

        val ctx = requireContext().applicationContext
        val progress = AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_restore_gallery)
            .setMessage(getString(R.string.restore_scanning))
            .setCancelable(false)
            .create()
        progress.show()

        viewLifecycleOwner.lifecycleScope.launch {
            var restored = 0
            var failed = 0
            try {
                val r = repo ?: RecordRepository(ctx).also { repo = it }
                val cards = r.loadAllCards()

                // 扫描与恢复都要读相册字节，全程在 IO 线程（GalleryRestore 内部切）
                val report = GalleryRestore.scan(ctx, cards)
                if (report.ready.isEmpty()) {
                    progress.dismiss()
                    showNothingFound(report)
                    return@launch
                }

                // 已经有 aiStyle 的卡不动它：找回只是把文件补回来，
                // 不该顺手把「用户当前选的是卡通」改成「高清」。
                val current = cards.associate { it.id to it.aiStyle }
                val claimed = mutableSetOf<Long>()

                report.ready.forEachIndexed { i, c ->
                    progress.setMessage(
                        getString(R.string.restore_progress_fmt, i + 1, report.ready.size)
                    )
                    if (GalleryRestore.restore(ctx, c)) {
                        restored++
                        if (current[c.cardId] == null && claimed.add(c.cardId)) {
                            r.setAiStyle(c.cardId, c.style.code)
                        }
                        // 与「生成完成」走同一个事件：列表/详情/预览都会立刻换封面
                        AiTaskCenter.notifyCardUpdated(c.cardId)
                    } else {
                        failed++
                    }
                }
                progress.dismiss()
                showRestored(report, restored, failed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "restore failed", e)
                progress.dismiss()
                toast(getString(R.string.restore_error))
            } finally {
                runCatching { progress.dismiss() }
                restoring = false
            }
        }
    }

    private fun showNothingFound(report: GalleryRestore.Report) {
        // 三种「没找回」的性质完全不同，说的必须是不同的话：
        //  ① 相册里根本没有我们的图 → 教他怎么产生图；
        //  ② 有图但一张都认不出卡片 → 八成是重装后还没同步记录，让他先下拉刷新（
        //     这是重装后第一波用户最常撞上的坑，报「找不到可恢复的」会让人以为是图坏了）；
        //  ③ 认得出但都不合格 → 如实列原因。
        val unknownAll = report.scanned > 0 &&
            report.skipCount(GalleryRestore.Reason.UNKNOWN_CARD) == report.scanned
        val body = when {
            report.scanned == 0 -> getString(R.string.restore_none_empty)
            unknownAll -> getString(R.string.restore_need_sync, report.scanned)
            else -> getString(R.string.restore_none_found_fmt, report.scanned, reasonSummary(report))
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_restore_gallery)
            .setMessage(body)
            .setPositiveButton(R.string.common_ok, null)
            .show()
    }

    private fun showRestored(report: GalleryRestore.Report, restored: Int, failed: Int) {
        val parts = mutableListOf<String>()
        if (report.guessed > 0) {
            parts += getString(R.string.restore_done_guessed_fmt, report.guessed)
        }
        if (failed > 0) {
            parts += getString(R.string.restore_failed_fmt, failed)
        }
        if (report.skipped.isNotEmpty()) {
            parts += getString(
                R.string.restore_done_skip_fmt, report.skipped.size, reasonSummary(report)
            )
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.restore_done_title_fmt, restored))
            .setMessage(parts.joinToString("\n\n"))
            .setPositiveButton(R.string.common_ok, null)
            .show()
    }

    /** 把「跳过原因」拼成一句人话，例如「已在本地 3, 找不到卡片 1」 */
    private fun reasonSummary(report: GalleryRestore.Report): String {
        val parts = mutableListOf<String>()
        fun add(reason: GalleryRestore.Reason, resId: Int) {
            val n = report.skipCount(reason)
            if (n > 0) parts += getString(resId, n)
        }
        add(GalleryRestore.Reason.ALREADY_LOCAL, R.string.restore_reason_local)
        add(GalleryRestore.Reason.UNKNOWN_CARD, R.string.restore_reason_unknown)
        add(GalleryRestore.Reason.DUPLICATE_NAME, R.string.restore_reason_dup)
        add(GalleryRestore.Reason.TOO_SMALL, R.string.restore_reason_small)
        add(GalleryRestore.Reason.UNREADABLE, R.string.restore_reason_unreadable)
        return parts.joinToString(", ")
    }

    /** 界面语言：只影响 App 文案，与「物品名称语言」互不影响 */
    private fun refreshUiLanguage() {
        binding.tvUiLangValue.text = UiLanguage.displayName(requireContext())
    }

    /**
     * 选择界面语言。
     *
     * ⚠️ 不支持「跟随系统」：产品要求默认中文，多一个选项就多一类
     * 「为什么我的界面突然变英文了」的客诉。要哪一国文字就明确选哪一项。
     *
     * 用自定义视图而不是 setSingleChoiceItems + setMessage：
     * 真机反馈「只看到说明文字、看不到选项」——说明与单选列表同屏时，
     * 部分机型把列表压进折叠区。自定义视图保证三个选项必然完整可见。
     */
    private fun pickUiLanguage() {
        val current = UiLanguage.storedTag(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_ui_language, null)
        view.findViewById<View>(R.id.tvUiCheckZh).visibility =
            if (current == UiLanguage.ZH) View.VISIBLE else View.INVISIBLE
        view.findViewById<View>(R.id.tvUiCheckTw).visibility =
            if (current == UiLanguage.TW) View.VISIBLE else View.INVISIBLE
        view.findViewById<View>(R.id.tvUiCheckEn).visibility =
            if (current == UiLanguage.EN) View.VISIBLE else View.INVISIBLE

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.ui_lang_title)
            .setView(view)
            .setNegativeButton(R.string.common_cancel, null)
            .show()

        view.findViewById<View>(R.id.rowUiZh).setOnClickListener {
            applyUiLanguage(dialog, UiLanguage.ZH)
        }
        view.findViewById<View>(R.id.rowUiTw).setOnClickListener {
            applyUiLanguage(dialog, UiLanguage.TW)
        }
        view.findViewById<View>(R.id.rowUiEn).setOnClickListener {
            applyUiLanguage(dialog, UiLanguage.EN)
        }
    }

    private fun applyUiLanguage(dialog: AlertDialog, target: String) {
        dialog.dismiss()
        // 同语言不重建界面（避免无意义的闪一下）
        if (UiLanguage.storedTag(requireContext()) == target) return
        UiLanguage.set(requireContext(), target)
        toast(getString(R.string.ui_lang_changed_fmt, UiLanguage.displayNameOf(target)))
    }

    /**
     * 名称语言：**只显示本地写法**（日本語 / Español / 繁體中文）。
     *
     * 不再拼「本地名 + 中文名」两条：值列只有 150dp，
     * 「Español  西班牙语」在 12sp 下会被 ellipsize 切成「Español  西班牙…」，
     * 越长的语言越惨（Bahasa Indonesia 那种直接变半截）。
     * 本地写法本身就够认，中文对照放在选择页的副标题里。
     */
    private fun refreshLanguage() {
        val lang = LanguagePrefs.current(requireContext())
        binding.tvLanguageValue.text = lang.native
    }

    private fun refreshAccount() {
        val account = runCatching { AccountStore.current(requireContext()) }.getOrNull()
        if (account == null) {
            binding.tvAccountName.setText(R.string.profile_login)
            binding.tvAccountMeta.setText(R.string.profile_account_meta)
            binding.ivAvatar.setImageResource(android.R.drawable.ic_menu_myplaces)
        } else {
            binding.tvAccountName.text = account.displayName
            binding.tvAccountMeta.text = account.channel
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
