package com.turing.thumbcard.data.repo

import android.content.Context
import android.util.Log
import com.turing.thumbcard.data.api.ApiCode
import com.turing.thumbcard.data.api.RecordApi
import com.turing.thumbcard.data.api.RecordItem
import com.turing.thumbcard.data.db.AppDatabase
import com.turing.thumbcard.data.db.WordCardEntity
import com.turing.thumbcard.data.i18n.NameLanguage
import com.turing.thumbcard.data.i18n.NameStore
import com.turing.thumbcard.util.CredentialStore
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 同步范围 —— 直接决定请求量与首屏延迟。
 *
 * ⚠️ 云端**没有增量参数**（无 since_id / since_time），能做的只有：
 *   ① 已存在的记录不再落库；
 *   ② 整页都落在「上次已拉取过的 id 边界」内时停止翻页。
 * 所以「不重复拉取」是靠 [RecordRepository.sync] 的边界判断实现的，不是接口能力。
 */
enum class SyncScope {
    /**
     * 首页 / 单词卡：只拉最新一页（最多 100 条），不翻页。
     *
     * 首页只展示「今天拍的」、单词卡只展示收藏，都不需要历史全量；
     * 进 App 只付 1 次请求，而不是翻十几页。
     */
    LATEST,

    /**
     * 全部照片页：从最新翻到「已拉取过的边界」，最多 [RecordRepository.MAX_PAGES] 页。
     */
    ALL
}

/**
 * 记录同步结果。
 */
sealed class SyncResult {
    /**
     * 成功。
     *
     * @param added     本次新增条数
     * @param total     本地总条数
     * @param cloudTotal 云端该 deviceId 下的记录总数（用于区分"没拍"与"没上传"）
     * @param deviceId  实际查询用的 deviceId
     * @param fetched   本次实际从云端拿到的条数（含本地已有的）
     * @param skipped   其中本地已有、被跳过未重复保存的条数
     * @param pages     实际请求页数
     * @param stoppedByLocal true 表示因「整页本地已有」提前停止翻页
     */
    data class Success(
        val added: Int,
        val total: Int,
        val cloudTotal: Int = 0,
        val deviceId: String = "",
        val fetched: Int = 0,
        val skipped: Int = 0,
        val pages: Int = 0,
        val stoppedByLocal: Boolean = false
    ) : SyncResult()

    /** 凭据无效（4005），需重新配网 */
    data class InvalidCredential(val message: String) : SyncResult()

    /** 网络或其他错误 */
    data class Failure(val message: String) : SyncResult()
}

/**
 * 单词卡仓库：负责从云端拉取记录并落本地库。
 *
 * ⚠️ 接口局限（实测确认）：
 *  - 不支持增量参数（无 since_id / since_time）→ 用「本地已有 id 跳过 + 全量边界 id 停页」
 *    模拟增量，见 [SyncScope] 与 [sync]
 *  - 排序规则未在文档中声明，本地一律按 id 倒序展示
 *  - 不存在的 deviceId 返回 200 + 空列表，无法据此判断设备真实性
 *
 * ⚠️ 兼容性：本地库不可用时（如卓易通容器），[sync] 仍会拉取云端数据，
 *    但仅保存在内存中（[lastCloudCache]），不落库，绝不抛异常。
 */
class RecordRepository(context: Context) {

    companion object {
        private const val TAG = "RecordRepository"
        private const val PAGE_SIZE = 100          // 服务端上限
        private const val MAX_PAGES = 20           // 安全上限，防止死循环

        /** 首页「今天全部」最多翻 3 页（300 条），防止 create_time 异常时死翻 */
        private const val LATEST_MAX_PAGES = 3

        /** 与 BuildConfig.RECORD_BASE_URL 保持一致；此处显式声明以便仓库层独立可用 */
        const val BASE_URL = "https://nglearn.turingapi.com/"
    }

    private val db: AppDatabase? = AppDatabase.get(context)

    /** 用于写译名缓存（译名随记录一起下发时不额外请求） */
    private val appContext: Context = context.applicationContext

    /** 临时模式下的 apikey 覆盖（存储不可用时使用） */
    private var apiKeyOverride: String? = null

    /** 设置临时 apikey（用于离线演示模式） */
    fun setApiKeyOverride(key: String?) {
        apiKeyOverride = key
    }

    /** 取当前生效的 apikey：优先临时覆盖，其次持久化存储 */
    private fun effectiveApiKey(): String? =
        apiKeyOverride?.takeIf { it.isNotBlank() } ?: CredentialStore.getApiKey()

    /** 数据库不可用时的内存缓存（按 deviceId 分组） */
    private val cloudCache = HashMap<String, List<WordCardEntity>>()

    /** 本地存储是否可用 */
    val isStorageAvailable: Boolean get() = db != null

    private val api: RecordApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(RecordApi::class.java)

    /**
     * 同步指定设备的记录。
     *
     * 增量策略（接口无 since_id，只能这样省）：
     *  1. 先取本地已有 id 集合，**本地已有的记录不再重复保存**（[SyncResult.Success.skipped] 会报数）；
     *  2. [SyncScope.ALL]：完成过一次全量拉取后，**整页记录都已在本地**即停止翻页
     *     （云端按 id 倒序，新记录只会出现在最前面）；
     *  3. [SyncScope.LATEST]：从最新往后翻，跨过「今天」为止，最多 [LATEST_MAX_PAGES] 页。
     *
     * @param langCode 名称语言代码。**默认 null = 不向云端传该参数**，
     *                 保持与服务端现状完全一致；翻译接口就绪后由上层传入，
     *                 服务端会按该语言下发 `name_localized`，此时自动写入译名缓存，
     *                 UI 无需再做改动。
     * @param scope    同步范围，见 [SyncScope]
     */
    suspend fun sync(
        deviceId: String,
        langCode: String? = null,
        scope: SyncScope = SyncScope.ALL
    ): SyncResult {
        val apiKey = effectiveApiKey()
            ?: return SyncResult.InvalidCredential("本地未保存凭据，请先完成设备配网")

        val dao = db?.wordCardDao()

        // 本地已知 id —— 增量判据。读失败就退化成空集合（等价全量，只多花流量，不会漏数据）
        val knownIds = HashSet<Long>()
        runCatching { dao?.idsByDevice(deviceId) }.getOrNull()?.let { knownIds.addAll(it) }
        if (knownIds.isEmpty()) {
            cloudCache[deviceId].orEmpty().mapTo(knownIds) { it.id }
        }

        // 是否完成过一次完整全量拉取：决定「整页本地已有即可停」这个判据是否可信
        // （首次全量之前不能停，否则首页只拉过 1 页时点「全部」会立刻误停）
        val fullDone = if (scope == SyncScope.ALL) isFullDone(deviceId) else false

        val fetched = ArrayList<RecordItem>()
        var page = 1
        var total = Int.MAX_VALUE
        var pages = 0
        var stoppedByLocal = false
        var partial = false
        val todayStart = StudyStats.startOfToday()

        try {
            while (page <= MAX_PAGES) {
                // 埋点：产品要求「全球访问 2 秒内」，App 侧必须能量出自己这一段。
                // 只算请求+解析，不含渲染 —— 渲染慢是另一类问题，混在一起没法定位。
                val t0 = System.currentTimeMillis()
                val resp = api.list(apiKey, deviceId, page, PAGE_SIZE, langCode)
                LatencyStore.recordListRequest(appContext, System.currentTimeMillis() - t0)

                if (resp.code == ApiCode.APIKEY_INVALID) {
                    return SyncResult.InvalidCredential("凭据无效（4005），请重新配网")
                }
                if (resp.code != ApiCode.SUCCESS) {
                    return SyncResult.Failure("接口返回 ${resp.code}：${resp.message ?: "未知"}")
                }

                val data = resp.data ?: break
                total = data.total
                if (data.list.isEmpty()) break
                pages++

                fetched.addAll(data.list)

                // 增量停止：整页记录本地都已经有了（且此前确实完整拉过一次）
                // 云端按 id 倒序，新记录只会出现在最前面，所以「整页命中」= 更老的也都在本地
                if (fullDone && data.list.all { it.id in knownIds }) {
                    stoppedByLocal = true
                    break
                }

                if (scope == SyncScope.LATEST) {
                    // 首页要的是「今天的全部」：一旦本页出现今天之前的记录，说明今天已经取完
                    if (pages >= LATEST_MAX_PAGES) break
                    if (containsOlderThanToday(data.list, todayStart)) break
                    if (fetched.size >= total) break
                    page++
                    continue
                }

                if (fetched.size >= total) break              // 已取完
                page++
            }
        } catch (e: Exception) {
            Log.e(TAG, "sync failed", e)
            // 已经拿到一部分就先落库，比全丢好（下次会接着补）
            if (fetched.isEmpty()) {
                return SyncResult.Failure("网络异常：${e.message ?: "未知错误"}")
            }
            partial = true
        }

        // 服务端已按语言下发名称 → 顺手写进译名缓存（零额外请求）。
        // 这里对**整页数据**都写，这样切换语言后老卡片也能刷新译名。
        if (langCode != null) {
            persistLocalizedNames(fetched, langCode)
        }

        // 只保存本地没有的 —— 已下载过的直接跳过，不重复写库
        val fresh = fetched.filter { it.id !in knownIds }
        val entities = fresh.map { it.toEntity(deviceId) }
        val reportedTotal = if (total == Int.MAX_VALUE) fetched.size else total

        // 完整走完（非中途异常）才把「已全量拉取过」记下来，避免半途失败后下次直接停
        if (scope == SyncScope.ALL && !partial) {
            markFullDone(deviceId)
        }

        // ---- 落库（数据库不可用时降级为内存缓存） ----
        if (dao == null) {
            val prev = cloudCache[deviceId].orEmpty()
            val existing = prev.mapTo(HashSet()) { it.id }
            val merged = ArrayList(prev)
            merged.addAll(entities.filter { it.id !in existing })
            // 内存模式下收藏只在本次运行有效，保持已有标记
            merged.sortByDescending { it.id }
            cloudCache[deviceId] = merged
            Log.d(TAG, "sync(memory) scope=$scope fetched=${fetched.size} fresh=${entities.size}")
            return SyncResult.Success(
                added = entities.size, total = merged.size,
                cloudTotal = reportedTotal, deviceId = deviceId,
                fetched = fetched.size, skipped = fetched.size - entities.size,
                pages = pages, stoppedByLocal = stoppedByLocal
            )
        }

        return try {
            val before = dao.countByDevice(deviceId)

            if (entities.isNotEmpty()) {
                // ⚠️ upsertAll 用 REPLACE 整行覆盖，云端没有 favorite / aiStyle / aiAt 字段 →
                //    极端情况下（knownIds 读取失败退化为全量）仍可能覆盖到已收藏行、
                //    以及已经花钱生成的 AI 图标记，因此合并前统一回填一次。
                val favIds = runCatching { dao.favoriteIds(deviceId) }
                    .getOrDefault(emptyList()).toSet()
                val aiStates = runCatching { dao.aiStates(deviceId) }
                    .getOrDefault(emptyList()).associateBy { it.id }
                val merged = entities.map { e ->
                    val ai = aiStates[e.id]
                    when {
                        e.id in favIds || ai != null -> e.copy(
                            favorite = e.id in favIds,
                            aiStyle = ai?.aiStyle,
                            aiAt = ai?.aiAt
                        )
                        else -> e
                    }
                }
                dao.upsertAll(merged)
            }

            val after = dao.countByDevice(deviceId)
            Log.d(
                TAG,
                "sync(db) scope=$scope pages=$pages fetched=${fetched.size} " +
                    "fresh=${entities.size} stoppedByLocal=$stoppedByLocal"
            )
            SyncResult.Success(
                added = (after - before).coerceAtLeast(0), total = after,
                cloudTotal = reportedTotal, deviceId = deviceId,
                fetched = fetched.size, skipped = fetched.size - entities.size,
                pages = pages, stoppedByLocal = stoppedByLocal
            )
        } catch (t: Throwable) {
            Log.e(TAG, "persist failed, fallback to memory: ${t.javaClass.simpleName}")
            cloudCache[deviceId] = entities
            SyncResult.Success(
                added = entities.size, total = entities.size,
                cloudTotal = reportedTotal, deviceId = deviceId,
                fetched = fetched.size, skipped = fetched.size - entities.size,
                pages = pages, stoppedByLocal = stoppedByLocal
            )
        }
    }

    // ==================== 增量标记（全部照片页用） ====================

    /**
     * 这一页里是否已经出现「今天之前」的记录。
     *
     * create_time 解析失败时以 `todayStart` 兜底 → 不会被误判成历史记录，
     * 最坏情况是多翻几页（已被 [LATEST_MAX_PAGES] 兜住）。
     */
    private fun containsOlderThanToday(items: List<RecordItem>, todayStart: Long): Boolean =
        items.any { StudyStats.parseTime(it.createTime, todayStart) < todayStart }

    private val syncPrefs by lazy {
        appContext.getSharedPreferences("thumbcard_sync", Context.MODE_PRIVATE)
    }

    private fun fullDoneKey(deviceId: String) = "full_done_$deviceId"

    /** 本设备是否完成过一次完整全量拉取 */
    private fun isFullDone(deviceId: String): Boolean =
        runCatching { syncPrefs.getBoolean(fullDoneKey(deviceId), false) }.getOrDefault(false)

    private fun markFullDone(deviceId: String) {
        runCatching { syncPrefs.edit().putBoolean(fullDoneKey(deviceId), true).apply() }
    }

    // ==================== 收藏 ====================

    /**
     * 切换收藏状态，返回切换后的状态。
     *
     * 收藏是**纯本地**行为（云端接口没这个字段，也没有写入接口），
     * 卸载重装 / 换机不会同步——产品文案上不要承诺「收藏云端同步」。
     */
    suspend fun toggleFavorite(id: Long): Boolean {
        val dao = db?.wordCardDao()
        if (dao != null) {
            val now = runCatching { dao.isFavorite(id) }.getOrNull() ?: false
            val next = !now
            runCatching { dao.setFavorite(id, next) }
                .onFailure { Log.w(TAG, "setFavorite failed: $id", it) }
            return next
        }
        // 内存模式：直接改缓存副本
        cloudCache.keys.toList().forEach { deviceId ->
            val list = cloudCache[deviceId] ?: return@forEach
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val next = !list[idx].favorite
                cloudCache[deviceId] = list.toMutableList().also {
                    it[idx] = it[idx].copy(favorite = next)
                }
                return next
            }
        }
        return false
    }

    /** 单张是否已收藏（详情页进入时用） */
    suspend fun isFavorite(id: Long): Boolean {
        db?.wordCardDao()?.let { dao ->
            runCatching { dao.isFavorite(id) }.getOrNull()?.let { return it }
        }
        return cloudCache.values.flatten().find { it.id == id }?.favorite ?: false
    }

    /** 单词卡页数据源：**只返回已收藏** */
    suspend fun loadFavorites(deviceId: String): List<WordCardEntity> {
        val dao = db?.wordCardDao()
            ?: return cloudCache[deviceId].orEmpty().filter { it.favorite }
        return runCatching { dao.listFavorites(deviceId) }
            .getOrElse { cloudCache[deviceId].orEmpty().filter { it.favorite } }
    }

    /** 首页学习报告 */
    suspend fun stats(deviceId: String): StudyStats = StudyStats.of(loadLocal(deviceId))

    /** 读取本地缓存的卡片（数据库不可用时读内存缓存） */
    suspend fun loadLocal(deviceId: String): List<WordCardEntity> {
        val dao = db?.wordCardDao() ?: return cloudCache[deviceId] ?: emptyList()
        return runCatching { dao.listByDevice(deviceId) }
            .getOrElse { cloudCache[deviceId] ?: emptyList() }
    }

    /**
     * 读取全部卡片（跨设备），供「从相册找回 AI 图」使用。
     *
     * 不用 [loadLocal] 是因为它按 deviceId 切；而找回是按云端记录 id 匹配，
     * 用户换过绑定设备时，旧设备拍的那批图同样该能认回来。
     */
    suspend fun loadAllCards(): List<WordCardEntity> {
        val dao = db?.wordCardDao() ?: return cloudCache.values.flatten()
        return runCatching { dao.listAll() }
            .getOrElse { cloudCache.values.flatten() }
    }

    /**
     * 读取**全部设备**的卡片，按 id 倒序 —— 服务「全部单词记录」页的「全部」筛选。
     *
     * 与 [loadLocal] 的区别只有「是否按 deviceId 切」；排序必须显式指定，
     * 否则合并两台设备后行序会变成「设备 A 一批、设备 B 一批」。
     */
    suspend fun loadLocalAll(): List<WordCardEntity> {
        val dao = db?.wordCardDao()
            ?: return cloudCache.values.flatten().sortedByDescending { it.id }
        return runCatching { dao.listAllOrdered() }
            .getOrElse { cloudCache.values.flatten().sortedByDescending { it.id } }
    }

    /**
     * 逐台同步（「全部」模式用）。
     *
     * 为什么要串行而不是并发：每台设备都是一次「翻页到底」的同步，
     * 并发跑等于同时开 N 条翻页流水，弱网下互相抢带宽，还更容易触发服务端限流；
     * 而用户在这个页面等的是「有个列表能看」，不是「最快速度拿全」。
     *
     * 单台失败不影响其它台：失败的设备返回 [SyncResult.Failure]，上层只提示不中断。
     */
    suspend fun syncDevices(
        deviceIds: List<String>,
        langCode: String? = null,
        scope: SyncScope = SyncScope.ALL
    ): List<Pair<String, SyncResult>> = deviceIds.map { id ->
        id to runCatching { sync(id, langCode, scope) }
            .getOrElse { SyncResult.Failure("网络异常：${it.message ?: "未知错误"}") }
    }

    /** 读取单条 */    suspend fun findCard(id: Long): WordCardEntity? {
        val dao = db?.wordCardDao()
            ?: return cloudCache.values.flatten().find { it.id == id }
        return runCatching { dao.findById(id) }
            .getOrNull()
            ?: cloudCache.values.flatten().find { it.id == id }
    }

    /**
     * 按需拉取详情。
     *
     * 注：list 已返回全量字段，此方法仅作为将来字段精简后的兜底。
     *
     * @param langCode 必须与列表同步时传的值一致。传 null 则拿不到 `name_localized`，
     *                 详情页会显示中文原词——与列表的韩语不一致。
     */
    suspend fun fetchDetail(id: Long, langCode: String? = null): RecordItem? {
        val apiKey = effectiveApiKey() ?: return null
        return try {
            api.detail(apiKey, id, langCode).data?.record
        } catch (e: Exception) {
            Log.w(TAG, "fetch detail failed: $id", e)
            null
        }
    }

    /** 仅删除本地卡片（云端无删除接口） */    suspend fun deleteLocal(id: Long) {
        runCatching { db?.wordCardDao()?.deleteById(id) }
    }

    /**
     * 记下这张卡当前用哪张 AI 图。传 null = 回退原图。
     *
     * 数据库不可用时同步改内存缓存，保证本次运行内界面一致。
     */
    suspend fun setAiStyle(id: Long, style: String?) {
        val at = if (style == null) null else System.currentTimeMillis()
        val dao = db?.wordCardDao()
        if (dao != null) {
            runCatching { dao.setAiStyle(id, style, at) }
                .onFailure { Log.w(TAG, "setAiStyle failed: $id", it) }
        }
        cloudCache.keys.toList().forEach { deviceId ->
            val list = cloudCache[deviceId] ?: return@forEach
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) {
                cloudCache[deviceId] = list.toMutableList().also {
                    it[idx] = it[idx].copy(aiStyle = style, aiAt = at)
                }
            }
        }
    }

    /** 清空某设备的本地缓存 */
    suspend fun clearLocal(deviceId: String) {
        cloudCache.remove(deviceId)
        runCatching { db?.wordCardDao()?.clearByDevice(deviceId) }
    }

    /**
     * 把服务端随记录下发的译名写入本地缓存。
     *
     * 只接受 `name_localized_lang` 与请求语言一致的结果——防止服务端在未支持
     * 该语言时把中文原样塞进 `name_localized`，那样会让 UI 误以为是真译名。
     */
    private fun persistLocalizedNames(items: List<RecordItem>, langCode: String) {
        val lang = NameLanguage.fromCode(langCode)
        // 中文（含繁体）不走译名缓存：简体用原始结果，繁体在本机转换，
        // 两者都不该把服务端下发的 name_localized 当译名存起来。
        if (lang.isChinese) return
        items.forEach { item ->
            val text = item.nameLocalized?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            val echoed = item.nameLocalizedLang
            if (echoed != null && !echoed.equals(langCode, ignoreCase = true)) return@forEach
            runCatching { NameStore.write(appContext, item.id, lang, item.nameChinese, text) }
        }
        Log.d(TAG, "localized names cached: lang=$langCode count=${items.size}")
    }

    private fun RecordItem.toEntity(deviceId: String) = WordCardEntity(
        id = id,
        deviceId = this.deviceId ?: deviceId,
        nameChinese = nameChinese,
        nameEnglish = nameEnglish,
        pinyin = pinyin,
        phonetic = phonetic,
        description = description,
        imageUrl = imageUrl,
        ttsChineseUrl = ttsChineseUrl,
        ttsEnglishUrl = ttsEnglishUrl,
        createTime = createTime,
        savedAt = System.currentTimeMillis()
    )
}
