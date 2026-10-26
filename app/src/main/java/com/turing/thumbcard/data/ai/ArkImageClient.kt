package com.turing.thumbcard.data.ai

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 火山方舟 · 图片生成（Doubao Seedream 5.0 pro）。
 *
 * 接口：`POST https://ark.cn-beijing.volces.com/api/v3/images/generations`
 * 鉴权：`Authorization: Bearer <API Key>`
 *
 * 关键参数取舍（都踩过文档）：
 *  - `size`：产品硬要求「至少 2K」。方舟的 5.0 pro 只接受
 *    `1K / 1.5K / 2K` 三档或 `宽x高`（总像素 921600–4624220）。
 *    **默认按参考图的宽高比算出确切尺寸**（见 [AiImageSize]），不再依赖档位：
 *    档位下比例由模型决定，换模型/换版本就可能变；而客户相机有 4:3 / 16:9 / 1:1 多种，
 *    比例一旦不是原图的，「存相册」拿到的就是一张被改过构图的图。
 *    写死 `2048x2048` 更不行——那会把 2:1 的相机图硬切成正方形。
 *  - `response_format = "b64_json"`：直接拿字节，省掉「再下一次 24 小时临时链接」这一步，
 *    也避免链接过期导致「刚生成好却下不回来」。
 *  - `watermark = false`：卡片图不带「AI 生成」角标，否则和 App 自己画的文字叠在一层。
 *  - **不传** `sequential_image_generation`：5.0 pro 不支持该字段，传了会报参数错误。
 */
object ArkImageClient {

    private const val TAG = "ArkImageClient"

    /** 读不出原图比例时的兜底档位（极少走到：原图落盘后一定有尺寸） */
    private const val DEFAULT_SIZE = "2K"

    /** 单张 2K 实测 60–90 秒，读超时留足余量；再长就是服务端排队异常 */
    private const val READ_TIMEOUT_SEC = 300L

    private val gson = Gson()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    /** 失败原因 —— 由 UI 层映射成用户能看懂的一句话，不在网络层拼文案 */
    enum class Fail {
        /** 没网 / 连不上 */
        NETWORK,

        /** 超过读超时 */
        TIMEOUT,

        /** 401 / 403：密钥无效或没开通模型 */
        AUTH,

        /** 429：限流，稍后重试 */
        RATE_LIMIT,

        /** 内容审核不通过 */
        CONTENT,

        /** 其他服务端错误 */
        SERVER
    }

    sealed class Result {
        data class Ok(val bytes: ByteArray, val size: String?) : Result()
        data class Err(val fail: Fail, val detail: String? = null) : Result()
    }

    /**
     * 以本地原图为参考生成一张图。
     *
     * @param apiKey 方舟 API Key
     * @param model  模型 ID，如 `doubao-seedream-5-0-pro-260628`
     * @param prompt 提示词
     * @param source 本地原图文件（**必须是本地文件**：传 base64 保证送进去的就是用户看到的那张，
     *               不依赖 OSS 链接是否还有效）
     * @param size   强制覆盖分辨率（`2K` 档位或 `宽x高`）。**传 null（推荐）** 时
     *               按 [source] 的宽高比自动算，保证出图与拍摄比例一致
     */
    suspend fun generate(
        apiKey: String,
        model: String,
        prompt: String,
        source: File,
        size: String? = null
    ): Result = withContext(Dispatchers.IO) {
        // 比例跟着原图走：客户相机可能是 4:3 / 16:9 / 1:1，不能写死 2K 档位靠模型猜
        val resolved = size?.takeIf { it.isNotBlank() }
            ?: AiImageSize.forSource(source)
            ?: DEFAULT_SIZE
        try {
            val b64 = Base64.encodeToString(source.readBytes(), Base64.NO_WRAP)
            val payload = JsonObject().apply {
                addProperty("model", model)
                addProperty("prompt", prompt)
                add("image", gson.toJsonTree(listOf("data:image/jpeg;base64,$b64")))
                addProperty("size", resolved)
                addProperty("response_format", "b64_json")
                addProperty("watermark", false)
            }
            Log.d(TAG, "generate size=$resolved")

            val req = Request.Builder()
                .url("https://ark.cn-beijing.volces.com/api/v3/images/generations")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "http ${resp.code}: ${text.take(400)}")
                    return@withContext Result.Err(mapFail(resp.code, text), errorMessage(text))
                }
                parseOk(text) ?: Result.Err(Fail.SERVER, "响应里没有图片数据")
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "timeout", e)
            Result.Err(Fail.TIMEOUT, e.message)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "io", e)
            Result.Err(Fail.NETWORK, e.message)
        } catch (e: Exception) {
            Log.e(TAG, "unexpected", e)
            Result.Err(Fail.SERVER, e.message)
        }
    }

    /**
     * 解析成功响应。
     *
     * `data[0].b64_json` 优先；万一服务端只回 `url`（老版本或参数被改写），
     * 这里也不报错 —— 交给调用方用 `url` 二次下载。
     */
    private fun parseOk(text: String): Result? {
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        val data = root.getAsJsonArray("data") ?: return null
        val first = data.firstOrNull()?.asJsonObject ?: return null

        val size = first.get("size")?.asString
        val b64 = first.get("b64_json")?.asString
        if (!b64.isNullOrBlank()) {
            val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return Result.Ok(bytes, size)
        }
        val url = first.get("url")?.asString
        if (!url.isNullOrBlank()) {
            val bytes = runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return Result.Ok(bytes, size)
        }
        return null
    }

    private fun mapFail(code: Int, body: String): Fail = when {
        code == 401 || code == 403 -> Fail.AUTH
        code == 429 -> Fail.RATE_LIMIT
        // 方舟内容审核不通过一般回 400 + 特定 code，用关键字兜住
        body.contains("ContentFilter", true) ||
            body.contains("sensitive", true) ||
            body.contains("审核", false) -> Fail.CONTENT
        else -> Fail.SERVER
    }

    private fun errorMessage(body: String): String? = runCatching {
        JsonParser.parseString(body).asJsonObject
            .getAsJsonObject("error")?.get("message")?.asString
    }.getOrNull()
}
