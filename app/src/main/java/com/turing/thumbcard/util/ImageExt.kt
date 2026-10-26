package com.turing.thumbcard.util

import android.widget.ImageView
import coil.load
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import com.turing.thumbcard.R
import com.turing.thumbcard.data.db.WordCardEntity
import com.turing.thumbcard.data.image.ImageStore
import java.io.File

/**
 * 图片加载扩展。
 *
 * 云端返回的图片为阿里云 OSS 地址，使用 Coil 加载并启用磁盘缓存。
 */

/**
 * 直接按 URL 加载（没有本地文件时的路径）
 *
 * @param fullSize 解码原尺寸而不是按控件尺寸降采样。**看图页必须开**：
 *   降采样是按「控件多大就解多大」算的，控件 1080 宽、图 2048 宽时会解成 1080，
 *   用户双指放到 5× 看到的就是放大后的 1080 像素 —— 糊，且会得出「高清图没用」的错误结论。
 */
fun ImageView.loadImage(
    url: String?,
    crossfade: Boolean = true,
    fullSize: Boolean = false,
    onSettled: ((Boolean) -> Unit)? = null
) {
    if (url.isNullOrBlank()) {
        setImageResource(R.drawable.ic_image_placeholder)
        onSettled?.invoke(false)
        return
    }
    load(url) {
        if (crossfade) crossfade(true)
        if (fullSize) size(Size.ORIGINAL)
        diskCachePolicy(CachePolicy.ENABLED)
        memoryCachePolicy(CachePolicy.ENABLED)
        placeholder(R.drawable.ic_image_placeholder)
        error(R.drawable.ic_image_placeholder)
        settle(onSettled)
    }
}

/**
 * 按「卡片当前该显示哪张图」加载。
 *
 * 优先级：**已选中的 AI 图 → 本地原图 → 云端 URL**。
 *  - 本地文件优先：AI 图只有本地有（云端没这个接口），原图落过盘就不必再走网络；
 *  - 最后才回退 URL：新卡第一次出现在列表里时本地还没有原图，只能先显示云端的。
 *
 * 只做一次 `exists()` 判断，不做 IO 读取，可以放在 onBindViewHolder 里。
 */
fun ImageView.loadCardImage(card: WordCardEntity) {
    val file = ImageStore.displayFile(context, card.id, card.aiStyle)
    if (file != null) {
        loadFile(file)
    } else {
        loadImage(card.imageUrl)
    }
}

/**
 * 加载本地文件；文件存在但解码失败时退到占位图，不闪空白。
 *
 * 🔴 **必须显式给 memoryCacheKey**，否则「重新生成同一张卡的图」会显示旧图。
 *    Coil 对 `File` 的默认 key 是路径（是否带 lastModified 取决于磁盘缓存策略），
 *    而我们在下面关掉了磁盘缓存 —— 一关，key 就只剩路径：
 *    同一个路径被新内容覆盖后，内存缓存照样命中旧 bitmap，
 *    表现就是「AI 图生成好了，卡片封面还是原来那张」。
 *    把 lastModified 拼进 key，文件一变就是新 key，旧 bitmap 自然失效。
 */
fun ImageView.loadFile(
    file: File,
    crossfade: Boolean = true,
    fullSize: Boolean = false,
    onSettled: ((Boolean) -> Unit)? = null
) {
    load(file) {
        if (crossfade) crossfade(true)
        if (fullSize) size(Size.ORIGINAL)
        memoryCachePolicy(CachePolicy.ENABLED)
        // 本地文件不必再进磁盘缓存，省一份空间（代价见上面的 key 说明）
        diskCachePolicy(CachePolicy.DISABLED)
        memoryCacheKey(MemoryCache.Key("${file.absolutePath}:${file.lastModified()}"))
        placeholder(R.drawable.ic_image_placeholder)
        error(R.drawable.ic_image_placeholder)
        settle(onSettled)
    }
}

/**
 * 图片「有结果了」的回调：成功传 true、失败传 false，**两种都会回调一次**。
 *
 * 为什么必须区分成败（调用方是大图页切卡）：
 *  · 成功 → 才可以淡入新图（先淡入一张还没解码的图，看到的是黑屏）；
 *  · 失败 → 必须换成「点击重试」占位，而不是继续留着一张旧卡的照片冒充新卡。
 *
 * 为什么失败也要回调：只挂成功回调的话，一张加载失败的图会永远停在半透明上。
 *
 * 线程：Coil 的这两个回调都在主线程，可直接改 View。
 */
private fun ImageRequest.Builder.settle(onSettled: ((Boolean) -> Unit)?) {
    if (onSettled == null) return
    listener(
        onSuccess = { _, _ -> onSettled(true) },
        onError = { _, _ -> onSettled(false) }
    )
}

/** 通用入口：传 File 走本地，传 String 走 URL，传 null 走占位图 */
fun ImageView.loadAny(
    model: Any?,
    crossfade: Boolean = true,
    fullSize: Boolean = false,
    onSettled: ((Boolean) -> Unit)? = null
) {
    when (model) {
        is File -> loadFile(model, crossfade, fullSize, onSettled)
        is String -> loadImage(model, crossfade, fullSize, onSettled)
        else -> {
            setImageResource(R.drawable.ic_image_placeholder)
            onSettled?.invoke(false)
        }
    }
}
