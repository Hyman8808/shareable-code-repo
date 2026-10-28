package com.turing.thumbcard.data.device

import android.content.Context
import android.util.Log
import com.turing.thumbcard.data.api.DeviceLangApi
import com.turing.thumbcard.data.i18n.NameLanguage
import com.turing.thumbcard.data.repo.RecordRepository
import com.turing.thumbcard.data.db.AppDatabase
import com.turing.thumbcard.util.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 把「物品名称语言」写到云端 `/api/device/lang`。
 *
 * ## 这件事为什么非做不可
 *
 * 2026-10-28 起，云端在**识别那一刻**就按 `deviceId` 查语言设定并固化进记录
 * （`name_localized` / `tts_url_localized`），识别请求不再接受 `lang` 参数。
 * 也就是说：
 *
 * ```
 * 用户选日语 → 只写本地 SP → 拍照 → 云端查设备语言仍是默认 zh → 返回中文名
 * ```
 *
 * 本地存得再对也没用，**云端不知道就等于没设**。这个类补的就是这一跳。
 *
 * ## 与 [DeviceLangStore] 的分工（两者都要写，别合并）
 *
 * | 存储 | 给谁看 | 走什么通道 | 失败影响 |
 * |---|---|---|---|
 * | [DeviceLangStore] | 设备固件 | BLE `0x12` 三个 TLV | 设备提示语不变（固件本版未实现，长期 pending） |
 * | [DeviceLangSync] | 云端 | HTTP `POST /api/device/lang` | **拍出来的卡是旧语言** |
 *
 * 两条通道互相独立：固件那版没实现 `0x12` 也不影响云端这条，反之亦然。
 * 所以 [DeviceLangStore.pending] 与这里的云端 pending **必须分开存**，
 * 否则「云端已生效、设备未下发」会被显示成「什么都没生效」。
 *
 * ## 多设备：逐台上报，不是只报当前那台
 *
 * 云端语言是**按 deviceId 存**的。用户在一个 App 里绑了两台机器时，
 * 只报「当前设备」会出现「这台改了、换一台又不行」。
 * 而且设置页没有「当前设备」概念（`CurrentDeviceStore.ALL` 是伪设备，不能用来上报），
 * 所以这里直接遍历 `bound_device` 全部上报，失败的单台留待重试。
 *
 * ## pending 重试
 *
 * 三种场景会让上报失败，都必须能自动补：
 *  1. 还没配网 → 没有 deviceId，无处可写；
 *  2. 断网 / 超时；
 *  3. apikey 尚未从设备读到（首次配网前）。
 *
 * 失败时把语言码记进 SP，由 [flushPendingAsync] 在下列时机重试：
 * 进语言设置页、配网成功、App 回前台。
 * 全部成功后清标记，避免每次进页面都白打一轮请求。
 */
object DeviceLangSync {

    private const val TAG = "DeviceLangSync"

    /** 独立 SP：不能和 [DeviceLangStore] 共用，否则 pending 语义互相污染 */
    private const val PREF = "thumbcard_lang_sync"
    private const val KEY_PENDING = "pending_lang"
    private const val KEY_PENDING_AT = "pending_at"
    private const val KEY_LAST_OK = "last_ok_lang"

    /** 单台设备的请求超时。云端这个接口很快（实测 < 200ms），8s 足够 */
    private const val TIMEOUT_MS = 8_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val api: DeviceLangApi by lazy {
        Retrofit.Builder()
            .baseUrl(RecordRepository.BASE_URL)
            .client(
                okhttp3.OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .build()
            )
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DeviceLangApi::class.java)
    }

    /**
     * 上报结果。
     *
     * UI 只在**明确失败**时提示，成功不打扰 —— 用户点一下语言不该弹两句话。
     */
    sealed class Result {
        /** 全部设备写入成功 */
        data class Ok(val devices: Int) : Result()

        /** 部分成功。`failed` 台会在下次 [flushPendingAsync] 重试 */
        data class Partial(val ok: Int, val failed: Int) : Result()

        /** 还没绑设备，无从上报道云端。已记 pending，配网后自动补 */
        object NoDevice : Result()

        /** apikey 还没读到（未配网 / 存储异常）。已记 pending */
        object NoCredential : Result()

        /** 网络失败。已记 pending */
        data class Failed(val message: String) : Result()
    }

    /**
     * 客户端语言码 → 云端白名单码。
     *
     * 🔴 `none`（仅英文模式）**不在云端白名单里**（白名单是
     * `zh/zh-Hant/en/ja/ko/es/fr/de/ru/ar`）。它表示「名称只留英文」，
     * 云端语义上等价于 `en`，必须映射，否则会拿到 `40000 lang参数错误`。
     */
    fun cloudCodeOf(lang: NameLanguage): String =
        if (lang == NameLanguage.NONE) "en" else lang.code

    /** 后台上报，调用方不等待。用户点选语言后直接调这个。 */
    fun pushAsync(context: Context, lang: NameLanguage) {
        val app = context.applicationContext
        scope.launch { runCatching { push(app, lang) } }
    }

    /**
     * 上报到全部已绑设备。
     *
     * 幂等：重复提交同一个 lang 云端返回 200，不报错。
     */
    suspend fun push(context: Context, lang: NameLanguage): Result =
        pushCode(context, cloudCodeOf(lang))

    /**
     * 按**云端语言码**直接上报。
     *
     * 🔴 pending 重试必须走这里，不能先用 [NameLanguage.fromCode] 把码转回枚举：
     *    [NameLanguage] 里**没有 `en`**（`none` 就是「仅英文」模式，代表 en），
     *    而 `fromCode("en")` 找不到时会**静默回落成中文**（`DEFAULT = ZH`）。
     *    结果是「仅英文模式下断网一次，重试把云端改成了中文」——
     *    不报错、不提示，只有用户拍照发现又变中文才看得出来。
     */
    suspend fun pushCode(context: Context, cloudCode: String): Result {
        val app = context.applicationContext
        val code = cloudCode

        val key = runCatching { CredentialStore.getApiKey() }.getOrNull()
        if (key.isNullOrBlank()) {
            markPending(app, code)
            Log.d(TAG, "no apikey, pending=$code")
            return Result.NoCredential
        }

        val deviceIds = runCatching {
            AppDatabase.get(app)?.boundDeviceDao()?.listAll()?.map { it.deviceId }.orEmpty()
        }.getOrDefault(emptyList())

        if (deviceIds.isEmpty()) {
            markPending(app, code)
            Log.d(TAG, "no bound device, pending=$code")
            return Result.NoDevice
        }

        var ok = 0
        var failed = 0
        deviceIds.forEach { deviceId ->
            val r = runCatching { withTimeoutOrNull(TIMEOUT_MS) { api.set(key, deviceId, code) } }
                .getOrNull()
            if (r != null && r.isOk) {
                ok++
            } else {
                failed++
                Log.w(TAG, "set failed device=$deviceId code=${r?.code} msg=${r?.message}")
            }
        }

        return when {
            failed == 0 -> {
                clearPending(app)
                markLastOk(app, code)
                Log.d(TAG, "pushed $code to $ok device(s)")
                Result.Ok(ok)
            }
            ok == 0 -> {
                markPending(app, code)
                Result.Failed("云端未接受语言设置")
            }
            else -> {
                markPending(app, code)
                Result.Partial(ok, failed)
            }
        }
    }

    /** 有 pending 就重试；没有则什么都不做（不产生请求） */
    fun flushPendingAsync(context: Context) {
        val app = context.applicationContext
        val code = pendingCode(app) ?: return
        scope.launch { runCatching { pushCode(app, code) } }
    }

    /**
     * 当前云端口径的语言码（用于 UI 判断「本地与云端是否已一致」）。
     *
     * 返回 null 表示还没有成功上报过任何语言。
     */
    fun lastOkCode(context: Context): String? = runCatching {
        prefs(context).getString(KEY_LAST_OK, null)
    }.getOrNull()

    /** 是否有尚未上报成功的语言变更 */
    fun hasPending(context: Context): Boolean = pendingCode(context) != null

    // ---------- pending 存取 ----------

    private fun pendingCode(context: Context): String? = runCatching {
        prefs(context).getString(KEY_PENDING, null)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun markPending(context: Context, code: String) {
        runCatching {
            prefs(context).edit()
                .putString(KEY_PENDING, code)
                .putLong(KEY_PENDING_AT, System.currentTimeMillis())
                .apply()
        }.onFailure { Log.w(TAG, "markPending failed: ${it.javaClass.simpleName}") }
    }

    private fun clearPending(context: Context) {
        runCatching {
            prefs(context).edit().remove(KEY_PENDING).remove(KEY_PENDING_AT).apply()
        }.onFailure { Log.w(TAG, "clearPending failed: ${it.javaClass.simpleName}") }
    }

    private fun markLastOk(context: Context, code: String) {
        runCatching {
            prefs(context).edit().putString(KEY_LAST_OK, code).apply()
        }.onFailure { Log.w(TAG, "markLastOk failed: ${it.javaClass.simpleName}") }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
