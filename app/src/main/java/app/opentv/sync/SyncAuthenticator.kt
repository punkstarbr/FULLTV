/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json

/** Authenticates the complete v5 payload without ever putting a group key in NAS JSON. */
internal object SyncAuthenticator {
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val KEY_BYTES = 32
    private const val MAC_HEX_LENGTH = 64
    private val canonicalJson = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
        ignoreUnknownKeys = false
    }

    /**
     * Canonical JSON is compact, has defaults enabled, and uses the generated serializer's stable
     * declaration order. The MAC member itself is represented by the fixed empty value to avoid a
     * circular definition; every other v5 field, including the transferred key, is covered.
     */
    fun canonicalBytes(bundle: SyncBundle): ByteArray =
        canonicalJson.encodeToString(SyncBundle.serializer(), bundle.copy(mac = ""))
            .toByteArray(StandardCharsets.UTF_8)

    fun sign(key: ByteArray, bundle: SyncBundle): SyncBundle =
        bundle.copy(mac = compute(key, bundle))

    fun verify(key: ByteArray, bundle: SyncBundle): Boolean {
        if (key.size != KEY_BYTES || !bundle.mac.matches(Regex("[0-9a-f]{$MAC_HEX_LENGTH}"))) {
            return false
        }
        val expected = compute(key, bundle).toByteArray(StandardCharsets.US_ASCII)
        val supplied = bundle.mac.toByteArray(StandardCharsets.US_ASCII)
        // Both inputs are fixed at 64 ASCII bytes after validation.
        return MessageDigest.isEqual(expected, supplied)
    }

    fun compute(key: ByteArray, bundle: SyncBundle): String {
        require(key.size == KEY_BYTES) { "The sync-group key has an invalid size." }
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(canonicalBytes(bundle)).toHex()
    }

    fun legacyIdentity(bundle: SyncBundle): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalBytes(bundle))
        return digest.copyOfRange(0, 16).toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
