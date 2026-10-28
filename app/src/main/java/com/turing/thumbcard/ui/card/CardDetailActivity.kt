package com.turing.thumbcard.ui.card

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import com.turing.thumbcard.R
import com.turing.thumbcard.data.ai.AiStyle
import com.turing.thumbcard.data.db.WordCardEntity
import com.turing.thumbcard.data.image.ImageStore
import com.turing.thumbcard.data.dict.DictClient
import com.turing.thumbcard.data.dict.DictEntry
import com.turing.thumbcard.data.dict.DictResult
import com.turing.thumbcard.data.dict.PhraseBreaker
import com.turing.thumbcard.data.dict.PhraseWord
import com.turing.thumbcard.data.i18n.HantConverter
import com.turing.thumbcard.data.i18n.LanguagePrefs
import com.turing.thumbcard.data.i18n.NameLanguage
import com.turing.thumbcard.data.i18n.NameResolver
import com.turing.thumbcard.data.i18n.UiLanguage
import com.turing.thumbcard.data.i18n.inHant
import com.turing.thumbcard.data.repo.RecordRepository
import com.turing.thumbcard.databinding.ActivityCardDetailBinding
import com.turing.thumbcard.ui.ai.AiBeautifyActivity
import com.turing.thumbcard.util.AudioPlayer
import com.turing.thumbcard.util.loadAny
import com.turing.thumbcard.util.loadImage
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 单词卡详情页。
 *
 * 内容分两层：
 *  1. **识别结果**（协议原样返回）：中文名 / 英文名 / 拼音 / 音标 / 释义 / 发音
 *  2. **词典补充**（调图灵在线词典）：词性释义、例句、近义词反义词、词形变化、短语
 *
 * 词典接口为 WebSocket + AES 加密，见 DictClient。
 * 词典只是「增强」，失败不影响主内容展示。
 */
class CardDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CARD_ID = "extra_card_id"

        /**
         * 左右滑动时可切换的卡片 id 顺序（来源列表的**当前顺序**）。
         *
         * 为什么直接把 id 数组传进来，而不是「传来源、进来再自己查一遍」：
         *  - 收藏页可能带着搜索关键词，重新查一遍库就把过滤条件丢了；
         *  - 首页的顺序是「今天 → 更早」，和 id 倒序并不等价；
         *  - 「全部记录」页可能选了「全部设备」，跨设备的顺序只有那一页知道。
         * 传 id 数组 = 让「上一张/下一张」与用户点进来时看到的列表**逐项一致**。
         */
        const val EXTRA_IDS = "extra_ids"

        /** 来源，仅用于文案（第 x / y 张 的提示语气）与埋点，不参与取数 */
        const val EXTRA_SOURCE = "extra_source"
        const val SOURCE_HOME = "home"
        const val SOURCE_ALL = "all"
        const val SOURCE_FAVORITES = "favorites"

        private const val TAG = "CardDetail"

        /** 触发切卡的最小横向位移（dp）：太小会把「上下滚动时的手抖」判成切卡 */
        private const val SWIPE_MIN_DP = 72

        /** 触发切卡的最小横向速度（px/s）：慢慢横拖是在看内容，不该翻页 */
        private const val SWIPE_MIN_VELOCITY = 320f

        /**
         * 只起语法作用、不参与「直译」拼句的词。
         *
         * 🔴 冠词也在这里：**词砖上照常出现**（数量必须与英文名的词数一致，否则用户会以为拆漏了），
         *    但「a potted plant」不该被拼成「一 · 盆栽的 · 植物」。
         */
        private val FUNCTION_WORDS =
            setOf("a", "an", "the", "of", "for", "with", "in", "on", "and", "to", "from", "by")

        /** 词砖占位 View 的 tag：行内等高时必须跳过它（它的高度没有意义） */
        private const val TAG_PHRASE_SPACER = "phrase_spacer"

        /**
         * 词砖高度的有效门槛（dp），对应 `item_phrase_word` 的 minHeight 104dp。
         * 行内最大高度低于它就说明这一帧没量准 → 放弃等高，宁可参差也不压砖。
         */
        private const val MIN_TILE_HEIGHT_DP = 96
    }

    /**
     * 从大图预览回来时，同步到用户最后停留的那张。
     *
     * 不同步的后果很具体：用户在预览里翻了 5 张、决定就看这张，按返回，
     * 看到的却是第 1 张的详情 —— 他会以为自己退错了，再点一次。
     *
     * 只认 [ImagePreviewActivity.RESULT_CARD_ID]：没切过卡时预览页不会 setResult，
     * 这里就什么都不做，不会无端重建页面。
     */
    private val previewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val id = r.data?.getLongExtra(ImagePreviewActivity.RESULT_CARD_ID, -1L) ?: -1L
        val target = ids.indexOf(id)
        if (r.resultCode == Activity.RESULT_OK && target >= 0 && target != index) {
            index = target
            cardId = id
            // 与 step() 共用同一套清理：不清理就会出现「新卡标题 + 旧卡例句」
            resetForNewCard()
            loadCard()
        }
    }

    private lateinit var binding: ActivityCardDetailBinding
    private lateinit var repo: RecordRepository
    private lateinit var audioPlayer: AudioPlayer

    private var cardId: Long = -1
    private var chineseUrl: String? = null
    private var englishUrl: String? = null

    /** 可切换的卡片序列（来源列表顺序）与当前位置 */
    private var ids: LongArray = longArrayOf()
    private var index: Int = 0

    /** 来源（home / all / favorites），只影响提示文案 */
    private var source: String = SOURCE_ALL

    /** 识物接口返回的拼音，词典查不到时用它兜底 */
    private var cardPinyin: String? = null

    /** 词典给的美音 / 英音 mp3，点音标旁的小喇叭试听 */
    private var audioUs: String? = null
    private var audioUk: String? = null

    /** 上次渲染用的名称语言，用于 onResume 时判断是否需要重绘 */
    private var lastLang: NameLanguage? = null

    /** 「无（仅英文）」模式：详情页隐藏一切中文内容（中文名/拼音/汉语词典/中文释义/直译条） */
    private var enOnly: Boolean = false

    /**
     * 繁体中文模式：中文内容照常展示，但**字形换繁体**。
     *
     * 与 [enOnly] 的区别很关键：繁体用户要拼音、要汉语词典、要直译条，
     * 只是不想读简体字。所以这里不是「少显示什么」，而是「换个写法显示」。
     */
    private var hant: Boolean = false

    /** 当前卡片，短语拆解区拼「直译 → 整体」时要用它的中/英文名 */
    private var currentCard: WordCardEntity? = null

    /** 短语拆解结果，点词砖弹详情时用 */
    private var phraseWords: List<PhraseWord> = emptyList()

    /** 中文发音按钮的文案（外语模式下叫「中文原文发音」） */
    private var chineseBtnLabel: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCardDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cardId = intent.getLongExtra(EXTRA_CARD_ID, -1L)
        if (cardId <= 0) {
            toast(getString(R.string.detail_missing_card_info)); finish(); return
        }

        source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_ALL
        ids = intent.getLongArrayExtra(EXTRA_IDS)
            ?.takeIf { it.isNotEmpty() }
            ?: longArrayOf(cardId)
        // 传进来的列表里没有这张卡（列表刚刷新过、卡被删）：退化成「只有这一张」，
        // 至少保证当前这张能正常看，而不是把 index 定在 -1 上后面全部越界
        index = ids.indexOf(cardId).let { if (it >= 0) it else 0 }
        if (index >= ids.size) ids = longArrayOf(cardId)
        index = ids.indexOf(cardId).coerceAtLeast(0)

        repo = RecordRepository(this)
        audioPlayer = AudioPlayer(this)
        audioPlayer.onPlayingChanged = { playing -> updatePlayButtons(playing) }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayChinese.setOnClickListener { togglePlay(chineseUrl) }
        binding.btnPlayEnglish.setOnClickListener { togglePlay(englishUrl) }
        binding.btnSpeakUs.setOnClickListener { togglePlay(audioUs) }
        binding.btnSpeakUk.setOnClickListener { togglePlay(audioUk) }
        binding.btnDeleteCard.setOnClickListener { confirmDelete() }
        binding.btnRetryDict.setOnClickListener { loadCard() }
        binding.btnRetryCnDict.setOnClickListener { loadCard() }
        binding.btnFavoriteCard.setOnClickListener { toggleFavorite() }

        // 主图高度按图片真实比例自适应（adjustViewBounds），但要封顶：
        // 竖拍图（3:4 / 9:16）不封顶会把首屏全占掉，下面的词头与音标要滚半屏才能看到。
        // 封顶后仍然 fitCenter，图不会被裁，只是两侧留出卡底色。
        binding.ivDetailImage.maxHeight = (resources.displayMetrics.heightPixels * 0.55f).toInt()

        // 点图片看大图（可缩放 / 可存相册 / 可左右滑上下张）
        binding.ivDetailImage.setOnClickListener {
            previewLauncher.launch(
                Intent(this, ImagePreviewActivity::class.java)
                    .putExtra(ImagePreviewActivity.EXTRA_CARD_ID, cardId)
                    // 把详情页这一套顺序带过去：预览里滑动的范围与详情页完全一致
                    .putExtra(ImagePreviewActivity.EXTRA_IDS, ids)
            )
        }
        // AI 美化 / 卡通化
        binding.btnAiBeautify.setOnClickListener {
            startActivity(
                Intent(this, AiBeautifyActivity::class.java)
                    .putExtra(AiBeautifyActivity.EXTRA_CARD_ID, cardId)
            )
        }

        loadCard()
        renderPosition()
    }

    /** 收藏状态：进页面读一次（首页/列表点了收藏，这里要一致） */
    private fun renderFavorite(favorite: Boolean) {
        binding.btnFavoriteCard.setImageResource(
            if (favorite) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
        )
        binding.btnFavoriteCard.setBackgroundResource(
            if (favorite) R.drawable.bg_heart_fav else R.drawable.bg_heart_unfav
        )
        // 矢量心形只有基色，最终色由 tint 定：未收藏=tertiary 描边心，已收藏=深墨实心。
        // 顶栏也是暖白纸底，这里必须和列表行（WordCardAdapter / 全部照片页）用同一对
        // 配色，否则三处收藏态的墨色轻重不一致，看起来像三种不同的东西。
        binding.btnFavoriteCard.imageTintList = ContextCompat.getColorStateList(
            this, if (favorite) R.color.text_on_accent else R.color.text_tertiary
        )
    }

    private fun toggleFavorite() {
        lifecycleScope.launch {
            val next = repo.toggleFavorite(cardId)
            renderFavorite(next)
            toast(getString(if (next) R.string.home_fav_added else R.string.home_fav_removed))
        }
    }

    override fun onDestroy() {
        audioPlayer.release()
        super.onDestroy()
    }

    private fun loadCard() {
        lifecycleScope.launch {
            val card = repo.findCard(cardId)
            if (card == null) {
                toast(getString(R.string.detail_card_missing)); finish(); return@launch
            }
            chineseUrl = card.ttsChineseUrl
            englishUrl = card.ttsEnglishUrl

            renderCardImage(card)
            renderAiEntry(card)
            binding.tvDetailTime.text =
                getString(R.string.detail_time_fmt, card.createTime ?: "—")
            renderFavorite(card.favorite)

            val lang = LanguagePrefs.current(this@CardDetailActivity)
            lastLang = lang
            enOnly = lang.isNone
            hant = lang.isHant
            applyNames(card, lang)

            // 拼音先用协议里的值占位（词典查到后覆盖），音标与释义一律等词典结果
            // 拼音是中文注音，选外语时不显示（否则「日文名 + 中文拼音」自相矛盾）
            cardPinyin = card.pinyin?.takeIf { it.isNotBlank() }
            applyBasics(
                pinyin = cardPinyin.takeIf { lang.isChinese },
                phoneticUs = null, phoneticUk = null, desc = null
            )

            binding.btnPlayChinese.isEnabled = !chineseUrl.isNullOrBlank()
            binding.btnPlayEnglish.isEnabled = !englishUrl.isNullOrBlank()

            // 多词短语先拆成单个词（tea tray → tea / tray），让用户看懂整个表达怎么拼出来
            currentCard = card
            loadPhraseBreakdown(card, lang)

            loadDictionaries(card.nameEnglish, card.nameChinese, lang)
        }
    }

    // ==================== 左右滑动切卡 ====================

    /**
     * 轻扫切卡：**左滑下一张、右滑上一张**（与翻书一致）。
     *
     * 三个判据缺一不可，否则会误伤正常阅读：
     *  1. 横向位移 ≥ [SWIPE_MIN_DP]（72dp）—— 过滤手指抖动；
     *  2. 横向位移 ≥ 纵向位移的 1.5 倍 —— 斜着上下滚页面时不该翻页；
     *  3. 横向速度 ≥ [SWIPE_MIN_VELOCITY] —— 慢慢横拖是在看内容，不是要翻页。
     *
     * 边界不给对话框，只在屏幕上「弹一下」+ 一句轻提示：
     * 这是浏览型操作，弹窗会把「继续看」打断。
     */
    private val swipeDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val from = e1 ?: return false
                val dx = e2.x - from.x
                val dy = e2.y - from.y
                if (abs(dx) < dp(SWIPE_MIN_DP)) return false
                if (abs(dx) < abs(dy) * 1.5f) return false
                if (abs(velocityX) < SWIPE_MIN_VELOCITY) return false
                if (dx < 0) step(+1) else step(-1)
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 交给 detector 只是「听」，不影响事件继续下发给 ScrollView / 按钮
        swipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 切到相对位置的另一张。
     *
     * @param delta +1 = 下一张（左滑），-1 = 上一张（右滑）
     */
    private fun step(delta: Int) {
        val target = index + delta
        if (target !in ids.indices) {
            toast(
                getString(
                    if (delta > 0) R.string.detail_swipe_last else R.string.detail_swipe_first
                )
            )
            // 到头了：往反方向回弹 12dp，给一个「顶住了」的物理反馈
            val w = resources.displayMetrics.widthPixels * 0.03f * delta
            binding.root.animate().translationX(-w).setDuration(90).setInterpolator(
                DecelerateInterpolator()
            ).withEndAction {
                binding.root.animate().translationX(0f).setDuration(120).start()
            }.start()
            return
        }

        val prevId = cardId
        index = target
        cardId = ids[target]
        Log.d(TAG, "swipe $delta: $prevId -> $cardId (${index + 1}/${ids.size}, source=$source)")

        resetForNewCard()
        loadCard()
        slideIn(delta)
    }

    /**
     * 换卡前清掉上一张的所有临时状态。
     *
     * 不清的后果是「串卡」：上一张的词典卡、短语拆解砖、试听地址全部还在，
     * 网络稍慢一点，用户会看到新卡的标题配着旧卡的例句。
     */
    private fun resetForNewCard() {
        audioPlayer.stop()
        currentCard = null
        phraseWords = emptyList()
        chineseUrl = null
        englishUrl = null
        audioUs = null
        audioUk = null
        cardPinyin = null

        binding.cardPhraseSection.visibility = View.GONE
        binding.grpPhraseWords.removeAllViews()
        binding.grpPhraseLoading.visibility = View.GONE
        binding.grpPhraseBody.visibility = View.GONE
        binding.tvPhraseFormula.visibility = View.GONE
        showDictLoading()
        binding.cardDictSection.visibility = View.GONE
        binding.cardCnSection.visibility = View.GONE
        binding.tvLangNotice.visibility = View.GONE
        binding.tvDetailChineseOrigin.visibility = View.GONE
        binding.tvDetailLangTag.visibility = View.GONE
        binding.tvDetailEnglish.text = ""
        binding.tvDetailChinese.text = ""

        // 新卡必须从顶部开始读：停在上一张的滚动位置，会让人以为「这张怎么没标题」
        binding.root.scrollTo(0, 0)
        renderPosition()
    }

    /** 第几张 / 共几张：只在能切的时候显示（只有一张时写「1/1」是噪声） */
    private fun renderPosition() {
        if (ids.size <= 1) {
            binding.tvDetailPos.visibility = View.GONE
            return
        }
        binding.tvDetailPos.visibility = View.VISIBLE
        binding.tvDetailPos.text = getString(R.string.detail_pos_fmt, index + 1, ids.size)
    }

    /** 切卡的横向滑入：从 delta 方向偏 18% 屏宽滑进，180ms 落地 */
    private fun slideIn(delta: Int) {
        val w = resources.displayMetrics.widthPixels
        binding.root.translationX = w * 0.18f * delta
        binding.root.alpha = 0.35f
        binding.root.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    override fun onResume() {
        super.onResume()
        val lang = LanguagePrefs.current(this)
        // 在详情页停留期间语言被改（例如从通知/多窗口）→ 整体重绘
        if (lastLang != null && lastLang != lang) {
            loadCard()
            return
        }
        // 从预览页 / AI 美化页回来：图可能被换掉了（生成成功会自动切到 AI 图），
        // 这里重读一次卡片记录，只刷新图片与入口文案，不整页重绘（避免词典重新联网）。
        if (currentCard != null) {
            lifecycleScope.launch {
                repo.findCard(cardId)?.let {
                    currentCard = it
                    renderCardImage(it)
                    renderAiEntry(it)
                }
            }
        }
    }

    /**
     * 渲染主卡图片：**全尺寸优先**（AI 全尺寸 → 原图），不再用列表封面那张裁切图。
     *
     * 图框高度跟着图片真实比例走（[binding.ivDetailImage] 的 adjustViewBounds），
     * 所以 2:1 / 16:9 / 4:3 / 1:1 的图都能完整显示，用户点进来看到的就是生成的那张全貌；
     * 要看细节再点一下进预览页（那边可双指缩放）。
     */
    private fun renderCardImage(card: WordCardEntity) {
        val style = AiStyle.fromCode(card.aiStyle)
        val file = ImageStore.detailFile(this, card.id, card.aiStyle)
        binding.ivDetailImage.loadAny(file ?: card.imageUrl)

        Log.d(TAG, "detail image: card=${card.id} style=${card.aiStyle} file=${file?.name ?: "URL"}")
        binding.tvImageBadge.text = if (style != null) {
            getString(style.badgeRes) + " · " + getString(R.string.detail_view_original)
        } else {
            getString(R.string.detail_view_original)
        }
    }

    /** 入口文案随状态变：已经生成过的不要再写「美化这张图」，那会让用户以为没生效 */
    private fun renderAiEntry(card: WordCardEntity) {
        binding.btnAiBeautify.setText(
            if (card.aiStyle != null) R.string.detail_ai_entry_done else R.string.detail_ai_entry
        )
    }

    /**
     * 应用「物品名称语言」。
     *
     * 三条展示规则：
     *  1. 主名称 = 当前语言名称；副名称 = 英文名（固定）
     *  2. 主名称不是中文时，下面补一行「中文原文」，让用户知道拍的是什么
     *  3. **没有真译名时必须有提示**（服务未接入 / 该词无译名），
     *     否则用户会把中文当成翻译结果，判定功能失效
     */
    private fun applyNames(card: WordCardEntity, lang: NameLanguage) {
        val d = NameResolver.resolve(this, card.id, card.nameChinese, card.nameEnglish, lang)

        // 新层级：英文是主角行（大字号 serif），中文降为释义行。
        // 「无（仅英文）」时 primary 本身就是英文，必须写进英文行；
        // 否则 34sp 的主角行会空着，中文反倒占了大字号位。
        if (d.secondary == null) {
            binding.tvDetailEnglish.text = d.primary
            // 词头本身是中文（记录里没有英文名）→ 换中文字阶：
            // serif 在 CJK 上会回退到系统字体，字重不可控，30sp sans-medium 更稳。
            val headwordStyle = if (containsHan(d.primary)) {
                R.style.TextAppearance_ThumbCard_HeadwordXlCjk
            } else {
                R.style.TextAppearance_ThumbCard_HeadwordXl
            }
            TextViewCompat.setTextAppearance(binding.tvDetailEnglish, headwordStyle)
            binding.tvDetailEnglish.visibility = View.VISIBLE
            binding.tvDetailChinese.visibility = View.GONE
        } else {
            binding.tvDetailEnglish.text = d.secondary
            binding.tvDetailEnglish.visibility = View.VISIBLE
            binding.tvDetailChinese.text = d.primary
            binding.tvDetailChinese.visibility = View.VISIBLE
        }

        if (lang.isSource || lang.isNone) {
            binding.tvDetailLangTag.visibility = View.GONE
        } else {
            // 中文名只在中文界面显示；英文/阿语界面用英文名，否则阿语用户看到的是中文方块字
            binding.tvDetailLangTag.text =
                "${lang.native}   ${lang.labelFor(UiLanguage.storedTag(this))}"
            binding.tvDetailLangTag.visibility = View.VISIBLE
        }

        if (d.showSource) {
            binding.tvDetailChineseOrigin.text =
                getString(R.string.detail_cn_origin_fmt, d.source ?: "")
            binding.tvDetailChineseOrigin.visibility = View.VISIBLE
        } else {
            binding.tvDetailChineseOrigin.visibility = View.GONE
        }

        if (!lang.isChinese && !d.translated) {
            val langName = lang.labelFor(UiLanguage.storedTag(this))
            binding.tvLangNotice.text = if (d.pending) {
                getString(R.string.detail_translating_fmt, langName)
            } else {
                getString(R.string.detail_no_translation_fmt, langName)
            }
            binding.tvLangNotice.visibility = View.VISIBLE
        } else {
            binding.tvLangNotice.visibility = View.GONE
        }

        // 外语模式下中文发音按钮点明是「中文原词」的发音，避免误解；
        // 「无（仅英文）」模式下中文发音按钮整个退场——页面上不该再有中文发音
        binding.btnPlayChinese.visibility = if (lang.isNone) View.GONE else View.VISIBLE
        // 繁体也是中文：按钮叫「中文发音」，不能再加「原文」二字
        chineseBtnLabel =
            if (lang.isChinese) getString(R.string.card_play_chinese)
            else getString(R.string.detail_play_cn_origin)
        updatePlayButtons(audioPlayer.isPlaying())
    }

    /** 名称里是否含汉字（含扩展 A 区与兼容区），用于决定词头用西文衬线还是中文字阶 */
    private fun containsHan(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return text.any { it.code in 0x3400..0x4DBF || it.code in 0x4E00..0x9FFF || it.code in 0xF900..0xFAFF }
    }

    // ==================== 词典 ====================

    /**
     * 同时查两个词典：
     *  - **英文词典**（en_word）：音标、词性释义、例句、近反义词、词形变化、搭配
     *  - **汉语词典**（cn_word / cn_char / cn_idiom）：拼音、汉字词释义、组词、部首笔画
     *
     * 为何要两个：识物接口返回的 pinyin / description 常常为空或不规范，
     * 用汉语词典补齐「拼音 + 释义」；音标则以英文词典为准（识物返回的音标格式不统一）。
     *
     * 两个查询互相独立，任一失败不影响另一个。
     */
    private fun loadDictionaries(english: String?, chinese: String?, lang: NameLanguage) {
        // 整串查（"tea tray" 原样送），由服务端判定是 en_phrase 还是 en_word。
        // ❗ 旧实现用 substringBefore(" ") 只查首词，导致 "tea tray" 只能看到 tea，
        //    整个短语的表达被丢掉；逐词部分改由短语拆解区承担。
        val enKey = normalizeEnglishPhrase(english)
        // 汉语词典查的是中文原词；选了外语还展示中文拼音/释义会与主名称对不上
        // → 仅中文模式查汉语词典（英文保留，它是固定第二名称）
        val cnKey = if (lang.isChinese) {
            chinese?.trim()?.takeIf { it.isNotBlank() && it.length <= 12 }
        } else null

        // 两个词都没有 → 两张卡都不出现
        if (enKey == null && cnKey == null) {
            binding.cardDictSection.visibility = View.GONE
            binding.cardCnSection.visibility = View.GONE
            return
        }
        showDictLoading()

        lifecycleScope.launch {
            // ---- 英文词典 ----
            val enRes = enKey?.let { DictClient.query(it, listOf("en_word", "en_phrase")) }
            // ---- 汉语词典 ----
            val cnRes = cnKey?.let { DictClient.query(it, listOf("cn_word", "cn_char", "cn_idiom")) }

            // dict=others 表示**该词未收录**，只回一条机器翻译，不算词典释义 → 视为查不到
            var enEntry = (enRes as? DictResult.Found)?.entry?.takeIf { it.dict != "others" }
            var cnEntry = (cnRes as? DictResult.Found)?.entry?.takeIf { it.dict != "others" }

            // 繁体模式：词条的**中文内容**本地转繁体（释义/例句中文/近反义词/组词/部首）。
            // 音标与英文一个字节都不动——用户要的是同样的内容、繁体的写法。
            if (lang.isHant) {
                enEntry = enEntry?.inHant()
                cnEntry = cnEntry?.inHant()
            }

            // 有词条却拿不到拼音（复合词常见）→ 逐字取拼音，**只取拼音，不拼释义**
            val pinyinFallback = if (cnKey != null && cnEntry?.pinyin.isNullOrBlank()) {
                DictClient.pinyinByCharacters(cnKey)
            } else null

            renderEnglishDict(enEntry, enRes is DictResult.Failed)
            renderChineseDict(cnEntry, pinyinFallback, cnRes is DictResult.Failed)

            // 回填顶部：拼音取词典结果，音标取英文词典（美/英各一行，带试听喇叭），释义取汉语词典第一条
            applyBasics(
                pinyin = if (lang.isChinese) {
                    cnEntry?.pinyin?.takeIf { it.isNotBlank() } ?: pinyinFallback ?: cardPinyin
                } else null,
                // 英美同音也两行都显示：学习场景里「两种口音一样」本身就是要传达的信息
                phoneticUs = enEntry?.phoneticUsDisplay,
                phoneticUk = enEntry?.phoneticUkDisplay,
                // 释义是中文的（n. 茶壶）→「无（仅英文）」模式下不给
                desc = if (lang.isNone) null else
                    cnEntry?.meanings?.firstOrNull()?.meaning
                        ?: enEntry?.meanings?.firstOrNull()?.meaning,
                usAudio = enEntry?.audioUs,
                ukAudio = enEntry?.audioUk
            )
        }
    }

    /** 两张词典卡先隐藏（没内容就不该闪一下空白卡），内容回来再按需显示 */
    private fun showDictLoading() {
        binding.cardDictSection.visibility = View.GONE
        binding.cardCnSection.visibility = View.GONE
        binding.grpDictLoading.visibility = View.GONE
        binding.grpDictError.visibility = View.GONE
        binding.grpDictContent.visibility = View.GONE
        binding.grpCnLoading.visibility = View.GONE
        binding.grpCnError.visibility = View.GONE
        binding.grpCnContent.visibility = View.GONE
    }


    /**
     * 逐字兜底已移到 [DictClient.pinyinByCharacters]：**只取拼音、不拼释义**。
     * 拼接出来的释义（把每个字的解释堆在一起）没有可读性，产品上明确不要。
     */

    /**
     * 归一化英文名，**保留完整短语**。
     *
     * 只做三件事：去冠词、去首尾标点、剔掉含非法字符的值。
     * 不像旧版那样截到第一个词——「tea tray」必须整串送进词典，
     * 否则用户永远学不到「茶盘」在英文里的完整说法。
     */
    private fun normalizeEnglishPhrase(raw: String?): String? {
        val t = raw?.trim() ?: return null
        if (t.isBlank()) return null
        val cleaned = t.replace(Regex("^(a|an|the)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
            .trim(',', '.', ';', ':', '!', '?', '"', '\'', '(', ')')
        if (cleaned.isEmpty() || cleaned.length > 40) return null
        // 允许空格分隔的多个词（以及连字符、撇号），其它字符一律视为脏数据
        if (!cleaned.matches(Regex("[A-Za-z][A-Za-z\\-' ]*"))) return null
        return cleaned
    }

    // ==================== 短语拆解 ====================

    /**
     * 把多词短语拆成单个词，各查一次英文词典。
     *
     * 只在**两个词以上**时出现；单词（apple）由上面的主卡和词典卡负责，不必重复。
     *
     * 🔴 铁律：**拆出几个词，就必须画几块砖**。
     *    查词典只是「给砖补内容」，绝不能决定「砖存不存在」。旧实现直接渲染
     *    `lookup()` 的返回值，一旦它比拆词结果短（并发上限、单词语义去重、异常吞掉），
     *    界面就会「写着 3 个词，只有 2 块砖」，而缺的那块恰恰常是用户最想看的实词。
     */
    private fun loadPhraseBreakdown(card: WordCardEntity, lang: NameLanguage) {
        val words = PhraseBreaker.split(card.nameEnglish)
        if (words.size < 2) {
            binding.cardPhraseSection.visibility = View.GONE
            return
        }

        binding.cardPhraseSection.visibility = View.VISIBLE
        // 先按拆词数写一次（查词期间就有数），查完再用**实际出砖数**覆盖 —— 两者必然相等
        binding.tvPhraseCount.text = getString(R.string.phrase_count_fmt, words.size)
        binding.grpPhraseLoading.visibility = View.VISIBLE
        binding.grpPhraseBody.visibility = View.GONE
        binding.tvPhraseFormula.visibility = View.GONE
        Log.d(TAG, "phrase split: \"${card.nameEnglish}\" -> $words (n=${words.size})")

        lifecycleScope.launch {
            val looked = runCatching { PhraseBreaker.lookup(words) }.getOrNull().orEmpty()
            if (isFinishing || isDestroyed) return@launch

            // 以拆词列表为准回填：能按序号对上就用查词结果，对不上就用「暂未收录」占位。
            // 这样即使词典整体挂掉，三块砖也一块不少地摆出来。
            val tiles = words.mapIndexed { i, w ->
                looked.getOrNull(i)?.takeIf { it.text.equals(w, ignoreCase = true) }
                    ?: PhraseWord(i + 1, w)
            }
            if (tiles.size != words.size) {
                Log.w(TAG, "phrase tile count mismatch: words=$words tiles=${tiles.map { it.text }}")
            }
            phraseWords = tiles
            val drawn = renderPhraseWords(tiles)
            binding.tvPhraseCount.text = getString(R.string.phrase_count_fmt, drawn)
            Log.d(TAG, "phrase rendered: n=${tiles.size} drawn=$drawn -> ${tiles.map { it.text }}")
            renderPhraseFormula(card, lang)
            binding.grpPhraseLoading.visibility = View.GONE
            binding.grpPhraseBody.visibility = View.VISIBLE
        }
    }

    /**
     * 词砖：每行两块，宽度均分。
     *
     * 奇数个词时右侧补一个透明占位，避免最后一块被拉满整行（拉满会显得像重点）。
     *
     * @return 实际加进去的砖数（供调用方写徽章、也供日志核对）
     *
     * 🔴 2026-10 修「奇数个词丢最后一块」：
     *    旧实现给砖设 `lp.height = MATCH_PARENT`，并在 `onLayoutChange` 里把同一行的高度
     *    统一成「行内最大 measuredHeight」。占位 View 的初始高度是 **1px**，一旦某一帧
     *    砖还没量出高度（例如 `grpPhraseBody` 刚由 GONE 转 VISIBLE 的同一帧），
     *    `h = max(砖, 1) = 1`，于是 listener 把砖的 `lp.height` 改成 1 —— 砖被**永久钉成 1px**，
     *    下一帧再量还是 1，`max` 还是 1，自我维持，页面上就只剩前一行的砖。
     *    这就是「3 个词只显示 2 块砖」的真正原因（只有奇数行才带占位）。
     *    现在：高度交给 wrap_content，行内对齐改用 `minimumHeight`（**只抬不压**），
     *    并且低于砖的最小高度就整行放弃对齐 —— 宁可参差一点，也绝不让砖消失。
     */
    private fun renderPhraseWords(words: List<PhraseWord>): Int {
        val box = binding.grpPhraseWords
        box.removeAllViews()
        val gap = dp(10)
        var tiles = 0

        words.chunked(2).forEachIndexed { rowIndex, row ->
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            row.forEachIndexed { i, word ->
                val tile = layoutInflater.inflate(R.layout.item_phrase_word, rowView, false)
                bindPhraseTile(tile, word)
                val lp = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                if (i > 0) lp.marginStart = gap
                rowView.addView(tile, lp)
                tiles++
            }
            if (row.size == 1) {
                // 占位只用来「撑住列宽」，高度给 0 且打 tag —— 对齐时明确跳过它，
                // 绝不参与 max() 计算（旧 bug 就是被它的 1px 带偏的）
                rowView.addView(
                    View(this).apply { tag = TAG_PHRASE_SPACER },
                    LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = gap }
                )
            }
            box.addView(
                rowView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (rowIndex > 0) topMargin = gap }
            )
            equalizeRow(rowView)
        }

        // 自检（双保险）：等布局稳定后逐块量一遍，任何一块砖矮于最小高度就强行抬高并重排。
        // 旧 bug 就是砖被压成 1px 却没人体检，从「看起来少一块」变成「永远少一块」。
        box.postDelayed({ auditPhraseTiles() }, 320)
        return tiles
    }

    /**
     * 逐块量砖高，矮于最小高度的强行抬到 104dp 并重排，同时打 error 日志。
     *
     * 正常情况下一块砖都不可能触发（`item_phrase_word` 自带 `minHeight=104dp`）；
     * 一旦触发就说明布局链上又出现了「压缩砖」的写法，日志里能直接看到是哪一行第几块。
     */
    private fun auditPhraseTiles() {
        val box = binding.grpPhraseWords
        if (!box.isAttachedToWindow) return
        var crushed = 0
        for (r in 0 until box.childCount) {
            val row = box.getChildAt(r) as? LinearLayout ?: continue
            for (i in 0 until row.childCount) {
                val c = row.getChildAt(i)
                if (c.tag == TAG_PHRASE_SPACER) continue
                if (c.height < dp(MIN_TILE_HEIGHT_DP)) {
                    crushed++
                    Log.e(TAG, "phrase tile crushed: row=$r idx=$i h=${c.height}px -> force 104dp")
                    c.minimumHeight = dp(104)
                }
            }
        }
        if (crushed > 0) box.requestLayout()
    }

    /**
     * 行内等高（只抬不压）。
     *
     * 用 `minimumHeight` 而不是改 `layoutParams.height`：最小高度只在内容不足时生效，
     * 一旦测量异常（0/极小）也不会把砖压缩到看不见。并且设了「砖的最小高度」作为门槛，
     * 量不准就整行跳过 —— 参差可以接受，砖消失不行。
     */
    private fun equalizeRow(row: LinearLayout) {
        row.doOnLayout {
            var h = 0
            for (i in 0 until row.childCount) {
                val c = row.getChildAt(i)
                if (c.tag == TAG_PHRASE_SPACER) continue
                h = maxOf(h, c.measuredHeight)
            }
            if (h < dp(MIN_TILE_HEIGHT_DP)) {
                Log.w(TAG, "phrase row skip equalize: measured max=$h < ${dp(MIN_TILE_HEIGHT_DP)}")
                return@doOnLayout
            }
            for (i in 0 until row.childCount) {
                val c = row.getChildAt(i)
                if (c.tag == TAG_PHRASE_SPACER) continue
                if (c.measuredHeight < h) c.minimumHeight = h
            }
        }
    }

    /**
     * 繁体模式下把中文文本转繁体；其它模式原样返回。
     *
     * 只给**中文文本**用（释义、例句、直译、名称）。英文名、拼音、音标永远不要过它——
     * 转换器会把个别字母之外的字符原样返回，但拼音声调符号等一旦被误转就会毁掉注音。
     */
    private fun zht(text: String?): String? = if (hant) HantConverter.convert(text) else text

    private fun bindPhraseTile(tile: View, word: PhraseWord) {
        tile.findViewById<TextView>(R.id.tvWordOrder).text = word.order.toString()
        tile.findViewById<TextView>(R.id.tvWordText).text = word.text

        // 音标：美 / 英 各一行，各带自己的喇叭。
        // 学习发音时这两条是重点——只给一条（还不标是哪国音）等于把信息藏了一半。
        // 缺哪条就整行不出现，**不拿另一条顶替**：把英音标成「美」比不显示更误导。
        bindPhoneticRow(
            tile.findViewById(R.id.rowWordUs),
            tile.findViewById(R.id.tvWordPhoneticUs),
            tile.findViewById(R.id.btnWordAudioUs),
            word.phoneticUs, word.audioUs, word.text, R.string.a11y_play_word_us_fmt
        )
        bindPhoneticRow(
            tile.findViewById(R.id.rowWordUk),
            tile.findViewById(R.id.tvWordPhoneticUk),
            tile.findViewById(R.id.btnWordAudioUk),
            word.phoneticUk, word.audioUk, word.text, R.string.a11y_play_word_uk_fmt
        )
        tile.findViewById<View>(R.id.grpWordPhonetic).visibility =
            if (word.hasPhonetic) View.VISIBLE else View.GONE

        // 查不到也要把词显示出来：用户至少知道这个短语由哪几个词拼成。
        // 释义是中文的（n. 托盘）→「无（仅英文）」模式下整行隐藏，词砖只留单词与音标。
        val meaning = tile.findViewById<TextView>(R.id.tvWordMeaning)
        if (enOnly) {
            meaning.visibility = View.GONE
        } else if (word.found) {
            meaning.visibility = View.VISIBLE
            meaning.text = buildString {
                word.pos?.let { append(it).append(". ") }
                append(zht(word.meaning).orEmpty())
            }.trim()
            meaning.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            meaning.visibility = View.VISIBLE
            meaning.text = getString(R.string.phrase_word_missing)
            meaning.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        }

        tile.setOnClickListener { showWordDialog(word) }
    }

    /**
     * 绑定一行音标（美 或 英）。
     *
     * @param phonetic 该口音的音标；null 表示词典没给这一种 → 整行隐藏
     * @param audio    该口音的 mp3；没有时喇叭置灰，不用另一种口音顶替
     */
    private fun bindPhoneticRow(
        row: View,
        text: TextView,
        speaker: ImageButton,
        phonetic: String?,
        audio: String?,
        word: String,
        a11yFmt: Int
    ) {
        if (phonetic.isNullOrBlank()) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        text.text = phonetic
        speaker.contentDescription = getString(a11yFmt, word)
        speaker.alpha = if (audio.isNullOrBlank()) 0.25f else 1f
        speaker.isEnabled = !audio.isNullOrBlank()
        speaker.setOnClickListener { togglePlay(audio) }
    }

    /**
     * 底部「直译 → 整体」。
     *
     * 例：`直译  茶 · 托盘  →  茶盘`。
     * 任一核心义缺失就不显示——拼不完整的直译比不显示更误导。
     */
    private fun renderPhraseFormula(card: WordCardEntity, lang: NameLanguage) {
        // 直译条整条是中文（直译 茶 · 托盘 → 茶盘）→「无（仅英文）」模式没有它
        if (enOnly) {
            binding.tvPhraseFormula.visibility = View.GONE
            return
        }
        // 功能词（of / for / with…）直译出来不成句（pair of scissors → 「双·的·剪刀」），
        // 只让实词参与拼句；词砖上仍然会显示它们，学习时不漏。
        val contentWords = phraseWords.filter { it.text.lowercase() !in FUNCTION_WORDS }
        val cores = contentWords.map { zht(it.coreMeaning) }
        if (cores.isEmpty() || cores.any { it.isNullOrBlank() }) {
            binding.tvPhraseFormula.visibility = View.GONE
            return
        }
        val whole = if (lang.isChinese) {
            zht(card.nameChinese?.trim()?.takeIf { it.isNotBlank() }) ?: card.nameEnglish
        } else {
            card.nameEnglish?.trim()?.takeIf { it.isNotBlank() }
        }
        if (whole.isNullOrBlank()) {
            binding.tvPhraseFormula.visibility = View.GONE
            return
        }
        binding.tvPhraseFormula.text =
            getString(R.string.phrase_formula_fmt, cores.joinToString(" · "), whole)
        binding.tvPhraseFormula.visibility = View.VISIBLE
    }

    /** 点词砖：看该词的完整释义与例句，可顺带试听 */
    private fun showWordDialog(word: PhraseWord) {
        val title = if (word.hasPhonetic) "${word.text}   ${word.phoneticPrimary}" else word.text
        val body = buildString {
            // 音标放在最前：点开词砖的第一诉求是「怎么读」
            word.phoneticUs?.let { append(getString(R.string.detail_us)).append("  ").append(it).append("\n") }
            word.phoneticUk?.let { append(getString(R.string.detail_uk)).append("  ").append(it).append("\n") }
            if (word.hasPhonetic) append("\n")

            // 释义与例句是中文内容 →「无（仅英文）」模式下只给音标
            if (!enOnly) {
                if (word.allMeanings.isEmpty()) {
                    append(getString(R.string.phrase_word_missing))
                } else {
                    word.allMeanings.take(8).forEach { append("· ").append(zht(it)).append("\n") }
                }
                if (word.examples.isNotEmpty()) {
                    append("\n").append(getString(R.string.detail_examples)).append("\n")
                    word.examples.forEach { append(zht(it)).append("\n") }
                }
            }
        }.trim()

        // 弹窗只放一个播放键，默认美音（学习主口径）；英音在词砖上那一行单独点
        val audio = word.audioUs ?: word.audioUk

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(R.string.common_ok, null)
            .apply {
                if (!audio.isNullOrBlank()) {
                    setNeutralButton(R.string.card_play_english) { _, _ -> togglePlay(audio) }
                }
            }
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 回填顶部基础信息。
     *
     * **只显示词典里真实存在的内容**：任一栏为 null 就隐藏该组（连标签和分隔线一起），
     * 不再用「—」占位。释义只取自词典词条，不回退到识物接口的 description。
     *
     * 音标分「美」「英」两行，每行带一个小喇叭试听对应发音（音频来自词典
     * `phoneticUsAudio` / `phoneticEnAudio`）；没有音频的那一行喇叭置灰。
     */
    private fun applyBasics(
        pinyin: String?,
        phoneticUs: String?,
        phoneticUk: String?,
        desc: String?,
        usAudio: String? = null,
        ukAudio: String? = null
    ) {
        if (!pinyin.isNullOrBlank()) {
            binding.tvDetailPinyin.text = pinyin
            binding.grpPinyin.visibility = View.VISIBLE
        } else {
            binding.grpPinyin.visibility = View.GONE
        }

        audioUs = usAudio
        audioUk = ukAudio

        if (!phoneticUs.isNullOrBlank()) {
            binding.tvPhoneticUs.text = phoneticUs
            binding.grpPhoneticUs.visibility = View.VISIBLE
        } else {
            binding.grpPhoneticUs.visibility = View.GONE
        }

        if (!phoneticUk.isNullOrBlank()) {
            binding.tvPhoneticUk.text = phoneticUk
            binding.grpPhoneticUk.visibility = View.VISIBLE
        } else {
            binding.grpPhoneticUk.visibility = View.GONE
        }

        // 两行音标都没有 → 整个音标组（含分隔线）隐藏
        binding.grpPhonetic.visibility =
            if (phoneticUs.isNullOrBlank() && phoneticUk.isNullOrBlank()) View.GONE
            else View.VISIBLE

        // 喇叭的可用状态 / 高亮跟着音频一起刷新
        updatePlayButtons(audioPlayer.isPlaying())

        if (!desc.isNullOrBlank()) {
            binding.tvDetailDescription.text = desc
            binding.grpDesc.visibility = View.VISIBLE
        } else {
            binding.grpDesc.visibility = View.GONE
        }
    }

    /**
     * 英文词典区。
     *
     * 没有一条可用内容时**整张卡不出现**（不显示「暂未查到」这类空状态）；
     * 只有网络失败才露出重试入口。
     */
    private fun renderEnglishDict(e: DictEntry?, failed: Boolean) {
        binding.grpDictLoading.visibility = View.GONE

        // 「无（仅英文）」：词条的释义/例句/近反义词都是中文内容，整张词典卡退场。
        // 但查询照常进行——顶部「美 / 英」音标与试听仍取自这个词条（见 applyBasics）。
        if (enOnly) {
            binding.cardDictSection.visibility = View.GONE
            return
        }

        val hasContent = e != null && (
            e.meanings.isNotEmpty() || e.examples.isNotEmpty() ||
                e.phrases.isNotEmpty() || e.forms.isNotEmpty() ||
                e.synonyms.isNotEmpty() || e.antonyms.isNotEmpty()
            )

        if (!hasContent) {
            if (failed) {
                binding.cardDictSection.visibility = View.VISIBLE
                binding.grpDictContent.visibility = View.GONE
                binding.grpDictError.visibility = View.VISIBLE
                binding.tvDictError.setText(R.string.detail_dict_en_failed)
            } else {
                binding.cardDictSection.visibility = View.GONE
            }
            return
        }

        binding.cardDictSection.visibility = View.VISIBLE
        binding.grpDictError.visibility = View.GONE
        binding.grpDictContent.visibility = View.VISIBLE
        val entry = e!!
        binding.tvDictType.text = when (entry.dict) {
            "en_word" -> getString(R.string.pos_en_word)
            "en_phrase" -> getString(R.string.pos_en_phrase)
            else -> getString(R.string.pos_en)
        }
        binding.tvDictType.visibility = View.VISIBLE

        if (entry.meanings.isEmpty()) {
            binding.tvDictMeanings.visibility = View.GONE
        } else {
            binding.tvDictMeanings.visibility = View.VISIBLE
            binding.tvDictMeanings.text = entry.meanings.joinToString("\n") { m ->
                if (m.pos.isNullOrBlank()) "· ${m.meaning}" else "${m.pos}.  ${m.meaning}"
            }
        }

        if (entry.forms.isEmpty()) {
            binding.tvDictForms.visibility = View.GONE
        } else {
            binding.tvDictForms.visibility = View.VISIBLE
            binding.tvDictForms.text = entry.forms.joinToString("   ") { (k, v) -> "$k $v" }
        }

        if (entry.examples.isEmpty()) {
            binding.grpDictExamples.visibility = View.GONE
        } else {
            binding.grpDictExamples.visibility = View.VISIBLE
            binding.tvDictExamples.text = entry.examples.joinToString("\n\n") { ex ->
                buildString {
                    ex.en?.let { append(it) }
                    ex.cn?.let {
                        if (isNotEmpty()) append("\n")
                        append(it)
                    }
                }
            }
        }

        val related = buildString {
            if (entry.synonyms.isNotEmpty()) {
                append(getString(R.string.dict_synonym)).append("  ")
                append(entry.synonyms.joinToString("、"))
            }
            if (entry.antonyms.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append(getString(R.string.dict_antonym)).append("  ")
                append(entry.antonyms.joinToString("、"))
            }
        }
        binding.tvDictRelated.text = related
        binding.tvDictRelated.visibility = if (related.isBlank()) View.GONE else View.VISIBLE

        if (entry.phrases.isEmpty()) {
            binding.grpDictPhrases.visibility = View.GONE
        } else {
            binding.grpDictPhrases.visibility = View.VISIBLE
            binding.tvDictPhrases.text = entry.phrases.joinToString("\n") { p ->
                listOfNotNull(p.en, p.cn).joinToString("  ")
            }
        }
    }

    /**
     * 汉语词典区。
     *
     * 展示原则：
     *  - 释义**只来自词典词条**，绝不使用逐字拼接的结果
     *  - 只有拼音可查（如「立白洗洁精」）→ 只显示拼音，标题也随之变成「拼音」
     *  - 什么都没有 → 整张卡不出现
     */
    private fun renderChineseDict(e: DictEntry?, pinyinFallback: String?, failed: Boolean) {
        binding.grpCnLoading.visibility = View.GONE

        val pinyin = e?.pinyin?.takeIf { it.isNotBlank() } ?: pinyinFallback
        val meanings = e?.meanings ?: emptyList()
        val cnExamples = e?.examples?.mapNotNull { it.cn?.takeIf { c -> c.isNotBlank() } } ?: emptyList()
        val combine = e?.combineWords ?: emptyList()

        // 部首 / 结构 / 笔画（仅汉字有）
        val meta = buildString {
            e?.radical?.let { append(getString(R.string.dict_radical_fmt, it)) }
            e?.structure?.let { append("$it    ") }
            // 服务端 strokesNumText 常已带「画」字（如「共七画」），避免重复拼接。
            // 繁体下这个字是「畫」，两个都要去掉，否则会出现「七畫 畫」。
            e?.strokes?.let { s ->
                append(getString(R.string.dict_strokes_fmt, s.replace("画", "").replace("畫", "").trim()))
            }
        }.trim()

        val related = buildString {
            if (!e?.synonyms.isNullOrEmpty()) {
                append(getString(R.string.dict_synonym)).append("  ")
                append(e!!.synonyms.joinToString("、"))
            }
            if (!e?.antonyms.isNullOrEmpty()) {
                if (isNotEmpty()) append("\n")
                append(getString(R.string.dict_antonym)).append("  ")
                append(e!!.antonyms.joinToString("、"))
            }
        }

        val hasContent = !pinyin.isNullOrBlank() || meanings.isNotEmpty() ||
            cnExamples.isNotEmpty() || combine.isNotEmpty() ||
            related.isNotBlank() || meta.isNotBlank()

        if (!hasContent) {
            if (failed) {
                binding.cardCnSection.visibility = View.VISIBLE
                binding.grpCnContent.visibility = View.GONE
                binding.grpCnError.visibility = View.VISIBLE
                binding.tvCnError.setText(R.string.detail_dict_cn_failed)
            } else {
                binding.cardCnSection.visibility = View.GONE
            }
            return
        }

        binding.cardCnSection.visibility = View.VISIBLE
        binding.grpCnError.visibility = View.GONE
        binding.grpCnContent.visibility = View.VISIBLE

        // 没有释义时，标题就叫「拼音」，不要挂「释义」二字却只有拼音
        binding.tvCnTitle.text = when {
            meanings.isNotEmpty() -> getString(R.string.detail_dict_cn)
            meta.isNotBlank() -> getString(R.string.pos_cn_char)
            else -> getString(R.string.pos_pinyin)
        }

        binding.tvCnType.text = when (e?.dict) {
            "cn_word" -> getString(R.string.pos_cn_word)
            "cn_char" -> getString(R.string.pos_cn_char)
            "cn_idiom" -> getString(R.string.pos_cn_idiom)
            else -> ""
        }
        binding.tvCnType.visibility =
            if (binding.tvCnType.text.isNullOrBlank() || meanings.isEmpty()) View.GONE else View.VISIBLE

        // 拼音
        if (pinyin.isNullOrBlank()) {
            binding.grpCnPinyinRow.visibility = View.GONE
        } else {
            binding.grpCnPinyinRow.visibility = View.VISIBLE
            binding.tvCnPinyin.text = pinyin
        }

        binding.tvCnMeta.text = meta
        binding.tvCnMeta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE

        // 释义
        if (meanings.isEmpty()) {
            binding.grpCnMeanings.visibility = View.GONE
        } else {
            binding.grpCnMeanings.visibility = View.VISIBLE
            binding.tvCnMeanings.text = meanings
                .mapIndexed { i, m -> "${i + 1}. ${m.meaning}" }
                .joinToString("\n")
        }

        // 例句
        if (cnExamples.isEmpty()) {
            binding.grpCnExamples.visibility = View.GONE
        } else {
            binding.grpCnExamples.visibility = View.VISIBLE
            binding.tvCnExamples.text = cnExamples.joinToString("\n") { "· $it" }
        }

        // 近反义词
        binding.tvCnRelated.text = related
        binding.tvCnRelated.visibility = if (related.isBlank()) View.GONE else View.VISIBLE

        // 组词
        if (combine.isEmpty()) {
            binding.grpCnCombine.visibility = View.GONE
        } else {
            binding.grpCnCombine.visibility = View.VISIBLE
            binding.tvCnCombine.text = combine.joinToString("  ")
        }
    }

    // ==================== 发音 ====================

    private fun togglePlay(url: String?) {
        if (url.isNullOrBlank()) {
            toast(getString(R.string.detail_no_audio))
            return
        }
        if (audioPlayer.currentPlayingUrl() == url && audioPlayer.isPlaying()) {
            audioPlayer.stop()
            return
        }
        if (!audioPlayer.play(url)) toast(getString(R.string.detail_play_failed))
    }

    private fun updatePlayButtons(playing: Boolean) {
        val current = audioPlayer.currentPlayingUrl()
        binding.btnPlayChinese.text = if (playing && current == chineseUrl) {
            getString(R.string.detail_stop)
        } else {
            chineseBtnLabel.ifBlank { getString(R.string.card_play_chinese) }
        }
        binding.btnPlayEnglish.text = if (playing && current == englishUrl) {
            getString(R.string.detail_stop)
        } else {
            getString(R.string.card_play_english)
        }

        // 音标旁的喇叭：正在放哪个就把哪个点亮，其余置灰（没有音频文件的直接半透明）
        val active = ContextCompat.getColor(this, R.color.brand_primary)
        val normal = ContextCompat.getColor(this, R.color.text_secondary)
        binding.btnSpeakUs.imageTintList = ColorStateList.valueOf(
            when {
                audioUs.isNullOrBlank() -> normal
                playing && current == audioUs -> active
                else -> normal
            }
        )
        binding.btnSpeakUs.alpha = if (audioUs.isNullOrBlank()) 0.35f else 1f
        binding.btnSpeakUk.imageTintList = ColorStateList.valueOf(
            when {
                audioUk.isNullOrBlank() -> normal
                playing && current == audioUk -> active
                else -> normal
            }
        )
        binding.btnSpeakUk.alpha = if (audioUk.isNullOrBlank()) 0.35f else 1f
    }

    // ==================== 删除 ====================

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.detail_delete_title)
            .setMessage(R.string.detail_delete_msg)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_ok) { _, _ ->
                lifecycleScope.launch {
                    repo.deleteLocal(cardId)
                    toast(getString(R.string.detail_deleted))
                    finish()
                }
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
