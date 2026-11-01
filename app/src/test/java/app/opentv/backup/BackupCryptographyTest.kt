/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.backup

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
}
