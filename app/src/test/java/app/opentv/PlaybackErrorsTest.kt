/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import android.content.res.Configuration
import androidx.media3.common.PlaybackException
import androidx.test.core.app.ApplicationProvider
import app.opentv.player.PlaybackErrors
import com.google.common.truth.Truth.assertThat
import java.net.UnknownHostException
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlaybackErrorsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `retry policy retries transient HTTP but not permanent responses`() {
        for (code in listOf(403, 405, 408, 429, 500, 502, 503, 504, 599)) {
            assertThat(PlaybackErrors.shouldAutomaticallyRetryHttpStatus(code)).isTrue()
        }
        for (code in listOf(400, 401, 404, 410, 451, 499, 600)) {
            assertThat(PlaybackErrors.shouldAutomaticallyRetryHttpStatus(code)).isFalse()
        }
    }

    @Test
    fun `connectivity recovery only reacts to transient server responses`() {
        for (code in listOf(408, 429, 500, 503, 599)) {
            assertThat(PlaybackErrors.isNetworkHttpStatus(code)).isTrue()
        }
        for (code in listOf(401, 403, 404, 410, 451)) {
            assertThat(PlaybackErrors.isNetworkHttpStatus(code)).isFalse()
        }
    }

    @Test
    fun `automatic retry budget is finite for transient failures and timeouts`() {
        val transient = PlaybackException(
            "offline",
            UnknownHostException("provider.example"),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )
        assertThat(PlaybackErrors.shouldScheduleAutomaticRetry(transient, failuresSoFar = 1, maximumRestarts = 3))
            .isTrue()
        assertThat(PlaybackErrors.shouldScheduleAutomaticRetry(transient, failuresSoFar = 2, maximumRestarts = 3))
            .isTrue()
        assertThat(PlaybackErrors.shouldScheduleAutomaticRetry(transient, failuresSoFar = 3, maximumRestarts = 3))
            .isFalse()
        assertThat(
            PlaybackErrors.shouldRetryAfterTransientFailure(
                isTransient = true,
                failuresSoFar = 3,
                maximumRestarts = 3,
            ),
        ).isFalse()
        assertThat(
            PlaybackErrors.shouldRetryAfterTransientFailure(
                isTransient = false,
                failuresSoFar = 0,
                maximumRestarts = 3,
            ),
        ).isFalse()
    }

    @Test
    fun `network errors retry but unsupported media errors do not`() {
        val network = PlaybackException(
            "offline",
            UnknownHostException("provider.example"),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )
        val permanent = PlaybackException(
            "unsupported format",
            IllegalArgumentException("unsupported"),
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
        assertThat(PlaybackErrors.shouldAutomaticallyRetry(network)).isTrue()
        assertThat(PlaybackErrors.isNetworkFailure(network)).isTrue()
        assertThat(PlaybackErrors.shouldAutomaticallyRetry(permanent)).isFalse()
        assertThat(PlaybackErrors.isNetworkFailure(permanent)).isFalse()
    }

    @Test
    fun `HTTP failures map to helpful messages instead of technical error names`() {
        assertThat(PlaybackErrors.describeHttpStatus(context, 401)).contains("password")
        assertThat(PlaybackErrors.describeHttpStatus(context, 404)).contains("channel")
        assertThat(PlaybackErrors.describeHttpStatus(context, 503)).contains("503")
        assertThat(PlaybackErrors.describeHttpStatus(context, 503)).doesNotContain("ERROR_CODE")
    }

    @Test
    fun `network message is localized in Brazilian Portuguese`() {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale("pt", "BR"))
        }
        val portuguese = context.createConfigurationContext(configuration)
        assertThat(portuguese.getString(app.opentv.R.string.player_error_no_signal))
            .contains("Sem sinal")
        assertThat(PlaybackErrors.describeHttpStatus(portuguese, 401)).contains("senha")
    }
}
