/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.FullTvCard
import app.opentv.ui.FullTvChrome
import app.opentv.ui.FullTvSectionHeader
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.fullTvFocusRing

/**
 * The provider list: what's connected, with the plumbing to add another or remove one. Removing
 * a source deletes its channels and guide with it — the confirm step is here because that is a
 * lot of data to lose to a stray click on a remote.
 */
@Composable
fun ProvidersScreen(
    onAddSource: () -> Unit,
    onEditSource: (Source) -> Unit,
    onBack: () -> Unit,
    viewModel: SourcesViewModel = viewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    var pendingRemove by remember { mutableStateOf<Source?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 620.dp
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (compact) FullTvChrome.compactPageInset else FullTvChrome.pageInset,
                    vertical = if (compact) 16.dp else 24.dp,
                ),
        ) {
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    FullTvSectionHeader(
                        title = stringResource(R.string.settings_providers_title),
                        subtitle = stringResource(R.string.settings_providers_subtitle),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProviderActionButton(
                            label = stringResource(R.string.providers_add),
                            onClick = onAddSource,
                            primary = true,
                        )
                        ProviderActionButton(
                            label = stringResource(R.string.common_done),
                            onClick = onBack,
                        )
                    }
                }
            } else {
                FullTvSectionHeader(
                    title = stringResource(R.string.settings_providers_title),
                    subtitle = stringResource(R.string.settings_providers_subtitle),
                    trailingContent = {
                        ProviderActionButton(
                            label = stringResource(R.string.providers_add),
                            onClick = onAddSource,
                            primary = true,
                        )
                        ProviderActionButton(
                            label = stringResource(R.string.common_done),
                            onClick = onBack,
                        )
                    },
                )
            }

            Spacer(Modifier.height(FullTvChrome.sectionGap))

            if (ui.sources.isEmpty()) {
                FullTvCard(Modifier.fillMaxWidth().widthIn(max = 900.dp)) {
                    Text(
                        stringResource(R.string.providers_empty),
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(FullTvChrome.cardGap),
                    modifier = Modifier.fillMaxWidth().widthIn(max = 960.dp),
                ) {
                    items(ui.sources, key = { it.id }) { source ->
                        ProviderRow(
                            source = source,
                            confirming = pendingRemove?.id == source.id,
                            onEdit = { onEditSource(source) },
                            onAskRemove = { pendingRemove = source },
                            onCancelRemove = { pendingRemove = null },
                            onConfirmRemove = {
                                viewModel.delete(source)
                                pendingRemove = null
                            },
                            onSetLiveFormat = { viewModel.setLiveFormat(source, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    source: Source,
    confirming: Boolean,
    onEdit: () -> Unit,
    onAskRemove: () -> Unit,
    onCancelRemove: () -> Unit,
    onConfirmRemove: () -> Unit,
    onSetLiveFormat: (LiveStreamFormat) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 620.dp
        FullTvCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = if (confirming) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
            Column(Modifier.fillMaxWidth().padding(if (compact) 16.dp else 20.dp)) {
                if (compact) {
                    ProviderIdentity(source)
                    Spacer(Modifier.height(12.dp))
                    if (confirming) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RemoveConfirmation(
                                onConfirmRemove = onConfirmRemove,
                                onCancelRemove = onCancelRemove,
                                compact = true,
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProviderTextAction(stringResource(R.string.common_edit), onEdit)
                            ProviderTextAction(stringResource(R.string.common_remove), onAskRemove)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderIdentity(source, Modifier.weight(1f))
                        if (confirming) {
                            RemoveConfirmation(
                                onConfirmRemove = onConfirmRemove,
                                onCancelRemove = onCancelRemove,
                                compact = false,
                            )
                        } else {
                            ProviderTextAction(stringResource(R.string.common_edit), onEdit)
                            ProviderTextAction(stringResource(R.string.common_remove), onAskRemove)
                        }
                    }
                }

                // Stream format is an Xtream-panel concept — M3U sources carry their own URLs, so the
                // choice is meaningless there and hidden.
                if (source.kind == SourceKind.XTREAM) {
                    Spacer(Modifier.height(16.dp))
                    StreamFormatSelector(selected = source.liveFormat, onSelect = onSetLiveFormat)
                }
            }
        }
    }
}

@Composable
private fun ProviderIdentity(source: Source, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            source.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(3.dp))
        val kindLabel = when (source.kind) {
            SourceKind.XTREAM -> stringResource(R.string.onboarding_xtream_login)
            SourceKind.M3U -> stringResource(R.string.onboarding_m3u_url)
            SourceKind.YOUTUBE_LIVE_SEARCH -> stringResource(R.string.onboarding_youtube_live)
            SourceKind.STALKER -> stringResource(R.string.onboarding_stalker_portal)
        }
        Text(
            "$kindLabel · ${hostOf(source.url)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RemoveConfirmation(
    onConfirmRemove: () -> Unit,
    onCancelRemove: () -> Unit,
    compact: Boolean,
) {
    if (compact) {
        Text(
            stringResource(R.string.providers_remove_confirm),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderTextAction(stringResource(R.string.providers_yes_remove), onConfirmRemove, danger = true)
            ProviderTextAction(stringResource(R.string.common_cancel), onCancelRemove)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.providers_remove_confirm),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            ProviderTextAction(stringResource(R.string.providers_yes_remove), onConfirmRemove, danger = true)
            ProviderTextAction(stringResource(R.string.common_cancel), onCancelRemove)
        }
    }
}

@Composable
private fun StreamFormatSelector(
    selected: LiveStreamFormat,
    onSelect: (LiveStreamFormat) -> Unit,
) {
    Column {
        Text(
            stringResource(R.string.provider_stream_format),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormatSegment(
                label = stringResource(R.string.provider_stream_format_hls),
                selected = selected == LiveStreamFormat.HLS,
                onClick = { onSelect(LiveStreamFormat.HLS) },
            )
            FormatSegment(
                label = stringResource(R.string.provider_stream_format_ts),
                selected = selected == LiveStreamFormat.MPEG_TS,
                onClick = { onSelect(LiveStreamFormat.MPEG_TS) },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.provider_stream_format_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProviderActionButton(label: String, onClick: () -> Unit, primary: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val modifier = Modifier
        .onFocusChanged { focused = it.isFocused }
        .fullTvFocusRing(focused, MaterialTheme.colorScheme.primary)
    if (primary) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun ProviderTextAction(label: String, onClick: () -> Unit, danger: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .fullTvFocusRing(focused, MaterialTheme.colorScheme.primary),
    ) {
        Text(label, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}

/** One option of the stream-format control: filled when chosen, outlined otherwise. */
@Composable
private fun FormatSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val modifier = Modifier
        .onFocusChanged { focused = it.isFocused }
        .fullTvFocusRing(focused, MaterialTheme.colorScheme.primary)
        .semantics { this.selected = selected }
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

/** Host only — the full URL carries the login and has no place on a settings list. */
private fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
