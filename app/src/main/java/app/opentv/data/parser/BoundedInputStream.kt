/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Counts actual bytes, not just Content-Length, and fails closed on an over-limit stream. */
class BoundedInputStream(
    input: InputStream,
    private val maxBytes: Long,
    private val label: String = "Input",
) : FilterInputStream(input) {
    private var consumed = 0L

    init {
        require(maxBytes >= 0)
    }

    override fun read(): Int {
        if (consumed >= maxBytes) {
            val extra = `in`.read()
            if (extra < 0) return -1
            throw IOException("$label exceeds the safety limit.")
        }
        val value = `in`.read()
        if (value >= 0) consumed++
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val remaining = maxBytes - consumed
        if (remaining <= 0) {
            val extra = `in`.read()
            if (extra < 0) return -1
            throw IOException("$label exceeds the safety limit.")
        }
        val allowed = minOf(length.toLong(), remaining).toInt()
        val count = `in`.read(buffer, offset, allowed)
        if (count > 0) consumed += count
        return count
    }
}
