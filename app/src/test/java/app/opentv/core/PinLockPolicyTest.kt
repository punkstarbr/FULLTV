/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PinLockPolicyTest {
    @Test
    fun `fifth failure creates a monotonic lock`() {
        var state = PinLockState()
        repeat(5) { state = PinLockPolicy.registerFailure(state, 1_000L) }

        assertThat(state.failures).isEqualTo(5)
        assertThat(state.lockDurationMillis).isEqualTo(10_000L)
        assertThat(PinLockPolicy.isLocked(state, 1_001L)).isTrue()
        assertThat(PinLockPolicy.isLocked(state, 11_000L)).isFalse()
    }

    @Test
    fun `uptime rollback keeps failures and restarts the lock in the new boot`() {
        val oldBoot = PinLockState(
            failures = 5,
            lockUntilElapsedMillis = 100_000L,
            lockDurationMillis = 10_000L,
            lastElapsedMillis = 90_000L,
        )

        val recovered = PinLockPolicy.reconcile(oldBoot, 500L)

        assertThat(recovered.failures).isEqualTo(5)
        assertThat(recovered.lastElapsedMillis).isEqualTo(500L)
        assertThat(recovered.lockUntilElapsedMillis).isEqualTo(10_500L)
        assertThat(PinLockPolicy.isLocked(recovered, 501L)).isTrue()
    }

    @Test
    fun `successful verification clears backoff but keeps current uptime anchor`() {
        val state = PinLockState(
            failures = 8,
            lockUntilElapsedMillis = 20_000L,
            lockDurationMillis = 40_000L,
            lastElapsedMillis = 10_000L,
        )

        val cleared = PinLockPolicy.successful(state, 21_000L)

        assertThat(cleared.failures).isEqualTo(0)
        assertThat(cleared.lockUntilElapsedMillis).isEqualTo(0L)
        assertThat(cleared.lastElapsedMillis).isEqualTo(21_000L)
    }
}
