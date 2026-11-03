/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.security

/**
 * Small admission controller for the short-lived LAN servers.
 *
 * A permit limits both total handshake/request workers and simultaneous workers from one peer. A
 * bounded rolling attempt map also prevents a flood of unique LAN addresses from growing memory.
 */
internal class LanConnectionLimiter(
    private val maxConcurrent: Int = 4,
    private val maxConcurrentPerIp: Int = 2,
    private val windowMillis: Long = 10_000L,
    private val maxAttemptsPerWindow: Int = 8,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxTrackedIps: Int = 1_024,
) {
    private data class IpState(
        var windowStartMillis: Long,
        var attempts: Int = 0,
        var active: Int = 0,
    )

    private val lock = Any()
    private val byIp = HashMap<String, IpState>()
    private var active = 0

    fun tryAcquire(ip: String?): Permit? {
        val key = ip?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        synchronized(lock) {
            val now = nowMillis()
            removeExpiredIdleEntries(now)
            var state = byIp[key]
            if (state == null) {
                // Do not allocate one entry per rejected peer while all worker slots are occupied.
                if (active >= maxConcurrent || byIp.size >= maxTrackedIps) return null
                state = IpState(now)
                byIp[key] = state
            }
            if (windowElapsed(now, state.windowStartMillis)) {
                state.windowStartMillis = now
                state.attempts = 0
            }
            if (state.attempts >= maxAttemptsPerWindow ||
                active >= maxConcurrent ||
                state.active >= maxConcurrentPerIp
            ) {
                return null
            }
            state.attempts++
            state.active++
            active++
            return Permit(key)
        }
    }

    /** Clears attempt history at session start without losing accounting for in-flight old workers. */
    fun reset() = synchronized(lock) {
        val now = nowMillis()
        val iterator = byIp.values.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next()
            if (state.active == 0) {
                iterator.remove()
            } else {
                state.windowStartMillis = now
                state.attempts = 0
            }
        }
    }

    private fun removeExpiredIdleEntries(now: Long) {
        val iterator = byIp.values.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next()
            if (state.active == 0 && windowElapsed(now, state.windowStartMillis)) iterator.remove()
        }
    }

    private fun windowElapsed(now: Long, start: Long): Boolean =
        now >= start && now - start >= windowMillis

    internal inner class Permit(private val ip: String) : AutoCloseable {
        private var released = false

        override fun close() {
            synchronized(lock) {
                if (released) return
                released = true
                active = (active - 1).coerceAtLeast(0)
                byIp[ip]?.let { state ->
                    state.active = (state.active - 1).coerceAtLeast(0)
                    if (state.active == 0 && state.attempts == 0) byIp.remove(ip)
                }
            }
        }
    }
}
