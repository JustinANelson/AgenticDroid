package com.justnels.agenticdroid.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages secure storage of credentials (SSH passwords, API keys).
 */
class CredentialManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveCredential(key: String, value: String) {
        sharedPreferences.edit().putString(key, value).apply()
    }

    fun getCredential(key: String): String? {
        return sharedPreferences.getString(key, null)
    }

    fun clearCredential(key: String) {
        sharedPreferences.edit().remove(key).apply()
    }
}
