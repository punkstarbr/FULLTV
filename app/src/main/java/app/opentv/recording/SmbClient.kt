/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.util.EnumSet

/**
 * A NAS / SMB share to record to. Stored on-device (SharedPreferences), exactly like a provider's
 * credentials — it never leaves the device except to talk to the user's own server.
 */
data class SmbConfig(
    val host: String,
    val share: String,
    /** Sub-folder within the share, e.g. `OpenTV`. Blank = the share root. */
    val folder: String,
    val username: String,
    val password: String,
) {
    val isConfigured: Boolean get() = host.isNotBlank() && share.isNotBlank()
}

/** An open SMB file being written to. Closing tears the whole SMB stack down cleanly. */
class SmbWriteHandle(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
    private val file: com.hierynomus.smbj.share.File,
    val output: OutputStream,
    /** The `smb://host/share/path` locator stored on the recording row. */
    val locator: String,
) : Closeable {
    override fun close() {
        var failure: Throwable? = null
        fun closeStep(block: () -> Unit) {
            runCatching(block).onFailure { closeError ->
                val earlier = failure
                if (earlier == null) failure = closeError else earlier.addSuppressed(closeError)
            }
        }
        closeStep { output.flush() }
        closeStep { output.close() }
        closeStep { file.close() }
        closeStep { share.close() }
        closeStep { session.close() }
        closeStep { connection.close() }
        closeStep { client.close() }
        failure?.let { error ->
            if (error is IOException) throw error
            throw IOException("SMB recording destination could not be closed.", error)
        }
    }
}

/**
 * A random-access reader over an SMB file, for playing a NAS recording back in-app. Media3's
 * data source seeks by asking for bytes at an offset; SMB supports exactly that.
 */
class SmbReadHandle(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
    private val file: com.hierynomus.smbj.share.File,
    val length: Long,
) : Closeable {
    /** Reads up to [length] bytes at [fileOffset]; returns bytes read or -1 at EOF. */
    fun readAt(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int =
        file.read(buffer, fileOffset, bufferOffset, length)

    override fun close() {
        runCatching { file.close() }
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}

/**
 * Thin SMB3 client over SMBJ. One connection per operation (recording holds it for the
 * capture; playback holds it for the play). Deliberately not pooled — an IPTV box records one or
 * two things at a time, and a fresh connection is simpler and robust against a NAS that drops idle
 * sessions.
 */
object SmbClient {

    // Bound every SMB operation. Without this a write to a NAS that has gone quiet (a Wi-Fi blip, a
    // drive spinning up, the session dropping) blocks forever — which silently freezes a recording
    // mid-capture with no error. A timeout turns that hang into a normal dropped-connection the
    // capture loop can retry.
    private val smbjConfig by lazy {
        com.hierynomus.smbj.SmbConfig.builder()
            // SMB1/SMB2 and unsigned/plain SMB3 sessions can expose credentials or data on the LAN.
            // Fail closed instead of silently downgrading to a legacy NAS configuration.
            .withDialects(
                com.hierynomus.mssmb2.SMB2Dialect.SMB_3_1_1,
                com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0_2,
                com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0,
            )
            .withSigningRequired(true)
            .withEncryptData(true)
            .withSoTimeout(35, java.util.concurrent.TimeUnit.SECONDS)
            .withTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun connect(config: SmbConfig): Quad {
        validateConfig(config)
        val client = SMBClient(smbjConfig)
        val connection = client.connect(config.host)
        var session: Session? = null
        var share: DiskShare? = null
        try {
            val auth = AuthenticationContext(config.username, config.password.toCharArray(), null)
            val authenticated = connection.authenticate(auth)
            session = authenticated
            // withEncryptData(true) only advertises a requirement to SMBJ. The server can still
            // authenticate a signing-only session, so verify the negotiated state before opening
            // the share and fail closed instead of silently downgrading recording/playback.
            requireEncryptedSession { authenticated.shouldEncryptData() }
            val connectedShare = authenticated.connectShare(config.share) as DiskShare
            share = connectedShare
            return Quad(client, connection, authenticated, connectedShare)
        } catch (t: Throwable) {
            runCatching { share?.close() }
            runCatching { session?.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
            throw t
        }
    }

    /** Testable fail-closed boundary for a session that negotiated signing but not encryption. */
    internal fun requireEncryptedSession(negotiated: () -> Boolean) {
        if (!negotiated()) {
            throw IOException("SMB session encryption was not negotiated; refusing a signing-only session.")
        }
    }

    private class Quad(
        val client: SMBClient,
        val connection: Connection,
        val session: Session,
        val share: DiskShare,
    )

    /** SMB paths use backslashes; the folder may be nested and is created if missing. */
    private fun ensureFolder(share: DiskShare, folder: String) {
        if (folder.isBlank()) return
        val parts = safeSegments(folder)
        var acc = ""
        for (part in parts) {
            acc = if (acc.isEmpty()) part else "$acc\\$part"
            if (!share.folderExists(acc)) runCatching { share.mkdir(acc) }
        }
    }

    /** Build the `smb://host/share/folder/filename` locator a recording will be stored at. */
    fun locator(config: SmbConfig, filename: String): String {
        validateConfig(config)
        val safeName = safeSegments(filename).singleOrNull()
            ?: throw IllegalArgumentException("A single safe file name is required.")
        val folder = safeSegments(config.folder)
        val path = (folder + safeName).joinToString("/")
        return "smb://${config.host.trim()}/${config.share.trim()}/$path"
    }

    /** Open the file named by [locator] for writing. Caller writes to [SmbWriteHandle.output]. */
    fun openForWrite(config: SmbConfig, locator: String, overwrite: Boolean = true): SmbWriteHandle {
        val parsed = validateLocator(config, locator)
        val q = connect(config)
        var created = false
        try {
            val folder = parsed.path.substringBeforeLast('/', "")
            ensureFolder(q.share, folder)
            val file = q.share.openFile(
                parsed.path.replace('/', '\\'),
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                if (overwrite) SMB2CreateDisposition.FILE_OVERWRITE_IF else SMB2CreateDisposition.FILE_CREATE,
                null,
            )
            created = true
            return SmbWriteHandle(
                q.client, q.connection, q.session, q.share, file,
                file.outputStream, locator,
            )
        } catch (t: Throwable) {
            if (created) runCatching { q.share.rm(parsed.path.replace('/', '\\')) }
            runCatching { q.share.close() }
            runCatching { q.session.close() }
            runCatching { q.connection.close() }
            runCatching { q.client.close() }
            throw t
        }
    }

    /** Open an existing `smb://host/share/path` locator for random-access reading (playback). */
    fun openForRead(config: SmbConfig, locator: String): SmbReadHandle {
        val parsed = validateLocator(config, locator)
        val q = connect(config)
        try {
            val file = q.share.openFile(
                parsed.path.replace('/', '\\'),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            val size = file.fileInformation.standardInformation.endOfFile
            return SmbReadHandle(q.client, q.connection, q.session, q.share, file, size)
        } catch (t: Throwable) {
            runCatching { q.share.close() }
            runCatching { q.session.close() }
            runCatching { q.connection.close() }
            runCatching { q.client.close() }
            throw t
        }
    }

    /** Stream an existing SMB file into [out] (used when remuxing a NAS recording to MP4). */
    fun copyTo(config: SmbConfig, locator: String, out: OutputStream) {
        val handle = openForRead(config, locator)
        try {
            val buffer = ByteArray(256 * 1024)
            var offset = 0L
            while (offset < handle.length) {
                val want = minOf(buffer.size.toLong(), handle.length - offset).toInt()
                val read = handle.readAt(buffer, offset, 0, want)
                if (read <= 0) break
                out.write(buffer, 0, read)
                offset += read
            }
        } finally {
            handle.close()
        }
    }

    /** Delete a recording from the NAS and verify that the server no longer exposes the file. */
    fun delete(config: SmbConfig, locator: String): Boolean {
        val parsed = validateLocator(config, locator)
        val q = connect(config)
        try {
            val path = parsed.path.replace('/', '\\')
            if (!q.share.fileExists(path)) return false
            q.share.rm(path)
            return !q.share.fileExists(path)
        } finally {
            runCatching { q.share.close() }
            runCatching { q.session.close() }
            runCatching { q.connection.close() }
            runCatching { q.client.close() }
        }
    }

    /** Return the server-reported byte length of an SMB recording. */
    fun sizeOf(config: SmbConfig, locator: String): Long {
        val handle = openForRead(config, locator)
        return try {
            handle.length
        } finally {
            handle.close()
        }
    }

    /**
     * File names directly inside [folder] within the share (backslash- or slash-separated path,
     * relative to the share root). Empty when the folder does not exist yet. Used by NAS sync to
     * find every device's bundle file. Directory entries (including `.`/`..`) are left in — callers
     * filter by the extension they expect.
     */
    fun list(config: SmbConfig, folder: String): List<String> {
        validateConfig(config)
        val parts = safeSegments(folder)
        val configuredRoot = safeSegments(config.folder)
        require(parts.startsWithSegments(configuredRoot)) { "SMB listing is outside the configured folder." }
        val q = connect(config)
        try {
            val rel = parts.joinToString("\\")
            if (rel.isNotEmpty() && !q.share.folderExists(rel)) return emptyList()
            return q.share.list(rel).asSequence()
                .map { it.fileName }
                .filter { name -> runCatching { safeSegments(name).size == 1 }.getOrDefault(false) }
                .take(MAX_DIRECTORY_ENTRIES)
                .toList()
        } finally {
            runCatching { q.share.close() }
            runCatching { q.session.close() }
            runCatching { q.connection.close() }
            runCatching { q.client.close() }
        }
    }

    /** Overwrite the file at [locator] with [text] (UTF-8). Creates the parent folder if missing. */
    fun writeText(config: SmbConfig, locator: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "Text file exceeds the configured SMB safety limit." }
        val handle = openForWrite(config, locator, overwrite = true)
        try {
            handle.output.write(bytes)
            handle.output.flush()
        } finally {
            handle.close()
        }
    }

    /** Read the whole file at [locator] as a UTF-8 string. */
    fun readText(config: SmbConfig, locator: String, maxBytes: Int = MAX_TEXT_BYTES): String {
        require(maxBytes in 1..MAX_TEXT_BYTES)
        val handle = openForRead(config, locator)
        try {
            if (handle.length > maxBytes.toLong()) throw IOException("Remote text file exceeds the safety limit.")
            if (handle.length > Int.MAX_VALUE) throw IOException("Remote text file is too large.")
            val size = handle.length.toInt()
            if (size <= 0) return ""
            val buffer = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = handle.readAt(buffer, offset.toLong(), offset, size - offset)
                if (read <= 0) break
                offset += read
            }
            if (offset != size) throw IOException("Remote text file ended before its declared size.")
            return String(buffer, 0, offset, Charsets.UTF_8)
        } finally {
            handle.close()
        }
    }

    /** Prove the settings work: connect, authenticate, open the share. Throws on failure. */
    fun test(config: SmbConfig) {
        val q = connect(config)
        try {
            ensureFolder(q.share, config.folder)
        } finally {
            runCatching { q.share.close() }
            runCatching { q.session.close() }
            runCatching { q.connection.close() }
            runCatching { q.client.close() }
        }
    }

    data class Parsed(val host: String, val share: String, val path: String)

    /** smb://host/share/a/b/c.ts → (host, share, "a/b/c.ts"). */
    fun parse(locator: String): Parsed {
        require(locator.startsWith("smb://", ignoreCase = true)) { "Only SMB locators are accepted." }
        val body = locator.substringAfter("://")
        require(body.none { it == '?' || it == '#' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) {
            "SMB locator contains forbidden characters."
        }
        val firstSlash = body.indexOf('/')
        require(firstSlash > 0) { "SMB locator is missing a share and file path." }
        val host = body.substring(0, firstSlash)
        require(host.matches(HOST_PATTERN)) { "SMB locator has an invalid host." }
        val segments = safeSegments(body.substring(firstSlash + 1))
        require(segments.size >= 2) { "SMB locator must include a share and a file path." }
        return Parsed(host, segments.first(), segments.drop(1).joinToString("/"))
    }

    fun validateLocator(config: SmbConfig, locator: String): Parsed {
        validateConfig(config)
        val parsed = parse(locator)
        require(parsed.host.equals(config.host.trim(), ignoreCase = true)) {
            "SMB locator does not belong to the configured NAS host."
        }
        require(parsed.share.equals(config.share.trim(), ignoreCase = true)) {
            "SMB locator does not belong to the configured share."
        }
        val configuredRoot = safeSegments(config.folder)
        val pathSegments = safeSegments(parsed.path)
        require(pathSegments.size > configuredRoot.size && pathSegments.startsWithSegments(configuredRoot)) {
            "SMB locator is outside the configured recording folder."
        }
        return parsed
    }

    fun isValidLocator(config: SmbConfig, locator: String): Boolean =
        runCatching { validateLocator(config, locator) }.isSuccess

    /** Relative recording path below the configured folder, safe to transfer in a sync bundle. */
    fun relativePath(config: SmbConfig, locator: String): String {
        val parsed = validateLocator(config, locator)
        val root = safeSegments(config.folder)
        return safeSegments(parsed.path).drop(root.size).joinToString("/")
    }

    /** Rebuild a locator from a path that is relative to the configured recording folder. */
    fun locatorForRelativePath(config: SmbConfig, relativePath: String): String {
        validateConfig(config)
        val relative = safeSegments(relativePath)
        require(relative.isNotEmpty()) { "A relative SMB file path is required." }
        val path = (safeSegments(config.folder) + relative).joinToString("/")
        return "smb://${config.host.trim()}/${config.share.trim()}/$path"
    }

    fun isSmb(locator: String): Boolean = locator.startsWith("smb://", ignoreCase = true)

    private fun validateConfig(config: SmbConfig) {
        require(config.host.trim().matches(HOST_PATTERN)) { "Enter a valid SMB host name or IPv4 address." }
        require(safeSegments(config.share).size == 1) { "Enter one valid SMB share name." }
        safeSegments(config.folder)
    }

    private fun safeSegments(raw: String): List<String> {
        val normalized = raw.replace('\\', '/')
        require(!normalized.startsWith('/')) { "Absolute SMB paths are not accepted." }
        if (normalized.isEmpty()) return emptyList()
        val segments = normalized.split('/')
        require(segments.all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".." && segment.length <= 255 &&
                segment.none { char ->
                    char in SMB_INVALID_PATH_CHARS || char.code < 0x20 || char.code == 0x7f
                }
        }) { "SMB path contains an unsafe segment." }
        return segments
    }

    private fun List<String>.startsWithSegments(prefix: List<String>): Boolean =
        size >= prefix.size && prefix.indices.all { get(it).equals(prefix[it], ignoreCase = true) }

    private const val MAX_TEXT_BYTES = 8 * 1024 * 1024
    private const val MAX_DIRECTORY_ENTRIES = 512
    private val HOST_PATTERN = Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
    private val SMB_INVALID_PATH_CHARS = setOf('/', '\\', ':', '?', '#', '*', '"', '<', '>', '|', '%')
}
