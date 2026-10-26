package com.turing.thumbcard.data.image

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.turing.thumbcard.data.ai.AiStyle
import com.turing.thumbcard.data.db.WordCardEntity
import com.turing.thumbcard.util.GallerySaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从系统相册把「以前生成过、并已存进相册」的 AI 图捞回来。
 *
 * ## 它解决的是真金白银的问题
 * 一次生成 60–110 秒、扣 1 张额度。用户重装、清数据、换机之后，
 * `filesDir/cards/ai/` 里的成果全没了，但相册里那份还在 ——
 * 让他为了同一张卡再付一次钱、再等两分钟，是没有道理的。
 * 找回**不调模型、不扣额度**，只是把字节搬回本地。
 *
 * ## 三级匹配（从可信到不可信）
 * | 级别 | 依据 | 可信度 |
 * |---|---|---|
 * | [Match.EXIF] | 存图时写进 EXIF 的 `ThumbCard\|v=1\|id=..\|style=..` | 精确 |
 * | [Match.FILE_NAME] | 文件名里的 `_TC{id}_` 段 | 精确（同一套 id） |
 * | [Match.ENGLISH_NAME] | 老图没有标识，只能按英文名比对 | **猜的**，需在结果里如实告知 |
 *
 * 老版本的图确实没有标识（那时还没有 EXIF/TC 段），所以弱匹配不是「兼容性装饰」，
 * 而是让用户那批已经花过钱的老图也能救回来。但弱匹配必须设两道闸：
 *  ① 英文名在库里命中多张卡 → 直接放弃（宁可少捞，不能错配 —— 错配会污染卡片且用户不易察觉）；
 *  ② 像素不足全尺寸 → 放弃（卡片用图只有 87 万像素，拿它当高清图会让详情页放大后发虚）。
 *
 * ## 为什么只扫自己的相册目录
 * 全库扫要逐张读 EXIF，几千张图会卡住几十秒且要全量媒体权限。
 * 我们写入的图固定落在 `Pictures/单词卡相机/`，按 `RELATIVE_PATH` / `DATA` 过滤就够了。
 */
object GalleryRestore {

    private const val TAG = "GalleryRestore"

    /**
     * 全尺寸门槛。
     *
     * 实测：全尺寸 2048×1024 = 210 万像素；卡片用图 1080×810 = 87 万像素。
     * 卡在 100 万就能把「卡片用图」干净地挡在外面 —— 这类图是 4:3 裁过的，
     * 当全尺寸用会缺两边，放大后也会糊。
     */
    private const val MIN_PIXELS = 1_000_000L

    /** 另加宽度下限：防止某种极端长条图靠面积蒙过像素检查 */
    private const val MIN_WIDTH = 1080

    /** 匹配依据 */
    enum class Match {
        /** 存图时写进 EXIF 的标识 */
        EXIF,

        /** 文件名里的 TC 段 */
        FILE_NAME,

        /** 只有英文名可比对（老图） */
        ENGLISH_NAME
    }

    /** 没被收进 [Report.ready] 的原因 */
    enum class Reason {
        /** 本地库里没有这个 cardId 的卡片（换了账号或还没同步到） */
        UNKNOWN_CARD,

        /** 英文名在库里命中多张卡，无法确定是哪一张 */
        DUPLICATE_NAME,

        /** 像素不足全尺寸（多半存的是卡片用图或截图） */
        TOO_SMALL,

        /** 本地已经有这张卡的该风格图了，不必覆盖 */
        ALREADY_LOCAL,

        /** 读不出来（权限被收回、文件损坏、云端占位没落盘） */
        UNREADABLE
    }

    data class Candidate(
        val cardId: Long,
        val style: AiStyle,
        val uri: Uri,
        val displayName: String,
        val match: Match
    )

    data class Skip(val displayName: String, val reason: Reason)

    data class Report(
        /** 相册里扫到多少张属于本应用的图 */
        val scanned: Int,
        /** 可以恢复的 */
        val ready: List<Candidate>,
        /** 扫到了但没恢复的 */
        val skipped: List<Skip>
    ) {
        /** 靠标识精确认出来的张数 */
        val exact: Int get() = ready.count { it.match != Match.ENGLISH_NAME }

        /** 靠英文名猜出来的张数 —— 必须在 UI 上单独说明 */
        val guessed: Int get() = ready.count { it.match == Match.ENGLISH_NAME }

        fun skipCount(reason: Reason): Int = skipped.count { it.reason == reason }
    }

    // ==================== 扫描 ====================

    /**
     * 扫相册，给出「哪些能恢复」。
     *
     * @param cards 本地库里的全部卡片（用云端记录 id 做主键，见 [GalleryTag] 的说明）
     */
    suspend fun scan(ctx: Context, cards: List<WordCardEntity>): Report =
        withContext(Dispatchers.IO) {
            val byId = cards.associateBy { it.id }
            // 英文名 → 候选卡片。同一个英文名可能对应多张卡（同一物体拍过多次），
            // 所以 value 是列表，命中多个时上层直接放弃。
            val bySlug = cards.mapNotNull { c ->
                val s = slug(c.nameEnglish)
                if (s.length < 3) null else s to c
            }.groupBy({ it.first }, { it.second })

            val rows = queryImages(ctx)
            val ready = mutableListOf<Candidate>()
            val skipped = mutableListOf<Skip>()
            // 同 cardId + 同风格只收一张：用户可能把同一张图存了三次，
            // 恢复三遍除了浪费 IO 没有意义（字节完全一样）。
            val taken = mutableSetOf<String>()

            for (row in rows) {
                // ---- 1/2. 先找自带标识：EXIF 优先，其次文件名里的 TC 段 ----
                val exifMeta = readExifMeta(ctx, row.uri)
                val nameMeta =
                    if (exifMeta == null) GalleryTag.parseFromName(row.displayName) else null
                val idMeta = exifMeta ?: nameMeta

                val match = when {
                    exifMeta != null -> Match.EXIF
                    nameMeta != null -> Match.FILE_NAME
                    else -> Match.ENGLISH_NAME
                }

                // 卡片与风格。写成 val + 分支内赋值而不是可空 var：
                // 后面有任何一处拿到 null 都意味着这一张不参与恢复，
                // 用 continue 早退比让可空一路传到下面的 Elvis 更清楚。
                val target: WordCardEntity
                val style: AiStyle

                if (idMeta != null) {
                    val c = byId[idMeta.cardId]
                    if (c == null) {
                        skipped += Skip(row.displayName, Reason.UNKNOWN_CARD)
                        continue
                    }
                    target = c
                    style = idMeta.style
                } else {
                    // ---- 3. 老图没有标识 → 只能按英文名猜 ----
                    when (val m = matchByName(row.displayName, bySlug)) {
                        is NameMatch.One -> {
                            target = m.card
                            style = GalleryTag.styleOfName(row.displayName)
                        }
                        NameMatch.Many -> {
                            skipped += Skip(row.displayName, Reason.DUPLICATE_NAME)
                            continue
                        }
                        NameMatch.None -> {
                            skipped += Skip(row.displayName, Reason.UNKNOWN_CARD)
                            continue
                        }
                    }
                }

                val key = "${target.id}|${style.code}"
                if (!taken.add(key)) continue

                // ---- 4. 已是本地的就跳过（不覆盖可能更好的那份） ----
                if (ImageStore.ai(ctx, target.id, style) != null) {
                    skipped += Skip(row.displayName, Reason.ALREADY_LOCAL)
                    continue
                }

                // ---- 5. 尺寸校验：只收全尺寸 ----
                val size = sizeOf(ctx, row.uri)
                if (size == null) {
                    skipped += Skip(row.displayName, Reason.UNREADABLE)
                    continue
                }
                val (w, h) = size
                if (w < MIN_WIDTH || w.toLong() * h < MIN_PIXELS) {
                    Log.d(TAG, "too small ${row.displayName} ${w}x$h")
                    skipped += Skip(row.displayName, Reason.TOO_SMALL)
                    continue
                }

                Log.d(TAG, "found ${row.displayName} ${w}x$h -> card=${target.id} style=${style.code} by=$match")
                ready += Candidate(target.id, style, row.uri, row.displayName, match)
            }

            Log.d(
                TAG,
                "scan: photos=${rows.size} ready=${ready.size} " +
                    "(exact=${ready.count { it.match != Match.ENGLISH_NAME }}, " +
                    "guessed=${ready.count { it.match == Match.ENGLISH_NAME }}) " +
                    "skipped=${skipped.size}"
            )
            Report(rows.size, ready, skipped)
        }

    // ==================== 恢复 ====================

    /**
     * 把一张相册图搬回本地。
     *
     * 复用 [ImageStore.saveAi]：它会同时落全尺寸 + 4:3 卡片用图，
     * 与正常生成走完全同一条路径 —— 这样列表封面、详情页、看图页的行为
     * 与「刚刚生成完」完全一致，不会出现「恢复的图看不了大图」这种分叉。
     *
     * @return 是否成功
     */
    suspend fun restore(ctx: Context, c: Candidate): Boolean = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            ctx.contentResolver.openInputStream(c.uri)?.use { it.readBytes() }
        }.getOrNull()

        if (bytes == null || bytes.isEmpty()) {
            Log.w(TAG, "restore read failed: ${c.displayName}")
            return@withContext false
        }
        val file = ImageStore.saveAi(ctx, c.cardId, c.style, bytes)
        if (file == null) {
            Log.w(TAG, "restore save failed: ${c.displayName} -> ${c.cardId}/${c.style.code}")
            return@withContext false
        }
        Log.d(TAG, "restored ${c.displayName} -> card=${c.cardId} style=${c.style.code} ${bytes.size / 1024}KB")
        true
    }

    // ==================== MediaStore ====================

    private data class Row(val uri: Uri, val displayName: String)

    private fun queryImages(ctx: Context): List<Row> {
        val out = mutableListOf<Row>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )

        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 用 MediaColumns / ImageColumns 显式限定：这些常量在
            // MediaStore.Images.Media 上也能访问，但那是接口继承来的，
            // kotlinc 对继承常量的可见性判断在不同版本上不一致，写全更稳。
            selection = "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?) " +
                "OR (${MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME} = ?)"
            args = arrayOf("%${GallerySaver.ALBUM}%", GallerySaver.ALBUM)
        } else {
            @Suppress("DEPRECATION")
            selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
            args = arrayOf("%/${GallerySaver.ALBUM}/%")
        }

        runCatching {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val name = c.getString(nameIdx) ?: continue
                    out += Row(
                        ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                        ),
                        name
                    )
                }
            }
        }.onFailure {
            // 没授权时 query 会抛 SecurityException。此时不是「相册里没有图」，
            // 而是「看不到」—— 上层据 scanned==0 给出的文案必须留有余地。
            Log.w(TAG, "query failed", it)
        }
        return out
    }

    // ==================== 工具 ====================

    private fun readExifMeta(ctx: Context, uri: Uri): GalleryTag.Meta? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val exif = ExifInterface(ins)
            GalleryTag.parse(exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
                ?: GalleryTag.parse(exif.getAttribute(ExifInterface.TAG_USER_COMMENT))
        }
    }.getOrNull()

    /** 只读尺寸，不把整张图解码到内存 */
    private fun sizeOf(ctx: Context, uri: Uri): Pair<Int, Int>? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(ins, null, o)
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        }
    }.getOrNull()

    private sealed class NameMatch {
        data class One(val card: WordCardEntity) : NameMatch()
        data object None : NameMatch()
        data object Many : NameMatch()
    }

    /**
     * 老图按英文名猜。
     *
     * 文件名形如 `Iced_Drink_高清_12345678.jpg`，slug 化后是 `iceddrink高清12345678`，
     * 而卡片英文名 slug 是 `iceddrink` —— 用「文件名以卡片名开头」来判定。
     *
     * 为什么用 startsWith 而不是相等：文件名后面永远跟着风格段和日期，
     * 剥掉它们需要穷举 17 种界面语言的风格词，不可维护。
     *
     * 命中多张就返回 [NameMatch.Many]，由上层计入「无法确定」而不是随便挑一个。
     */
    private fun matchByName(name: String, bySlug: Map<String, List<WordCardEntity>>): NameMatch {
        val s = slug(name.substringBeforeLast('.'))
        if (s.length < 3) return NameMatch.None

        val hits = bySlug.entries
            .filter { s.startsWith(it.key) }
            .flatMap { it.value }
            .distinctBy { it.id }

        return when (hits.size) {
            0 -> NameMatch.None
            1 -> NameMatch.One(hits.first())
            else -> NameMatch.Many
        }
    }

    /** 只留字母数字并转小写：`Iced Drink` / `iced_drink` / `Iced-Drinks` 视为同一个词 */
    private fun slug(raw: String?): String =
        raw.orEmpty().lowercase().filter { it.isLetterOrDigit() }
}
