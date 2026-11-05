/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

/**
 * Returns the LazyColumn index of the currently selected category-rail entry.
 *
 * When there is more than one source, the list begins with a header, "all sources", one item per
 * source, and a divider. The pinned Favourites and All entries follow that optional block.
 * A category that disappeared (for example because its source changed) falls back to All.
 */
internal fun guideRailSelectedItemIndex(
    sourceCount: Int,
    categoryCount: Int,
    selectedCategoryIndex: Int?,
    favouritesOnly: Boolean,
): Int {
    val sourceBlockSize = if (sourceCount > 1) sourceCount + 3 else 0
    val favouritesIndex = sourceBlockSize
    if (favouritesOnly) return favouritesIndex

    val allIndex = favouritesIndex + 1
    val validCategoryIndex = selectedCategoryIndex?.takeIf { it in 0 until categoryCount }
        ?: return allIndex
    return allIndex + 1 + validCategoryIndex
}
