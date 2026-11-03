/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.opentv.core.AppSettings
import app.opentv.recording.FtpClient
import app.opentv.recording.FtpReadHandle
import app.opentv.recording.RecordingStorage

/** Seekable reader for completed FullTV FTP recordings; the locator is revalidated before each connection. */
@OptIn(UnstableApi::class)
class FtpDataSource(private val settings: AppSettings) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var handle: FtpReadHandle? = null
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val remote = FtpClient.openForRead(
            RecordingStorage.ftpConfig(settings),
            dataSpec.uri.toString(),
            dataSpec.position,
        )
        handle = remote
        val available = if (remote.size >= 0L) {
            (remote.size - dataSpec.position).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }
        val requested = dataSpec.length
        bytesRemaining = when {
            requested != C.LENGTH_UNSET.toLong() && available != C.LENGTH_UNSET.toLong() ->
                minOf(requested, available)
            requested != C.LENGTH_UNSET.toLong() -> requested
            else -> available
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val count = handle?.read(
            buffer,
            offset,
            if (bytesRemaining > 0L) minOf(length.toLong(), bytesRemaining).toInt() else length,
        ) ?: return C.RESULT_END_OF_INPUT
        if (count < 0) return C.RESULT_END_OF_INPUT
        if (bytesRemaining > 0L) bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            handle?.close()
        } finally {
            handle = null
            if (opened) {
                opened = false
                transferEnded()
            }
            uri = null
        }
    }

    class Factory(private val settings: AppSettings) : DataSource.Factory {
        override fun createDataSource(): DataSource = FtpDataSource(settings)
    }
}
