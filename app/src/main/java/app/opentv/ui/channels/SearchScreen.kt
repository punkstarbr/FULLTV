/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Programme
import app.opentv.data.model.Series
import app.opentv.data.model.shownName
import app.opentv.data.parser.displayTitle
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.FullTvCard
import app.opentv.ui.FullTvChrome
import app.opentv.ui.FullTvSectionHeader
import app.opentv.ui.VodViewModel
import app.opentv.ui.fullTvFocusRing
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Channel search with an on-screen keyboard.
 *
 * Android TV's default text field punts to "type on your phone", which is useless if your phone
 * isn't to hand. This screen draws its own d-pad keyboard so search works with the remote alone,
 * and shows matching channels live as you type.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    onPlayChannel: (Channel) -> Unit,
    onPlayMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onBack: () -> Unit,
    onOpenProgramme: (ChannelsViewModel.Row, Programme) -> Unit = { _, _ -> },
    viewModel: ChannelsViewModel = viewModel(),
    vodViewModel: VodViewModel = viewModel(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    val channelResults by viewModel.searchResults.collectAsState()
    val programmeResults by viewModel.programmeResults.collectAsState()
    val movieResults by vodViewModel.movieResults.collectAsState()
    val seriesResults by vodViewModel.seriesResults.collectAsState()
    val anyResults = programmeResults.isNotEmpty() || channelResults.isNotEmpty() ||
        movieResults.isNotEmpty() || seriesResults.isNotEmpty()

    LaunchedEffect(query) {
        viewModel.setSearchQuery(query)
        vodViewModel.setVodSearchQuery(query)
    }
    BackHandler { onBack() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRestorer()
            .focusGroup(),
    ) {
        val wideLayout = maxWidth >= 900.dp || (maxHeight < 520.dp && maxWidth >= 780.dp)
        val horizontalInset = if (wideLayout) FullTvChrome.pageInset else FullTvChrome.compactPageInset

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalInset, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (wideLayout) 16.dp else 10.dp),
        ) {
            FullTvSectionHeader(
                title = stringResource(R.string.nav_search),
                modifier = Modifier.semantics { heading() },
                trailingContent = {
                    OutlinedButton(onClick = onBack) {
                        Text(stringResource(R.string.common_done))
                    }
                },
            )

            SearchQueryPanel(query = query)

            if (wideLayout) {
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FullTvChrome.sectionGap),
                ) {
                    KeyboardPanel(
                        modifier = Modifier
                            .weight(1.12f)
                            .fillMaxHeight(),
                        compact = false,
                        query = query,
                        onQueryChange = { query = it },
                    )
                    SearchResultsPane(
                        modifier = Modifier
                            .weight(0.88f)
                            .fillMaxHeight(),
                        query = query,
                        anyResults = anyResults,
                        programmeResults = programmeResults,
                        channelResults = channelResults,
                        movieResults = movieResults,
                        seriesResults = seriesResults,
                        onPlayChannel = onPlayChannel,
                        onPlayMovie = onPlayMovie,
                        onOpenSeries = onOpenSeries,
                        onOpenProgramme = onOpenProgramme,
                    )
                }
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    KeyboardPanel(
                        modifier = Modifier.fillMaxWidth(),
                        compact = true,
                        query = query,
                        onQueryChange = { query = it },
                    )
                    SearchResultsPane(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        query = query,
                        anyResults = anyResults,
                        programmeResults = programmeResults,
                        channelResults = channelResults,
                        movieResults = movieResults,
                        seriesResults = seriesResults,
                        onPlayChannel = onPlayChannel,
                        onPlayMovie = onPlayMovie,
                        onOpenSeries = onOpenSeries,
                        onOpenProgramme = onOpenProgramme,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchQueryPanel(query: String) {
    FullTvCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        shape = FullTvChrome.controlShape,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = query.ifEmpty { stringResource(R.string.search_type_name) },
                style = if (query.isEmpty()) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.headlineSmall,
                color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun KeyboardPanel(
    modifier: Modifier,
    compact: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    FullTvCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = FullTvChrome.cardShape,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (compact) Modifier else Modifier.fillMaxHeight())
                .padding(if (compact) 8.dp else 14.dp),
        ) {
            OnScreenKeyboard(
                compact = compact,
                onKey = { if (query.length < 40) onQueryChange(query + it) },
                onSpace = { if (query.length < 40) onQueryChange(query + " ") },
                onBackspace = { onQueryChange(query.dropLast(1)) },
                onClear = { onQueryChange("") },
            )
        }
    }
}

@kotlin.OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SearchResultsPane(
    modifier: Modifier,
    query: String,
    anyResults: Boolean,
    programmeResults: List<ChannelsViewModel.ProgrammeResult>,
    channelResults: List<ChannelsViewModel.Row>,
    movieResults: List<Movie>,
    seriesResults: List<Series>,
    onPlayChannel: (Channel) -> Unit,
    onPlayMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenProgramme: (ChannelsViewModel.Row, Programme) -> Unit,
) {
    FullTvCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = FullTvChrome.cardShape,
    ) {
        when {
            query.isBlank() -> Hint(Modifier.fillMaxSize(), stringResource(R.string.search_start_hint))
            query.trim().length < 2 -> Hint(Modifier.fillMaxSize(), stringResource(R.string.common_keep_typing))
            !anyResults -> Hint(
                Modifier.fillMaxSize(),
                stringResource(R.string.search_no_results, query),
            )
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRestorer()
                    .focusGroup()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (programmeResults.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.search_programmes),
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(
                        programmeResults,
                        key = {
                            "p${it.row.key}:${it.programme.epgChannelId}:" +
                                "${it.programme.startUtcMillis}:${it.programme.id}"
                        },
                    ) { result ->
                        ProgrammeResultRow(
                            result = result,
                            onClick = { onOpenProgramme(result.row, result.programme) },
                        )
                    }
                }
                if (channelResults.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.common_channels),
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(channelResults, key = { "c${it.key}" }) { row ->
                        SearchResultRow(row = row, onClick = { onPlayChannel(row.primary) })
                    }
                }
                if (movieResults.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.nav_movies),
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(movieResults, key = { "m${it.id}" }) { movie ->
                        VodResultRow(movie.displayTitle, movie.posterUrl, movie.year?.toString()) {
                            onPlayMovie(movie)
                        }
                    }
                }
                if (seriesResults.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.nav_shows),
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(seriesResults, key = { "s${it.id}" }) { show ->
                        VodResultRow(show.displayTitle, show.posterUrl, show.year?.toString()) {
                            onOpenSeries(show)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(modifier: Modifier, text: String) {
    Box(
        modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    FullTvSectionHeader(
        title = title,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun VodResultRow(name: String, posterUrl: String?, subtitle: String?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    SearchResultCard(
        focused = focused,
        onFocusChanged = { focused = it },
        onClick = onClick,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(width = 44.dp, height = 62.dp)
                    .clip(FullTvChrome.controlShape),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(row: ChannelsViewModel.Row, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    SearchResultCard(
        focused = focused,
        onFocusChanged = { focused = it },
        onClick = onClick,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = row.primary.logoUrl,
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(FullTvChrome.controlShape),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.primary.shownName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.now?.let { "${searchTime(it.startUtcMillis)}  ${it.title}" }
                        ?: stringResource(R.string.guide_no_info),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProgrammeResultRow(
    result: ChannelsViewModel.ProgrammeResult,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val programme = result.programme
    SearchResultCard(
        focused = focused,
        onFocusChanged = { focused = it },
        onClick = onClick,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = result.row.primary.logoUrl ?: programme.iconUrl,
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(FullTvChrome.controlShape),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    programme.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        R.string.search_programme_time,
                        result.row.primary.shownName,
                        searchTime(programme.startUtcMillis),
                        searchTime(programme.endUtcMillis),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    focused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val shape = FullTvChrome.controlShape
    FullTvCard(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .fullTvFocusRing(
                focused = focused,
                color = MaterialTheme.colorScheme.primary,
                shape = shape,
            )
            .clickable(role = Role.Button, onClick = onClick),
        containerColor = if (focused) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = shape,
        content = { content() },
    )
}

private val searchTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun searchTime(utcMillis: Long): String = searchTimeFormat.format(Date(utcMillis))
