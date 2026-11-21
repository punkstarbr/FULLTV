/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.model.EpgFeed

/**
 * The user-visible EPG order is also the conflict precedence: smaller priority wins, with the
 * stable Room id as a deterministic tie-breaker. A move compacts the order to 0..N-1.
 */
object EpgFeedPriority {
    private val order = compareBy<EpgFeed> { it.priority }.thenBy { it.id }

    fun ordered(feeds: List<EpgFeed>): List<EpgFeed> = feeds.sortedWith(order)

    /** Returns normalized (feed id, new priority) pairs; an empty list means no move was possible. */
    fun reorderedPriorities(
        feeds: List<EpgFeed>,
        feedId: Long,
        direction: Int,
    ): List<Pair<Long, Int>> {
        require(direction == -1 || direction == 1) { "Direction must be -1 or 1." }
        val reordered = ordered(feeds).toMutableList()
        val from = reordered.indexOfFirst { it.id == feedId }
        if (from < 0) return emptyList()
        val to = from + direction
        if (to !in reordered.indices) return emptyList()
        val moved = reordered.removeAt(from)
        reordered.add(to, moved)
        return reordered.mapIndexed { index, feed -> feed.id to index }
    }
}
