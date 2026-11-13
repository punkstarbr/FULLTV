/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * Best-effort private-disk cache for the playlist-scoped XMLTV IDs retained by the EPG filter.
 * The snapshot stores only cache keys and retained IDs; any read or write problem is a cache miss.
 */
internal class RetainedEpgIdCache(private val file: File) {
    data class Key(
        val compressedXmltvSha256: String,
        val playlistFingerprint: String,
        val algorithmVersion: Int,
    )

    companion object {
        const val MAX_SNAPSHOT_BYTES: Int = 1024 * 1024
        const val MAX_ENTRIES: Int = 8
        const val MAX_IDS_PER_ENTRY: Int = 50_000

        private const val MAGIC = 0x52504543 // "RPEC"
        private const val SCHEMA_VERSION = 1
        private const val CHECKSUM_BYTES = 32
        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = MAX_SNAPSHOT_BYTES - CHECKSUM_BYTES

        private val processLocks = HashMap<String, Any>()

        private fun processLock(file: File): Any = synchronized(processLocks) {
            processLocks.getOrPut(file.absolutePath) { Any() }
        }
    }

    private val sharedFileLock = processLock(file)

    /** Returns an immutable copy of the retained IDs, or null when this key is not cached. */
    @Synchronized
    fun get(key: Key): Set<String>? = synchronized(sharedFileLock) {
        try {
            val snapshot = readSnapshot() ?: return@synchronized null
            snapshot[key]?.let { Collections.unmodifiableSet(LinkedHashSet(it)) }
        } catch (_: Exception) {
            null
        }
    }

    /** Adds/replaces an entry; oversized or unwritable snapshots are deliberately not cached. */
    @Synchronized
    fun put(key: Key, retainedIds: Set<String>) {
        synchronized(sharedFileLock) {
            try {
                if (retainedIds.size > MAX_IDS_PER_ENTRY) return

                val candidate = readSnapshot() ?: LinkedHashMap()
                candidate.remove(key)
                candidate[key] = LinkedHashSet(retainedIds)
                while (candidate.size > MAX_ENTRIES) removeOldest(candidate)

                var encoded = encode(candidate)
                // FIFO eviction keeps a new value when the aggregate snapshot reaches its byte cap.
                while (encoded == null && candidate.size > 1) {
                    val oldest = candidate.keys.firstOrNull() ?: break
                    if (oldest == key) break
                    candidate.remove(oldest)
                    encoded = encode(candidate)
                }
                if (encoded == null) return
                if (writeSnapshot(encoded)) Unit
            } catch (_: Exception) {
                // Cache storage is optional and must never prevent an EPG sync from continuing.
            }
        }
    }

    private fun readSnapshot(): LinkedHashMap<Key, Set<String>>? {
        return try {
            val bytes = AtomicFile(file).openRead().use(::readBounded)
            if (bytes == null) null else decode(bytes)
        } catch (_: Exception) {
            null
        }
    }

    /** Reads at most 1 MiB plus one buffer before rejecting, without trusting file metadata. */
    private fun readBounded(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (total.toLong() + count > MAX_SNAPSHOT_BYTES) return null
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    private fun decode(bytes: ByteArray): LinkedHashMap<Key, Set<String>>? {
        if (bytes.size !in (12 + CHECKSUM_BYTES)..MAX_SNAPSHOT_BYTES) return null
        val bodySize = bytes.size - CHECKSUM_BYTES
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes, 0, bodySize)
        val expectedChecksum = digest.digest()
        if (!MessageDigest.isEqual(expectedChecksum, bytes.copyOfRange(bodySize, bytes.size))) return null

        val input = DataInputStream(ByteArrayInputStream(bytes, 0, bodySize))
        if (input.readInt() != MAGIC || input.readInt() != SCHEMA_VERSION) return null
        val entryCount = input.readInt()
        if (entryCount !in 0..MAX_ENTRIES) return null

        val entries = LinkedHashMap<Key, Set<String>>()
        repeat(entryCount) {
            val xmltvHash = readString(input) ?: return null
            val fingerprint = readString(input) ?: return null
            val algorithmVersion = input.readInt()
            val idCount = input.readInt()
            if (idCount !in 0..MAX_IDS_PER_ENTRY) return null

            val ids = LinkedHashSet<String>()
            repeat(idCount) {
                ids += readString(input) ?: return null
            }
            val key = Key(xmltvHash, fingerprint, algorithmVersion)
            if (entries.containsKey(key)) return null
            entries[key] = ids
        }
        if (input.available() != 0) return null
        return entries
    }

    private fun readString(input: DataInputStream): String? {
        val byteCount = input.readInt()
        if (byteCount < 0 || byteCount > MAX_SNAPSHOT_BYTES || byteCount > input.available()) return null
        val encoded = ByteArray(byteCount)
        input.readFully(encoded)
        return String(encoded, Charsets.UTF_8)
    }

    private fun encode(entries: LinkedHashMap<Key, Set<String>>): ByteArray? {
        if (entries.size > MAX_ENTRIES || entries.values.any { it.size > MAX_IDS_PER_ENTRY }) return null

        val bodyBytes = ByteArrayOutputStream()
        val output = DataOutputStream(bodyBytes)
        if (!writeInt(output, bodyBytes, MAGIC) ||
            !writeInt(output, bodyBytes, SCHEMA_VERSION) ||
            !writeInt(output, bodyBytes, entries.size)
        ) return null

        for ((key, ids) in entries) {
            if (!writeString(output, bodyBytes, key.compressedXmltvSha256) ||
                !writeString(output, bodyBytes, key.playlistFingerprint) ||
                !writeInt(output, bodyBytes, key.algorithmVersion) ||
                !writeInt(output, bodyBytes, ids.size)
            ) return null
            for (id in ids.sorted()) {
                if (!writeString(output, bodyBytes, id)) return null
            }
        }

        val body = bodyBytes.toByteArray()
        if (body.size > MAX_BODY_BYTES) return null
        val checksum = MessageDigest.getInstance("SHA-256").digest(body)
        val result = ByteArray(body.size + CHECKSUM_BYTES)
        System.arraycopy(body, 0, result, 0, body.size)
        System.arraycopy(checksum, 0, result, body.size, CHECKSUM_BYTES)
        return result
    }

    private fun writeInt(output: DataOutputStream, body: ByteArrayOutputStream, value: Int): Boolean {
        if (body.size().toLong() + Int.SIZE_BYTES > MAX_BODY_BYTES) return false
        output.writeInt(value)
        return true
    }

    private fun writeString(
        output: DataOutputStream,
        body: ByteArrayOutputStream,
        value: String,
    ): Boolean {
        if (value.length > MAX_SNAPSHOT_BYTES) return false
        val encoded = value.toByteArray(Charsets.UTF_8)
        if (body.size().toLong() + Int.SIZE_BYTES + encoded.size > MAX_BODY_BYTES) return false
        output.writeInt(encoded.size)
        output.write(encoded)
        return true
    }

    private fun writeSnapshot(bytes: ByteArray): Boolean {
        val atomicFile = AtomicFile(file)
        var output: FileOutputStream? = null
        return try {
            val started = atomicFile.startWrite()
            output = started
            started.write(bytes)
            started.flush()
            started.fd.sync()
            atomicFile.finishWrite(started)
            output = null
            true
        } catch (_: Exception) {
            output?.let { failed ->
                try {
                    atomicFile.failWrite(failed)
                } catch (_: Exception) {
                    // Best-effort rollback; the caller treats any failure as a cache miss.
                }
            }
            false
        }
    }

    private fun removeOldest(entries: LinkedHashMap<Key, Set<String>>) {
        entries.keys.firstOrNull()?.let(entries::remove)
    }
}
