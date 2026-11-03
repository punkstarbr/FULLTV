/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class StremioDestinationSecurityTest {
    @Test
    fun filtersPrivateNetworkStreamsFromAddonResponses() {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val payload = """
                    {"streams":[
                      {"url":"https://192.168.1.25/private.m3u8","title":"private"},
                      {"url":"https://1.1.1.1/public.m3u8","title":"public CDN"},
                      {"url":"https://8.8.8.8/same-origin.m3u8","title":"same origin"},
                      {"url":"file:///etc/passwd","title":"file"}
                    ]}
                """.trimIndent()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(payload.toResponseBody())
                    .build()
            }
            .build()

        val streams = StremioClient(client).streams(
            addonManifestUrl = "https://8.8.8.8/manifest.json",
            addonName = "Test add-on",
            type = "movie",
            id = "tt1234567",
        )

        assertThat(streams.map { it.url }).containsExactly(
            "https://1.1.1.1/public.m3u8",
            "https://8.8.8.8/same-origin.m3u8",
        ).inOrder()
    }
}
