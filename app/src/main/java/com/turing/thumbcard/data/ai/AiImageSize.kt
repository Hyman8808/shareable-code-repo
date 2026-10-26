package com.turing.thumbcard.data.ai

import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.roundToInt

/**
 * 把「参考图的宽高比」翻译成方舟要的 `宽x高`。
 *
 * 为什么不再传 `2K` 档位：
 *  档位只是「总像素量级」，比例由模型自己看着办。实测下来模型确实会跟随输入图，
 *  但那是**模型的行为**，不是接口的契约 —— 换模型、换版本、换一张构图特别的图，
 *  都可能出一张比例不对的图。而比例是产品硬要求：
 *  · 全尺寸图要能原样「存进相册 / 打印」，多一刀裁切就是废片；
 *  · 卡片用图要按卡片框比例裁，源图比例错了就等于白裁。
 *  所以这里自己算出确切像素，把比例从「模型的自由发挥」变成「我们的输入」。
 *
 * 这个类对「客户相机不是我们这种 2:1」是必需的：4:3 / 16:9 / 1:1 各自算出对应尺寸，
 * 一比一还原，不靠猜。
 *
 * 约束（来自方舟文档）：总像素 921600–4624220，宽高比 1:4—4:1。
 * 长边固定 [LONG_EDGE]=2048（2K 档），短边按比例缩并**对齐到 8** ——
 * 主流编码器按 8/16 对齐做宏块，非对齐尺寸会被隐式补边，既不整齐也浪费码率。
 */
object AiImageSize {

    /** 长边像素。对应方舟的「2K」档，是产品要求的下限「至少 2K」 */
    const val LONG_EDGE = 2048

    /** 方舟允许的总像素区间 */
    private const val MIN_PIXELS = 921_600L
    private const val MAX_PIXELS = 4_624_220L

    /** 短边下限：再窄就不是照片，是长条了 */
    private const val MIN_SIDE = 512

    /** 宽高比上下限（方舟上限 4:1，超出会被服务端拒） */
    private const val MIN_ASPECT = 1f / 4f
    private const val MAX_ASPECT = 4f

    /** 宽高对齐粒度 */
    private const val ALIGN = 8

    /**
     * 读参考图比例 → 返回 `"2048x1536"` 这样的尺寸串。
     *
     * 只读文件头（`inJustDecodeBounds`），不真解码，一张 2:1 的原图不到 1ms。
     * 读不出来（文件损坏 / 不是图片）返回 null，由调用方退回档位。
     */
    fun forSource(file: File): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return fit(bounds.outWidth, bounds.outHeight)
    }

    /**
     * 按比例算尺寸。纯函数，方便单测（[width]/[height] 只要能表达比例，具体像素无所谓）。
     */
    fun fit(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "${LONG_EDGE}x${LONG_EDGE}"

        val ratio = (width.toFloat() / height).coerceIn(MIN_ASPECT, MAX_ASPECT)

        var w: Int
        var h: Int
        if (ratio >= 1f) {
            w = LONG_EDGE
            h = (LONG_EDGE / ratio).roundToInt()
        } else {
            h = LONG_EDGE
            w = (LONG_EDGE * ratio).roundToInt()
        }

        w = align(w)
        h = align(h)

        // 总像素兜底：正常比例走不到这里，防的是将来改 LONG_EDGE 或放宽比例上限
        val pixels = w.toLong() * h.toLong()
        if (pixels > MAX_PIXELS) {
            val k = kotlin.math.sqrt(MAX_PIXELS.toDouble() / pixels).toFloat()
            w = align((w * k).toInt())
            h = align((h * k).toInt())
        } else if (pixels < MIN_PIXELS) {
            val k = kotlin.math.sqrt(MIN_PIXELS.toDouble() / pixels).toFloat()
            w = align((w * k).toInt())
            h = align((h * k).toInt())
        }

        return "${w.coerceAtLeast(MIN_SIDE)}x${h.coerceAtLeast(MIN_SIDE)}"
    }

    /** 向下对齐到 8 的倍数（宁可小一点，不要超出上限） */
    private fun align(v: Int): Int = (v / ALIGN) * ALIGN
}
