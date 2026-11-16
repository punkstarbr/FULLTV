/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.model.shownName
import app.opentv.security.ParentalAuthorization
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.FullTvCard
import app.opentv.ui.FullTvChrome
import app.opentv.ui.FullTvSectionHeader
import app.opentv.ui.ParentalPinGate
import app.opentv.ui.fullTvFocusRing
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Channel manager: browse your channels by category and, for each, favourite it or hide it from
 * the guide.
 *
 * Two panes. LEFT is Search plus a scrollable category list (with a source filter when more than
 * one provider is configured, so a second source can be kept separate rather than merged). RIGHT
 * is the channels in the selected category — each a logo, name, favourite star and a show/hide
 * switch. Hidden channels appear here too, greyed, so they can be brought back; the guide never
 * shows them, but the manager shows everything.
 *
 * The right pane is always scoped to one category (and optionally one source): a flat list of a
 * 20,000-channel provider is unscrollable on a remote, so browsing is by category by design.
 */
@Composable
fun ChannelManagerScreen(
    onBack: () -> Unit,
    viewModel: ChannelsViewModel = viewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { ServiceLocator.get(context).settings }
    val pinIsSet by settings.pinIsSet.collectAsState()
    val hiddenUnlocked by settings.hiddenUnlocked.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val categoryGroups by viewModel.managerCategoryGroups.collectAsState()
    val selectedSource by viewModel.managerSelectedSource.collectAsState()
    val selectedCategory by viewModel.managerSelectedCategory.collectAsState()
    val browseRows by viewModel.managerRows.collectAsState()

    // Search remains an option alongside category browsing, with the same keyboard and actions.
    var searchMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val searchResults by viewModel.managerResults.collectAsState()
    var pendingUnhide by remember { mutableStateOf<ChannelsViewModel.Row?>(null) }
    LaunchedEffect(query) { viewModel.setManagerQuery(query) }

    // The selected rail entry owns this requester, so LEFT from a channel row returns to the
    // current category (or Search) instead of restarting focus at the top of the menu.
    val railFocusRequester = remember { FocusRequester() }
    val onExitLeftToRail: () -> Boolean = {
        runCatching { railFocusRequester.requestFocus() }.isSuccess
    }

    BackHandler { onBack() }

    fun toggleHidden(row: ChannelsViewModel.Row, hidden: Boolean) {
        val showing = !hidden
        if (showing && !ParentalAuthorization.mayUnhide(pinIsSet, hiddenUnlocked)) {
            pendingUnhide = row
        } else {
            viewModel.setRowHidden(row, showing)
        }
    }

    pendingUnhide?.let { row ->
        ParentalPinGate(
            onCancel = { pendingUnhide = null },
            onSubmit = { entered ->
                settings.verifyPin(entered).also { valid ->
                    if (valid) {
                        settings.setHiddenUnlocked(true)
                        viewModel.setRowHidden(row, hidden = false)
                        pendingUnhide = null
                    }
                }
            },
        )
        return
    }

    // Open on the first category so channels show straight away — the whole complaint was that the
    // manager never displayed any. Re-runs when the category set changes so a newly-chosen source
    // immediately shows its channels.
    LaunchedEffect(categoryGroups, searchMode) {
        if (!searchMode && selectedCategory == null && categoryGroups.isNotEmpty()) {
            viewModel.selectManagerCategory(categoryGroups.first().key)
        }
    }
    // One-time initial focus only: background refreshes never steal focus from the channel list.
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(categoryGroups, searchMode) {
        if (!initialFocusDone && !searchMode && categoryGroups.isNotEmpty()) {
            delay(80)
            runCatching { railFocusRequester.requestFocus() }
            initialFocusDone = true
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 760.dp
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(
                    horizontal = if (compact) FullTvChrome.compactPageInset else FullTvChrome.pageInset,
                    vertical = if (compact) 14.dp else 18.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FullTvSectionHeader(
                    title = stringResource(R.string.channels_manager_title),
                    subtitle = stringResource(R.string.channels_manager_hint),
                    modifier = Modifier.weight(1f),
                )
                ManagerDoneButton(onBack)
            }

            if (compact) {
                MobileManagerPicker(
                    sources = sources,
                    categoryGroups = categoryGroups,
                    selectedSource = selectedSource,
                    selectedCategory = selectedCategory,
                    searchMode = searchMode,
                    railFocusRequester = railFocusRequester,
                    onSearch = { searchMode = true },
                    onSelectSource = {
                        searchMode = false
                        viewModel.selectManagerSource(it)
                    },
                    onSelectCategory = {
                        searchMode = false
                        viewModel.selectManagerCategory(it)
                    },
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = FullTvChrome.compactPageInset, vertical = 10.dp),
                ) {
                    if (searchMode) {
                        SearchPane(
                            compact = true,
                            query = query,
                            results = searchResults,
                            onKey = { if (query.length < 40) query += it },
                            onSpace = { if (query.length < 40) query += " " },
                            onBackspace = { query = query.dropLast(1) },
                            onClear = { query = "" },
                            onToggleHidden = ::toggleHidden,
                            onToggleFavourite = { viewModel.toggleFavourite(it) },
                        )
                    } else {
                        BrowsePane(
                            hasCategories = categoryGroups.isNotEmpty(),
                            categorySelected = selectedCategory != null,
                            rows = browseRows,
                            onExitLeft = onExitLeftToRail,
                            onToggleHidden = ::toggleHidden,
                            onToggleFavourite = { viewModel.toggleFavourite(it) },
                        )
                    }
                }
            } else {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    // ---- LEFT pane: Search, source filter and category list ---------------------
                    FullTvCard(
                        modifier = Modifier.width(300.dp).fillMaxHeight(),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().focusGroup(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            item {
                                RailEntry(
                                    label = stringResource(R.string.nav_search),
                                    selected = searchMode,
                                    onClick = { searchMode = true },
                                    modifier = if (searchMode) Modifier.focusRequester(railFocusRequester) else Modifier,
                                )
                            }

                            if (sources.size > 1) {
                                item { SectionLabel(stringResource(R.string.channels_manager_source_header)) }
                                item {
                                    RailEntry(
                                        label = stringResource(R.string.channels_manager_all_sources),
                                        selected = selectedSource == null,
                                        onClick = { searchMode = false; viewModel.selectManagerSource(null) },
                                    )
                                }
                                items(sources, key = { "src-${it.id}" }) { source ->
                                    RailEntry(
                                        label = source.name,
                                        selected = selectedSource == source.id,
                                        onClick = { searchMode = false; viewModel.selectManagerSource(source.id) },
                                    )
                                }
                            }

                            item { SectionLabel(stringResource(R.string.channels_manager_categories_header)) }
                            items(categoryGroups, key = { "cat-${it.key}" }) { group ->
                                val groupSelected = !searchMode && selectedCategory == group.key
                                RailEntry(
                                    label = group.label,
                                    selected = groupSelected,
                                    onClick = { searchMode = false; viewModel.selectManagerCategory(group.key) },
                                    modifier = if (groupSelected) Modifier.focusRequester(railFocusRequester) else Modifier,
                                )
                            }
                        }
                    }

                    // ---- RIGHT pane: the search UI, or the selected category's channels --------
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        if (searchMode) {
                            SearchPane(
                                compact = false,
                                query = query,
                                results = searchResults,
                                onKey = { if (query.length < 40) query += it },
                                onSpace = { if (query.length < 40) query += " " },
                                onBackspace = { query = query.dropLast(1) },
                                onClear = { query = "" },
                                onToggleHidden = ::toggleHidden,
                                onToggleFavourite = { viewModel.toggleFavourite(it) },
                            )
                        } else {
                            BrowsePane(
                                hasCategories = categoryGroups.isNotEmpty(),
                                categorySelected = selectedCategory != null,
                                rows = browseRows,
                                onExitLeft = onExitLeftToRail,
                                onToggleHidden = ::toggleHidden,
                                onToggleFavourite = { viewModel.toggleFavourite(it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Compact menu: every source and category remains reachable without squeezing two panes together. */
@Composable
private fun MobileManagerPicker(
    sources: List<app.opentv.data.model.Source>,
    categoryGroups: List<ChannelsViewModel.CategoryGroup>,
    selectedSource: Long?,
    selectedCategory: String?,
    searchMode: Boolean,
    railFocusRequester: FocusRequester,
    onSearch: () -> Unit,
    onSelectSource: (Long?) -> Unit,
    onSelectCategory: (String) -> Unit,
) {
    val categoryListState = rememberLazyListState()
    LaunchedEffect(selectedCategory, searchMode, categoryGroups) {
        if (!searchMode && selectedCategory != null) {
            val index = categoryGroups.indexOfFirst { it.key == selectedCategory }
            if (index >= 0) categoryListState.animateScrollToItem(index)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(bottom = 6.dp),
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().focusGroup(),
            contentPadding = PaddingValues(horizontal = FullTvChrome.compactPageInset, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                RailEntry(
                    label = stringResource(R.string.nav_search),
                    selected = searchMode,
                    onClick = onSearch,
                    compact = true,
                    modifier = if (searchMode) Modifier.focusRequester(railFocusRequester) else Modifier,
                )
            }
        }

        if (sources.size > 1) {
            SectionLabel(stringResource(R.string.channels_manager_source_header), compact = true)
            LazyRow(
                modifier = Modifier.fillMaxWidth().focusGroup(),
                contentPadding = PaddingValues(horizontal = FullTvChrome.compactPageInset, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    RailEntry(
                        label = stringResource(R.string.channels_manager_all_sources),
                        selected = selectedSource == null,
                        onClick = { onSelectSource(null) },
                        compact = true,
                    )
                }
                items(sources, key = { "mobile-src-${it.id}" }) { source ->
                    RailEntry(
                        label = source.name,
                        selected = selectedSource == source.id,
                        onClick = { onSelectSource(source.id) },
                        compact = true,
                    )
                }
            }
        }

        SectionLabel(stringResource(R.string.channels_manager_categories_header), compact = true)
        LazyRow(
            state = categoryListState,
            modifier = Modifier.fillMaxWidth().focusGroup(),
            contentPadding = PaddingValues(horizontal = FullTvChrome.compactPageInset, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(categoryGroups, key = { "mobile-cat-${it.key}" }) { group ->
                val groupSelected = !searchMode && selectedCategory == group.key
                RailEntry(
                    label = group.label,
                    selected = groupSelected,
                    onClick = { onSelectCategory(group.key) },
                    compact = true,
                    modifier = if (groupSelected) Modifier.focusRequester(railFocusRequester) else Modifier,
                )
            }
        }
    }
}

/** The kept-intact keyboard-driven search: keyboard first on mobile, matches alongside on TV. */
@Composable
private fun SearchPane(
    compact: Boolean,
    query: String,
    results: List<ChannelsViewModel.Row>,
    onKey: (String) -> Unit,
    onSpace: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onToggleHidden: (ChannelsViewModel.Row, Boolean) -> Unit,
    onToggleFavourite: (ChannelsViewModel.Row) -> Unit,
) {
    if (compact) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                Box(Modifier.widthIn(min = 560.dp)) {
                    OnScreenKeyboard(onKey = onKey, onSpace = onSpace, onBackspace = onBackspace, onClear = onClear)
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).fillMaxWidth()) {
                SearchResults(
                    query = query,
                    results = results,
                    onToggleHidden = onToggleHidden,
                    onToggleFavourite = onToggleFavourite,
                )
            }
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            OnScreenKeyboard(onKey = onKey, onSpace = onSpace, onBackspace = onBackspace, onClear = onClear)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f).fillMaxSize()) {
                SearchResults(
                    query = query,
                    results = results,
                    onToggleHidden = onToggleHidden,
                    onToggleFavourite = onToggleFavourite,
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    query: String,
    results: List<ChannelsViewModel.Row>,
    onToggleHidden: (ChannelsViewModel.Row, Boolean) -> Unit,
    onToggleFavourite: (ChannelsViewModel.Row) -> Unit,
) {
    when {
        query.isBlank() -> Hint(stringResource(R.string.channels_manager_search_hint))
        query.trim().length < 2 -> Hint(stringResource(R.string.common_keep_typing))
        results.isEmpty() -> Hint(stringResource(R.string.channels_manager_no_match, query))
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(FullTvChrome.cardGap)) {
            items(results, key = { it.key }) { row ->
                val hidden = row.variants.all { it.hidden }
                ManagerRow(
                    row = row,
                    hidden = hidden,
                    onToggleHidden = { onToggleHidden(row, hidden) },
                    onToggleFavourite = { onToggleFavourite(row) },
                )
            }
        }
    }
}

/** The selected category's channels — hidden ones included, greyed, so they can be un-hidden. */
@Composable
private fun BrowsePane(
    hasCategories: Boolean,
    categorySelected: Boolean,
    rows: List<ChannelsViewModel.Row>,
    onExitLeft: () -> Boolean,
    onToggleHidden: (ChannelsViewModel.Row, Boolean) -> Unit,
    onToggleFavourite: (ChannelsViewModel.Row) -> Unit,
) {
    when {
        !hasCategories -> Hint(stringResource(R.string.channels_manager_no_channels))
        !categorySelected -> Hint(stringResource(R.string.channels_manager_pick_category))
        rows.isEmpty() -> Hint(stringResource(R.string.channels_manager_empty_category))
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(FullTvChrome.cardGap),
        ) {
            items(rows, key = { it.key }) { row ->
                val hidden = row.variants.all { it.hidden }
                ManagerRow(
                    row = row,
                    hidden = hidden,
                    onToggleHidden = { onToggleHidden(row, hidden) },
                    onToggleFavourite = { onToggleFavourite(row) },
                    // LEFT from the row's leftmost control (the star) hops back to the category
                    // picker; from the switch it first steps left to the star, as usual.
                    leftEdgeModifier = Modifier.onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) onExitLeft()
                        else false
                    },
                )
            }
        }
    }
}

@Composable
private fun RailEntry(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val background = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val foreground = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = label,
        style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        color = foreground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .then(if (compact) Modifier.widthIn(max = 240.dp) else Modifier.fillMaxWidth())
            .onFocusChanged { focused = it.isFocused }
            .clip(FullTvChrome.controlShape)
            .background(background)
            .fullTvFocusRing(focused, MaterialTheme.colorScheme.onPrimary, FullTvChrome.controlShape)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                this.selected = selected
            }
            .padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 12.dp),
    )
}

@Composable
private fun SectionLabel(text: String, compact: Boolean = false) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = if (compact) FullTvChrome.compactPageInset else 12.dp,
            top = if (compact) 2.dp else 12.dp,
            bottom = 4.dp,
        ),
    )
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        FullTvCard(Modifier.fillMaxWidth().widthIn(max = 620.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
    }
}

@Composable
private fun ManagerDoneButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .fullTvFocusRing(focused, MaterialTheme.colorScheme.primary),
    ) {
        Text(stringResource(R.string.common_done))
    }
}

@Composable
private fun ManagerRow(
    row: ChannelsViewModel.Row,
    hidden: Boolean,
    onToggleHidden: () -> Unit,
    onToggleFavourite: () -> Unit,
    leftEdgeModifier: Modifier = Modifier,
) {
    val alpha = if (hidden) 0.62f else 1f
    var favouriteFocused by remember(row.key) { mutableStateOf(false) }
    var visibilityFocused by remember(row.key) { mutableStateOf(false) }
    val visibilityDescription = stringResource(R.string.channels_show)
    val visibilityState = stringResource(if (hidden) R.string.channels_hidden else R.string.channels_showing)

    FullTvCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = row.primary.logoUrl,
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.primary.shownName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (hidden) stringResource(R.string.channels_hidden) else stringResource(R.string.channels_showing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                )
            }

            // The star is the row's leftmost focusable, so it carries the LEFT-to-picker handler.
            IconButton(
                onClick = onToggleFavourite,
                modifier = leftEdgeModifier
                    .onFocusChanged { favouriteFocused = it.isFocused }
                    .fullTvFocusRing(favouriteFocused, MaterialTheme.colorScheme.primary),
            ) {
                Icon(
                    imageVector = if (row.primary.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (row.primary.favourite) stringResource(R.string.common_remove_favourite)
                    else stringResource(R.string.common_favourite),
                    tint = if (row.primary.favourite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.channels_show),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                // Checked = visible; off = hidden. TalkBack receives an explicit state as well.
                Switch(
                    checked = !hidden,
                    onCheckedChange = { onToggleHidden() },
                    modifier = Modifier
                        .onFocusChanged { visibilityFocused = it.isFocused }
                        .fullTvFocusRing(visibilityFocused, MaterialTheme.colorScheme.primary)
                        .semantics {
                            contentDescription = visibilityDescription
                            stateDescription = visibilityState
                        },
                )
            }
        }
    }
}
