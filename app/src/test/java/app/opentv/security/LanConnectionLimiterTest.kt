/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LanConnectionLimiterTest {

    @Test
    fun `limits simultaneous workers per ip while allowing another legitimate peer`() {
        val limiter = LanConnectionLimiter(maxConcurrentPerIp = 1)
        val first = limiter.tryAcquire("192.168.1.10")
        val sameIp = limiter.tryAcquire("192.168.1.10")
        val otherIp = limiter.tryAcquire("192.168.1.11")

        assertThat(first).isNotNull()
        assertThat(sameIp).isNull()
        assertThat(otherIp).isNotNull()

        first!!.close()
        otherIp!!.close()
    }

    @Test
    fun `caps repeated admissions in a short window`() {
        var now = 1_000L
        val limiter = LanConnectionLimiter(
            maxConcurrent = 8,
            maxConcurrentPerIp = 8,
            windowMillis = 100L,
            maxAttemptsPerWindow = 2,
            nowMillis = { now },
        )
        val first = limiter.tryAcquire("10.0.0.5")
        val second = limiter.tryAcquire("10.0.0.5")
        val third = limiter.tryAcquire("10.0.0.5")

        assertThat(first).isNotNull()
        assertThat(second).isNotNull()
        assertThat(third).isNull()

        first!!.close()
        second!!.close()
        now += 101L
        assertThat(limiter.tryAcquire("10.0.0.5")).isNotNull()
    }

    @Test
    fun `reset preserves in-flight accounting for the same peer`() {
        val limiter = LanConnectionLimiter(maxConcurrent = 2, maxConcurrentPerIp = 1)
        val oldSession = limiter.tryAcquire("192.168.1.20")!!
        limiter.reset()

        assertThat(limiter.tryAcquire("192.168.1.20")).isNull()
        oldSession.close()
        assertThat(limiter.tryAcquire("192.168.1.20")).isNotNull()
    }

    @Test
    fun `permit release is idempotent`() {
        val limiter = LanConnectionLimiter(maxConcurrent = 1)
        val permit = limiter.tryAcquire("10.0.0.6")!!
        permit.close()
        permit.close()

        assertThat(limiter.tryAcquire("10.0.0.6")).isNotNull()
    }
}
