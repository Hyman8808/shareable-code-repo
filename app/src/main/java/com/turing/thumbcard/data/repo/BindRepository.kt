package com.turing.thumbcard.data.repo

import android.content.Context
import android.util.Log
import com.turing.thumbcard.data.db.AppDatabase
import com.turing.thumbcard.data.db.BoundDeviceEntity
import com.turing.thumbcard.ble.model.DeviceInfoResult
import com.turing.thumbcard.util.CredentialStore

/**
 * 绑定结果。
 */
sealed class BindResult {
    data class Success(val deviceId: String) : BindResult()

    /** 设备未烧录凭据（固件未升级或产线漏烧） */
    data object NoCredential : BindResult()

    /** deviceId 格式非法 */
    data class InvalidDeviceId(val deviceId: String?) : BindResult()

    /** apikey 格式非法 */
    data object InvalidApiKey : BindResult()

    /** 本地存储不可用（容器环境限制），无法建立持久绑定 */
    data class StorageUnavailable(val detail: String) : BindResult()
}

/**
 * 绑定管理。
 *
 * 绑定成立条件（三条件与）：
 *  1. 从设备成功读取到 apikey 与 deviceId
 *  2. 两者格式合法
 *  3. 设备已成功联网（由调用方通过配网结果确认后调用 [bind]）
 *
 * ⚠️ 不以「能否拉到记录」作为绑定判据：
 *    云端对不存在的 deviceId 也返回 200 + 空列表，无法据此区分
 *    「新设备尚无记录」与「deviceId 错误」两种情况。
 *
 * ⚠️ 兼容性：数据库不可用时（如卓易通容器），本类所有方法安全降级，
 *    返回空列表 / 失败结果，绝不抛异常。
 */
class BindRepository(context: Context) {

    private val db: AppDatabase? = AppDatabase.get(context)
    private val boundDao = db?.boundDeviceDao()

    /** 本地存储是否可用 */
    val isStorageAvailable: Boolean get() = db != null

    /**
     * 建立绑定关系。
     *
     * apikey 存入加密存储（整批公用，全局一份）；
     * deviceId 存入设备表（可多台）。
     */
    suspend fun bind(info: DeviceInfoResult, deviceName: String? = null): BindResult {
        val apiKey = info.apikey
        val deviceId = info.deviceId

        if (apiKey.isNullOrBlank() || deviceId.isNullOrBlank()) {
            return BindResult.NoCredential
        }
        if (!info.isApiKeyValid) return BindResult.InvalidApiKey
        if (!info.isDeviceIdValid) return BindResult.InvalidDeviceId(deviceId)

        // 凭据先存（即使数据库不可用，apikey 也可能已落到内存/明文存储）
        CredentialStore.saveApiKey(apiKey)

        val dao = boundDao
            ?: return BindResult.StorageUnavailable(
                AppDatabase.lastInitError ?: "本地数据库不可用"
            )

        return try {
            val existing = dao.find(deviceId)
            dao.upsert(
                BoundDeviceEntity(
                    deviceId = deviceId,
                    deviceName = deviceName ?: existing?.deviceName ?: "我的拇指相机",
                    firmwareVersion = info.firmwareVersion,
                    bindTime = existing?.bindTime ?: System.currentTimeMillis(),
                    lastSyncTime = existing?.lastSyncTime
                )
            )
            BindResult.Success(deviceId)
        } catch (t: Throwable) {
            Log.e(TAG, "bind failed: ${t.javaClass.simpleName}")
            BindResult.StorageUnavailable("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 已绑定设备列表 */
    suspend fun listBound(): List<BoundDeviceEntity> = runCatching {
        boundDao?.listAll()
    }.getOrNull() ?: emptyList()

    /** 查询单台设备 */
    suspend fun find(deviceId: String): BoundDeviceEntity? = runCatching {
        boundDao?.find(deviceId)
    }.getOrNull()

    /** 更新同步时间 */
    suspend fun markSynced(deviceId: String, time: Long = System.currentTimeMillis()) {
        runCatching {
            val d = boundDao?.find(deviceId) ?: return
            boundDao.upsert(d.copy(lastSyncTime = time))
        }
    }

    /** 重命名设备 */
    suspend fun rename(deviceId: String, newName: String) {
        runCatching {
            val d = boundDao?.find(deviceId) ?: return
            boundDao.upsert(d.copy(deviceName = newName))
        }
    }

    /**
     * 解绑。
     *
     * 云端无删除绑定接口，因此仅清除本地记录与缓存。
     * 若解绑的是最后一台设备，同时清除 apikey。
     */
    suspend fun unbind(deviceId: String, clearCardCache: Boolean = true) {
        runCatching {
            boundDao?.delete(deviceId)
            if (clearCardCache) {
                db?.wordCardDao()?.clearByDevice(deviceId)
            }
            if (boundDao?.listAll().isNullOrEmpty()) {
                CredentialStore.clear()
            }
        }
    }

    /** 是否已有绑定设备 */
    suspend fun hasBoundDevice(): Boolean = listBound().isNotEmpty()

    private companion object {
        const val TAG = "BindRepository"
    }
}
