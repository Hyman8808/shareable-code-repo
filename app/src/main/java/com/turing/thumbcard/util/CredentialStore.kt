package com.turing.thumbcard.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * apikey 安全存储。
 *
 * ⚠️ 安全说明：
 * apikey 为**整批设备公用**的应用级密钥，一旦泄露将影响该应用下所有设备的记录。
 * 因此必须加密存储，且：
 *  - 禁止写入日志
 *  - 禁止硬编码在源码中（BuildConfig 中的默认值仅用于联调，生产应移除）
 *  - 禁止随崩溃上报或埋点外发
 *
 * ## 兼容性设计（重要）
 *
 * EncryptedSharedPreferences 依赖 Android Keystore。以下环境**不稳定或不可用**：
 *  - 华为鸿蒙 NEXT + 卓易通（Android 兼容容器，iSulad 沙盒）
 *  - 部分定制 ROM、模拟器、无 TEE 的设备
 *
 * 在这些环境下，`MasterKey.Builder.build()` 或 `EncryptedSharedPreferences.create()`
 * 可能抛出 `GeneralSecurityException`、`ProviderException`、
 * `NoSuchAlgorithmException`、甚至 `NullPointerException`（Keystore 返回 null）。
 *
 * **本类的设计目标是：无论加密存储是否可用，都绝不让 App 闪退。**
 * 因此采用「三级降级」策略：
 *   1. 尝试 EncryptedSharedPreferences（加密，首选）
 *   2. 失败则尝试普通 SharedPreferences（明文，可用但安全性降低）
 *   3. 再失败则退化为内存存储（不持久化，仅保证本次运行可用）
 *
 * 降级状态可通过 [isEncrypted] / [storageMode] 查询，供诊断页展示。
 */
object CredentialStore {

    private const val TAG = "CredentialStore"
    private const val FILE_NAME = "thumbcard_secure"
    private const val FILE_NAME_PLAIN = "thumbcard_plain"
    private const val KEY_APIKEY = "api_key"

    /** 存储模式 */
    enum class Mode {
        /** 加密存储（EncryptedSharedPreferences） */
        ENCRYPTED,

        /** 明文存储（加密不可用时的降级，仍可持久化） */
        PLAIN,

        /** 内存存储（完全不持久化，仅保证不崩） */
        MEMORY
    }

    private var prefs: SharedPreferences? = null
    private var memoryValue: String? = null

    @Volatile
    var storageMode: Mode = Mode.MEMORY
        private set

    /** 是否使用了加密存储 */
    val isEncrypted: Boolean get() = storageMode == Mode.ENCRYPTED

    /** 降级说明文案（供诊断页显示） */
    val storageDescription: String
        get() = when (storageMode) {
            Mode.ENCRYPTED -> "加密存储（Android Keystore）"
            Mode.PLAIN -> "明文存储（当前环境不支持加密存储，已自动降级）"
            Mode.MEMORY -> "内存存储（当前环境存储不可用，重启后需重新配网）"
        }

    /**
     * 初始化。**可安全重复调用**，任何异常都不会抛出。
     *
     * 必须在 Application.onCreate() 中调用。
     */
    fun init(context: Context) {
        if (prefs != null && storageMode != Mode.MEMORY) return

        // ---- 第 1 级：尝试加密存储 ----
        // 用 Throwable 兜底：部分 ROM 在 Keystore 异常时会抛 Error 而非 Exception
        try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encrypted = EncryptedSharedPreferences.create(
                context.applicationContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            // 触发一次真实读写，确认不是"能创建但不能用"
            encrypted.edit().putString("__probe__", "1").commit()
            encrypted.edit().remove("__probe__").commit()

            prefs = encrypted
            storageMode = Mode.ENCRYPTED
            Log.i(TAG, "storage initialized: ENCRYPTED")
            return
        } catch (t: Throwable) {
            // 只记录异常类型，不打印堆栈中可能含有的凭据信息
            Log.w(TAG, "encrypted storage unavailable: ${t.javaClass.simpleName}")
        }

        // ---- 第 2 级：降级为普通存储 ----
        try {
            val plain = context.applicationContext
                .getSharedPreferences(FILE_NAME_PLAIN, Context.MODE_PRIVATE)
            plain.edit().putString("__probe__", "1").commit()
            plain.edit().remove("__probe__").commit()

            prefs = plain
            storageMode = Mode.PLAIN
            Log.i(TAG, "storage initialized: PLAIN (degraded)")
            return
        } catch (t: Throwable) {
            Log.w(TAG, "plain storage unavailable: ${t.javaClass.simpleName}")
        }

        // ---- 第 3 级：内存存储（不持久化） ----
        prefs = null
        storageMode = Mode.MEMORY
        Log.w(TAG, "storage initialized: MEMORY (volatile)")
    }

    /** 保存 apikey（仅在从设备成功读取到有效值时调用） */
    fun saveApiKey(value: String) {
        if (value.isBlank()) return
        when (storageMode) {
            Mode.ENCRYPTED, Mode.PLAIN -> {
                try {
                    prefs?.edit()?.putString(KEY_APIKEY, value)?.apply()
                } catch (t: Throwable) {
                    Log.w(TAG, "saveApiKey failed: ${t.javaClass.simpleName}")
                    memoryValue = value
                    storageMode = Mode.MEMORY
                }
            }
            Mode.MEMORY -> memoryValue = value
        }
    }

    /** 读取 apikey；无则返回 null */
    fun getApiKey(): String? = when (storageMode) {
        Mode.ENCRYPTED, Mode.PLAIN -> {
            try {
                prefs?.getString(KEY_APIKEY, null)?.takeIf { it.isNotBlank() }
            } catch (t: Throwable) {
                Log.w(TAG, "getApiKey failed: ${t.javaClass.simpleName}")
                memoryValue
            }
        }
        Mode.MEMORY -> memoryValue
    }

    /** 清除凭据（解绑所有设备时调用） */
    fun clear() {
        memoryValue = null
        try {
            prefs?.edit()?.remove(KEY_APIKEY)?.apply()
        } catch (t: Throwable) {
            Log.w(TAG, "clear failed: ${t.javaClass.simpleName}")
        }
    }

    /** 是否已持有凭据 */
    fun hasApiKey(): Boolean = getApiKey() != null
}
