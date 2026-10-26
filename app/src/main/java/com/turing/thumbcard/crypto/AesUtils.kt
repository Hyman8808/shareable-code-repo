package com.turing.thumbcard.crypto

import android.util.Log
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128 加解密。
 *
 * ⚠️ 模式为 **ECB/PKCS7**，与联盛德官方 Android SDK `AESUtils` 一致。
 * 基线协议文档描述为 CBC，但三端官方 Demo 均为 ECB —— **以 ECB 为准**。
 * 若按 CBC 实现将导致与设备通信全部失败。
 */
object AesUtils {

    private const val TAG = "AesUtils"
    private var initialized = false

    private fun ensureProvider() {
        if (initialized) return
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        initialized = true
    }

    /**
     * AES-128 加密。
     *
     * @param src 明文
     * @param key 16 字节密钥
     * @return 密文；失败返回 null
     */
    fun encrypt(src: ByteArray, key: ByteArray?): ByteArray? {
        if (key == null || key.size != 16) {
            Log.e(TAG, "invalid aes key size: ${key?.size}")
            return null
        }
        return try {
            ensureProvider()
            val cipher = Cipher.getInstance("AES/ECB/PKCS7Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.doFinal(src)
        } catch (e: Exception) {
            Log.e(TAG, "encrypt failed", e)
            null
        }
    }

    /**
     * AES-128 解密。
     */
    fun decrypt(src: ByteArray, key: ByteArray?): ByteArray? {
        if (key == null || key.size != 16) {
            Log.e(TAG, "invalid aes key size: ${key?.size}")
            return null
        }
        return try {
            ensureProvider()
            val cipher = Cipher.getInstance("AES/ECB/PKCS7Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.doFinal(src)
        } catch (e: Exception) {
            Log.e(TAG, "decrypt failed", e)
            null
        }
    }
}
