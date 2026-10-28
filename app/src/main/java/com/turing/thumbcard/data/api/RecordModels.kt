package com.turing.thumbcard.data.api

import com.google.gson.annotations.SerializedName

/**
 * 记录接口数据模型。
 *
 * ★ 字段依据 2026-10-16 对 nglearn 云端接口的**实测响应**定义，
 *   而非仅依据协议文档（文档中部分字段未返回）。
 *
 * 实测补充说明：
 *  - list 接口返回的字段与 detail **完全一致**（全量字段，非摘要）
 *  - explore / learning 在当前记录中**未返回**，故定义为可空
 */
data class RecordListResponse(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("message") val message: String? = null,
    @SerializedName("data") val data: RecordListData? = null
)

data class RecordListData(
    @SerializedName("total") val total: Int = 0,
    @SerializedName("page") val page: Int = 1,
    @SerializedName("size") val size: Int = 20,
    @SerializedName("list") val list: List<RecordItem> = emptyList()
)

data class RecordDetailResponse(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("message") val message: String? = null,
    @SerializedName("data") val data: RecordDetailData? = null
)

data class RecordDetailData(
    @SerializedName("record") val record: RecordItem? = null
)

/**
 * 单条记录。
 *
 * 服务端 JSON 使用下划线命名，此处用 @SerializedName 映射到驼峰。
 */
data class RecordItem(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("client_request_id") val clientRequestId: String? = null,
    @SerializedName("global_id") val globalId: String? = null,
    @SerializedName("image_url") val imageUrl: String? = null,
    @SerializedName("image_format") val imageFormat: String? = null,
    @SerializedName("age") val age: Int = 0,
    @SerializedName("name_chinese") val nameChinese: String? = null,
    @SerializedName("name_english") val nameEnglish: String? = null,
    @SerializedName("information") val information: Information? = null,
    @SerializedName("tts_url") val ttsUrl: List<String>? = null,
    @SerializedName("tts_url_english") val ttsUrlEnglish: List<String>? = null,
    /**
     * 按**拍摄时设定语言**产出的发音地址（服务端已按 deviceId 匹配好语言）。
     *
     * 2026-10-28 起云端在识别时就把语言固化进记录，因此这份地址与
     * [tts_url] / [tts_url_english] 并列存在，不随请求参数变化。
     * 简体中文记录通常不带本字段（等价于 tts_url）。
     */
    @SerializedName("tts_url_localized") val ttsUrlLocalized: List<String>? = null,
    @SerializedName("create_time") val createTime: String? = null,
    // ---- 多语言名称（服务端翻译接口就绪后下发，当前恒为 null）----
    /** 按请求的 lang 下发的名称；为 null 表示服务端尚未支持该语言 */
    @SerializedName("name_localized") val nameLocalized: String? = null,
    /** 服务端实际返回的语言代码，用于校验是否真的是目标语言 */
    @SerializedName("name_localized_lang") val nameLocalizedLang: String? = null,
    // 以下字段当前接口未返回，保留以兼容后续扩展
    @SerializedName("explore") val explore: List<ExploreItem>? = null,
    @SerializedName("learning") val learning: List<LearningItem>? = null
) {
    /** 中文发音地址（取第一个） */
    val ttsChineseUrl: String? get() = ttsUrl?.firstOrNull()

    /** 英文发音地址（取第一个） */
    val ttsEnglishUrl: String? get() = ttsUrlEnglish?.firstOrNull()

    /**
     * 设定语言的发音地址。**没有时返回 null，由调用方回落到英文再回落到中文**。
     *
     * 回落顺序对齐接口文档：localized → english → tts_url → 静音。
     * 不要在这里一次性兜底到中文，否则「选了日语却播中文」会被当成 bug。
     */
    val ttsLocalizedUrl: String? get() = ttsUrlLocalized?.firstOrNull()

    /** 拼音 */
    val pinyin: String? get() = information?.pinyin

    /** 英文音标 */
    val phonetic: String? get() = information?.phonetic

    /** 中文释义 */
    val description: String? get() = information?.description
}

data class Information(
    @SerializedName("pinyin") val pinyin: String? = null,
    @SerializedName("phonetic") val phonetic: String? = null,
    @SerializedName("name_english") val nameEnglish: String? = null,
    @SerializedName("description") val description: String? = null
)

data class ExploreItem(
    @SerializedName("item") val item: String? = null,
    @SerializedName("relationship") val relationship: String? = null
)

data class LearningItem(
    @SerializedName("question") val question: String? = null,
    @SerializedName("answer") val answer: String? = null
)

/** 云端业务错误码 */
object ApiCode {
    const val SUCCESS = 200
    /** apikey 信息错误 —— 凭据无效 */
    const val APIKEY_INVALID = 4005
    /** deviceId 不能为空 */
    const val DEVICE_ID_EMPTY = 4006
    /** 记录/接口不存在 */
    const val NOT_FOUND = 40000
}
