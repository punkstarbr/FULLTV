/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

/** Ordered channel list handed from the guide to one player launch. */
object PlaybackQueue {
    data class Item(val id: Long, val name: String, val logoUrl: String?, val number: Int? = null)

    private var ownerChannelId: Long? = null
    private var pendingItems: List<Item> = emptyList()

    /** Publish a one-use queue for the launch that starts at [channelId]. */
    @Synchronized
    fun publish(channelId: Long, items: List<Item>) {
        ownerChannelId = channelId
        pendingItems = items.toList()
    }

    /** Consume only a queue anchored on this launch's channel; discard stale/mismatched state. */
    @Synchronized
    fun consumeFor(channelId: Long): List<Item> {
        val result = if (ownerChannelId == channelId) pendingItems else emptyList()
        ownerChannelId = null
        pendingItems = emptyList()
        return result
    }

    @Synchronized
    fun clear() {
        ownerChannelId = null
        pendingItems = emptyList()
    }
}
