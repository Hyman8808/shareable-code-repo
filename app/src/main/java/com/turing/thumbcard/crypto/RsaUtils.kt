package com.turing.thumbcard.crypto

import android.util.Log
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.interfaces.RSAPrivateKey
import javax.crypto.Cipher

/**
 * RSA-1024 密钥对生成与解密。
 *
 * ⚠️ 填充模式为 **RSA/NONE/PKCS1Padding**，即无摘要的裸 PKCS#1 v1.5 填充。
 * 这是联盛德官方 SDK 使用的模式，与设备固件强耦合，不可替换为
 * `RSA/ECB/PKCS1Padding` 等带摘要的模式。
 *
 * 移植自官方 Android SDK `RSAUtils`。
 */
object RsaUtils {

    private const val TAG = "RsaUtils"
    private const val KEY_ALGORITHM = "RSA"
    private const val RSA_KEY_SIZE = 1024

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /** 持有生成的密钥对，供后续解密使用 */
    class KeyHolder(val keyPair: KeyPair) {
        /** 公钥的 X.509 DER 编码，直接作为 TLV 0x09 的 Value */
        fun publicKeyEncoded(): ByteArray = keyPair.public.encoded

        /** 私钥，用于解密设备返回的 AES 密钥 */
        fun privateKey(): RSAPrivateKey = keyPair.private as RSAPrivateKey
    }

    /**
     * 生成 RSA-1024 密钥对。
     */
    fun generateKeyPair(): KeyHolder {
        val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        generator.initialize(RSA_KEY_SIZE)
        return KeyHolder(generator.generateKeyPair())
    }

    /**
     * 用私钥解密设备返回的对称密钥。
     *
     * 设备随机生成 16 字节 AES Key，用 App 的公钥加密后通过 TLV 0x87 回传。
     *
     * @param holder     密钥对
     * @param cipherText TLV 0x87 的 Value（128 字节）
     * @return 16 字节 AES Key；失败返回 null
     */
    fun decryptSessionKey(holder: KeyHolder, cipherText: ByteArray?): ByteArray? {
        if (cipherText == null || cipherText.isEmpty()) {
            Log.e(TAG, "cipher text is empty")
            return null
        }
        return try {
            val cipher = Cipher.getInstance("RSA/NONE/PKCS1Padding", "BC")
            cipher.init(Cipher.DECRYPT_MODE, holder.privateKey())
            val plain = cipher.doFinal(cipherText)
            if (plain.size != 16) {
                // 理论上 PKCS1 解出的就是 16 字节，异常说明填充或密钥不匹配
                Log.w(TAG, "decrypted key size = ${plain.size}, expected 16")
            }
            plain
        } catch (e: Exception) {
            Log.e(TAG, "decrypt session key failed", e)
            null
        }
    }
}
