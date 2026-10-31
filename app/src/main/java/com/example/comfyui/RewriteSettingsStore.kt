package com.example.comfyui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.comfyui.network.RewriteSettings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class RewriteSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("llm_settings", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("comfyui_rewrite_api_key", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("comfyui_rewrite_api_key", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun load(): RewriteSettings {
        val encrypted = prefs.getString("api_key_encrypted", "").orEmpty()
        val secret = if (encrypted.isEmpty()) "" else {
            val parts = encrypted.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }
        return RewriteSettings(prefs.getString("address", "").orEmpty(), secret, prefs.getString("model", "").orEmpty())
    }

    fun save(settings: RewriteSettings) {
        val encrypted = if (settings.apiKey.isEmpty()) "" else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipher.doFinal(settings.apiKey.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
        prefs.edit().putString("address", settings.address.trim()).putString("model", settings.model.trim())
            .putString("api_key_encrypted", encrypted).apply()
    }
}
