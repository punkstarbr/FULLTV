/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.shownName
import app.opentv.player.PlayerController
import app.opentv.player.YouTubeChannelUrl
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.FullTvChrome
import app.opentv.ui.FullTvSectionHeader
import app.opentv.ui.fullTvFocusRing
import kotlinx.coroutines.CancellationException

private const val MOSAIC_TILE_COUNT = 4

/**
 * Up to four independent live players. Playback is deliberately opt-in: opening the tab does not
 * open provider connections. The user selects channels and confirms the provider/device warning
 * before any decoder starts.
 */
@kotlin.OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MosaicScreen(
    selectedKeys: List<String>,
    onSelectionChange: (List<String>) -> Unit,
    onPlayChannel: (Channel) -> Unit,
) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val channelsViewModel: ChannelsViewModel = viewModel()
    val allChannels by remember(graph) { graph.catalogRepository.observeChannels() }
        .collectAsState(initial = emptyList())
    val visibleCategories by channelsViewModel.visibleCategoryGroups.collectAsState()
    val visibleCategoryIds = remember(visibleCategories) { visibleCategories.flatMap { it.ids }.toSet() }
    val channelOptions = remember(allChannels, visibleCategoryIds) {
        allChannels
            // The DAO has already removed individually hidden channels. Match the guide's parental
            // category filter as well, so adult categories hidden in settings do not leak here.
            .filter { it.categoryId == null || it.categoryId in visibleCategoryIds }
            .groupBy { it.mosaicKey() }
            .values
            .mapNotNull { variants -> variants.maxByOrNull { it.qualityRank } }
    }
    val channelByKey = remember(channelOptions) { channelOptions.associateBy { it.mosaicKey() } }
    val selectedChannels = remember(selectedKeys, channelByKey) {
        selectedKeys.distinct().take(MOSAIC_TILE_COUNT).mapNotNull { channelByKey[it] }
    }

    var defaultsApplied by remember { mutableStateOf(false) }
    LaunchedEffect(channelOptions) {
        if (!defaultsApplied && channelOptions.isNotEmpty()) {
            defaultsApplied = true
            if (selectedKeys.isEmpty()) {
                val preferred = channelOptions.filter { it.favourite } + channelOptions.filterNot { it.favourite }
                onSelectionChange(preferred.take(MOSAIC_TILE_COUNT).map { it.mosaicKey() })
            }
        }
    }

    var isRunning by remember { mutableStateOf(false) }
    var activeTile by remember { mutableIntStateOf(0) }
    var showPicker by remember { mutableStateOf(false) }
    var pickerQuery by remember { mutableStateOf("") }
    var draftKeys by remember { mutableStateOf(emptyList<String>()) }
    var showStartWarning by remember { mutableStateOf(false) }
    var pendingFullScreenChannel by remember { mutableStateOf<Channel?>(null) }
    val activeRecordings by graph.recordingRepository.observeActive().collectAsState(initial = emptyList())
    val openPicker: () -> Unit = {
        draftKeys = selectedKeys.take(MOSAIC_TILE_COUNT)
        pickerQuery = ""
        showPicker = true
    }

    LaunchedEffect(selectedChannels.size) {
        if (selectedChannels.isEmpty()) {
            activeTile = 0
            isRunning = false
        } else if (activeTile >= selectedChannels.size) {
            activeTile = selectedChannels.lastIndex
        }
    }

    // Stop/release every tile before opening the regular player. The extra frame lets the old
    // PlayerControllers leave composition before the full-screen player opens another connection.
    LaunchedEffect(pendingFullScreenChannel, isRunning) {
        val channel = pendingFullScreenChannel ?: return@LaunchedEffect
        if (!isRunning) {
            withFrameNanos { }
            onPlayChannel(channel)
            pendingFullScreenChannel = null
        }
    }

    if (showPicker) {
        val query = pickerQuery.trim()
        val filteredOptions = remember(channelOptions, query) {
            if (query.isEmpty()) channelOptions
            else channelOptions.filter { it.shownName.contains(query, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.mosaic_picker_title)) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                    Text(
                        stringResource(R.string.mosaic_picker_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pickerQuery,
                        onValueChange = { pickerQuery = it },
                        label = { Text(stringResource(R.string.mosaic_search_channels)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.mosaic_selected_count, draftKeys.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().focusRestorer().focusGroup(),
                    ) {
                        items(filteredOptions, key = { it.id }) { channel ->
                            val key = channel.mosaicKey()
                            val checked = key in draftKeys
                            val canAdd = draftKeys.size < MOSAIC_TILE_COUNT
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        draftKeys = when {
                                            checked -> draftKeys.filterNot { it == key }
                                            canAdd -> draftKeys + key
                                            else -> draftKeys
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    enabled = checked || canAdd,
                                    onCheckedChange = { selected ->
                                        draftKeys = when {
                                            !selected -> draftKeys.filterNot { it == key }
                                            canAdd -> draftKeys + key
                                            else -> draftKeys
                                        }
                                    },
                                )
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text(
                                        channel.shownName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    channel.qualityLabel.takeIf { it.isNotBlank() }?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSelectionChange(draftKeys.take(MOSAIC_TILE_COUNT))
                    showPicker = false
                }) {
                    Text(stringResource(R.string.common_done))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showStartWarning) {
        val warning = buildString {
            append(context.getString(R.string.mosaic_connection_warning, selectedChannels.size))
            if (activeRecordings.isNotEmpty()) {
                append("\n\n")
                append(context.getString(R.string.mosaic_recording_warning, activeRecordings.size))
            }
        }
        AlertDialog(
            onDismissRequest = { showStartWarning = false },
            title = { Text(stringResource(R.string.mosaic_warning_title)) },
            text = { Text(warning) },
            confirmButton = {
                TextButton(onClick = {
                    showStartWarning = false
                    isRunning = true
                }) {
                    Text(stringResource(R.string.mosaic_warning_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showStartWarning = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(FullTvChrome.compactPageInset)) {
        val compactLayout = maxWidth < 640.dp
        Column(
            Modifier.fillMaxSize().focusRestorer().focusGroup(),
            verticalArrangement = Arrangement.spacedBy(FullTvChrome.cardGap),
        ) {
            if (compactLayout) {
                FullTvSectionHeader(
                    title = stringResource(R.string.mosaic_title),
                    subtitle = stringResource(R.string.mosaic_subtitle, selectedChannels.size),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = openPicker, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.mosaic_choose_channels), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (isRunning) {
                        Button(onClick = { isRunning = false }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.mosaic_stop), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        Button(
                            enabled = selectedChannels.isNotEmpty(),
                            onClick = { showStartWarning = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.mosaic_start), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                FullTvSectionHeader(
                    title = stringResource(R.string.mosaic_title),
                    subtitle = stringResource(R.string.mosaic_subtitle, selectedChannels.size),
                    trailingContent = {
                        TextButton(onClick = openPicker) {
                            Text(stringResource(R.string.mosaic_choose_channels))
                        }
                        if (isRunning) {
                            Button(onClick = { isRunning = false }) {
                                Text(stringResource(R.string.mosaic_stop))
                            }
                        } else {
                            Button(
                                enabled = selectedChannels.isNotEmpty(),
                                onClick = { showStartWarning = true },
                            ) {
                                Text(stringResource(R.string.mosaic_start))
                            }
                        }
                    },
                )
            }

            if (channelOptions.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.mosaic_no_channels),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (compactLayout) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().focusRestorer().focusGroup(),
                    contentPadding = PaddingValues(bottom = FullTvChrome.compactPageInset),
                    verticalArrangement = Arrangement.spacedBy(FullTvChrome.cardGap),
                ) {
                    items((0 until MOSAIC_TILE_COUNT).toList(), key = { it }) { index ->
                        MosaicTile(
                            channel = selectedChannels.getOrNull(index),
                            isRunning = isRunning,
                            isActive = selectedChannels.getOrNull(index) != null && index == activeTile,
                            onActivate = { activeTile = index },
                            onChooseChannels = openPicker,
                            onChangeChannels = openPicker,
                            onPlayFullScreen = { selected ->
                                isRunning = false
                                pendingFullScreenChannel = selected
                            },
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        )
                    }
                }
            } else {
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FullTvChrome.cardGap),
                ) {
                    repeat(2) { row ->
                        Row(
                            Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(FullTvChrome.cardGap),
                        ) {
                            repeat(2) { column ->
                                val index = row * 2 + column
                                val channel = selectedChannels.getOrNull(index)
                                MosaicTile(
                                    channel = channel,
                                    isRunning = isRunning,
                                    isActive = channel != null && index == activeTile,
                                    onActivate = { activeTile = index },
                                    onChooseChannels = openPicker,
                                    onChangeChannels = openPicker,
                                    onPlayFullScreen = { selected ->
                                        isRunning = false
                                        pendingFullScreenChannel = selected
                                    },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MosaicTile(
    channel: Channel?,
    isRunning: Boolean,
    isActive: Boolean,
    onActivate: () -> Unit,
    onChooseChannels: () -> Unit,
    onChangeChannels: () -> Unit,
    onPlayFullScreen: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val tileDescription = if (channel == null) {
        stringResource(R.string.mosaic_empty_slot)
    } else {
        channel.shownName
    }
    val audioActiveDescription = stringResource(R.string.mosaic_audio_active)
    val borderColor = when {
        focused -> MaterialTheme.colorScheme.primary
        isActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val tileModifier = modifier
        .clip(FullTvChrome.cardShape)
        .border(
            BorderStroke(if (isActive) 2.dp else 1.dp, borderColor),
            FullTvChrome.cardShape,
        )
        .background(if (channel == null) MaterialTheme.colorScheme.surfaceVariant else Color.Black)
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused && channel != null) onActivate()
        }
        .semantics {
            contentDescription = tileDescription
            if (isActive && isRunning) stateDescription = audioActiveDescription
        }
        .fullTvFocusRing(
            focused = focused,
            color = MaterialTheme.colorScheme.primary,
            shape = FullTvChrome.cardShape,
            width = 3.dp,
        )
        .clickable(role = Role.Button) {
            if (channel == null) onChooseChannels() else onActivate()
        }
        .focusable()

    Box(tileModifier, contentAlignment = Alignment.Center) {
        if (channel == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.GridView,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(34.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.mosaic_empty_slot),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            if (isRunning) {
                MosaicVideo(channel = channel, audioEnabled = isActive, modifier = Modifier.fillMaxSize())
            } else {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(0.45f),
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        channel.shownName,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (isActive && isRunning) {
                        Text(
                            stringResource(R.string.mosaic_audio_active),
                            color = Color.White.copy(alpha = 0.75f),
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                MosaicTileAction(
                    image = Icons.Filled.Edit,
                    description = stringResource(R.string.mosaic_change_channel),
                    onClick = onChangeChannels,
                )
                MosaicTileAction(
                    image = Icons.Filled.Fullscreen,
                    description = stringResource(R.string.mosaic_fullscreen),
                    onClick = { onPlayFullScreen(channel) },
                )
            }
        }
    }
}

@Composable
private fun MosaicTileAction(
    image: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .clip(FullTvChrome.controlShape)
            .onFocusChanged { focused = it.isFocused }
            .fullTvFocusRing(focused, Color.White, FullTvChrome.controlShape),
    ) {
        Icon(image, contentDescription = description, tint = Color.White)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MosaicVideo(
    channel: Channel,
    audioEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val scope = rememberCoroutineScope()
    val controller = remember(channel.id) {
        PlayerController(
            context = context,
            scope = scope,
            httpClient = graph.streamingHttpClient,
            subtitlesEnabled = false,
            preview = true,
        )
    }
    val playerState by controller.state.collectAsState()

    DisposableEffect(controller) {
        onDispose { controller.release() }
    }

    LaunchedEffect(audioEnabled) {
        controller.player.volume = if (audioEnabled) 1f else 0f
    }

    LaunchedEffect(channel.id) {
        try {
            val source = graph.sourceRepository.byId(channel.sourceId)
            val youtubeLiveUrl = YouTubeChannelUrl.asLiveUrlOrNull(channel.streamUrl)
            if (youtubeLiveUrl != null) {
                controller.beginExternalResolution(channel.shownName)
                val resolved = graph.streamlinkResolver.resolveBest(youtubeLiveUrl)
                controller.play(
                    PlayerController.Request(
                        url = resolved.url,
                        title = channel.shownName,
                        userAgent = resolved.userAgent,
                        isLive = true,
                    ),
                    debounce = false,
                )
            } else {
                val url = graph.catalogRepository.resolvePlaybackUrl(channel, source)
                controller.play(
                    PlayerController.Request(
                        url = url,
                        title = channel.shownName,
                        userAgent = source?.userAgent ?: "OpenTV/0.1 (Android)",
                        isLive = true,
                    ),
                    debounce = false,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            controller.failExternalResolution(
                channel.shownName,
                context.getString(R.string.mosaic_playback_error),
            )
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    player = controller.player
                }
            },
            update = { it.player = controller.player },
        )
        when (val state = playerState) {
            is PlayerController.State.Buffering -> CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
            is PlayerController.State.Error -> Text(
                state.message.ifBlank { context.getString(R.string.mosaic_playback_error) },
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                    .padding(8.dp),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
            else -> Unit
        }
    }
}

private fun Channel.mosaicKey(): String = groupKey.takeIf { it.isNotBlank() } ?: "channel:$id"
