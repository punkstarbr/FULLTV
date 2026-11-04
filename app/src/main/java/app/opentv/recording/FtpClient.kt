/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient as CommonsFtpClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply

/** Credentials and folder for the deliberately cleartext, local-only recording transport. */
data class FtpConfig(
    val host: String,
    val port: Int,
    val folder: String,
    val username: String,
    val password: String,
    val plaintextAcknowledged: Boolean,
)

/** Parsed pieces of an endpoint pasted into the host field; absent fields keep their separate inputs. */
data class FtpEndpointInput(
    val host: String,
    val port: Int?,
    val folder: String?,
)

/** FTP is an exception for recording destinations only; it is not a general media/source policy. */
object FtpClient {
    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val CONTROL_TIMEOUT_MILLIS = 15_000
    private const val DATA_TIMEOUT_MILLIS = 20_000
    private const val MAX_PATH_SEGMENTS = 32
    private const val MAX_SEGMENT_CHARS = 128
    private const val MAX_CREDENTIAL_CHARS = 256
    private val BARE_ENDPOINT_INPUT = Regex("""^(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?(?:/.*)?$""")

    /** Accept a local FTP URL or IPv4:port/path pasted into the host field, never embedded credentials. */
    fun parseEndpointInput(raw: String): FtpEndpointInput? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val hasScheme = value.startsWith("ftp://", ignoreCase = true)
        if (!hasScheme && !BARE_ENDPOINT_INPUT.matches(value)) return null

        val uri = runCatching { URI(if (hasScheme) value else "ftp://$value") }.getOrNull() ?: return null
        if (!uri.scheme.equals("ftp", ignoreCase = true) || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null
        ) return null
        val host = uri.host?.takeIf(::isPrivateRfc1918Ipv4) ?: return null
        val port = uri.port.takeIf { it != -1 }
        if (port != null && port !in 1..65_535) return null

        val rawPath = uri.rawPath.orEmpty()
        val folder = when {
            rawPath.isEmpty() -> if (hasScheme) "" else null
            !rawPath.startsWith('/') || rawPath.startsWith("//") -> return null
            rawPath == "/" -> ""
            else -> runCatching {
                URLDecoder.decode(
                    rawPath.removePrefix("/").removeSuffix("/").replace("+", "%2B"),
                    Charsets.UTF_8.name(),
                ).also { safeSegments(it, allowEmpty = true) }
            }.getOrNull() ?: return null
        }
        // A bare host keeps the separately entered port and folder. A URL can also omit both.
        if (!hasScheme && port == null && rawPath.isEmpty()) return null
        return FtpEndpointInput(host, port, folder)
    }

    /** Returns a user-safe validation message, or null when the explicit local FTP config is valid. */
    fun validationError(config: FtpConfig): String? =
        runCatching { validateConfig(config) }.exceptionOrNull()?.message

    fun isValidConfig(config: FtpConfig): Boolean = validationError(config) == null

    /** The stored locator contains only a private IP, port, and relative path — never userinfo. */
    fun locator(config: FtpConfig, filename: String): String {
        validateConfig(config)
        val filenameSegments = safeSegments(filename, allowEmpty = false)
        require(filenameSegments.size == 1) { "An FTP recording filename must be a single path segment." }
        val path = config.folderSegments() + uniqueRecordingFilename(filenameSegments.single())
        require(path.size <= MAX_PATH_SEGMENTS) { "FTP path has too many segments." }
        return "ftp://${config.host.trim()}:${config.port}/" +
            path.joinToString("/") { encodePathSegment(it) }
    }

    /** Opens a remote `.partial` object; close publishes it with FTP RNFR/RNTO after transfer success. */
    fun openForWrite(config: FtpConfig, locator: String): FtpWriteHandle {
        val parsed = validateLocator(config, locator)
        val ftp = connect(config)
        try {
            ensureRemoteFolder(ftp, config.folderSegments())
            val finalName = parsed.remotePath.substringAfterLast('/')
            val partialName = "$finalName.partial"

            if (remoteFileExists(ftp, finalName) != false) {
                throw IOException("FTP could not safely confirm that the recording name is unused.")
            }
            // A deterministic partial suffix makes a crash recoverable without listing unrelated files.
            when (remoteFileExists(ftp, partialName)) {
                true -> if (!ftp.deleteFile(partialName)) {
                    throw IOException("FTP could not clear the previous partial recording.")
                }
                false -> Unit
                null -> throw IOException("FTP could not safely inspect the previous partial recording.")
            }
            if (remoteFileExists(ftp, partialName) != false) {
                throw IOException("FTP could not confirm cleanup of the previous partial recording.")
            }
            val output = ftp.storeFileStream(partialName)
                ?: throw IOException("FTP server refused the upload (reply ${ftp.replyCode}).")
            return FtpWriteHandle(ftp, output, partialName, finalName)
        } catch (error: Throwable) {
            disconnect(ftp)
            throw error
        }
    }

    /** Opens a passive, binary read at [offset]; Media3 seeks by opening a new DataSpec at that offset. */
    fun openForRead(config: FtpConfig, locator: String, offset: Long = 0L): FtpReadHandle {
        require(offset >= 0) { "FTP read offset must not be negative." }
        val parsed = validateLocator(config, locator)
        val ftp = connect(config)
        try {
            val remoteSize = remoteFileSize(ftp, parsed.remotePath)
            if (remoteSize >= 0 && offset >= remoteSize) {
                return FtpReadHandle(ftp, ByteArrayInputStream(byteArrayOf()), remoteSize, noPendingTransfer = true)
            }
            ftp.setRestartOffset(offset)
            val input = ftp.retrieveFileStream(parsed.remotePath)
                ?: throw IOException("FTP server refused the download (reply ${ftp.replyCode}).")
            return FtpReadHandle(ftp, input, remoteSize)
        } catch (error: Throwable) {
            disconnect(ftp)
            throw error
        }
    }

    fun copyTo(config: FtpConfig, locator: String, output: OutputStream) {
        val handle = openForRead(config, locator)
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = handle.read(buffer, 0, buffer.size)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
        } finally {
            handle.close()
        }
    }

    /** Returns -1 when the server cannot report a reliable remote size. */
    fun sizeOf(config: FtpConfig, locator: String): Long {
        val parsed = validateLocator(config, locator)
        val ftp = connect(config)
        return try {
            remoteFileSize(ftp, parsed.remotePath)
        } finally {
            disconnect(ftp)
        }
    }

    /** Deletes both the published file and any deterministic partial from a crashed upload. */
    fun delete(config: FtpConfig, locator: String): Boolean {
        val parsed = validateLocator(config, locator)
        val ftp = connect(config)
        try {
            val partialPath = "${parsed.remotePath}.partial"
            val finalExists = remoteFileExists(ftp, parsed.remotePath)
            val partialExists = remoteFileExists(ftp, partialPath)
            if (finalExists == null || partialExists == null) return false
            if (!finalExists && !partialExists) return true

            var success = true
            if (finalExists && !ftp.deleteFile(parsed.remotePath)) success = false
            if (partialExists && !ftp.deleteFile(partialPath)) success = false
            return success && remoteFileExists(ftp, parsed.remotePath) == false &&
                remoteFileExists(ftp, partialPath) == false
        } finally {
            disconnect(ftp)
        }
    }

    /** Promote recoverable bytes after a process crash and return the verified byte count, or -1. */
    fun recoverInterrupted(config: FtpConfig, locator: String): Long {
        val parsed = validateLocator(config, locator)
        val ftp = connect(config)
        try {
            when (remoteFileExists(ftp, parsed.remotePath)) {
                true -> return remoteFileSize(ftp, parsed.remotePath).takeIf { it > 0L } ?: -1L
                false -> Unit
                null -> return -1L
            }
            val partialPath = "${parsed.remotePath}.partial"
            if (remoteFileExists(ftp, partialPath) != true) return -1L
            val size = remoteFileSize(ftp, partialPath)
            if (size == 0L) {
                if (!ftp.deleteFile(partialPath) || remoteFileExists(ftp, partialPath) != false) {
                    throw IOException("FTP could not remove the empty partial recording.")
                }
                return -1L
            }
            if (size < 0L) return -1L
            if (!ftp.rename(partialPath, parsed.remotePath)) {
                throw IOException("FTP server could not recover the partial recording (reply ${ftp.replyCode}).")
            }
            val publishedSize = remoteFileSize(ftp, parsed.remotePath)
            if (publishedSize != size || remoteFileExists(ftp, parsed.remotePath) != true) {
                throw IOException("FTP server could not verify the recovered recording.")
            }
            return publishedSize
        } finally {
            disconnect(ftp)
        }
    }

    /** Verify login, folder access, the staged upload/rename path, readback, and cleanup. */
    fun test(config: FtpConfig) {
        validateConfig(config)
        val testLocator = locator(config, ".fulltv-test-${UUID.randomUUID()}.tmp")
        val payload = "FullTV FTP test".toByteArray(Charsets.UTF_8)
        try {
            val upload = openForWrite(config, testLocator)
            try {
                upload.output.write(payload)
            } finally {
                upload.close()
            }
            val readback = java.io.ByteArrayOutputStream()
            copyTo(config, testLocator, readback)
            if (!readback.toByteArray().contentEquals(payload)) {
                throw IOException("FTP test readback did not match the uploaded bytes.")
            }
            if (!delete(config, testLocator)) throw IOException("FTP test file could not be removed.")
        } finally {
            runCatching { delete(config, testLocator) }
        }
    }

    internal fun validateConfig(config: FtpConfig) {
        require(config.plaintextAcknowledged) {
            "Confirm that FTP sends credentials and recordings without encryption."
        }
        require(isPrivateRfc1918Ipv4(config.host.trim())) {
            "FTP accepts only a numeric private IPv4 address on the local network."
        }
        require(config.port in 1..65_535) { "Enter a valid FTP port." }
        config.folderSegments()
        validateCredential(config.username, "FTP username", allowEmpty = true)
        validateCredential(config.password, "FTP password", allowEmpty = true)
    }

    internal fun isPrivateRfc1918Ipv4(raw: String): Boolean {
        val parts = raw.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || (it.length > 1 && it.startsWith('0')) }) {
            return false
        }
        val octets = parts.map { it.toIntOrNull()?.takeIf { value -> value in 0..255 } ?: return false }
        if (octets.joinToString(".") != raw) return false
        val (a, b, c, d) = octets
        val inRfc1918 = a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
        if (!inRfc1918) return false
        // Avoid the most common subnet/network endpoints; no hostname or address translation is used.
        if (d == 0 || d == 255) return false
        if (a == 10 && b == 0 && c == 0 && d == 0) return false
        if (a == 172 && b == 16 && c == 0 && d == 0) return false
        if (a == 192 && b == 168 && c == 0 && d == 0) return false
        return true
    }

    internal fun validateLocator(config: FtpConfig, locator: String): ParsedLocator {
        validateConfig(config)
        require(locator.length in 1..8_192 && locator.none { it.code <= 0x20 || it.code == 0x7f }) {
            "FTP locator is invalid."
        }
        val uri = runCatching { URI(locator) }.getOrNull()
            ?: throw IllegalArgumentException("FTP locator is invalid.")
        require(uri.scheme.equals("ftp", ignoreCase = true)) { "Only FTP recording locators are accepted." }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "FTP locators may not include credentials, queries, or fragments."
        }
        require(uri.host?.equals(config.host.trim(), ignoreCase = true) == true && uri.port == config.port) {
            "FTP locator does not belong to the configured local server."
        }
        val rawPath = uri.rawPath.orEmpty()
        require(rawPath.startsWith('/') && !rawPath.startsWith("//")) {
            "FTP locator must contain an absolute encoded path."
        }
        val segments = rawPath.substring(1).split('/').map(::decodePathSegment)
        require(segments.size in 1..MAX_PATH_SEGMENTS) { "FTP locator has an invalid path." }
        segments.forEach(::validateSegment)
        val root = config.folderSegments()
        require(segments.size > root.size && segments.take(root.size) == root) {
            "FTP locator is outside the configured recording folder."
        }
        return ParsedLocator(segments.joinToString("/"))
    }

    internal data class ParsedLocator(val remotePath: String)

    private fun connect(config: FtpConfig): CommonsFtpClient {
        validateConfig(config)
        val ftp = CommonsFtpClient().apply {
            setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
            setDefaultTimeout(CONTROL_TIMEOUT_MILLIS)
            @Suppress("DEPRECATION")
            setDataTimeout(DATA_TIMEOUT_MILLIS)
            setRemoteVerificationEnabled(true)
            // Never trust an address embedded in PASV; use the already-validated private control host.
            setIpAddressFromPasvResponse(false)
            setControlEncoding("UTF-8")
            setUseEPSVwithIPv4(true)
        }
        try {
            ftp.connect(config.host.trim(), config.port)
            if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
                throw IOException("FTP server rejected the connection (reply ${ftp.replyCode}).")
            }
            if (!ftp.login(config.username, config.password)) {
                throw IOException("FTP login was rejected (reply ${ftp.replyCode}).")
            }
            // connect() resets transfer mode. Always select passive and binary after connecting.
            ftp.enterLocalPassiveMode()
            if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw IOException("FTP server rejected binary mode (reply ${ftp.replyCode}).")
            }
            return ftp
        } catch (error: Throwable) {
            disconnect(ftp)
            throw error
        }
    }

    private fun ensureRemoteFolder(ftp: CommonsFtpClient, segments: List<String>) {
        segments.forEach { segment ->
            if (!ftp.changeWorkingDirectory(segment)) {
                // Another client may have created the segment between CWD and MKD; verify by CWD again.
                runCatching { ftp.makeDirectory(segment) }
                if (!ftp.changeWorkingDirectory(segment)) {
                    throw IOException("FTP folder is unavailable (reply ${ftp.replyCode}).")
                }
            }
        }
    }

    internal fun remoteFileSize(ftp: CommonsFtpClient, path: String): Long {
        val mlst = runCatching { ftp.mlistFile(path) }.getOrNull()
        if (mlst?.isFile == true && mlst.size >= 0L) return mlst.size
        val listed = runCatching { ftp.listFiles(path) }.getOrNull().orEmpty()
        return listed.firstOrNull { it.isFile && it.size >= 0L }?.size ?: -1L
    }

    internal fun remoteFileExists(ftp: CommonsFtpClient, path: String): Boolean? {
        val mlst = runCatching { ftp.mlistFile(path) }.getOrNull()
        if (mlst != null) return true
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val currentDirectory = if (parent.isNotEmpty()) {
            runCatching { ftp.printWorkingDirectory() }.getOrNull() ?: return null
        } else {
            null
        }
        if (parent.isNotEmpty() && !ftp.changeWorkingDirectory(parent)) return null
        return try {
            val listed: Array<FTPFile> = runCatching { ftp.listFiles() }.getOrNull() ?: return null
            // An empty result is absence only after the parent directory was listed successfully.
            if (!FTPReply.isPositiveCompletion(ftp.replyCode)) return null
            listed.any { it.name == name }
        } finally {
            if (currentDirectory != null) runCatching { ftp.changeWorkingDirectory(currentDirectory) }
        }
    }

    private fun disconnect(ftp: CommonsFtpClient) {
        runCatching { if (ftp.isConnected) ftp.logout() }
        runCatching { if (ftp.isConnected) ftp.disconnect() }
    }

    private fun validateCredential(value: String, label: String, allowEmpty: Boolean) {
        require((allowEmpty || value.isNotEmpty()) && value.length <= MAX_CREDENTIAL_CHARS) {
            "$label is too long or empty."
        }
        require(value.none { it.code < 0x20 || it.code == 0x7f }) {
            "$label contains a control character."
        }
    }

    private fun FtpConfig.folderSegments(): List<String> {
        val segments = safeSegments(folder.trim(), allowEmpty = true)
        require(segments.size <= MAX_PATH_SEGMENTS) { "FTP folder path has too many segments." }
        return segments
    }

    private fun safeSegments(raw: String, allowEmpty: Boolean): List<String> {
        if (raw.isEmpty()) {
            require(allowEmpty) { "A remote FTP folder or file name is required." }
            return emptyList()
        }
        require(raw.length <= MAX_PATH_SEGMENTS * MAX_SEGMENT_CHARS) { "FTP path is too long." }
        require(!raw.startsWith('/') && !raw.endsWith('/') && !raw.contains('\\')) {
            "FTP paths must be relative and use forward slashes only."
        }
        val segments = raw.split('/')
        require(segments.size <= MAX_PATH_SEGMENTS) { "FTP path has too many segments." }
        segments.forEach(::validateSegment)
        return segments
    }

    private fun encodePathSegment(segment: String): String =
        URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")

    private fun uniqueRecordingFilename(filename: String): String {
        val extension = filename.substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() && it.length <= 8 && it.all(Char::isLetterOrDigit) }
            ?.let { ".$it" }
            .orEmpty()
        val stem = filename.removeSuffix(extension)
        val suffix = "-${UUID.randomUUID().toString().replace("-", "")}"
        val maxStemLength = MAX_SEGMENT_CHARS - suffix.length - extension.length
        require(maxStemLength > 0) { "FTP recording filename is too long." }
        val safeStem = stem.take(maxStemLength).trimEnd(' ', '.', '-', '_').ifEmpty { "recording" }
        return "$safeStem$suffix$extension"
    }

    private fun decodePathSegment(segment: String): String =
        URLDecoder.decode(segment.replace("+", "%2B"), Charsets.UTF_8.name())

    private fun validateSegment(segment: String) {
        require(segment.isNotEmpty() && segment != "." && segment != ".." &&
            segment.length <= MAX_SEGMENT_CHARS &&
            segment.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }
        ) { "FTP path contains an unsafe segment." }
    }

}

/** FTP upload whose partial remote file is atomically renamed only after the server accepts completion. */
class FtpWriteHandle internal constructor(
    private val ftp: CommonsFtpClient,
    private val rawOutput: OutputStream,
    private val partialPath: String,
    private val finalPath: String,
) : Closeable {
    private var closed = false
    @Volatile private var outputFailure: IOException? = null
    val output: OutputStream = object : OutputStream() {
        override fun write(value: Int) {
            try {
                rawOutput.write(value)
            } catch (error: IOException) {
                recordOutputFailure(error)
                throw error
            }
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            try {
                rawOutput.write(bytes, offset, length)
            } catch (error: IOException) {
                recordOutputFailure(error)
                throw error
            }
        }

        override fun flush() {
            try {
                rawOutput.flush()
            } catch (error: IOException) {
                recordOutputFailure(error)
                throw error
            }
        }

        override fun close() = this@FtpWriteHandle.close()
    }

    @Synchronized
    private fun recordOutputFailure(error: IOException) {
        val earlier = outputFailure
        if (earlier == null) outputFailure = error else earlier.addSuppressed(error)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: IOException? = outputFailure
        try {
            rawOutput.flush()
        } catch (error: Exception) {
            if (failure == null) failure = IOException("FTP upload stream could not be flushed.", error)
        }
        try {
            rawOutput.close()
        } catch (error: Exception) {
            if (failure == null) failure = IOException("FTP upload stream could not be closed.", error)
        }

        var transferCompleted = false
        try {
            // Required after storeFileStream: close the data stream, then validate the final reply.
            transferCompleted = ftp.completePendingCommand()
            if (!transferCompleted && failure == null) {
                failure = IOException("FTP server did not confirm the upload (reply ${ftp.replyCode}).")
            }
        } catch (error: Exception) {
            if (failure == null) failure = IOException("FTP upload completion failed.", error)
        }

        if (failure == null && transferCompleted) {
            try {
                if (FtpClient.remoteFileExists(ftp, finalPath) != false) {
                    failure = IOException("FTP could not safely verify the final recording name.")
                } else {
                    val partialSize = FtpClient.remoteFileSize(ftp, partialPath)
                    if (partialSize <= 0L) {
                        failure = IOException("FTP upload is empty or its size could not be verified.")
                    } else if (!ftp.rename(partialPath, finalPath)) {
                        failure = IOException("FTP server could not publish the completed recording (reply ${ftp.replyCode}).")
                    } else if (FtpClient.remoteFileExists(ftp, finalPath) != true ||
                        FtpClient.remoteFileSize(ftp, finalPath) != partialSize
                    ) {
                        failure = IOException("FTP server could not verify the published recording.")
                    }
                }
            } catch (error: Exception) {
                failure = IOException("FTP server could not publish the completed recording.", error)
            }
        }
        if (failure != null) runCatching { ftp.deleteFile(partialPath) }
        try {
            runCatching { if (ftp.isConnected) ftp.logout() }
            runCatching { if (ftp.isConnected) ftp.disconnect() }
        } finally {
            failure?.let { throw it }
        }
    }
}

/** One FTP read session. An early seek/cancel closes the socket; EOF validates the server completion reply. */
class FtpReadHandle internal constructor(
    private val ftp: CommonsFtpClient,
    private val input: InputStream,
    val size: Long,
    private val noPendingTransfer: Boolean = false,
) : Closeable {
    private var closed = false
    private var completionChecked = noPendingTransfer

    @Synchronized
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "FTP read handle is closed." }
        val count = input.read(buffer, offset, length)
        if (count < 0 && !completionChecked) {
            input.close()
            completionChecked = true
            if (!ftp.completePendingCommand()) {
                throw IOException("FTP server did not confirm the download (reply ${ftp.replyCode}).")
            }
        }
        return count
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            runCatching { input.close() }
            // A seek or cancellation ends early; disconnect rather than reusing this one-shot session.
        } finally {
            runCatching { if (ftp.isConnected) ftp.logout() }
            runCatching { if (ftp.isConnected) ftp.disconnect() }
        }
    }
}
