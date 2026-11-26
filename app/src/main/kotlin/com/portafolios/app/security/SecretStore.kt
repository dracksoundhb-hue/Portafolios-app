package com.portafolios.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets (database passphrase, market data API key) encrypted with an AES-256-GCM key that
 * lives in the Android Keystore and never leaves it. The key does not require user authentication,
 * so the background price-alert worker can open the database; the app itself is protected by the
 * biometric/PIN lock in [com.portafolios.app.MainActivity].
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_SIZE))
        return cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE)
    }

    /** Random 32-byte passphrase for SQLCipher, created on first launch. */
    @Synchronized
    fun databasePassphrase(): ByteArray {
        prefs.getString(DB_PASSPHRASE, null)?.let { return decrypt(it) }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(DB_PASSPHRASE, encrypt(fresh)).commit()
        return fresh
    }

    fun getString(name: String): String? = prefs.getString("s_$name", null)?.let { String(decrypt(it), Charsets.UTF_8) }

    fun putString(name: String, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove("s_$name") else putString("s_$name", encrypt(value.toByteArray(Charsets.UTF_8)))
        }.apply()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "portafolios_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val DB_PASSPHRASE = "db_passphrase"
        const val FINNHUB_API_KEY = "finnhub_api_key"
    }
}
