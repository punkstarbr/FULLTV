/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.InvalidKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small encrypted store for secrets that should not remain as plaintext in app preferences. */
class EncryptedSecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** A typed result prevents callers from treating a broken ciphertext as an empty secret. */
    sealed interface ReadResult {
        data object Absent : ReadResult
        data class Present(val value: String) : ReadResult
        data object DecryptError : ReadResult
        data object KeystoreUnavailable : ReadResult
    }

    /**
     * Reads a ciphertext without ever returning plaintext from preferences as a fallback.
     * Keystore failures are kept distinct from an invalid/tampered ciphertext so migration code
     * can preserve a legacy value without exposing it to runtime consumers.
     */
    fun get(name: String): ReadResult = synchronized(lock) {
        require(name.matches(KEY_NAME)) { "Invalid secret key name." }
        val encoded = prefs.getString(name, null) ?: return@synchronized ReadResult.Absent
        try {
            ReadResult.Present(decrypt(name, encoded))
        } catch (_: KeystoreUnavailableException) {
            ReadResult.KeystoreUnavailable
        } catch (_: Exception) {
            ReadResult.DecryptError
        }
    }

    /** AES-GCM encrypts each value with a fresh random IV and binds ciphertext to its preference key. */
    fun put(name: String, value: String) = synchronized(lock) {
        require(name.matches(KEY_NAME)) { "Invalid secret key name." }
        if (value.isEmpty()) {
            check(prefs.edit().remove(name).commit()) { "Could not remove encrypted secret." }
            return@synchronized
        }
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val record = byteArrayOf(FORMAT_VERSION) + cipher.iv + encrypted
        check(prefs.edit().putString(name, Base64.encodeToString(record, Base64.NO_WRAP)).commit()) {
            "Could not persist encrypted secret."
        }
    }

    /** Non-exportable Keystore HMAC key; the result can be stored for local verification without enabling offline guesses. */
    fun hmac(name: String, value: String): ByteArray = synchronized(lock) {
        require(name.matches(KEY_NAME)) { "Invalid secret key name." }
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(hmacKey())
            update(name.toByteArray(Charsets.UTF_8))
            update(0.toByte())
            doFinal(value.toByteArray(Charsets.UTF_8))
        }
    }

    private fun decrypt(name: String, encoded: String): String {
        require(name.matches(KEY_NAME)) { "Invalid secret key name." }
        val record = Base64.decode(encoded, Base64.NO_WRAP)
        require(record.size >= 1 + IV_BYTES + GCM_TAG_BYTES && record[0] == FORMAT_VERSION) {
            "Invalid encrypted secret record."
        }
        val iv = record.copyOfRange(1, 1 + IV_BYTES)
        val ciphertext = record.copyOfRange(1 + IV_BYTES, record.size)
        val key = try {
            aesKey()
        } catch (error: Exception) {
            throw KeystoreUnavailableException(error)
        }
        try {
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (error: InvalidKeyException) {
            throw KeystoreUnavailableException(error)
        }
    }

    private class KeystoreUnavailableException(cause: Throwable) : Exception(cause)

    private fun aesKey(): SecretKey = getOrCreateKey(AES_ALIAS) {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    AES_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private fun hmacKey(): SecretKey = getOrCreateKey(HMAC_ALIAS) {
        KeyGenerator.getInstance(HMAC_ALGORITHM, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    HMAC_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun getOrCreateKey(alias: String, generate: () -> SecretKey): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return generate()
    }

    private companion object {
        const val PREFS_NAME = "opentv_secrets_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_ALIAS = "app.fulltv.secret.aes.v1"
        const val HMAC_ALIAS = "app.fulltv.secret.hmac.v1"
        const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val IV_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val FORMAT_VERSION: Byte = 1
        val KEY_NAME = Regex("[a-z0-9_]{1,80}")
        val lock = Any()
    }
}
