package com.turing.thumbcard.data.dict

import android.util.Log
import com.turing.thumbcard.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * 词典请求构造（TuringOS-Retrieval-V2.0）。
 *
 * ── 实测结论（2026-10-20 端到端验证）────────────────────────────
 * 官方文档写明 `data` 字段需 AES/CBC/PKCS5 + Base64 加密，
 * 但**实际服务端并不接受该密文**：按文档实现（含正确密钥）恒返回
 * `4008 数据内容格式错误`；而**明文 data 可以直接查询成功**。
 *
 * 结论：
 *  1. 该接口当前**不需要加密，也不需要 Secret**。
 *     → 因此本项目**不内置 Secret**，从根上消除「Secret 硬编码泄露」风险。
 *  2. 协议是「一连接一次请求」：服务端依次返回
 *     `210 参数初始化成功` → `220 参数上传完成` → `200 success`（带 nlpResponse）→ done=true。
 *     因此客户端必须**等 done=true 再关闭**，不能收到第一帧就走。
 *  3. 若未来服务端强制加密，只需在本类恢复 [encryptWithSecret] 分支即可
 *     （届时 Secret 应由云端代理签名下发，不要重新写入 APK）。
 */
object DictRequest {

    private const val TAG = "DictRequest"

    /** 官方建议的词典顺序（避免输出古诗词等非必需类型） */
    val DEFAULT_DICTS = listOf(
        "cn_char", "cn_idiom", "cn_word_person", "cn_word", "en_word"
    )

    /**
     * 组装外层请求：{ key, timestamp, data }
     *
     * @param content 待查词，如 "Carton" / "纸箱"
     */
    fun build(
        content: String,
        dicts: List<String> = DEFAULT_DICTS,
        deviceId: String = "thumbcard0000001"
    ): String {
        val ts = System.currentTimeMillis()

        val inner = JSONObject().apply {
            // deviceId 官方要求 16 位字母数字
            put("deviceId", deviceId.take(16).padEnd(16, '0'))
            put("requestType", JSONArray().put(1))   // 1 = NLP
            put(
                "nlpRequest",
                JSONObject().apply {
                    put("content", JSONArray().put(JSONObject().put("data", content)))
                    put(
                        "clientInfo",
                        JSONObject().apply {
                            put(
                                "appState",
                                JSONObject().put("code", 1000633).put("operateState", 1101)
                            )
                            put(
                                "robotSkill",
                                JSONObject().put(
                                    "1000633",
                                    JSONObject().put("dicts", JSONArray(dicts))
                                )
                            )
                        }
                    )
                }
            )
        }

        return JSONObject().apply {
            put("key", BuildConfig.DEFAULT_API_KEY)
            put("timestamp", ts.toString())
            // 明文（实测可用的方式，见类注释）
            put("data", inner.toString())
        }.toString()
    }

    /**
     * 备用：文档描述的 AES 加密方式。
     *
     * ⚠️ 当前服务端不接受（返回 4008），仅保留以备协议变更。
     * ⚠️ 使用它需要 Secret，请勿把 Secret 写回客户端，
     *    应改为调用自家云端接口获取签名结果。
     */
    fun encryptWithSecret(plain: String, apiKey: String, secret: String, timestamp: Long): String? =
        runCatching {
            val md5 = java.security.MessageDigest.getInstance("MD5")
                .digest("$apiKey$secret$timestamp".toByteArray(Charsets.UTF_8))
            val key16 = md5.joinToString("") { String.format("%02x", it) }.substring(0, 16)

            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            val kb = key16.toByteArray(Charsets.UTF_8)
            cipher.init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(kb, "AES"),
                javax.crypto.spec.IvParameterSpec(kb)
            )
            android.util.Base64.encodeToString(
                cipher.doFinal(plain.toByteArray(Charsets.UTF_8)),
                android.util.Base64.NO_WRAP
            )
        }.onFailure { Log.w(TAG, "encrypt failed", it) }.getOrNull()
}
