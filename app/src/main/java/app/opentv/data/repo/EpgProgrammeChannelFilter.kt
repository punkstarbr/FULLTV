/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.ProgrammeDao
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.parser.ChannelNameNormalizer
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Keeps programme rows whose XMLTV channel is identified by the current playlist catalogue. */
internal object EpgProgrammeChannelFilter {
    private const val DELETE_CHUNK_SIZE = 500
    private const val MIN_KEY_LENGTH = 2
    private const val HEX_DIGITS = "0123456789abcdef"

    /** Increment whenever the matching behavior or the fingerprinted Matcher inputs change. */
    const val FILTER_ALGORITHM_VERSION: Int = 1

    /**
     * Fingerprints only the ordered fields consumed by [Matcher]. Length prefixes and a distinct
     * null marker make the byte stream unambiguous; list order is significant for duplicate keys.
     */
    fun fingerprint(playlistChannels: List<Channel>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateInt(playlistChannels.size)
        playlistChannels.forEach { channel ->
            digest.updateString(channel.groupKey)
            digest.updateNullableString(channel.epgOverrideId)
            digest.updateNullableString(channel.epgChannelId)
        }
        return buildString(64) {
            digest.digest().forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(HEX_DIGITS[unsigned ushr 4])
                append(HEX_DIGITS[unsigned and 0x0f])
            }
        }
    }

    private fun MessageDigest.updateInt(value: Int) {
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
    }

    private fun MessageDigest.updateString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        updateInt(bytes.size)
        update(bytes)
    }

    private fun MessageDigest.updateNullableString(value: String?) {
        if (value == null) updateInt(-1) else updateString(value)
    }

    /**
     * Incremental equivalent of [EpgMatcher.match], scoped to playlist group keys. It retains only
     * relevant candidates in a trie, not every alias from a potentially very large XMLTV guide.
     */
    class Matcher(playlistChannels: List<Channel>) {
        private class CandidateIds {
            var firstId: String? = null
                private set
            var ambiguous: Boolean = false
                private set

            fun add(id: String) {
                if (firstId == null) firstId = id
                else if (firstId != id) ambiguous = true
            }

            fun merge(other: CandidateIds) {
                other.firstId?.let(::add)
                if (other.ambiguous) ambiguous = true
            }

            fun copyState(): CandidateIds = CandidateIds().also { it.merge(this) }

            fun uniqueOrNull(): String? = firstId.takeUnless { ambiguous }
        }

        private class TrieNode {
            val children = HashMap<Char, TrieNode>()
            var channelKey: String? = null
            /** At least one playlist channel key continues below this node. */
            var hasChannelKeyStrictlyBelow: Boolean = false
            val exactAliases = CandidateIds()
            val aliasesThatExtendChannelKey = CandidateIds()
            val aliasesThatPrefixChannelKeys = CandidateIds()
        }

        private val root = TrieNode()
        private val directIds = LinkedHashSet<String>()
        private val candidatesByChannelKey = HashMap<String, TrieNode>()

        init {
            for (channel in playlistChannels) {
                listOfNotNull(channel.epgOverrideId, channel.epgChannelId)
                    .filter(String::isNotBlank)
                    .forEach(directIds::add)

                val key = channel.groupKey
                if (key.length < MIN_KEY_LENGTH || candidatesByChannelKey.containsKey(key)) continue

                var node = root
                for (character in key) {
                    node.hasChannelKeyStrictlyBelow = true
                    node = node.children.getOrPut(character) { TrieNode() }
                }
                node.channelKey = key
                candidatesByChannelKey[key] = node
            }
        }

        /** Adds a `<channel>` id/name pair without retaining the source alias record. */
        fun addAlias(epgId: String, displayName: String?) {
            if (epgId.isBlank()) return
            val aliasKeys = listOf(displayName ?: epgId, epgId)
                .distinct()
                .map { ChannelNameNormalizer.normalize(it).groupKey }
                .filter { it.length >= MIN_KEY_LENGTH }

            for (aliasKey in aliasKeys) {
                var node = root
                var pathExists = true
                var index = 0
                while (index < aliasKey.length) {
                    val next = node.children[aliasKey[index]]
                    if (next == null) {
                        pathExists = false
                        break
                    }
                    node = next
                    if (node.channelKey != null && node.channelKey != aliasKey) {
                        node.aliasesThatExtendChannelKey.add(epgId)
                    }
                    index++
                }
                if (!pathExists) continue

                if (node.channelKey == aliasKey) node.exactAliases.add(epgId)
                if (node.hasChannelKeyStrictlyBelow) {
                    node.aliasesThatPrefixChannelKeys.add(epgId)
                }
            }
        }

        fun retainedEpgIds(): Set<String> {
            val retained = LinkedHashSet(directIds)
            for ((channelKey, channelNode) in candidatesByChannelKey) {
                val exact = channelNode.exactAliases.uniqueOrNull()
                if (exact != null) {
                    // Match EpgMatcher: a unique exact match wins over any prefix candidates.
                    retained += exact
                    continue
                }

                val prefixCandidates = channelNode.aliasesThatExtendChannelKey.copyState()
                // EpgMatcher removes an ambiguous exact key from its exact map, then considers
                // those same IDs again in the prefix tier. Keep them here too; otherwise another
                // shorter prefix could appear unique even though the reference matcher rejects it.
                prefixCandidates.merge(channelNode.exactAliases)
                var node = root
                for (character in channelKey) {
                    node = node.children[character] ?: break
                    prefixCandidates.merge(node.aliasesThatPrefixChannelKeys)
                }
                prefixCandidates.uniqueOrNull()?.let(retained::add)
            }
            return retained
        }
    }

    /** Builds the same ambiguity-safe result from an in-memory alias collection, for tests/tools. */
    fun matchingEpgIds(
        playlistChannels: List<Channel>,
        aliases: Iterable<EpgChannelAlias>,
    ): Set<String> = Matcher(playlistChannels).let { matcher ->
        aliases.forEach { matcher.addAlias(it.epgId, it.displayName) }
        matcher.retainedEpgIds()
    }

    /** Predicate used before appending any programme to a Room batch. */
    fun shouldKeepProgramme(
        epgChannelId: String,
        startUtcMillis: Long,
        endUtcMillis: Long,
        retainedEpgIds: Set<String>?,
        fromUtcMillis: Long,
        toUtcMillis: Long,
    ): Boolean =
        (retainedEpgIds == null || epgChannelId in retainedEpgIds) &&
            endUtcMillis >= fromUtcMillis && startUtcMillis <= toUtcMillis

    /** Legacy cleanup utility; new playlist-scoped ingestion filters before staging instead. */
    suspend fun retainOnly(
        programmeDao: ProgrammeDao,
        feedId: Long,
        retainedEpgIds: Set<String>,
    ): Int {
        val unwantedIds = programmeDao.channelIdsForFeed(feedId)
            .filterNot(retainedEpgIds::contains)
        unwantedIds.chunked(DELETE_CHUNK_SIZE).forEach { batch ->
            programmeDao.deleteChannelsForFeed(feedId, batch)
        }
        return programmeDao.countForFeed(feedId)
    }
}
