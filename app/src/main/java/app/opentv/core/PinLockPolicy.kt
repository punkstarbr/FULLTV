/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/** Persisted PIN lock state. [lastElapsedMillis] is used to detect an uptime reset (reboot). */
data class PinLockState(
    val failures: Int = 0,
    val lockUntilElapsedMillis: Long = 0L,
    val lockDurationMillis: Long = 0L,
    val lastElapsedMillis: Long = -1L,
)

/**
 * Pure monotonic PIN backoff policy. Wall-clock time is intentionally absent: changing the clock
 * cannot expire a lock while the device remains on. If uptime goes backwards, the previous lock
 * duration is conservatively restarted in the new boot instead of clearing the failure count.
 */
internal object PinLockPolicy {
    const val MIN_LOCK_FAILURES = 5

    fun reconcile(state: PinLockState, nowElapsedMillis: Long): PinLockState {
        require(nowElapsedMillis >= 0L)
        val duration = state.lockDurationMillis.takeIf { it > 0L } ?: lockMillis(state.failures)
        val rebooted = state.lastElapsedMillis >= 0L && nowElapsedMillis < state.lastElapsedMillis
        val lockUntil = when {
            state.failures < MIN_LOCK_FAILURES || duration <= 0L -> 0L
            rebooted || state.lockUntilElapsedMillis <= 0L -> safeAdd(nowElapsedMillis, duration)
            else -> state.lockUntilElapsedMillis
        }
        return state.copy(
            lockUntilElapsedMillis = lockUntil,
            lockDurationMillis = duration,
            lastElapsedMillis = nowElapsedMillis,
        )
    }

    fun registerFailure(state: PinLockState, nowElapsedMillis: Long): PinLockState {
        require(nowElapsedMillis >= 0L)
        val failures = (state.failures + 1).coerceAtLeast(1)
        val duration = lockMillis(failures)
        return PinLockState(
            failures = failures,
            lockUntilElapsedMillis = if (duration == 0L) 0L else safeAdd(nowElapsedMillis, duration),
            lockDurationMillis = duration,
            lastElapsedMillis = nowElapsedMillis,
        )
    }

    fun successful(state: PinLockState, nowElapsedMillis: Long): PinLockState {
        require(nowElapsedMillis >= 0L)
        return PinLockState(lastElapsedMillis = nowElapsedMillis)
    }

    fun isLocked(state: PinLockState, nowElapsedMillis: Long): Boolean =
        nowElapsedMillis < state.lockUntilElapsedMillis

    fun lockMillis(failures: Int): Long = when {
        failures < MIN_LOCK_FAILURES -> 0L
        failures < 10 -> (failures - 4).toLong() * 10_000L
        failures < 15 -> (failures - 9).toLong() * 60_000L
        else -> 30L * 60_000L
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
