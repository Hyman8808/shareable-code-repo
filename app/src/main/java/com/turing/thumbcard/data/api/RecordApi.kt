package com.turing.thumbcard.data.api

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 拍照识物记录接口。
 *
 * 实测行为（2026-10-16 验证）：
 *  - size 上限 100，超出自动降为 100
 *  - 默认 page=1&size=20
 *  - page=0 被服务端归一为 1
 *  - 任意不存在的 deviceId 返回 200 + 空列表（不校验归属）
 *  - 缺少 key 返回 4005，缺少 deviceId 返回 4006
 */
interface RecordApi {

    /**
     * 查询记录列表。
     *
     * @param key      apikey，出厂烧录在设备中，配网时由 App 读取
     * @param deviceId 设备标识
     * @param page     页码，从 1 开始
     * @param size     每页条数，1~100
     * @param lang     名称语言代码（zh/ja/ko/...）。**null 表示不传**，
     *                 服务端按默认中文返回——与改造前行为完全一致。
     *                 翻译接口就绪后才由上层传入，届时服务端按该语言下发 name_localized。
     */
    @GET("api/record/list")
    suspend fun list(
        @Query("key") key: String,
        @Query("deviceId") deviceId: String,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
        @Query("lang") lang: String? = null
    ): RecordListResponse

    /**
     * 查询记录详情。
     *
     * 注：list 已返回全量字段，正常情况下无需调用此接口。
     *
     * @param lang 与 [list] 同义。**必须与列表请求传同一个值**，
     *             否则详情页拿到的 `name_localized` 与列表不一致，
     *             会出现「列表是韩语、点进去变中文」。
     */
    @GET("api/record/detail")
    suspend fun detail(
        @Query("key") key: String,
        @Query("id") id: Long,
        @Query("lang") lang: String? = null
    ): RecordDetailResponse
}
