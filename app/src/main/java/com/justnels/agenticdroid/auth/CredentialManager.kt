package com.justnels.agenticdroid.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores credentials with an app-owned AES/GCM key held directly by Android Keystore. */
class CredentialManager(private val context: Context) {
    companion object {
        const val GITHUB_TOKEN = "github_token"
        private const val KEY_ALIAS = "agenticdroid_credentials_v2"
        private const val STORE_NAME = "secure_prefs_v2"
        private const val MIGRATION_FLAG = "legacy_migrated"
    }

    private val preferences = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        migrateLegacyStoreOnce()
    }

    @Synchronized
    fun saveCredential(key: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        preferences.edit { putString(key, encoded) }
    }

    @Synchronized
    fun getCredential(key: String): String? {
        val encoded = preferences.getString(key, null) ?: return null
        return try {
            val parts = encoded.split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            // A restored/corrupt store or invalidated key cannot be decrypted safely.
            preferences.edit { remove(key) }
            null
        }
    }

    fun clearCredential(key: String) {
        preferences.edit { remove(key) }
    }

    fun clearAll() {
        preferences.edit { clear() }
        runCatching { keyStore.deleteEntry(KEY_ALIAS) }
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    @Suppress("DEPRECATION")
    private fun migrateLegacyStoreOnce() {
        if (preferences.getBoolean(MIGRATION_FLAG, false)) return
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val legacy = EncryptedSharedPreferences.create(
                context,
                "secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            legacy.all.forEach { (key, value) ->
                if (value is String && preferences.getString(key, null) == null) saveCredential(key, value)
            }
            legacy.edit { clear() }
        }
        preferences.edit { putBoolean(MIGRATION_FLAG, true) }
    }
}
