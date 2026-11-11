package io.github.kylin33.aifitgym.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 的加密存储。
 *
 * 没有引入 `androidx.security:security-crypto`，两个原因：
 *  1. 那个库自带一套 Tink 依赖，而 `EncryptedSharedPreferences` 的 1.1.x 长期停在 alpha；
 *  2. 它内部做的事（Keystore 里的 AES 主密钥 + 逐条加密）用系统 API 三十行就能写完，
 *     没有第三方依赖也就没有它的兼容问题。
 *
 * 实现要点：
 *  - 密钥住在 **AndroidKeyStore** 里，进程拿到的是句柄，私钥材料永远不出 TEE/StrongBox；
 *  - AES/GCM 带认证标签，密文被改一个字节解密就会抛异常，不会被静默解出垃圾；
 *  - IV 每次随机生成并跟着密文一起存（GCM 复用 IV 是致命的，这里绝不复用）。
 *
 * 解密失败（换机恢复备份、系统清了 Keystore）时返回 null 并清掉脏数据，
 * 上层据此提示「需要重新填写 Key」，而不是让用户面对一个永远 401 的输入框。
 */
object AiSecretStore {

    private const val PREFS = "ai_secrets"
    private const val KEY_ALIAS = "fitgym_ai_api_key"
    private const val FIELD = "api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun save(context: Context, plainKey: String?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val trimmed = plainKey?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(FIELD).apply()
            return
        }
        val payload = runCatching { encrypt(trimmed) }.getOrNull() ?: return
        prefs.edit().putString(FIELD, payload).apply()
    }

    fun read(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val payload = prefs.getString(FIELD, null) ?: return null
        val plain = runCatching { decrypt(payload) }.getOrNull()
        if (plain == null) prefs.edit().remove(FIELD).apply()
        return plain
    }

    fun hasKey(context: Context): Boolean = read(context) != null

    // ------------------------------------------------------------ 加解密

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // [1 字节 IV 长度][IV][密文]，一起 Base64
        val packed = ByteArray(1 + iv.size + body.size)
        packed[0] = iv.size.toByte()
        iv.copyInto(packed, 1)
        body.copyInto(packed, 1 + iv.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val packed = Base64.decode(payload, Base64.NO_WRAP)
        require(packed.size > 2) { "密文长度异常" }
        val ivSize = packed[0].toInt()
        require(ivSize in 12..16 && packed.size > 1 + ivSize) { "IV 长度异常" }
        val iv = packed.copyOfRange(1, 1 + ivSize)
        val body = packed.copyOfRange(1 + ivSize, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
