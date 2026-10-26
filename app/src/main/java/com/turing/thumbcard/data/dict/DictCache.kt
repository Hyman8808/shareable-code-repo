package com.turing.thumbcard.data.dict

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

/**
 * 词典查询结果的**本地缓存**。
 *
 * 为什么需要：词典走 WebSocket，一次查询 0.2~0.8s，而卡片详情页每次进入都会查
 * 「英文词 + 汉语词」（复合词还要逐字查，最多 8 次请求）。同一个词被反复打开时
 * 全部重查既慢又费流量。缓存后第二次打开是**毫秒级**，且断网也能看。
 *
 * 存储方式：**文件缓存**，不用 Room。
 *  - Room 当前 version=1 且启用了 destructive migration，加表会清空已有卡片记录；
 *  - 词典数据是"只读派生数据"，丢了随时可重查，放文件里最省事、零迁移风险。
 *
 * 目录：`filesDir/dict/<md5>.json`（filesDir 不会被系统当缓存清掉）
 *
 * 有效期：
 *  - 命中词条 [TTL_HIT]（30 天）—— 词典内容很少变动
 *  - 未收录 [TTL_MISS]（7 天）—— 避免每次打开「立白洗洁精」都重查 5 个汉字
 */
object DictCache {

    private const val TAG = "DictCache"
    private const val DIR = "dict"

    /** 缓存键版本：字段结构变化时 +1（v2：新增美/英音标分开存储） */
    private const val KEY_VERSION = "v2"
    private val TTL_HIT = 30L * 24 * 3600 * 1000
    private val TTL_MISS = 7L * 24 * 3600 * 1000

    private val gson = Gson()

    @Volatile
    private var dir: File? = null

    /** 命中结果；[entry] 为 null 表示"已确认查不到"（负缓存） */
    data class Hit(val entry: DictEntry?)

    /** 落盘结构，带写入时间用于判过期 */
    private data class Record(
        val t: Long = 0L,
        val hit: Boolean = false,
        val e: DictEntry? = null
    )

    /** 在 Application 里调一次；失败也只是不启用缓存，不影响功能 */
    fun init(context: Context) {
        try {
            dir = File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }
        } catch (t: Throwable) {
            Log.w(TAG, "init failed: ${t.javaClass.simpleName}")
            dir = null
        }
    }

    fun keyOf(content: String, dicts: List<String>?): String {
        // 带版本号：词条结构变更（如 v2 新增 phoneticUs）后旧缓存自动失效，
        // 否则老缓存反序列化出的新字段是 null，界面会「只剩英音」而看不出原因。
        val norm = "$KEY_VERSION|" + content.trim().lowercase() + "|" +
            (dicts?.joinToString(",") ?: "default")
        return md5(norm)
    }

    /** 读缓存，未命中或已过期返回 null */
    fun read(key: String): Hit? {
        val f = fileOf(key) ?: return null
        if (!f.exists()) return null
        return try {
            val rec = gson.fromJson(f.readText(), Record::class.java) ?: return null
            val age = System.currentTimeMillis() - rec.t
            val ttl = if (rec.hit) TTL_HIT else TTL_MISS
            if (age < 0 || age > ttl) {
                f.delete()
                null
            } else {
                Hit(if (rec.hit) rec.e else null)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "read failed: ${t.javaClass.simpleName}")
            runCatching { f.delete() }
            null
        }
    }

    /** 写缓存（entry 为 null 表示"查不到"，同样缓存） */
    fun write(key: String, entry: DictEntry?) {
        val f = fileOf(key) ?: return
        try {
            val rec = Record(
                t = System.currentTimeMillis(),
                hit = entry != null,
                e = entry
            )
            f.writeText(gson.toJson(rec))
        } catch (t: Throwable) {
            Log.w(TAG, "write failed: ${t.javaClass.simpleName}")
        }
    }

    /** 清空缓存（诊断页 / 需要强制刷新时用） */
    fun clear() {
        runCatching { dir?.listFiles()?.forEach { it.delete() } }
    }

    fun sizeBytes(): Long =
        runCatching { dir?.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    private fun fileOf(key: String): File? = dir?.let { File(it, "$key.json") }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
