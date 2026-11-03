/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import android.content.Context
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Per-install sync-group key, encrypted at rest with a non-exportable Android Keystore AES key.
 * The raw key is transferred only inside the pinned HTTPS sync bundle or the user's encrypted
 * password-protected backup; NAS rendezvous files contain only HMAC identifiers.
 */
object SyncKeyStore {
    private const val PREFS_NAME = "fulltv_sync_security"
    private const val VALUE_KEY = "encrypted_group_key_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "fulltv_sync_group_aes_v1"
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val NEXT_SEQUENCE_KEY = "next_bundle_sequence"
    private const val ACCEPTED_SEQUENCE_PREFIX = "accepted_bundle_sequence_"
    private val lock = Any()
    private val random = SecureRandom()

    fun getOrCreate(context: Context): ByteArray = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encoded = prefs.getString(VALUE_KEY, null)
        if (encoded != null) return@synchronized decrypt(encoded)
        ByteArray(KEY_BYTES).also(random::nextBytes).also { store(context, it) }
    }

    /** Adopt a peer's key only after its transfer bundle has passed the pinned TLS checks. */
    fun replace(context: Context, key: ByteArray) = synchronized(lock) {
        require(key.size == KEY_BYTES) { "The sync-group key has an invalid size." }
        store(context, key)
    }

    fun encodeForTransfer(key: ByteArray): String {
        require(key.size == KEY_BYTES)
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }

    fun decodeTransferKey(encoded: String): ByteArray? = runCatching {
        Base64.decode(encoded, Base64.NO_WRAP).takeIf { it.size == KEY_BYTES }
    }.getOrNull()

    /** Allocates the next authenticated sender counter durably before a bundle is published. */
    fun nextSequence(context: Context): Long = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getLong(NEXT_SEQUENCE_KEY, 0L)
        require(current < Long.MAX_VALUE) { "The sync sequence counter is exhausted." }
        val next = current + 1L
        check(prefs.edit().putLong(NEXT_SEQUENCE_KEY, next).commit()) {
            "Could not persist the sync sequence counter."
        }
        next
    }

    /**
     * Atomically records a peer counter. A counter is claimed before applying any bundle fields;
     * an older or equal counter is therefore rejected after process/device restarts as well.
     */
    fun acceptSequence(context: Context, senderDeviceId: String, sequence: Long): Boolean = synchronized(lock) {
        require(senderDeviceId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            "The sync sender identity is invalid."
        }
        require(sequence > 0L) { "The sync sequence must be positive." }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = ACCEPTED_SEQUENCE_PREFIX + sequenceKey(senderDeviceId)
        val previous = prefs.getLong(key, 0L)
        if (sequence <= previous) return@synchronized false
        check(prefs.edit().putLong(key, sequence).commit()) {
            "Could not persist the accepted sync sequence."
        }
        true
    }

    private fun sequenceKey(senderDeviceId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(senderDeviceId.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 16)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun store(context: Context, key: ByteArray) {
        val encoded = encrypt(key)
        check(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(VALUE_KEY, encoded).commit()) {
            "Could not persist the encrypted sync-group key."
        }
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, androidKey())
        val sealed = cipher.doFinal(plain)
        val packed = cipher.iv + sealed
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size == IV_BYTES + KEY_BYTES + TAG_BITS / 8) {
            "The encrypted sync-group key is damaged."
        }
        val iv = packed.copyOfRange(0, IV_BYTES)
        val ciphertext = packed.copyOfRange(IV_BYTES, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, androidKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext).also {
            require(it.size == KEY_BYTES) { "The sync-group key is damaged." }
        }
    }

    private fun androidKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}

/** HMAC identifiers keep provider URLs/passwords out of NAS/lan sync JSON. */
internal object SyncPrivacy {
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val GROUP_DOMAIN = "FullTV/sync-group/v4"
    private const val URL_DOMAIN = "FullTV/sync-url/v4"

    fun mediaKey(key: ByteArray, kind: String, url: String): String {
        val domain = URL_DOMAIN.toByteArray(Charsets.UTF_8)
        val kindBytes = kind.toByteArray(Charsets.UTF_8)
        val urlBytes = url.toByteArray(Charsets.UTF_8)
        val message = ByteBuffer.allocate(4 + domain.size + 4 + kindBytes.size + 4 + urlBytes.size)
            .putInt(domain.size).put(domain)
            .putInt(kindBytes.size).put(kindBytes)
            .putInt(urlBytes.size).put(urlBytes)
            .array()
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(message).toHex()
    }

    fun groupId(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(GROUP_DOMAIN.toByteArray(Charsets.UTF_8) + key)
        return digest.copyOfRange(0, 12).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
