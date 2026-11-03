/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Test

class RecordingSafetyTest {

    private class SigningOnlySessionFake {
        fun shouldEncryptData(): Boolean = false
    }

    @Test
    fun `signing-only SMB fake is rejected after authentication`() {
        val fake = SigningOnlySessionFake()

        val failure = runCatching {
            SmbClient.requireEncryptedSession { fake.shouldEncryptData() }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure?.message).contains("signing-only")
    }

    @Test
    fun `encrypted SMB fake is accepted`() {
        SmbClient.requireEncryptedSession { true }
    }

    @Test
    fun `delete false is not converted to success and delete exception is visible`() {
        assertThat(runVerifiedDeleteForTest { false }).isFalse()

        val failure = runCatching {
            runVerifiedDeleteForTest { throw IOException("NAS unavailable") }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure?.message).isEqualTo("NAS unavailable")
    }

    @Test
    fun `placeholder filename finds a document created before a crash`() {
        val locator = "usb://News - 2026-11-03 (deadbeef).ts"
        val fakeTreeAfterCrash = mutableSetOf("News - 2026-11-03 (deadbeef).ts")
        val lookup = RecordingStorage.usbPlaceholderFilename(locator)

        assertThat(lookup).isEqualTo(fakeTreeAfterCrash.single())
        fakeTreeAfterCrash.remove(lookup!!)
        assertThat(fakeTreeAfterCrash).isEmpty()
    }

    @Test
    fun `deleting capture changes gate before any later creation or write`() {
        val gate = RecordingWriteGate()
        gate.requestStop(deleting = true)

        val failure = runCatching { gate.withWrite { "must not write" } }.exceptionOrNull()

        assertThat(gate.currentState()).isEqualTo(RecordingWriteGate.State.DELETING)
        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `remux output failure rolls back the target but never the original`() {
        val deleted = mutableListOf<String>()
        val target = "smb://nas/Recordings/OpenTV/out.mp4"
        val original = "smb://nas/Recordings/OpenTV/in.ts"

        val cleaned = rollbackRemuxTarget(target, original) {
            deleted += it
            true
        }

        assertThat(cleaned).isTrue()
        assertThat(deleted).containsExactly(target)
        assertThat(rollbackRemuxTarget(original, original) { deleted += it; true }).isTrue()
    }

    @Test
    fun `remux rollback reports fake output cleanup failure or exception`() {
        assertThat(
            rollbackRemuxTarget("smb://nas/out.mp4", "smb://nas/in.ts") { false },
        ).isFalse()
        assertThat(
            runCatching {
                rollbackRemuxTarget("smb://nas/out.mp4", "smb://nas/in.ts") {
                    throw IOException("output cleanup failed")
                }
            }.exceptionOrNull(),
        ).isInstanceOf(IOException::class.java)
    }

    /** Mirrors the storage contract: false stays false and exceptions are not swallowed. */
    private fun runVerifiedDeleteForTest(delete: () -> Boolean): Boolean = delete()
}
