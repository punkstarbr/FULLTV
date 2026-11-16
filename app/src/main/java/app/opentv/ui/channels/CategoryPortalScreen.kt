/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Channel
import app.opentv.data.model.shownName
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.FullTvCard
import app.opentv.ui.FullTvChrome
import app.opentv.ui.FullTvSectionHeader
import app.opentv.ui.fullTvFocusRing
import coil.compose.AsyncImage

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun CategoryPortalScreen(
    isSyncing: Boolean,
    hasSources: Boolean,
    onPlayChannel: (Channel) -> Unit,
    onOpenGuide: () -> Unit,
    viewModel: ChannelsViewModel = viewModel(),
) {
    val categories by viewModel.visibleCategoryGroups.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val favouritesOnly by viewModel.favouritesOnly.collectAsState()
    val favourites by viewModel.favourites.collectAsState()
    val uniqueFavourites = remember(favourites) {
        favourites.distinctBy { it.groupKey.ifBlank { "${it.sourceId}:${it.id}" } }.take(12)
    }
    val categoryListState = rememberLazyListState()
    val favouriteListState = rememberLazyListState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 640.dp
        val pageInset = if (compact) FullTvChrome.compactPageInset else FullTvChrome.pageInset

        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .focusRestorer()
                .focusGroup()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = pageInset, vertical = pageInset),
            verticalArrangement = Arrangement.spacedBy(FullTvChrome.sectionGap),
        ) {
            FullTvSectionHeader(
                title = stringResource(R.string.nav_live_tv),
                subtitle = stringResource(R.string.app_name),
                trailingContent = {
                    if (!compact) {
                        Button(onClick = onOpenGuide) {
                            Text(stringResource(R.string.portal_open_guide))
                        }
                    }
                },
            )

            FullTvCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (compact) 0.dp else 144.dp)
                        .padding(horizontal = if (compact) 18.dp else 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .width(4.dp)
                            .heightIn(min = 72.dp)
                            .clip(FullTvChrome.controlShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(if (compact) 14.dp else 20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.portal_hero_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.portal_hero_desc),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (compact) 3 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (compact) {
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onOpenGuide) {
                                Text(stringResource(R.string.portal_open_guide))
                            }
                        }
                    }
                    if (!compact) {
                        Spacer(Modifier.width(20.dp))
                        Button(onClick = onOpenGuide) {
                            Text(stringResource(R.string.portal_open_guide))
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FullTvSectionHeader(title = stringResource(R.string.portal_categories))
                if (categories.isEmpty()) {
                    FullTvCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(
                                stringResource(
                                    if (isSyncing || hasSources) R.string.portal_no_channels
                                    else R.string.portal_no_source,
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyRow(
                        state = categoryListState,
                        modifier = Modifier.focusRestorer().focusGroup(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(FullTvChrome.cardGap),
                    ) {
                        item(key = "all") {
                            PortalCategoryCard(
                                label = stringResource(R.string.guide_all_channels),
                                selected = selectedCategory == null && !favouritesOnly,
                                onClick = { viewModel.selectCategory(null) },
                            )
                        }
                        item(key = "favourites") {
                            PortalCategoryCard(
                                label = stringResource(R.string.guide_favourites),
                                selected = favouritesOnly,
                                onClick = viewModel::selectFavourites,
                            )
                        }
                        items(categories, key = { it.key }) { category ->
                            PortalCategoryCard(
                                label = category.label,
                                selected = selectedCategory == category.key && !favouritesOnly,
                                onClick = { viewModel.selectCategory(category.key) },
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FullTvSectionHeader(
                    title = stringResource(R.string.portal_favourites),
                    trailingContent = {
                        TextButton(onClick = onOpenGuide) {
                            Text(stringResource(R.string.portal_open_guide))
                        }
                    },
                )
                if (uniqueFavourites.isEmpty()) {
                    FullTvCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            stringResource(R.string.portal_no_favourites),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                        )
                    }
                } else {
                    LazyRow(
                        state = favouriteListState,
                        modifier = Modifier.focusRestorer().focusGroup(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(FullTvChrome.cardGap),
                    ) {
                        items(uniqueFavourites, key = { "fav-${it.sourceId}-${it.id}" }) { channel ->
                            FavouriteChannelCard(channel = channel) { onPlayChannel(channel) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PortalCategoryCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = FullTvChrome.cardShape
    FullTvCard(
        modifier = Modifier
            .widthIn(min = 164.dp, max = 248.dp)
            .heightIn(min = 84.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier,
            )
            .fullTvFocusRing(focused = focused, color = MaterialTheme.colorScheme.primary, shape = shape)
            .selectable(selected = selected, role = Role.Button, onClick = onClick),
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FavouriteChannelCard(channel: Channel, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    FullTvCard(
        modifier = Modifier
            .widthIn(min = 156.dp, max = 176.dp)
            .heightIn(min = 132.dp)
            .onFocusChanged { focused = it.isFocused }
            .fullTvFocusRing(focused = focused, color = MaterialTheme.colorScheme.primary)
            .clickable(role = Role.Button, onClick = onClick),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = null,
                modifier = Modifier.size(52.dp).clip(FullTvChrome.controlShape),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                channel.shownName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
