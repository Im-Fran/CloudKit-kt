package io.github.imfran.cloudkit.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.imfran.cloudkit.CloudKitStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [CloudKitStore] on SharedPreferences, values encrypted with an AES-GCM key kept in the Android Keystore.
 * Use it for `Auth.ApiToken(store = …)` (web auth token) and `CloudKitSync` (change tokens).
 * Values that can't be decrypted (e.g. restored from a backup on another device) read as null.
 */
public class PreferencesStore(context: Context, name: String = "cloudkit") : CloudKitStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val key: SecretKey by lazy { loadOrCreateKey() }

    override fun get(key: String): String? = prefs.getString(key, null)?.let { runCatching { decrypt(it) }.getOrNull() }

    override fun set(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, encrypt(value)) }.apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val data = cipher.doFinal(value.toByteArray())
        return b64(cipher.iv) + ":" + b64(data)
    }

    private fun decrypt(stored: String): String {
        val (iv, data) = stored.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return String(cipher.doFinal(data))
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val ALIAS = "io.github.imfran.cloudkit"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun loadOrCreateKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        }
    }
}
