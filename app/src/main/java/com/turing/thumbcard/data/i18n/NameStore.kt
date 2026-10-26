package com.turing.thumbcard.data.i18n

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

/**
 * 译名本地缓存：`filesDir/i18n/<md5(cardId|lang)>.json`
 *
 * 与 [com.turing.thumbcard.data.dict.DictCache] 同一思路：
 *  - 不用 Room（加表会清空 bound_device，导致用户重新配网）
 *  - 文件存在 filesDir，不会被系统当缓存清掉
 *  - 翻译是派生数据，丢了随时能重新拉，风险为零
 *
 * 有效期 30 天：译名一旦生成基本不会变，但语言模型迭代时允许自然过期重拉。
 * **「确认无译名」也缓存**（负缓存 7 天），否则每次打开都要对同一个词发起请求。
 */
object NameStore {

    private const val TAG = "NameStore"
    private const val DIR = "i18n"
    private val TTL_HIT = 30L * 24 * 3600 * 1000
    private val TTL_MISS = 7L * 24 * 3600 * 1000

    private val gson = Gson()

    @Volatile
    private var dir: File? = null

    /** 查询结果三态：有译名 / 确认无译名 / 未缓存 */
    sealed class Lookup {
        data class Hit(val text: String?) : Lookup()
        object Miss : Lookup()
    }

    private data class Record(
        val t: Long = 0L,
        /** 是否有译名；false 表示服务端明确回复"该词无此语言译名" */
        val ok: Boolean = false,
        val text: String? = null,
        /** 译名基于哪个原文（中文变了要作废） */
        val src: String? = null
    )

    fun init(context: Context) {
        try {
            dir = File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }
        } catch (t: Throwable) {
            Log.w(TAG, "init failed: ${t.javaClass.simpleName}")
            dir = null
        }
    }

    /**
     * 读缓存。
     *
     * @param source 当前中文原文；与缓存时的原文不一致则视为过期
     *               （防止同一 id 的中文名被修正后仍用旧译名）
     */
    fun read(context: Context, cardId: Long, lang: NameLanguage, source: String?): Lookup {
        val f = fileOf(context, cardId, lang) ?: return Lookup.Miss
        if (!f.exists()) return Lookup.Miss
        return try {
            val rec = gson.fromJson(f.readText(), Record::class.java) ?: return Lookup.Miss
            val age = System.currentTimeMillis() - rec.t
            val ttl = if (rec.ok) TTL_HIT else TTL_MISS
            if (age < 0 || age > ttl || rec.src != source) {
                f.delete()
                Lookup.Miss
            } else {
                Lookup.Hit(if (rec.ok) rec.text else null)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "read failed: ${t.javaClass.simpleName}")
            runCatching { f.delete() }
            Lookup.Miss
        }
    }

    /** 写缓存；[text] 为 null 表示「确认无译名」 */
    fun write(context: Context, cardId: Long, lang: NameLanguage, source: String?, text: String?) {
        val f = fileOf(context, cardId, lang) ?: return
        try {
            f.writeText(
                gson.toJson(
                    Record(
                        t = System.currentTimeMillis(),
                        ok = text != null,
                        text = text,
                        src = source
                    )
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "write failed: ${t.javaClass.simpleName}")
        }
    }

    fun clear() {
        runCatching { dir?.listFiles()?.forEach { it.delete() } }
    }

    fun sizeBytes(): Long =
        runCatching { dir?.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    private fun fileOf(context: Context, cardId: Long, lang: NameLanguage): File? {
        val base = dir ?: run {
            init(context)
            dir
        } ?: return null
        val key = md5("$cardId|${lang.code}")
        return File(base, "$key.json")
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
