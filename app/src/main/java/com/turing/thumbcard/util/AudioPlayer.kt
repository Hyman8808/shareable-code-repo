package com.turing.thumbcard.util

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.net.URL

/**
 * TTS 音频播放器，带本地缓存。
 *
 * 缓存策略：URL 的 MD5 作为文件名，存放于 cacheDir/audio。
 * 播放时优先使用本地文件，未命中则在线播放并异步缓存。
 */
class AudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val CACHE_DIR = "audio"
    }

    private var player: MediaPlayer? = null
    private var currentUrl: String? = null

    /** 播放状态回调：true=开始播放，false=停止/结束 */
    var onPlayingChanged: ((Boolean) -> Unit)? = null

    /**
     * 播放指定音频。
     *
     * @return true 表示已开始播放
     */
    fun play(url: String?): Boolean {
        if (url.isNullOrBlank()) {
            Log.w(TAG, "empty url")
            return false
        }
        stop()
        currentUrl = url
        val cached = cacheFileOf(url)
        val source = if (cached.exists() && cached.length() > 0) {
            cached.absolutePath
        } else {
            url
        }
        return try {
            player = MediaPlayer().apply {
                setDataSource(source)
                setOnPreparedListener {
                    it.start()
                    onPlayingChanged?.invoke(true)
                }
                setOnCompletionListener {
                    onPlayingChanged?.invoke(false)
                    currentUrl = null
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "playback error what=$what extra=$extra")
                    onPlayingChanged?.invoke(false)
                    true
                }
                prepareAsync()
            }
            // 未命中缓存则后台下载
            if (source == url) cacheAsync(url)
            true
        } catch (e: Exception) {
            Log.e(TAG, "play failed: $url", e)
            onPlayingChanged?.invoke(false)
            false
        }
    }

    /** 是否正在播放 */
    fun isPlaying(): Boolean = try {
        player?.isPlaying == true
    } catch (e: Exception) {
        false
    }

    /** 当前播放地址 */
    fun currentPlayingUrl(): String? = currentUrl

    fun stop() {
        try {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "stop failed", e)
        }
        player = null
        currentUrl = null
        onPlayingChanged?.invoke(false)
    }

    fun release() = stop()

    // ==================== 缓存 ====================

    private fun cacheDir(): File =
        File(context.cacheDir, CACHE_DIR).apply { if (!exists()) mkdirs() }

    private fun cacheFileOf(url: String): File =
        File(cacheDir(), md5(url) + ".mp3")

    /**
     * 后台下载并写入缓存。失败静默处理，不阻塞播放。
     */
    private fun cacheAsync(url: String) = Thread {
        val target = cacheFileOf(url)
        if (target.exists() && target.length() > 0) return@Thread
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            URL(url).openStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (tmp.renameTo(target)) {
                Log.d(TAG, "cached: ${target.name}")
            } else {
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cache failed: $url", e)
            tmp.delete()
        }
    }.apply { isDaemon = true }.start()

    private fun md5(s: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** 清理音频缓存 */
    fun clearCache() {
        cacheDir().listFiles()?.forEach { it.delete() }
    }
}
