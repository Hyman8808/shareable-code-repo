package com.turing.thumbcard.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 单词卡实体。
 *
 * 由云端记录转换而来，落本地库以支持离线查看。
 */
@Entity(tableName = "word_card")
data class WordCardEntity(
    /** 云端记录 id，作为主键用于去重 */
    @PrimaryKey val id: Long,
    val deviceId: String,
    val nameChinese: String?,
    val nameEnglish: String?,
    val pinyin: String?,
    val phonetic: String?,
    val description: String?,
    val imageUrl: String?,
    val ttsChineseUrl: String?,
    val ttsEnglishUrl: String?,
    /** 云端 create_time 原文，"yyyy-MM-dd HH:mm:ss" */
    val createTime: String?,
    /** 本地写入时间，用于排序兜底 */
    val savedAt: Long,
    /**
     * 是否已收藏。
     *
     * 纯本地状态（云端无该字段），决定这张卡片是否进入「单词卡」页：
     *  - true  → 进入单词卡，算作「学会了」
     *  - false → 只留在首页「最近拍的」，不占卡片位
     *
     * ⚠️ 同步时用 REPLACE 落库，会被整行覆盖 → [com.turing.thumbcard.data.repo.RecordRepository.sync]
     * 必须先读出现有收藏 id 再回填，否则每次同步收藏都会掉。
     *
     * ⚠️ `defaultValue = "0"` 必须声明：v1→v2 的 ALTER TABLE 带了 `DEFAULT 0`，
     * Room 启动时做 schema 校验，实体侧不声明默认值会报
     * "Migration didn't properly handle: word_card"。
     */
    @ColumnInfo(defaultValue = "0")
    val favorite: Boolean = false,

    /**
     * 已经生成过的 AI 图风格（见 [com.turing.thumbcard.data.ai.AiStyle.code]），null = 没生成过。
     *
     * 只存风格不存路径：文件路径可由 `cardId + style` 推导（[com.turing.thumbcard.data.image.ImageStore]），
     * 存绝对路径反而会因为应用数据目录变化（换机恢复、系统迁移）而失效。
     *
     * 同时**只保留一张**：用户重生成时会覆盖同一风格的文件，
     * 换来换去只改本字段，不会在磁盘上堆一堆历史版本。
     */
    val aiStyle: String? = null,

    /** AI 图生成时间（毫秒），null = 没生成过 */
    val aiAt: Long? = null
)

/**
 * 已绑定设备。
 *
 * 注：apikey 整批公用，存于 [com.turing.thumbcard.util.CredentialStore]，
 * 此表只记录设备维度信息。
 */
@Entity(tableName = "bound_device")
data class BoundDeviceEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String?,
    val firmwareVersion: Int?,
    val bindTime: Long,
    val lastSyncTime: Long?
)

/**
 * 只读投影：同步前把「哪些卡已经有 AI 图」捞出来回填。
 *
 * 与 [WordCardDao.favoriteIds] 同一个用意 —— `upsertAll` 是 REPLACE，
 * 而云端数据里没有 aiStyle/aiAt 两个字段，不回填就会被清成 null，
 * 表现是「同步一次，花过钱的图全退回原图」。
 */
data class CardAiState(
    val id: Long,
    val aiStyle: String?,
    val aiAt: Long?
)

@Dao
interface WordCardDao {

    @Query("SELECT * FROM word_card WHERE deviceId = :deviceId ORDER BY id DESC")
    suspend fun listByDevice(deviceId: String): List<WordCardEntity>

    /**
     * 全部卡片（跨设备）。
     *
     * 只服务「从相册找回 AI 图」：找回是按**云端记录 id** 全局匹配的，
     * 不需要知道这张卡是哪台相机拍的 —— id 本身跨设备唯一。
     */
    @Query("SELECT * FROM word_card")
    suspend fun listAll(): List<WordCardEntity>

    /**
     * 全部卡片（跨设备），按 id 倒序。
     *
     * 服务「全部单词记录」页的「全部」筛选：这里必须显式排序，
     * 不能指望 SQLite 的默认返回顺序 —— 合并多台设备后，行序会按插入顺序
     * 变成「设备 A 的一批 + 设备 B 的一批」，看起来像没排序。
     */
    @Query("SELECT * FROM word_card ORDER BY id DESC")
    suspend fun listAllOrdered(): List<WordCardEntity>

    /** 单词卡页：只取已收藏 */
    @Query("SELECT * FROM word_card WHERE deviceId = :deviceId AND favorite = 1 ORDER BY id DESC")
    suspend fun listFavorites(deviceId: String): List<WordCardEntity>

    /** 同步前读取已收藏 id，用于 REPLACE 回填（否则收藏会被云端数据覆盖掉） */
    @Query("SELECT id FROM word_card WHERE deviceId = :deviceId AND favorite = 1")
    suspend fun favoriteIds(deviceId: String): List<Long>

    /** 同步前读取已生成的 AI 图标记，用于 REPLACE 回填（否则同步一次 AI 图全丢） */
    @Query("SELECT id, aiStyle, aiAt FROM word_card WHERE deviceId = :deviceId AND aiStyle IS NOT NULL")
    suspend fun aiStates(deviceId: String): List<CardAiState>

    @Query("SELECT favorite FROM word_card WHERE id = :id LIMIT 1")
    suspend fun isFavorite(id: Long): Boolean?

    @Query("UPDATE word_card SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    /**
     * 记下这张卡用的是哪张 AI 图。
     *
     * 传 null 即「回退原图」（清掉 AI 标记，文件留着，下次还能切回来）。
     */
    @Query("UPDATE word_card SET aiStyle = :style, aiAt = :at WHERE id = :id")
    suspend fun setAiStyle(id: Long, style: String?, at: Long?)

    @Query("SELECT * FROM word_card WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): WordCardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(cards: List<WordCardEntity>)

    @Query("SELECT MAX(id) FROM word_card WHERE deviceId = :deviceId")
    suspend fun maxId(deviceId: String): Long?

    /**
     * 本地已有的记录 id 集合。
     *
     * 用于增量同步：**已存在的记录不再重复落库**，
     * 并且整页都命中时可以直接停止翻页（接口没有增量参数，这是唯一可行的省流手段）。
     */
    @Query("SELECT id FROM word_card WHERE deviceId = :deviceId")
    suspend fun idsByDevice(deviceId: String): List<Long>

    @Query("SELECT COUNT(*) FROM word_card WHERE deviceId = :deviceId")
    suspend fun countByDevice(deviceId: String): Int

    @Query("DELETE FROM word_card WHERE deviceId = :deviceId")
    suspend fun clearByDevice(deviceId: String)

    @Query("DELETE FROM word_card WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface BoundDeviceDao {

    @Query("SELECT * FROM bound_device ORDER BY bindTime DESC")
    suspend fun listAll(): List<BoundDeviceEntity>

    @Query("SELECT * FROM bound_device WHERE deviceId = :deviceId LIMIT 1")
    suspend fun find(deviceId: String): BoundDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: BoundDeviceEntity)

    @Query("DELETE FROM bound_device WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)
}

@Database(
    entities = [WordCardEntity::class, BoundDeviceEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun wordCardDao(): WordCardDao
    abstract fun boundDeviceDao(): BoundDeviceDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v1 → v2：word_card 增加 favorite 列。
         *
         * ⚠️ 必须显式写迁移，**不能靠 fallbackToDestructiveMigration 兜底**：
         * 重建库会把 bound_device 一起清掉，用户升级后就得重新配网。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE word_card ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v2 → v3：word_card 增加 aiStyle / aiAt（AI 美化图标记）。
         *
         * 两列都可空、无默认值 —— 实体侧也必须是 `String? = null` / `Long? = null`，
         * 否则 Room 的 schema 校验对不上（列类型/可空性必须逐项一致）。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE word_card ADD COLUMN aiStyle TEXT")
                db.execSQL("ALTER TABLE word_card ADD COLUMN aiAt INTEGER")
            }
        }

        /** 上次初始化失败的原因；null 表示正常 */
        @Volatile
        var lastInitError: String? = null
            private set

        /**
         * 获取数据库实例。
         *
         * ⚠️ 在华为鸿蒙 NEXT + 卓易通等 Android 兼容容器中，
         * SQLite / 文件系统能力可能受限，`build()` 可能抛出
         * `SQLiteException`、`IllegalStateException` 等。
         *
         * 为保证 App 不闪退，此方法在失败时**返回 null**，
         * 由调用方决定降级行为（例如仅浏览云端数据、不做本地缓存）。
         * 调用方必须处理 null。
         */
        fun get(context: Context): AppDatabase? {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: try {
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "thumbcard.db"
                    )
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                        .fallbackToDestructiveMigration()
                        .build()
                        .also {
                            instance = it
                            lastInitError = null
                        }
                } catch (t: Throwable) {
                    // 记录但不抛出：让上层以"无本地缓存"模式继续运行
                    lastInitError = "${t.javaClass.simpleName}: ${t.message}"
                    android.util.Log.e(TAG, "database init failed: $lastInitError")
                    null
                }
            }
        }

        /** 数据库是否可用 */
        val isAvailable: Boolean get() = instance != null

        private const val TAG = "AppDatabase"
    }
}
