package com.suivialimentation.android.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.suivialimentation.android.util.AppJson
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class SecureTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_auth", Context.MODE_PRIVATE)

    suspend fun load(): AuthSession? = withContext(Dispatchers.IO) {
        val encrypted = prefs.getString(KEY_SESSION, null) ?: return@withContext null
        runCatching {
            AppJson.decodeFromString<AuthSession>(decrypt(encrypted))
        }.getOrNull()
    }

    suspend fun save(session: AuthSession) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_SESSION, encrypt(AppJson.encodeToString(session)))
            .putString(KEY_LAST_INSTANCE_URL, session.instanceUrl)
            .commit()
        Unit
    }

    suspend fun loadLastInstanceUrl(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_LAST_INSTANCE_URL, null)?.takeIf(String::isNotBlank)
    }

    suspend fun saveLastInstanceUrl(instanceUrl: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_LAST_INSTANCE_URL, instanceUrl).commit()
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        // L'adresse du serveur est volontairement conservée lors d'une déconnexion
        // ou de l'expiration des jetons OAuth. Seuls les secrets de session sont effacés.
        prefs.edit().remove(KEY_SESSION).commit()
        Unit
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val cipherText = Base64.encodeToString(cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$iv:$cipherText"
    }

    private fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(cipherText).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_SESSION = "session"
        const val KEY_LAST_INSTANCE_URL = "last_instance_url"
        const val KEY_ALIAS = "suivi_alimentation_auth_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
