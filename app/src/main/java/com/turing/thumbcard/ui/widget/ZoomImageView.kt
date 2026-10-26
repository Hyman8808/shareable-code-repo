package com.turing.thumbcard.ui.widget

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.min

/**
 * 支持双指缩放、双击放大、放大后拖动的 ImageView。
 *
 * 为什么自己写而不引第三方（PhotoView / Subsampling 之类）：
 *  这个页面只干一件事——把一张图放大看细节，需求就三个手势。
 *  引库要多背几百 KB 和一个不再维护的依赖，不划算。
 *
 * 🔴 **`scaleType` 必须是 `MATRIX`**，这是这个控件唯一容易写错的地方：
 *    ImageView 的 `configureBounds()` 里写着——
 *    `if (ScaleType.MATRIX == mScaleType) 用 mMatrix，否则用 scaleType 算出来的矩阵`。
 *    也就是说只要 `scaleType` 是 `FIT_CENTER`（或任何别的值），
 *    `setImageMatrix()` 传进去的矩阵会在下一帧被**无条件覆盖**，
 *    手势回调照常执行、`currentScale` 照常变化，但画面纹丝不动 ——
 *    表现就是「能点、能拖，就是放不大」，且没有任何报错。
 *    所以这里把 fit-center 也自己算进 [baseMatrix]，全程只走 MATRIX 一条路。
 *
 * 坐标系约定：
 *  [baseMatrix] = 「图片完整显示在控件内并居中」的矩阵（含 padding）；
 *  [matrix] = baseMatrix × 用户的手势变换。所有 `postScale/postTranslate` 的
 *  焦点都是**控件坐标**（`MotionEvent.x/y`），与 Matrix 的输出空间一致，不会漂。
 *
 * 边界约束（[constrain]）：倍数夹在 1×—5×；回到 1× 时**直接锁定 baseMatrix**，
 * 不做「算一遍再修」，避免反复缩放后累积浮点误差、图越缩越偏。
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    companion object {
        /** 上限倍数。2K 图（长边 2048）在 1080p 屏上按 FIT 显示约 0.5×，5× 已经能看到像素级细节 */
        private const val MAX_SCALE = 5f

        /** 双击放大到 2.5×：够看清主体，又不至于一下飞出画面 */
        private const val DOUBLE_TAP_SCALE = 2.5f

        /** 倍率容差：小于它视为「没放大」，用于判断双击是放大还是复位 */
        private const val EPS = 1.02f

        /** drawable 尺寸迟迟拿不到时的重试次数（Coil 淡入、矢量占位图都可能延迟） */
        private const val MAX_RETRY = 3

        /** 触发切卡的横向位移门槛（dp）：沿用详情页 72dp，两页手感一致 */
        private const val SWIPE_MIN_DP = 72f

        /** 触发切卡的横向速度（px/s）：慢慢横拖是在看内容，不是要翻页 */
        private const val SWIPE_MIN_VELOCITY = 320f

        /**
         * 横向位移须达到纵向的 [SWIPE_AXIS_RATIO] 倍。
         *
         * 详情页是 1.5，这里放宽到 1.2：本页纵向**没有任何可滚内容**，
         * 纵向位移不存在合法意图，卡太严只会漏判（斜着滑一下就没反应）。
         */
        private const val SWIPE_AXIS_RATIO = 1.2f

        /** 超过 25% 宽度视为「明确长拖」，免速度校验：慢而长的横拖也是切卡意图 */
        private const val SWIPE_WIDE_RATIO = 0.25f
    }

    private val baseMatrix = Matrix()
    private val matrix = Matrix()
    private val values = FloatArray(9)
    private val rect = RectF()

    /** 当前累计缩放（相对 baseMatrix 的基准，1f = 完整显示） */
    private var currentScale = 1f

    /** baseMatrix 是否已经算好（drawable 尺寸 + 控件尺寸都就绪） */
    private var baseReady = false

    private var retry = 0

    /**
     * 未放大状态下检测到横向轻扫：`+1` = 下一张（左滑），`-1` = 上一张（右滑）。
     * 返回 true 表示外层已接管。
     *
     * 为什么把手势出口放在控件内部，而不是让 Activity 覆写 `dispatchTouchEvent`
     * 再挂一个 GestureDetector（单词详情页那种写法）：
     *  1. 本控件在 `ACTION_DOWN` 就 `requestDisallowInterceptTouchEvent(true)`，
     *     且 `onTouchEvent` 恒返回 true —— 两套 detector 会各自解析同一事件序列；
     *  2. 只有在这里，`currentScale` 才与手势是**同一时刻的真实值**。
     *     放到 Activity 层再问 `isZoomed()`，中间隔了一帧，双击放大后立刻横拖会误切卡。
     */
    var onSwipeOut: ((Int) -> Boolean)? = null

    /**
     * 已放大时用户仍试图横滑 —— 外层用它提示「先还原才能切卡」。
     * 以「尝试失败」触发，不做预教提示。
     */
    var onSwipeBlocked: (() -> Unit)? = null

    /**
     * 该方向上还有没有可平移的余地。
     *
     * 两种情况都算「挪不动」（返回 false）：
     *  1. 图片整体比控件窄（竖长图放大 2.5× 仍可能如此）→ 横向根本没有平移空间；
     *  2. 已经贴到控件对应边的内侧。
     *
     * 判据与 [constrain] 同源，直接复用它的矩形计算。
     *
     * @param dx 手指横向位移；`dx < 0` 表示手指向左（想看到图片右边的内容）
     */
    private fun canPanToward(dx: Float): Boolean {
        val d = drawable ?: return true
        matrix.getValues(values)
        if (values[Matrix.MSCALE_X] <= 0f) return true
        rect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        matrix.mapRect(rect)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        if (rect.width() <= right - left) return false
        return if (dx < 0) rect.right > right + 1f else rect.left < left - 1f
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!baseReady) return false
                val target = (currentScale * detector.scaleFactor).coerceIn(1f, MAX_SCALE)
                val factor = target / currentScale
                if (abs(factor - 1f) < 0.001f) return false
                currentScale = target
                matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                applyMatrix()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!baseReady) return false
                val zoomIn = currentScale <= EPS
                if (zoomIn) {
                    val factor = DOUBLE_TAP_SCALE / currentScale
                    currentScale = DOUBLE_TAP_SCALE
                    matrix.postScale(factor, factor, e.x, e.y)
                } else {
                    currentScale = 1f
                    matrix.set(baseMatrix)
                }
                applyMatrix()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // 没放大时把事件让给外层（本页没有可滚容器，但保留这个习惯，
                // 将来把控件放进 ScrollView/ViewPager 不会出现「滑不动页」）
                if (currentScale <= EPS) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                matrix.postTranslate(-distanceX, -distanceY)
                applyMatrix()
                return true
            }

            /**
             * 横向轻扫 = 切上一张 / 下一张。
             *
             * 判据（与详情页同源，只放宽了横纵比）：
             *  1. **放大态一律不切** —— 此时横拖是平移图片（[onScroll] 已接管）；
             *  2. 横向位移 ≥ 72dp 且横向速度 ≥ 320px/s，**或** 横向位移 ≥ 25% 控件宽
             *     （明确的长拖，免速度校验 —— 慢而长的横拖同样是切卡意图）；
             *  3. 横向位移 ≥ 纵向的 1.2 倍。
             */
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val from = e1 ?: return false
                val dx = e2.x - from.x
                val dy = e2.y - from.y

                if (currentScale > EPS) {
                    // 放大态的横滑绝大多数是「平移看局部」，不是想切卡。
                    // 只有「这个方向已经挪不动了」才是真的想切 —— 两个成因都算：
                    //  ① 已到平移边界；② 图放大后仍比控件窄，横向根本没有平移空间
                    //     （竖长图很常见）。少了 ②，这类图在放大态会被误判成「还能挪」，
                    //     提示永远不出现，用户只觉得「滑了没反应」。
                    val horizontal = abs(dx) >= SWIPE_MIN_DP * resources.displayMetrics.density &&
                        abs(dx) >= abs(dy) * SWIPE_AXIS_RATIO
                    if (horizontal && !canPanToward(dx)) onSwipeBlocked?.invoke()
                    // 🔴 放大态到此为止：绝不进入下面的切卡判定
                    return false
                }

                val wide = width > 0 && abs(dx) >= width * SWIPE_WIDE_RATIO
                if (!wide) {
                    if (abs(dx) < SWIPE_MIN_DP * resources.displayMetrics.density) return false
                    if (abs(velocityX) < SWIPE_MIN_VELOCITY) return false
                }
                if (abs(dx) < abs(dy) * SWIPE_AXIS_RATIO) return false

                return onSwipeOut?.invoke(if (dx < 0) 1 else -1) == true
            }
        }
    )

    init {
        // 🔴 见类注释：FIT_CENTER 会让手势矩阵被 configureBounds 覆盖，这里必须是 MATRIX
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    /** 是否处于放大状态（Activity 用它决定返回键先复位还是直接退出） */
    fun isZoomed(): Boolean = baseReady && currentScale > EPS

    /** 回到 1× 居中 */
    fun resetToBase() {
        currentScale = 1f
        matrix.set(baseMatrix)
        imageMatrix = matrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        // 双指缩放时不喂给 GestureDetector，否则会「边缩边飘」
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        retry = 0
        syncBase()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 尺寸变化后旧的平移量毫无意义，按新尺寸重算基准
        if (w != oldw || h != oldh) syncBase()
    }

    /**
     * 重算基准矩阵（fit-center）。
     *
     * 三个「还没就绪」的情形：drawable 没有 / drawable 没有 intrinsic 尺寸（异步解码、淡入中）/
     * 控件还没 layout。前两个靠 post 重试（有次数上限，防止无限调度），
     * 第三个等 [onSizeChanged] 自己再来一次。
     */
    private fun syncBase() {
        val d = drawable
        if (d == null) {
            baseReady = false
            return
        }
        val dw = d.intrinsicWidth
        val dh = d.intrinsicHeight
        if (dw <= 0 || dh <= 0) {
            baseReady = false
            if (retry < MAX_RETRY) {
                retry++
                post { if (drawable === d) syncBase() }
            }
            return
        }
        val vw = width - paddingLeft - paddingRight
        val vh = height - paddingTop - paddingBottom
        if (vw <= 0 || vh <= 0) {
            baseReady = false
            return
        }

        val s = min(vw.toFloat() / dw, vh.toFloat() / dh)
        baseMatrix.reset()
        baseMatrix.postScale(s, s)
        baseMatrix.postTranslate(
            (vw - dw * s) / 2f + paddingLeft,
            (vh - dh * s) / 2f + paddingTop
        )
        baseReady = true
        resetToBase()
    }

    private fun applyMatrix() {
        constrain()
        imageMatrix = matrix
    }

    /**
     * 把矩阵夹回「看得见的地方」。
     *
     * 判断依据是**图片实际占的矩形**（mapRect），不是控件矩形：
     * 图在 1× 时两侧会有留白，按控件矩形算就会允许把图拖出可视区。
     *
     * 回到 1× 附近时直接锁 baseMatrix —— 不做增量修正，避免累积误差。
     */
    private fun constrain() {
        if (currentScale <= EPS) {
            matrix.set(baseMatrix)
            return
        }
        val d = drawable ?: return
        matrix.getValues(values)
        if (values[Matrix.MSCALE_X] <= 0f) return

        rect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        matrix.mapRect(rect)

        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = (width - paddingRight).toFloat()
        val bottom = (height - paddingBottom).toFloat()

        var dx = 0f
        var dy = 0f

        if (rect.width() <= right - left) {
            dx = (left + right) / 2f - rect.centerX()
        } else {
            if (rect.left > left) dx = left - rect.left
            if (rect.right < right) dx = right - rect.right
        }

        if (rect.height() <= bottom - top) {
            dy = (top + bottom) / 2f - rect.centerY()
        } else {
            if (rect.top > top) dy = top - rect.top
            if (rect.bottom < bottom) dy = bottom - rect.bottom
        }

        if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
    }
}
