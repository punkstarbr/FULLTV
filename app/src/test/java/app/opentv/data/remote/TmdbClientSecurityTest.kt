/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Test

class TmdbClientSecurityTest {
    @Test
    fun chunkedBodyOverLimitIsRejectedByBoundedReader() {
        val payload = ByteArray(MAX_TMDB_JSON_BYTES.toInt() + 1) { '{'.code.toByte() }
        val chunkedBody = object : ResponseBody() {
            override fun contentLength(): Long = -1L
            override fun contentType() = null
            override fun source(): BufferedSource = Buffer().write(payload)
        }

        val failure = runCatching {
            readBoundedTmdbJson(chunkedBody, Json, MAX_TMDB_JSON_BYTES)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure?.message).contains("TMDB response")
    }
}
