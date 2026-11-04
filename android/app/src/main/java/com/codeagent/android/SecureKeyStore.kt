package com.codeagent.android

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
 * Encrypts provider API keys with an AES-256 key held by Android Keystore.
 * Only the ciphertext and IV are written to app-private preferences.
 */
class SecureKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)

    fun save(provider: String, apiKey: String) {
        require(provider.matches(Regex("[a-zA-Z0-9_-]{1,40}"))) { "Invalid provider id" }
        require(apiKey.isNotBlank()) { "API key cannot be empty" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString("${provider}_iv", iv).putString("${provider}_data", body).commit()) {
            "Could not persist encrypted key"
        }
        // Verify persistence and Keystore decryption before reporting success.
        check(read(provider) == apiKey) {
            "API key was written but could not be verified. Re-enter the key and try again."
        }
    }

    fun read(provider: String): String? {
        val ivText = prefs.getString("${provider}_iv", null) ?: return null
        val bodyText = prefs.getString("${provider}_data", null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(bodyText, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun delete(provider: String) {
        prefs.edit().remove("${provider}_iv").remove("${provider}_data").apply()
    }

    fun hasKey(provider: String): Boolean = prefs.contains("${provider}_data")

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "android_code_agent_api_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
