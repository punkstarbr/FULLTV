/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncAuthenticatorTest {
    private val key = ByteArray(32) { (it + 1).toByte() }

    private fun bundle(): SyncBundle = SyncBundle(
        version = 5,
        groupId = "0123456789abcdef01234567",
        generatedAtMillis = 1234L,
        senderDeviceId = "sender-1",
        sequence = 7L,
        profiles = listOf("Adult"),
        positions = listOf(
            SyncPosition(
                profile = "Adult",
                mediaKey = "a".repeat(64),
                kind = "movie",
                positionMillis = 10,
                durationMillis = 100,
                updatedAtMillis = 99,
            ),
        ),
        hiddenCategories = listOf("adult"),
        recordings = listOf(
            SyncRecording(
                channelName = "News",
                title = "Report",
                relativePath = "2026/report.ts",
                scheduledStartMillis = 1,
                scheduledEndMillis = 2,
                startedAtMillis = 1,
                endedAtMillis = 2,
                sizeBytes = 3,
            ),
        ),
    )

    @Test
    fun `MAC covers every security relevant field`() {
        val signed = SyncAuthenticator.sign(key, bundle())

        assertThat(SyncAuthenticator.verify(key, signed)).isTrue()
        assertThat(SyncAuthenticator.verify(key, signed.copy(generatedAtMillis = 1235L))).isFalse()
        assertThat(SyncAuthenticator.verify(key, signed.copy(senderDeviceId = "other"))).isFalse()
        assertThat(SyncAuthenticator.verify(key, signed.copy(sequence = 8L))).isFalse()
        assertThat(SyncAuthenticator.verify(key, signed.copy(profiles = listOf("Child")))).isFalse()
        assertThat(SyncAuthenticator.verify(key, signed.copy(hiddenCategories = listOf("safe")))).isFalse()
        assertThat(
            SyncAuthenticator.verify(
                key,
                signed.copy(recordings = signed.recordings.map { it.copy(relativePath = "other.ts") }),
            ),
        ).isFalse()
        assertThat(
            SyncAuthenticator.verify(
                key,
                signed.copy(positions = signed.positions.map { it.copy(updatedAtMillis = 100) }),
            ),
        ).isFalse()
    }

    @Test
    fun `MAC comparison rejects altered tag and wrong group key`() {
        val signed = SyncAuthenticator.sign(key, bundle())
        val alteredFirstNibble = signed.mac.replaceRange(0, 1, if (signed.mac[0] == '0') "1" else "0")

        assertThat(SyncAuthenticator.verify(key, signed.copy(mac = alteredFirstNibble))).isFalse()
        assertThat(SyncAuthenticator.verify(ByteArray(32) { 9 }, signed)).isFalse()
    }

    @Test
    fun `canonical bytes are stable and do not contain a MAC value`() {
        val signed = SyncAuthenticator.sign(key, bundle())

        assertThat(SyncAuthenticator.canonicalBytes(signed)).isEqualTo(SyncAuthenticator.canonicalBytes(bundle()))
        assertThat(SyncAuthenticator.canonicalBytes(signed).toString(Charsets.UTF_8)).doesNotContain(signed.mac)
    }
}
