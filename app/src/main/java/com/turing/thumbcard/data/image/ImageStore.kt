package com.turing.thumbcard.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.turing.thumbcard.data.ai.AiStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 卡片图片的**本地落盘仓库**：原图一张、AI 图按风格各两张（全尺寸 + 卡片用图）。
 *
 * 为什么不用 Coil 的磁盘缓存就够了：
 *  Coil 的 diskCache 是 LRU，**随时可能被系统或它自己淘汰**，
 *  而且它的 key 是 URL + 尺寸变换，同一个 url 在不同控件尺寸下会落多份。
 *  用户花了钱生成的 AI 图、以及要「下载到相册」的原图，都必须是确定存在的文件，
 *  所以这里另存一份自己的副本，由我们决定什么时候删。
 *
 * 目录结构（都在 filesDir 下，卸载即清）：
 * ```
 * cards/orig/<cardId>.jpg             原图（云端 OSS 拉下来的那份，160×80 也是它）
 * cards/ai/<cardId>_hd.jpg           高清修复 · 全尺寸（长边 2048，**保持原图宽高比**，看大图/存相册用）
 * cards/ai/<cardId>_hd_card.jpg      高清修复 · 卡片用图（按 [CARD_ASPECT] 居中裁切 + 缩放，列表封面用）
 * cards/ai/<cardId>_cartoon.jpg      卡通插画 · 全尺寸
 * cards/ai/<cardId>_cartoon_card.jpg 卡通插画 · 卡片用图
 * ```
 *
 * 🔴 为什么必须有两张：卡片是固定比例（4:3），而拍摄比例不是。
 *    我们自己的机器出 2:1，客户可能是 4:3 / 16:9 / 1:1，直接 `centerCrop` 会切掉一边：
 *    2:1 进 4:3 要裁掉 33% 宽度（实测「电水壶」的把手直接被裁掉）。
 *    所以卡片用图在**落盘时**就按卡片框比例居中裁好并缩到 [COVER_WIDTH] 宽（约 200KB），
 *    列表滚动也不用再解码一张 2K 图（2048×1536 解码一次约 12MB 内存）。
 *
 *    比例的分工只有一句话：**全尺寸图是「拍摄事实」，一张都不许裁；
 *    卡片用图是「展示物料」，按框裁。** 任何新的比例需求都只动卡片用图这一侧。
 *
 * 命名用 cardId 而不是记录里的文件名：云端 id 全局唯一且稳定，
 * 而 image_url 的路径里那串 hash 我们无从校验。
 */
object ImageStore {

    private const val TAG = "ImageStore"
    private const val DIR_ROOT = "cards"
    private const val DIR_ORIG = "orig"
    private const val DIR_AI = "ai"

    /** 小于这个字节数视为下载失败留下的残包（正常图至少几 KB） */
    private const val MIN_VALID_BYTES = 512L

    /**
     * 卡片图框比例 = 4:3。
     *
     * 🔴 **四处必须同步**（改比例时一起改，漏一处就会出现「卡片图裁好了但框是另一个比例」）：
     *  · 这里 · `item_word_card_grid.xml` 的 `layout_constraintDimensionRatio`
     *  · `activity_card_detail.xml` 的 `boxDetailImage` 比例约束
     *  · 收藏页/首页卡片的视觉规范（`docs/UI设计系统_V2.0`）
     *
     * 为什么是 4:3 而不是别的：客户相机三种常见比例 4:3 / 16:9 / 1:1，
     * 裁到 4:3 的最坏损失是 25%（16:9 裁左右、1:1 裁上下），是三者里最均衡的；
     * 选 3:2 对 16:9 更好但会让 4:3 的相机白白损失 11%。
     */
    const val CARD_ASPECT = 4f / 3f

    /** 卡片用图宽度。1080 在 3x 屏的 154dp 卡面上仍有余量，再大只是白占内存 */
    private const val COVER_WIDTH = 1080

    /** 卡片用图 JPEG 质量：88 时 1080×810 约 200–350KB，肉眼看不出压缩痕迹 */
    private const val COVER_QUALITY = 88

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ==================== 路径 ====================

    private fun root(ctx: Context) = File(ctx.filesDir, DIR_ROOT)

    fun origFile(ctx: Context, cardId: Long): File =
        File(File(root(ctx), DIR_ORIG).apply { mkdirs() }, "$cardId.jpg")

    fun aiFile(ctx: Context, cardId: Long, style: AiStyle): File =
        File(File(root(ctx), DIR_AI).apply { mkdirs() }, "${cardId}_${style.code}.jpg")

    /**
     * 「卡片用图」：4:3 裁切 + 缩放后的那一张。
     *
     * 后缀放在风格码之后（`_hd_card`），这样 `AiStyle.code` 仍是文件名里的完整标记，
     * 将来加风格不用动这里。
     */
    fun aiCardFile(ctx: Context, cardId: Long, style: AiStyle): File =
        File(File(root(ctx), DIR_AI).apply { mkdirs() }, "${cardId}_${style.code}_card.jpg")

    // ==================== 读 ====================

    /** 已落盘的原图；没下过返回 null */
    fun original(ctx: Context, cardId: Long): File? =
        origFile(ctx, cardId).takeIf { it.isFile && it.length() > MIN_VALID_BYTES }

    /** 已落盘的全尺寸 AI 图（看大图 / 存相册 / 切风格判据都用它） */
    fun ai(ctx: Context, cardId: Long, style: AiStyle): File? =
        aiFile(ctx, cardId, style).takeIf { it.isFile && it.length() > MIN_VALID_BYTES }

    /** 已落盘的卡片用图；老版本生成的图没有这一张，返回 null 由调用方退回全尺寸 */
    fun aiCard(ctx: Context, cardId: Long, style: AiStyle): File? =
        aiCardFile(ctx, cardId, style).takeIf { it.isFile && it.length() > MIN_VALID_BYTES }

    /**
     * 列表 / 详情页封面该显示哪个文件 —— **卡片用图优先**。
     *
     * 顺序：卡片用图 → 全尺寸 AI 图 → 已落盘原图 → null（由调用方退回云端 URL）。
     * 「全尺寸」这一步是给 v2.3.0 之前生成的图兜底的：那些图只有 2:1 一张，
     * 宁可被裁也别显示「没生成过」。
     *
     * ⚠️ 只认 [aiStyle] 指定的那一种风格：用户切回「原图」后
     *    另一种风格的旧文件还在磁盘上，但**不该**再被显示。
     */
    fun displayFile(ctx: Context, cardId: Long, aiStyle: String?): File? {
        val style = aiStyle?.let { AiStyle.fromCode(it) }
        if (style != null) {
            aiCard(ctx, cardId, style)?.let { return it }
            ai(ctx, cardId, style)?.let { return it }
        }
        return original(ctx, cardId)
    }

    /**
     * **详情页主图**该显示哪个文件 —— **全尺寸优先**，与 [displayFile] 相反。
     *
     * 两个方法的分工必须说清楚，否则会出现「详情页看到的和生成的那张不是一张」：
     *  · [displayFile] 给**列表封面**用：卡片框是固定比例，必须用裁好的那张，否则卡片参差；
     *  · [detailFile]  给**详情页 / 看大图 / 存相册**用：用户在这个位置要看的是「拍到的/AI 生成的原貌」，
     *    一张都不许裁。
     *
     * 🔴 旧实现详情页用的是 [displayFile]（裁切图）+ 固定 4:3 图框 + centerCrop，
     *    于是「列表缩略图 = 详情页主图 = 被裁过的一版」，用户点开卡片会认为「还是那张缩略图」。
     *    裁切图只该出现在**列表**；详情页显示全尺寸，高度按图自适应。
     */
    fun detailFile(ctx: Context, cardId: Long, aiStyle: String?): File? {
        val style = aiStyle?.let { AiStyle.fromCode(it) }
        if (style != null) {
            ai(ctx, cardId, style)?.let { return it }
            // 老数据 / 卡片用图单独落盘的极端情况：宁可给裁切图，也别显示「没生成过」
            aiCard(ctx, cardId, style)?.let { return it }
        }
        return original(ctx, cardId)
    }

    // ==================== 写 ====================

    /**
     * 确保原图在本地，返回本地文件。
     *
     * 已存在直接返回，不重复下载 —— 这是「点过的卡片都留在本地」的落地点：
     * 详情页打开时调一次，之后无论断网还是 OSS 过期都能看原图、能存相册。
     *
     * @param url 云端图片地址，null 表示记录里没有图
     * @return 本地文件；下载失败返回 null（调用方继续用 Coil 走 URL，不阻塞界面）
     */
    suspend fun ensureOriginal(ctx: Context, cardId: Long, url: String?): File? {
        original(ctx, cardId)?.let { return it }
        if (url.isNullOrBlank()) return null

        return withContext(Dispatchers.IO) {
            val target = origFile(ctx, cardId)
            val tmp = File(target.parentFile, "${target.name}.part")
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "download ${resp.code} for card $cardId")
                        return@withContext null
                    }
                    val body = resp.body ?: return@withContext null
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                }
                if (tmp.length() <= MIN_VALID_BYTES) {
                    tmp.delete()
                    return@withContext null
                }
                // 先写 .part 再改名：中途被杀不会留下一个「长度不为 0 但内容是半张」的文件，
                // 否则下次 original() 会把它当好图直接返回。
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                Log.d(TAG, "original saved: card=$cardId ${target.length()}B")
                target
            } catch (e: Exception) {
                Log.w(TAG, "download failed: card=$cardId", e)
                runCatching { tmp.delete() }
                null
            }
        }
    }

    /**
     * 保存 AI 生成结果：**一次写两张**。
     *
     * 全尺寸原样落盘（用户花 2 毛钱买的就是它）；
     * 同时裁一张 4:3 的卡片用图给列表/详情页用（见类注释里的理由）。
     *
     * 卡片用图裁失败**不算整体失败**：displayFile 会退回全尺寸，
     * 只是列表多解一次大图，不能因为这个让用户白等 90 秒再看到「失败」。
     */
    suspend fun saveAi(ctx: Context, cardId: Long, style: AiStyle, bytes: ByteArray): File? =
        withContext(Dispatchers.IO) {
            if (bytes.size <= MIN_VALID_BYTES) return@withContext null
            val full = writeAtomic(aiFile(ctx, cardId, style), bytes)
                ?: return@withContext null

            // 把「模型到底给了多大一张」记下来：这张日志是回答
            // 「生成图是不是有问题」的唯一证据（像素 + 体积 + 比例）。
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                val ratio = if (o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else 0f
                Log.d(
                    TAG,
                    "ai full card=$cardId style=${style.code} " +
                        "${o.outWidth}x${o.outHeight} ratio=${String.format("%.2f", ratio)} " +
                        "${bytes.size / 1024}KB"
                )
            }

            val cover = runCatching { coverBytes(bytes) }.getOrNull()
            if (cover != null && cover.size > MIN_VALID_BYTES) {
                if (writeAtomic(aiCardFile(ctx, cardId, style), cover) == null) {
                    Log.w(TAG, "cover write failed: card=$cardId")
                }
            } else {
                Log.w(TAG, "cover build failed: card=$cardId")
            }
            full
        }

    /** 先写 `.part` 再改名，避免半张文件被当成好图 */
    private fun writeAtomic(target: File, bytes: ByteArray): File? {
        val tmp = File(target.parentFile, "${target.name}.part")
        return runCatching {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            Log.d(TAG, "saved ${target.name} ${target.length()}B")
            target
        }.getOrElse {
            Log.w(TAG, "write failed: ${target.name}", it)
            runCatching { tmp.delete() }
            null
        }
    }

    /**
     * 由全尺寸图生成卡片用图：居中裁到 [CARD_ASPECT] → 缩到 1080 宽 → JPEG。
     *
     * 为什么是**居中**裁：AI 出图时主体就在画面中间（提示词要求「保留原有构图与主体位置」），
     * 裁边不裁心是最稳的策略。不做「智能找主体」，那需要额外模型，收益不值。
     *
     * 源图与卡片框比例越接近，这里越接近无损（4:3 的相机 → 完全不裁）。
     * 比例差得多时（2:1 裁 33% 宽、1:1 裁 25% 高）会打一条日志，
     * 用来在客户现场快速判断「图被裁得难看」是素材比例问题还是裁切逻辑问题。
     */
    private fun coverBytes(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 降采样的**判据是裁切后的高度**，不是原图宽度：
        // 2:1 的图裁成 4:3 之后宽度只剩 2/3（2048 宽 → 1365 宽），
        // 按宽度算采样率会把输出压到 720 宽，卡面在大屏上就发虚。
        val targetH = (COVER_WIDTH / CARD_ASPECT).toInt()
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= targetH) sample *= 2

        val src = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        return try {
            val srcRatio = src.width.toFloat() / src.height
            val cropW: Int
            val cropH: Int
            if (srcRatio > CARD_ASPECT) {
                cropH = src.height
                cropW = (src.height * CARD_ASPECT).toInt()
            } else {
                cropW = src.width
                cropH = (src.width / CARD_ASPECT).toInt()
            }
            val x = (src.width - cropW) / 2
            val y = (src.height - cropH) / 2
            val outW = minOf(COVER_WIDTH, cropW).coerceAtLeast(320)
            val outH = (outW / CARD_ASPECT).toInt()

            // 保留面积占比：1.0 = 一点没裁
            val kept = (cropW.toFloat() * cropH) / (src.width.toFloat() * src.height)
            Log.d(
                TAG,
                "cover src=${src.width}x${src.height} " +
                    "ratio=${String.format("%.2f", srcRatio)} crop=${cropW}x${cropH} " +
                    "kept=${String.format("%.0f", kept * 100)}% out=${outW}x${outH}"
            )
            if (kept < 0.6f) {
                // 比例差异过大（竖图 / 长条）：卡片会被裁掉将近一半，值得在日志里显眼一次
                Log.w(TAG, "cover 裁切比例过大 kept=${String.format("%.0f", kept * 100)}%")
            }

            val cropped = Bitmap.createBitmap(src, x, y, cropW, cropH)
            val scaled = Bitmap.createScaledBitmap(cropped, outW, outH, true)
            if (scaled !== cropped) cropped.recycle()

            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, out)
                scaled.recycle()
                out.toByteArray()
            }
        } finally {
            if (!src.isRecycled) src.recycle()
        }
    }

    /** 下载 AI 图（接口返回 url 时用） */
    suspend fun downloadTo(ctx: Context, cardId: Long, style: AiStyle, url: String): File? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).build()
                val bytes = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    resp.body?.bytes()
                } ?: return@withContext null
                saveAi(ctx, cardId, style, bytes)
            } catch (e: Exception) {
                Log.w(TAG, "download ai failed: card=$cardId", e)
                null
            }
        }

    // ==================== 清理 ====================

    fun deleteFor(ctx: Context, cardId: Long) {
        runCatching { origFile(ctx, cardId).delete() }
        AiStyle.values().forEach {
            runCatching { aiFile(ctx, cardId, it).delete() }
            runCatching { aiCardFile(ctx, cardId, it).delete() }
        }
    }

    /** 占用空间（字节），「我的」页展示用 */
    fun usedBytes(ctx: Context): Long =
        runCatching { root(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            .getOrDefault(0L)
}
