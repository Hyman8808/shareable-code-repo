package com.turing.thumbcard.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.turing.thumbcard.data.image.GalleryTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把本地图片存进系统相册。
 *
 * 为什么必须分两条路：
 *  - **Android 10（API 29）起**：分区存储，只能通过 MediaStore 写，
 *    好处是**不需要任何权限**，`RELATIVE_PATH` 直接指定 `Pictures/单词卡相机/`。
 *  - **Android 9 及以下**：MediaStore 不认 RELATIVE_PATH，得自己写文件到
 *    `Pictures/单词卡相机/` 再通知媒体扫描，且**需要 WRITE_EXTERNAL_STORAGE**。
 *
 * ⚠️ `IS_PENDING` 三步（插入 → 写入 → 置 0）不是可选项：
 *    不置 pending 的话，相册 App 可能在你还没写完时就去读，显示一张半截图。
 */
object GallerySaver {

    private const val TAG = "GallerySaver"

    /** 相册子目录名。找回功能（GalleryRestore）也按这个名字筛，改要一起改 */
    internal const val ALBUM = "单词卡相机"

    /** EXIF 里写死的软件名，便于在电脑上「属性」里认出来 */
    private const val SOFTWARE = "ThumbCard"

    sealed class Result {
        /** 已存入相册 */
        data object Ok : Result()

        /** Android 9 及以下需要存储权限，调用方先申请再重试 */
        data object NeedPermission : Result()

        data class Err(val reason: String?) : Result()
    }

    /** 这个系统版本存相册是否需要运行时权限 */
    fun needsPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /**
     * @param src       本地图片文件
     * @param baseName  展示名（不含扩展名），会被清理掉路径分隔符等非法字符
     * @param meta      打在图上的身份标识；为 null 则存一张「认不回来」的图
     *                  （只有调试/分享场景才该传 null）
     */
    suspend fun save(
        ctx: Context,
        src: File,
        baseName: String,
        meta: GalleryTag.Meta? = null
    ): Result =
        withContext(Dispatchers.IO) {
            try {
                val raw = src.readBytes()
                if (raw.isEmpty()) return@withContext Result.Err("文件为空")

                // 先打标再落盘：EXIF 必须写进**最终那份字节**里，
                // 落盘后再去改 MediaStore 里的文件会多一次读写，且 API 29 起
                // 改别人的条目还要 Uri 权限，得不偿失。
                val bytes = if (meta != null) tag(ctx, raw, meta) else raw
                val name = buildName(baseName, meta)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveViaMediaStore(ctx, bytes, name)
                } else {
                    saveLegacy(ctx, bytes, name)
                }
            } catch (e: SecurityException) {
                Result.NeedPermission
            } catch (e: Exception) {
                Result.Err(e.message)
            }
        }

    /**
     * 文件名：`{英文名}_{风格}_TC{cardId}_{yyyyMMdd}.jpg`
     *
     * 三个槽位各有用途：
     *  - 英文名/风格 → 用户在系统相册里一眼能知道这是哪张词卡，不是一串乱码；
     *  - `TC{cardId}` → 找回时的快速索引，一次 MediaStore 查询就能筛出来；
     *  - 日期 → 同一张卡反复存不会覆盖（配合 MediaStore 自动加的 (1)(2)）。
     *
     * ⚠️ 不用「末尾 8 位时间戳」当 id 猜测依据：老版本就是这么存盘的，
     * 那样每张老图都会被安上一个假 id。标识必须是**显式标记**。
     */
    private fun buildName(baseName: String, meta: GalleryTag.Meta?): String {
        val base = sanitize(baseName)
        val tagPart = meta?.let { "_" + GalleryTag.nameTag(it.cardId) } ?: ""
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return "$base$tagPart${"_"}$day.jpg"
    }

    /**
     * 把身份标识写进 JPEG 的 EXIF。
     *
     * 写两个字段是刻意的：`ImageDescription` 会被大部分相册/修图工具保留，
     * 而 `UserComment` 是 EXIF 规范里给「用户备注」的地方、某些图库同步服务
     * 会单独处理它 —— 任一处被洗掉，另一处还能救回来。
     *
     * 实现上必须先落一个临时文件：`ExifInterface` 的 `saveAttributes()`
     * 只支持「文件/文件描述符」形式，用 InputStream 构造出来的实例是只读的。
     * 失败时**退回原字节**（宁可图认不回来，也不能因为写标识把图弄坏或让存图失败）。
     */
    private fun tag(ctx: Context, bytes: ByteArray, meta: GalleryTag.Meta): ByteArray {
        val tmp = File(ctx.cacheDir, "gal_tag_${System.nanoTime()}.jpg")
        return try {
            tmp.writeBytes(bytes)
            val exif = ExifInterface(tmp.absolutePath)
            val value = GalleryTag.encode(meta)
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, value)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, value)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, SOFTWARE)
            // ⚠️ 1.3.x 的 saveAttributes() 返回 void（Unit），拿它当条件判断会编译失败。
            // 写完直接看文件：长度 > 0 才认为确实落了盘。
            exif.saveAttributes()
            if (tmp.length() > 0) tmp.readBytes() else bytes
        } catch (e: Exception) {
            Log.w(TAG, "exif tag failed, save untagged", e)
            bytes
        } finally {
            runCatching { tmp.delete() }
        }
    }

    // ==================== API 29+ ====================

    private fun saveViaMediaStore(ctx: Context, bytes: ByteArray, name: String): Result {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + ALBUM
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return Result.Err("无法创建相册条目")

        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return Result.Err("无法写入相册")

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Result.Ok
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            Result.Err(e.message)
        }
    }

    // ==================== API ≤ 28 ====================

    private fun saveLegacy(ctx: Context, bytes: ByteArray, name: String): Result {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM
        )
        if (!dir.exists() && !dir.mkdirs()) return Result.Err("无法创建相册目录")

        val out = File(dir, name)
        out.writeBytes(bytes)

        // 通知媒体库收录，否则「相册」里看不到
        MediaScannerConnection.scanFile(
            ctx, arrayOf(out.absolutePath), arrayOf("image/jpeg"), null
        )
        return Result.Ok
    }

    private fun sanitize(raw: String): String {
        val cleaned = raw.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").trim('_')
        return cleaned.take(24).ifBlank { "卡片" }
    }
}
