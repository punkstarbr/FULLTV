/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.Test

class BoundedInputStreamTest {
    @Test
    fun `allows a stream whose size equals the limit`() {
        val input = BoundedInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
        assertThat(input.readBytes().toList())
            .containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()
    }

    @Test
    fun `rejects a stream with one extra byte`() {
        val input = BoundedInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 3, "playlist")
        val error = runCatching { input.readBytes() }.exceptionOrNull()
        assertThat(error).isInstanceOf(java.io.IOException::class.java)
        assertThat(error?.message).contains("playlist")
    }

    @Test
    fun `zero length reads do not consume or falsely exceed`() {
        val input = BoundedInputStream(ByteArrayInputStream(byteArrayOf(1)), 0)
        assertThat(input.read(ByteArray(0), 0, 0)).isEqualTo(0)
        assertThat(runCatching { input.read() }.isFailure).isTrue()
    }
}
