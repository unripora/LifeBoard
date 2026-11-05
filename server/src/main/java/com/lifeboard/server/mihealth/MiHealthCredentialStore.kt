package com.lifeboard.server.mihealth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 小米健康凭据的持久化存储。
 *
 * 用 Android Keystore 里的一把 AES-256-GCM 密钥加密后再落 SharedPreferences：
 * - 密钥由系统 TEE 保管，**不会被导出**，即使拿到应用私有目录也解不出明文
 * - GCM 自带完整性校验，被篡改会解密失败而不是静默返回垃圾
 *
 * 存的是 [MiHealthCredentials]（含 serviceToken / passToken），
 * 属于敏感数据，不能明文放 SharedPreferences。
 */
object MiHealthCredentialStore {

    private const val TAG = "LifeBoardMiHealth"
    private const val FILE = "mihealth_credentials"
    private const val KEY_CRED = "credentials"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "lifeboard_mihealth_creds"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 读取凭据；未登录、解密失败都返回 null（解密失败说明密钥已失效，视为未登录） */
    fun load(context: Context): MiHealthCredentials? {
        val raw = prefs(context).getString(KEY_CRED, null) ?: return null
        return runCatching {
            val blob = Base64.decode(raw, Base64.NO_WRAP)
            // 前 12 字节是 IV，其后是密文
            if (blob.size <= IV_SIZE) return@runCatching null
            val iv = blob.copyOfRange(0, IV_SIZE)
            val cipherText = blob.copyOfRange(IV_SIZE, blob.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plain = cipher.doFinal(cipherText)
            MiHealthCredentials.parse(String(plain, Charsets.UTF_8))
        }.getOrElse { e ->
            Log.w(TAG, "load credentials failed, treating as logged out", e)
            null
        }
    }

    /** 保存凭据（覆盖写） */
    fun save(context: Context, credentials: MiHealthCredentials): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(credentials.serialize().toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + cipherText
        prefs(context).edit()
            .putString(KEY_CRED, Base64.encodeToString(blob, Base64.NO_WRAP))
            .apply()
        true
    }.getOrElse { e ->
        Log.w(TAG, "save credentials failed", e)
        false
    }

    /** 退出登录 */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_CRED).apply()
    }

    fun isLoggedIn(context: Context): Boolean = load(context)?.isValid() == true

    /**
     * 诊断信息：凭据读不出来时用来区分「没存过」和「存了但解不开」。
     *
     * 这台设备上 App 的 logcat 是空的，只能把结论显示到界面上排查。
     */
    fun debugInfo(context: Context): String {
        val raw = prefs(context).getString(KEY_CRED, null)
        if (raw == null) return "诊断：本地没有保存过凭据（需要登录一次）"
        val blobSize = runCatching { Base64.decode(raw, Base64.NO_WRAP).size }.getOrDefault(-1)
        val cred = load(context)
        if (cred == null) {
            return "诊断：凭据存在但解密失败（密文 $blobSize 字节），Keystore 密钥可能已失效"
        }
        return "诊断：解密成功，ssecurity=${cred.ssecurity.length} " +
            "serviceToken=${cred.serviceToken.length} cUserId=${cred.cUserId.length} " +
            "passToken=${cred.passToken.length}"
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
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

    private const val IV_SIZE = 12
}
