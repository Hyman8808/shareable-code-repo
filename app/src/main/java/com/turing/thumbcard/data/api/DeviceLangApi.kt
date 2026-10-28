package com.turing.thumbcard.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 设备语言设定接口 —— 老栈 nglearn `/api/device/lang`。
 *
 * ## 为什么必须有这个接口
 *
 * 2026-10-28 起云端改成「**语言在拍摄时按 deviceId 固化**」：
 * 识别请求不再接受 `lang` 参数（实测带与不带返回一致），
 * 云端拿 `deviceId` 去 `study_device_lang` 表查这台机器该出哪国语言。
 *
 * 所以「用户在 App 里选了日语」必须写一次云端，否则：
 * 界面显示日语 → 拍照 → 云端仍按默认 `zh` 出中文 → 用户判定功能坏了。
 *
 * ## 🔴 参数必须放 query，不能放 JSON body
 *
 * 服务端**不解析 POST 的 JSON body**。实测（2026-10-28，演示机 `c8586a630000`）：
 *
 * | 请求 | 结果 |
 * |---|---|
 * | `POST .../api/device/lang?key=&deviceId=&lang=zh` | `200 {"code":200,"data":{"device_id":"…","lang":"zh"}}` |
 * | `POST .../api/device/lang` + JSON body 传三个字段 | **`4005 apikey信息错误`** |
 * | `POST ...?lang=xx`（非法码） | `40000 lang参数错误：支持 zh/zh-Hant/en/ja/ko/es/fr/de/ru/ar` |
 *
 * 因此 [set] 用 `@Query` 而非 `@Body`。这是一处**静默错**：
 * 报的是「apikey 错误」，会让人去查密钥，实际只是参数位置错了。
 *
 * ## 幂等
 *
 * 重复提交同一个 lang 返回 200，不会报错，可以安全重试。
 *
 * ## 语言码白名单
 *
 * `zh / zh-Hant / en / ja / ko / es / fr / de / ru / ar` —— **没有 `none`**。
 * `none`（仅英文模式）是客户端概念，上报时要映射成 `en`，见
 * `DeviceLangSync.cloudCodeOf`。
 */
interface DeviceLangApi {

    /**
     * 写入这台设备的名称语言。
     *
     * @param key      apikey，批次公用
     * @param deviceId 设备号（取 `0x8D/0x0B` 的 DEVICEID，不是广播 MAC）
     * @param lang     白名单内的语言码
     */
    @POST("api/device/lang")
    suspend fun set(
        @Query("key") key: String,
        @Query("deviceId") deviceId: String,
        @Query("lang") lang: String
    ): DeviceLangResponse

    /**
     * 读取这台设备当前的名称语言。
     *
     * 返回里的 `lang` 是**云端实际存的值**，可用来判断本地与云端是否已经一致
     * （例如换手机登录后，本地是日语但云端还是中文）。
     */
    @GET("api/device/lang")
    suspend fun get(
        @Query("key") key: String,
        @Query("deviceId") deviceId: String
    ): DeviceLangResponse
}

/** `/api/device/lang` 统一响应壳。老栈成功码是 `200`（不是新栈的 0）。 */
data class DeviceLangResponse(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("message") val message: String? = null,
    @SerializedName("data") val data: DeviceLangData? = null
) {
    /** 老栈 `200` 与新栈 `0` 都算成功，两边都认，避免接到新栈时静默失败 */
    val isOk: Boolean get() = code == 200 || code == 0
}

data class DeviceLangData(
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("lang") val lang: String? = null
)
