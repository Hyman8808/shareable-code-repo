package com.turing.thumbcard.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.turing.thumbcard.R
import com.turing.thumbcard.data.i18n.LanguagePrefs
import com.turing.thumbcard.data.i18n.NameLanguage
import com.turing.thumbcard.data.i18n.NameResolver
import com.turing.thumbcard.data.i18n.PendingNameTranslator
import com.turing.thumbcard.data.i18n.UiLanguage
import com.turing.thumbcard.databinding.ActivityLanguageBinding
import com.turing.thumbcard.databinding.ItemLanguageBinding

/**
 * 物品名称语言选择页。
 *
 * 共 10 项：无（仅英文）+ 中文（简体，原始识别结果）+ 中文（繁体，本机转换）+ 日韩西法德俄 + 阿拉伯语。
 *
 * 选择即生效（写入 SP 并通知首页/单词卡页重绘），不强制关闭页面，
 * 方便用户来回切换看效果。
 *
 * 生效方式：语言随同步请求的 `lang` 参数带给云端，云端按该语言下发 `name_localized`。
 * 因此**切语言后要等下一次同步**，历史卡才会显示新语言；已经同步到新语言的卡直接显示。
 * 拿不到译名的词回退中文原词，**不会伪造译名**（顶部说明条讲的就是这件事）。
 */
class LanguageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLanguageBinding
    private var current: NameLanguage = NameLanguage.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLanguageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        current = LanguagePrefs.current(this)
        binding.btnBack.setOnClickListener { finish() }

        val pending = NameResolver.translator is PendingNameTranslator
        binding.tvServiceNotice.text = if (pending) {
            getString(R.string.lang_pending_toast)
        } else {
            getString(R.string.lang_ready_toast)
        }
        binding.tvServiceNotice.visibility = if (pending) View.VISIBLE else View.GONE

        renderList()
    }

    private fun renderList() {
        binding.llLangList.removeAllViews()
        binding.tvCurrentValue.text = current.native

        val langs = NameLanguage.values().toList()
        // 副标题的中文名只在中文界面出现；英文/阿语界面给英文名，否则阿语用户看到的是「阿拉伯语」四个方块字
        val uiTag = UiLanguage.storedTag(this)

        langs.forEachIndexed { index, lang ->
            val row = ItemLanguageBinding.inflate(layoutInflater, binding.llLangList, false)
            row.tvNative.text = lang.native
            // 副标题只留「展示名 · 代码」。不再挂「翻译服务开发中」——
            // 语言是按次同步下发的，逐行挂一个长期标签既不准也吵；
            // 全局状态由顶部说明条统一讲一次。
            row.tvLabel.text = buildString {
                append(lang.labelFor(uiTag))
                append("  ·  ")
                append(lang.code)
            }
            // 繁体的中文名与本地名重复，副标题只留代码，避免「繁體中文 / 中文（繁体）」绕圈
            if (lang.isHant) row.tvLabel.text = lang.code

            val selected = lang == current
            row.tvCheck.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            if (selected) {
                row.root.setBackgroundResource(R.drawable.bg_lang_selected)
            } else {
                row.root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            row.root.setOnClickListener { pick(lang) }
            binding.llLangList.addView(row.root)

            if (index < langs.lastIndex) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    ).apply { marginStart = dp(18) }
                    setBackgroundColor(getColor(R.color.divider))
                }
                binding.llLangList.addView(divider)
            }
        }
    }

    private fun pick(lang: NameLanguage) {
        if (lang == current) return
        val changed = LanguagePrefs.set(this, lang)
        current = lang
        renderList()

        if (!changed) return

        // 选了「无（仅英文）」→ 界面语言自动切到 English。
        // 理由：这个选项本身就是「一切中文退场」的模式，界面还留着中文等于自相矛盾；
        // 而且选它的人基本是海外使用场景，要求「只有英文」就是要求整个 App 只有英文。
        // 只做单向：之后用户手动把界面切回中文不会被再掰回去（那是他的明确选择）。
        if (lang.isNone && !UiLanguage.isEnglish(this)) {
            // setApplicationLocales 会重建本页：重建后 onCreate 按新语言渲染，
            // 这句之后的 Toast 用的还是旧语言资源，所以直接交给「整屏变英文」当反馈。
            UiLanguage.set(this, UiLanguage.EN)
            return
        }

        val msg = when {
            lang.isHant -> getString(R.string.lang_switched_hant)
            lang.isSource -> getString(R.string.lang_switched_src)
            NameResolver.translator is PendingNameTranslator ->
                getString(R.string.lang_switched_pending_fmt, lang.labelFor(uiTag()))
            else -> getString(R.string.lang_switched_fmt, lang.labelFor(uiTag()))
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun uiTag(): String = UiLanguage.storedTag(this)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
