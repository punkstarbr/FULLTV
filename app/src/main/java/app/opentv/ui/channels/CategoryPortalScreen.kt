/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Channel
import app.opentv.data.model.shownName
import app.opentv.ui.ChannelsViewModel
import coil.compose.AsyncImage

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
    val background = Color(0xFF081E28)
    val surface = Color(0xFF102D38)
    val accent = Color(0xFFB7E41D)

    Column(
        Modifier
            .fillMaxSize()
            .background(background)
            .padding(horizontal = 30.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    stringResource(R.string.portal_live_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFB8C8CE),
                )
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onOpenGuide) {
                Text(stringResource(R.string.portal_open_guide))
            }
        }

        Spacer(Modifier.height(18.dp))

        Surface(
            modifier = Modifier.fillMaxWidth().height(144.dp),
            color = Color(0xFF123744),
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.portal_hero_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.portal_hero_desc),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFFCAD8DC),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(20.dp))
                Button(onClick = onOpenGuide) {
                    Text(stringResource(R.string.portal_open_guide))
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.portal_categories),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        if (categories.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = accent)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    stringResource(
                        if (isSyncing || hasSources) R.string.portal_no_channels else R.string.portal_no_source,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFB8C8CE),
                )
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "all") {
                    PortalCategoryCard(
                        label = stringResource(R.string.guide_all_channels),
                        selected = selectedCategory == null && !favouritesOnly,
                        surface = surface,
                        accent = accent,
                        onClick = { viewModel.selectCategory(null) },
                    )
                }
                item(key = "favourites") {
                    PortalCategoryCard(
                        label = stringResource(R.string.guide_favourites),
                        selected = favouritesOnly,
                        surface = surface,
                        accent = accent,
                        onClick = viewModel::selectFavourites,
                    )
                }
                items(categories, key = { it.key }) { category ->
                    PortalCategoryCard(
                        label = category.label,
                        selected = selectedCategory == category.key && !favouritesOnly,
                        surface = surface,
                        accent = accent,
                        onClick = { viewModel.selectCategory(category.key) },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.portal_favourites),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.portal_open_guide),
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpenGuide).padding(8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (uniqueFavourites.isEmpty()) {
            Text(
                stringResource(R.string.portal_no_favourites),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFB8C8CE),
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(uniqueFavourites, key = { "fav-${it.id}" }) { channel ->
                    FavouriteChannelCard(channel = channel, accent = accent) { onPlayChannel(channel) }
                }
            }
        }
    }
}

@Composable
private fun PortalCategoryCard(
    label: String,
    selected: Boolean,
    surface: Color,
    accent: Color,
    onClick: () -> Unit,
) {
    var focused = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .width(176.dp)
            .height(80.dp)
            .then(
                if (selected || focused.value) Modifier.border(
                    width = if (focused.value) 3.dp else 2.dp,
                    color = accent,
                    shape = RoundedCornerShape(12.dp),
                ) else Modifier,
            )
            .onFocusChanged { focused.value = it.isFocused }
            .clickable(onClick = onClick),
        color = if (selected) Color(0xFF1A3943) else surface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected || focused.value) accent else Color.White,
                fontWeight = if (selected || focused.value) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FavouriteChannelCard(channel: Channel, accent: Color, onClick: () -> Unit) {
    var focused = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .width(164.dp)
            .height(112.dp)
            .then(
                if (focused.value) Modifier.border(3.dp, accent, RoundedCornerShape(12.dp)) else Modifier,
            )
            .onFocusChanged { focused.value = it.isFocused }
            .clickable(onClick = onClick),
        color = Color(0xFF102D38),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                channel.shownName,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
