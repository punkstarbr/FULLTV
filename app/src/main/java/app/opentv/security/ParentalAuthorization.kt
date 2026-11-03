/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.security

import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.parser.ChannelNameNormalizer

/**
 * The single effective parental decision used by channel management, LAN management and playback.
 *
 * Category visibility is stored by the logical category key, while a channel stores the provider's
 * raw category id. Keeping the conversion here prevents one entry point from treating a hidden
 * category differently from another.
 */
object ParentalAuthorization {

    /** Raw category ids represented by the configured logical hidden-category keys. */
    fun hiddenCategoryIds(categories: Iterable<Category>, hiddenKeys: Set<String>): Set<String> =
        categories.asSequence()
            .filter { logicalCategoryKey(it) in hiddenKeys }
            .map { it.id }
            .toSet()

    fun isCategoryHidden(channel: Channel, hiddenCategoryIds: Set<String>): Boolean =
        channel.categoryId != null && channel.categoryId in hiddenCategoryIds

    /** A configured PIN blocks reactivating a hidden channel until this session is unlocked. */
    fun mayUnhide(pinIsSet: Boolean, hiddenUnlocked: Boolean): Boolean =
        !pinIsSet || hiddenUnlocked

    /**
     * Effective playback check. It is intentionally stricter than list filtering: a route, reminder,
     * resume request or numeric channel entry must not become a second way around parental controls.
     *
     * Category hiding is an explicit visibility choice even when no PIN is configured, so it always
     * requires the session unlock. Individual channel hiding remains a normal visibility preference
     * without a PIN, but is protected once the user has configured one.
     */
    fun mayStartPlayback(
        channelHidden: Boolean,
        categoryHidden: Boolean,
        pinIsSet: Boolean,
        hiddenUnlocked: Boolean,
    ): Boolean =
        (!categoryHidden || hiddenUnlocked) &&
            (!channelHidden || mayUnhide(pinIsSet, hiddenUnlocked))

    fun mayStartPlayback(
        channel: Channel,
        hiddenCategoryIds: Set<String>,
        pinIsSet: Boolean,
        hiddenUnlocked: Boolean,
    ): Boolean = mayStartPlayback(
        channelHidden = channel.hidden,
        categoryHidden = isCategoryHidden(channel, hiddenCategoryIds),
        pinIsSet = pinIsSet,
        hiddenUnlocked = hiddenUnlocked,
    )

    private fun logicalCategoryKey(category: Category): String {
        val normalized = ChannelNameNormalizer.normalize(category.name)
        return normalized.groupKey.ifEmpty { category.id }
    }
}
