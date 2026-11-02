/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import com.google.common.truth.Truth.assertThat
import java.net.ServerSocket
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test

class SafeNetworkInterceptorsTest {
    @Test
    fun untrustedPrivateDestinationIsRejectedBeforeTcpConnect() {
        ServerSocket(0).use { listener ->
            listener.soTimeout = 150
            val client = OkHttpClient.Builder()
                .addInterceptor(SafeNetworkRequestInterceptor())
                .build()
            val request = Request.Builder()
                .url("https://127.0.0.1:${listener.localPort}/secret")
                .build()

            val failure = runCatching { client.newCall(request).execute() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(java.io.IOException::class.java)
            assertThat(failure?.message).isEqualTo("The network destination is not allowed.")
            val connected = runCatching { listener.accept().use { true } }
                .getOrDefault(false)
            assertThat(connected).isFalse()
        }
    }
}
