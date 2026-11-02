/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.backup

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptographyTest {
    @Test
    fun encryptedBackupRoundTripsUtf8Payload() {
        val plain = "FullTV backup — canais, favoritos e configuração".toByteArray(Charsets.UTF_8)
        val encrypted = BackupCryptography.encrypt(plain, "senha-forte-2026")

        assertArrayEquals(plain, BackupCryptography.decrypt(encrypted, "senha-forte-2026"))
    }

    @Test
    fun wrongPassphraseIsRejected() {
        val encrypted = BackupCryptography.encrypt("secret".toByteArray(), "correct-passphrase")
        val failure = runCatching { BackupCryptography.decrypt(encrypted, "incorrect-passphrase") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun modifiedCiphertextIsRejected() {
        val encrypted = BackupCryptography.encrypt("secret".toByteArray(), "correct-passphrase")
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        val failure = runCatching { BackupCryptography.decrypt(encrypted, "correct-passphrase") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun newContainerRequiresTwelveCharacterPassphrase() {
        val failure = runCatching { BackupCryptography.encrypt(byteArrayOf(1), "shortpass") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun legacyV1BackupStillDecrypts() {
        val passphrase = "legacy-passphrase"
        val plain = "legacy backup".toByteArray()
        val compressed = ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(plain) }
        }.toByteArray()
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 120_000, 256)
        val rawKey = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(rawKey, "AES"), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(compressed)
        val legacyFile = "FULLTVBK".toByteArray() + byteArrayOf(1) + salt + nonce + ciphertext

        assertArrayEquals(plain, BackupCryptography.decrypt(legacyFile, passphrase))
    }

    @Test
    fun modifiedAuthenticatedHeaderIsRejected() {
        val encrypted = BackupCryptography.encrypt("secret".toByteArray(), "correct-passphrase")
        encrypted[13] = (encrypted[13].toInt() xor 1).toByte() // First salt byte.
        val failure = runCatching { BackupCryptography.decrypt(encrypted, "correct-passphrase") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
