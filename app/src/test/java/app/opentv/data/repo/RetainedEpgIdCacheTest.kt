/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.model.Channel
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RetainedEpgIdCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `round trips only retained IDs through the bounded disk snapshot`() {
        val file = snapshotFile()
        val key = key()
        val ids = linkedSetOf("xmltv-one", "xmltv-two")

        RetainedEpgIdCache(file).put(key, ids)

        assertThat(file.isFile).isTrue()
        assertThat(file.length()).isAtMost(RetainedEpgIdCache.MAX_SNAPSHOT_BYTES.toLong())
        assertThat(RetainedEpgIdCache(file).get(key)).containsExactlyElementsIn(ids)
    }

    @Test
    fun `each component of the cache key independently misses`() {
        val file = snapshotFile()
        val key = key()
        RetainedEpgIdCache(file).put(key, setOf("retained-id"))
        val reloaded = RetainedEpgIdCache(file)

        assertThat(reloaded.get(key)).containsExactly("retained-id")
        assertThat(reloaded.get(key.copy(compressedXmltvSha256 = "different-xml-hash"))).isNull()
        assertThat(reloaded.get(key.copy(playlistFingerprint = "different-playlist"))).isNull()
        assertThat(reloaded.get(key.copy(algorithmVersion = key.algorithmVersion + 1))).isNull()
    }

    @Test
    fun `fingerprint follows catalogue order and exactly the Matcher fields`() {
        val first = channel(groupKey = "first", overrideId = "override-a", epgId = "direct-a")
        val second = channel(groupKey = "first", overrideId = "override-b", epgId = "direct-b")
        val baseline = EpgProgrammeChannelFilter.fingerprint(listOf(first, second))

        assertThat(baseline).matches("[0-9a-f]{64}")
        assertThat(EpgProgrammeChannelFilter.fingerprint(listOf(second, first))).isNotEqualTo(baseline)
        assertThat(EpgProgrammeChannelFilter.fingerprint(listOf(first.copy(groupKey = "changed"), second)))
            .isNotEqualTo(baseline)
        assertThat(EpgProgrammeChannelFilter.fingerprint(listOf(first.copy(epgOverrideId = "changed"), second)))
            .isNotEqualTo(baseline)
        assertThat(EpgProgrammeChannelFilter.fingerprint(listOf(first.copy(epgChannelId = "changed"), second)))
            .isNotEqualTo(baseline)

        val singleFingerprint = EpgProgrammeChannelFilter.fingerprint(listOf(first))
        assertThat(
            EpgProgrammeChannelFilter.fingerprint(
                listOf(
                    first.copy(
                        name = "Different name",
                        displayName = "Different display name",
                        matchedEpgId = "ignored-match",
                        streamUrl = "https://different.invalid/stream",
                        logoUrl = "https://different.invalid/logo.png",
                        customName = "User rename",
                    ),
                ),
            ),
        ).isEqualTo(singleFingerprint)
        assertThat(
            EpgProgrammeChannelFilter.fingerprint(listOf(first.copy(epgOverrideId = ""))),
        ).isNotEqualTo(singleFingerprint)
        assertThat(EpgProgrammeChannelFilter.FILTER_ALGORITHM_VERSION).isGreaterThan(0)
    }

    @Test
    fun `checksum corruption truncation and unknown schema all become misses`() {
        val file = snapshotFile()
        val key = key()
        RetainedEpgIdCache(file).put(key, setOf("retained-id"))
        val validSnapshot = file.readBytes()

        file.writeBytes(
            validSnapshot.copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte()
            },
        )
        assertThat(RetainedEpgIdCache(file).get(key)).isNull()

        file.writeBytes(validSnapshot.copyOf(validSnapshot.size - 1))
        assertThat(RetainedEpgIdCache(file).get(key)).isNull()

        val unsupportedSchema = validSnapshot.copyOf()
        ByteBuffer.wrap(unsupportedSchema).putInt(Int.SIZE_BYTES, 999)
        val bodySize = unsupportedSchema.size - 32
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(unsupportedSchema.copyOfRange(0, bodySize))
        System.arraycopy(checksum, 0, unsupportedSchema, bodySize, checksum.size)
        file.writeBytes(unsupportedSchema)
        assertThat(RetainedEpgIdCache(file).get(key)).isNull()
    }

    @Test
    fun `missing file is a cache miss`() {
        assertThat(RetainedEpgIdCache(snapshotFile()).get(key())).isNull()
    }

    @Test
    fun `entry count is capped with deterministic FIFO eviction`() {
        val file = snapshotFile()
        val cache = RetainedEpgIdCache(file)
        val keys = (0..RetainedEpgIdCache.MAX_ENTRIES).map(::key)

        keys.forEachIndexed { index, key -> cache.put(key, setOf("id-$index")) }

        val reloaded = RetainedEpgIdCache(file)
        assertThat(reloaded.get(keys.first())).isNull()
        keys.drop(1).forEachIndexed { index, key ->
            assertThat(reloaded.get(key)).containsExactly("id-${index + 1}")
        }
        assertThat(file.length()).isAtMost(RetainedEpgIdCache.MAX_SNAPSHOT_BYTES.toLong())
    }

    @Test
    fun `oversized ID sets and byte snapshots are not cached`() {
        val file = snapshotFile()
        val cache = RetainedEpgIdCache(file)
        val key = key()
        val tooManyIds = (0..RetainedEpgIdCache.MAX_IDS_PER_ENTRY)
            .map { "id-$it" }
            .toSet()

        cache.put(key, tooManyIds)
        assertThat(cache.get(key)).isNull()
        assertThat(file.exists()).isFalse()

        cache.put(key, setOf("x".repeat(RetainedEpgIdCache.MAX_SNAPSHOT_BYTES)))
        assertThat(cache.get(key)).isNull()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `write failure never escapes into the caller`() {
        val blocker = File(temporaryFolder.root, "not-a-directory").apply { writeText("file") }
        val cache = RetainedEpgIdCache(File(blocker, "snapshot.bin"))

        val outcome = runCatching { cache.put(key(), setOf("retained-id")) }

        assertThat(outcome.isSuccess).isTrue()
        assertThat(cache.get(key())).isNull()
    }

    private fun snapshotFile() = File(temporaryFolder.root, "retained-epg-ids.bin")

    private fun key(index: Int = 0) = RetainedEpgIdCache.Key(
        compressedXmltvSha256 = "compressed-hash-$index",
        playlistFingerprint = "playlist-fingerprint-$index",
        algorithmVersion = EpgProgrammeChannelFilter.FILTER_ALGORITHM_VERSION + index,
    )

    private fun channel(
        groupKey: String,
        overrideId: String?,
        epgId: String?,
    ) = Channel(
        sourceId = 1,
        streamId = "stream-$groupKey",
        name = groupKey,
        displayName = groupKey,
        groupKey = groupKey,
        categoryId = null,
        logoUrl = "https://example.invalid/logo.png",
        epgChannelId = epgId,
        epgOverrideId = overrideId,
        number = null,
        streamUrl = "https://example.invalid/stream",
    )
}
