/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Intent
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.window.Dialog
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.ui.InterfacePalette
import app.opentv.ui.interfacePalette
import app.opentv.core.findActivity
import app.opentv.core.requestIgnoreBatteryOptimizations
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.Reminder
import app.opentv.data.model.shownName
import app.opentv.reminders.ReminderScheduler
import app.opentv.player.PlaybackQueue
import app.opentv.player.PlayerController
import app.opentv.player.YouTubeChannelUrl
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.RecordingBackgroundDialog
import app.opentv.ui.RecordingBackgroundPrompt
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Live TV: a category rail on the left, the channel list on the right.
 *
 * The rail is vertical because provider category lists run to dozens of entries and a
 * horizontal chip row hides all but the first few — on a d-pad, anything you cannot see
 * you cannot reach. Favourites and All are pinned at the top.
 *
 * The list shows what is on *now* and *next* against every channel. That doubles as a
 * permanent, glanceable health indicator for the guide: when the EPG stops updating, these
 * lines say so honestly instead of going quietly stale.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    isTelevision: Boolean,
    hasSources: Boolean,
    isSyncing: Boolean,
    onPlayChannel: (Channel) -> Unit,
    onPlayRecording: (app.opentv.data.model.Recording) -> Unit = {},
    onAddSource: () -> Unit,
    onRefresh: () -> Unit,
    onPlayCatchup: (mediaKey: String, url: String, title: String, ua: String) -> Unit = { _, _, _, _ -> },
    pendingProgrammeAction: Pair<ChannelsViewModel.Row, Programme>? = null,
    onProgrammeActionConsumed: () -> Unit = {},
    viewModel: ChannelsViewModel = viewModel(),
    interfaceStyle: AppSettings.InterfaceStyle = AppSettings.InterfaceStyle.CURRENT,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val settings = remember { graph.settings }
    val previewEnabled by settings.guidePreviewVideo.collectAsState()
    val previewEnabledForLayout = previewEnabled && interfaceStyle !in setOf(
        AppSettings.InterfaceStyle.CLASSIC_EPG,
        AppSettings.InterfaceStyle.EPG_FULLSCREEN,
    )
    val palette = interfacePalette(interfaceStyle, MaterialTheme.colorScheme)
    val channelLayout by settings.channelLayout.collectAsState()
    val guideDensity by settings.guideDensity.collectAsState()

    val categories by viewModel.visibleCategoryGroups.collectAsState()
    val rows by viewModel.rows.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val favouritesOnly by viewModel.favouritesOnly.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val selectedSource by viewModel.selectedSource.collectAsState()
    val selectedCategoryIndex = categories.indexOfFirst { it.key == selectedCategory }
        .takeIf { it >= 0 }
    val selectedRailIndex = remember(sources.size, categories.size, selectedCategoryIndex, favouritesOnly) {
        guideRailSelectedItemIndex(
            sourceCount = sources.size,
            categoryCount = categories.size,
            selectedCategoryIndex = selectedCategoryIndex,
            favouritesOnly = favouritesOnly,
        )
    }
    val windowStart by viewModel.windowStartMillis.collectAsState()
    // How many days ahead the guide is scrolled (0 = live now). Drives the ‹ Today › nav.
    val guideDayOffset by viewModel.guideDayOffset.collectAsState()
    // Tri-state: null = still checking the catalogue, true = channels on disk, false = confirmed
    // empty. Drives the choice between the loading spinner and a recoverable error below.
    val channelsPresent by viewModel.channelsPresent.collectAsState()

    // D-pad focus highlights a channel and updates the preview; OK opens that channel/programme's
    // action menu. The focus highlight is intentionally not a separate committed selection.
    var highlightedRow by remember { mutableStateOf<ChannelsViewModel.Row?>(null) }
    val previewSound by settings.guidePreviewSound.collectAsState()
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Recording from the guide: what's capturing now, and a scope to kick a capture off.
    val activeRecordings by graph.recordingRepository.observeActive().collectAsState(initial = emptyList())
    val recordScope = rememberCoroutineScope()
    // The programme the user pressed OK on in the grid — drives the per-programme record menu.
    var recordTarget by remember { mutableStateOf<Pair<ChannelsViewModel.Row, Programme>?>(null) }
    // The channel whose OK menu (Watch / Record / Schedule) is open.
    var channelMenu by remember { mutableStateOf<ChannelsViewModel.Row?>(null) }
    var externalChannelToConfirm by remember { mutableStateOf<Channel?>(null) }

    LaunchedEffect(pendingProgrammeAction) {
        val pending = pendingProgrammeAction ?: return@LaunchedEffect
        highlightedRow = pending.first
        recordTarget = pending
        onProgrammeActionConsumed()
    }

    fun openExternalChannel(channel: Channel) {
        recordScope.launch {
            val uri = android.net.Uri.parse(channel.streamUrl)
            if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) {
                Toast.makeText(context, context.getString(R.string.guide_no_external_player), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val ua = graph.sourceRepository.byId(channel.sourceId)?.userAgent ?: "OpenTV/0.1 (Android)"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                putExtra("title", channel.shownName)
                // External players can see both this URL and the User-Agent header.
                putExtra("headers", arrayOf("User-Agent", ua))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(
                    Intent.createChooser(intent, context.getString(R.string.guide_play_channel_with))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                Toast.makeText(context, context.getString(R.string.guide_no_external_player), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // First time the user records or schedules while OpenTV isn't exempt from battery optimisation,
    // offer the exemption so the capture survives standby. Once per session; never blocks recording.
    var showBackgroundPrompt by remember { mutableStateOf(false) }
    fun promptBackgroundIfNeeded() {
        if (RecordingBackgroundPrompt.shouldShow(context)) {
            RecordingBackgroundPrompt.markShown()
            showBackgroundPrompt = true
        }
    }

    // ---- Category rail collapse (Change 2) ---------------------------------------------------
    // The rail is full-width while focus is on it, then slides shut once focus moves into the
    // guide (onFocusRow) so the grid gets the whole width. Pressing d-pad LEFT from the guide's
    // leftmost (channel) column slides it back and drops focus on the selected category.
    var railExpanded by remember(interfaceStyle) {
        mutableStateOf(interfaceStyle != AppSettings.InterfaceStyle.EPG_FULLSCREEN)
    }
    val railWidth by animateDpAsState(
        targetValue = if (railExpanded) 240.dp else 0.dp,
        label = "railWidth",
    )
    val railFocusRequester = remember { FocusRequester() }
    val categoryRailListState = rememberLazyListState()
    // Set when LEFT reopens the rail. Wait for its width animation, scroll the selected entry into
    // the LazyColumn viewport, then request focus after that item has been laid out.
    var pendingRailFocus by remember { mutableStateOf(false) }
    LaunchedEffect(pendingRailFocus, railExpanded, selectedRailIndex) {
        if (!pendingRailFocus || !railExpanded) return@LaunchedEffect

        // Do not guess how long the animation takes: a slow TV box can lay out later than 50 ms.
        snapshotFlow { railWidth }.first { it >= 239.dp }
        categoryRailListState.animateScrollToItem(selectedRailIndex)
        snapshotFlow {
            categoryRailListState.layoutInfo.visibleItemsInfo.any { it.index == selectedRailIndex }
        }.first { it }
        withFrameNanos { }

        // Another RIGHT can move focus back into the guide while scrolling; in that case the keyed
        // effect is cancelled and must not steal focus back from the newly focused channel.
        if (pendingRailFocus && railExpanded) {
            runCatching { railFocusRequester.requestFocus() }
            pendingRailFocus = false
        }
    }

    // Re-evaluate "now" once a minute so progress bars advance without leaving the screen.
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            viewModel.tick()
            delay(60_000)
        }
    }

    // Keep the highlighted row valid as the list changes (e.g. switching category): stay on the
    // same channel if present, otherwise fall back to the top of the new list.
    LaunchedEffect(rows) {
        highlightedRow = rows.firstOrNull { it.key == highlightedRow?.key } ?: rows.firstOrNull()
    }

    // ---- Live preview player -----------------------------------------------------------------
    // One muted player, reused. It only ever decodes while the guide is the foreground screen,
    // and is stopped before any hand-off to full-screen, so the box never runs two decoders at
    // once — the thing that used to lock up cheap sticks.
    val previewScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val previewController = remember {
        PlayerController(context, previewScope, graph.httpClient, subtitlesEnabled = false, preview = true)
            .also { it.player.volume = 0f }
    }
    DisposableEffect(Unit) {
        onDispose {
            previewController.release()
            previewScope.cancel()
        }
    }

    // Watching a live channel while a recording runs opens a second stream on the same line — which
    // cuts the recording and can get a single-connection account banned. So every jump to full-screen
    // live is funnelled through [requestLive]: with a recording active it asks first.
    var pendingLiveChannel by remember { mutableStateOf<Channel?>(null) }
    fun startLive(channel: Channel) {
        PlaybackQueue.publish(channel.id, rows.map {
            PlaybackQueue.Item(it.primary.id, it.primary.shownName, it.primary.logoUrl, it.primary.number)
        })
        previewController.stop()
        onPlayChannel(channel)
    }
    fun requestLive(channel: Channel) {
        if (activeRecordings.isNotEmpty()) pendingLiveChannel = channel else startLive(channel)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var screenResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> screenResumed = true
                Lifecycle.Event.ON_PAUSE -> {
                    screenResumed = false
                    previewController.stop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Hold the screen awake while the guide's live preview is playing — otherwise the box's
    // screensaver fires while you're browsing with a channel running in the preview pane.
    DisposableEffect(previewEnabledForLayout, screenResumed) {
        val window = context.findActivity()?.window
        if (previewEnabledForLayout && screenResumed) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Preview audio follows the setting; muted by default so browsing stays quiet.
    LaunchedEffect(previewSound, previewEnabledForLayout) {
        previewController.player.volume = if (previewEnabledForLayout && previewSound) 1f else 0f
    }

    // Tune the preview to the highlighted channel, so it follows the d-pad as you browse — the
    // standard TV-guide behaviour. Debounced, so holding a direction doesn't re-tune every step.
    // While a recording is running the preview is silenced entirely: on a single-connection line a
    // muted preview is still a second stream, which fights the recording and risks a provider ban.
    val recordingActive = activeRecordings.isNotEmpty()
    LaunchedEffect(highlightedRow?.key, previewEnabledForLayout, screenResumed, recordingActive) {
        val row = highlightedRow
        if (!previewEnabledForLayout || !screenResumed || recordingActive || row == null) {
            previewController.stop()
            return@LaunchedEffect
        }
        val channel = row.primary
        val youtubeLiveUrl = YouTubeChannelUrl.asLiveUrlOrNull(channel.streamUrl)
        if (youtubeLiveUrl != null) {
            previewController.beginExternalResolution(channel.shownName)
            try {
                val resolved = graph.streamlinkResolver.resolveBest(youtubeLiveUrl)
                previewController.play(
                    PlayerController.Request(
                        url = resolved.url,
                        title = channel.shownName,
                        userAgent = resolved.userAgent,
                        isLive = true,
                    ),
                    debounce = true,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                previewController.stop()
            }
        } else {
            val source = graph.sourceRepository.byId(channel.sourceId)
            previewController.play(
                PlayerController.Request(
                    url = channel.streamUrl,
                    title = channel.shownName,
                    userAgent = source?.userAgent ?: "OpenTV/0.1 (Android)",
                    isLive = true,
                ),
                debounce = true,
            )
        }
    }

    Row(modifier.fillMaxSize().background(palette.screenBackground)) {

        // ---- Category rail -----------------------------------------------------------------
        // Width animates to 0 while focus is in the guide (see onFocusRow) so the grid gets the
        // whole screen; d-pad LEFT from the guide's channel column slides it back (onExitLeft…).
        Column(
            Modifier
                .width(railWidth)
                .fillMaxHeight()
                .background(palette.surface)
                .clipToBounds()
                .focusGroup()
                .padding(vertical = 16.dp),
        ) {
            Text(
                stringResource(R.string.nav_live_tv),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
            // The top "Search channels" bar was removed — Search now lives in the global nav rail.

            LazyColumn(
                state = categoryRailListState,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Provider switch — only when there's more than one source. Lets the user keep
                // several playlists and flip between them (cardiodoc's request); "All" folds them.
                if (sources.size > 1) {
                    item(key = "provider-header") {
                        Text(
                            stringResource(R.string.channels_manager_source_header),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    item(key = "provider-all") {
                        RailEntry(
                            label = stringResource(R.string.channels_manager_all_sources),
                            selected = selectedSource == null,
                            onClick = { viewModel.selectSource(null) },
                            palette = palette,
                        )
                    }
                    items(sources, key = { "src-${it.id}" }) { source ->
                        RailEntry(
                            label = source.name,
                            selected = selectedSource == source.id,
                            onClick = { viewModel.selectSource(source.id) },
                            palette = palette,
                        )
                    }
                    item(key = "provider-divider") { Spacer(Modifier.height(10.dp)) }
                }
                // The currently-selected entry carries the rail's FocusRequester, so reopening the
                // rail (d-pad LEFT in the guide) lands focus straight back on the current category.
                item {
                    RailEntry(
                        label = stringResource(R.string.guide_favourites),
                        selected = favouritesOnly,
                        onClick = viewModel::selectFavourites,
                        palette = palette,
                        modifier = if (favouritesOnly) Modifier.focusRequester(railFocusRequester) else Modifier,
                    )
                }
                item {
                    val allSelected = !favouritesOnly &&
                        (selectedCategory == null || selectedCategoryIndex == null)
                    RailEntry(
                        label = stringResource(R.string.guide_all_channels),
                        selected = allSelected,
                        onClick = { viewModel.selectCategory(null) },
                        palette = palette,
                        modifier = if (allSelected) Modifier.focusRequester(railFocusRequester) else Modifier,
                    )
                }
                items(categories, key = { it.key }) { group ->
                    val groupSelected = !favouritesOnly && selectedCategory == group.key
                    RailEntry(
                        label = group.label,
                        selected = groupSelected,
                        onClick = { viewModel.selectCategory(group.key) },
                        palette = palette,
                        modifier = if (groupSelected) Modifier.focusRequester(railFocusRequester) else Modifier,
                    )
                }
            }
        }

        // ---- Preview + guide ---------------------------------------------------------------
        Column(Modifier.weight(1f).background(palette.screenBackground)) {
            if (rows.isEmpty()) {
                when {
                    favouritesOnly -> NoFavouritesState()
                    // Work genuinely in progress: a sync is running, the catalogue check hasn't
                    // returned yet, or channels ARE on disk and the guide is still building. A
                    // large provider takes a while, and showing "No channels" during it reads as
                    // failure — which is how someone concludes an app is broken thirty seconds
                    // after installing it.
                    isSyncing || channelsPresent == null || channelsPresent == true -> LoadingState(isSyncing)
                    // Nothing is syncing and the catalogue is confirmed empty. With a provider
                    // configured, the last load failed or returned nothing — surface a clear error
                    // with Retry and a way back to setup instead of spinning forever.
                    hasSources -> ChannelsErrorState(onRetry = onRefresh, onEditProvider = onAddSource)
                    else -> EmptyState(onAddSource)
                }
            } else {
                // Hand the player the list you're browsing so it can zap channel up/down.
                fun goFullscreen(channel: Channel) = requestLive(channel)

                // Record the highlighted channel's now-programme (bounded to its end), or stop it
                // if it's already recording. Powers the preview pane's quick record dot.
                fun recordSelected() {
                    val row = highlightedRow ?: return
                    val active = activeRecordings.firstOrNull { it.channelId == row.primary.id }
                    if (active != null) {
                        graph.recordingEngine.stop(active.id)
                        Toast.makeText(context, context.getString(R.string.rec_recording_stopped), Toast.LENGTH_SHORT).show()
                    } else {
                        recordScope.launch { graph.recordingEngine.startChannel(row.primary, row.now) }
                        Toast.makeText(context, context.getString(R.string.rec_recording_started_see_tab, row.primary.shownName), Toast.LENGTH_LONG).show()
                        promptBackgroundIfNeeded()
                    }
                }

                val dayLabel = when (guideDayOffset) {
                    0 -> stringResource(R.string.guide_today)
                    1 -> stringResource(R.string.guide_tomorrow)
                    else -> remember(windowStart) {
                        SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(windowStart))
                    }
                }
                if (interfaceStyle !in setOf(
                        AppSettings.InterfaceStyle.CLASSIC_EPG,
                        AppSettings.InterfaceStyle.EPG_FULLSCREEN,
                    )
                ) {
                    GuidePreview(
                        row = highlightedRow,
                        nowMillis = nowMillis,
                        onWatch = { highlightedRow?.let { goFullscreen(it.primary) } },
                        onRefresh = onRefresh,
                        onAddSource = onAddSource,
                        previewPlayer = if (previewEnabledForLayout && !recordingActive) previewController.player else null,
                        isRecording = highlightedRow?.primary?.id?.let { id ->
                            activeRecordings.any { it.channelId == id }
                        } == true,
                        onRecord = { recordSelected() },
                        dayLabel = dayLabel,
                        canGoPrevDay = guideDayOffset > 0,
                        onPrevDay = { viewModel.nudgeGuideDay(-1) },
                        onNextDay = { viewModel.nudgeGuideDay(1) },
                        interfaceStyle = interfaceStyle,
                    )
                }
                // Shared by both layouts: focus follows the highlight and collapses the rail; LEFT
                // from the leftmost element reopens the rail (consumed only when it was hidden).
                val onFocusChannel: (ChannelsViewModel.Row) -> Unit = {
                    highlightedRow = it
                    pendingRailFocus = false
                    railExpanded = false
                }
                val onExitLeftChannel: () -> Boolean = {
                    if (!railExpanded) {
                        railExpanded = true
                        pendingRailFocus = true
                        true
                    } else {
                        false
                    }
                }
                val useList = channelLayout == AppSettings.ChannelLayout.LIST &&
                    (interfaceStyle == AppSettings.InterfaceStyle.CURRENT ||
                        interfaceStyle == AppSettings.InterfaceStyle.PLAYER_ZAPPING)
                if (useList) {
                    ChannelList(
                        rows = rows,
                        highlightedKey = highlightedRow?.key,
                        onSelectRow = { row -> channelMenu = row },
                        onFocusRow = onFocusChannel,
                        onToggleFavourite = { viewModel.toggleFavourite(it) },
                        onExitLeftFromChannel = onExitLeftChannel,
                        interfaceStyle = interfaceStyle,
                        modifier = Modifier.weight(1f).padding(start = 12.dp, end = 12.dp),
                    )
                } else {
                    GuideGrid(
                        rows = rows,
                        windowStartMillis = windowStart,
                        dayOffset = guideDayOffset,
                        nowMillis = nowMillis,
                        density = guideDensity,
                        highlightedKey = highlightedRow?.key,
                        // OK on a channel opens its menu: Watch, Record now, Schedule a later show,
                        // Record series. The preview already follows the highlight as you browse.
                        onSelectRow = { row -> channelMenu = row },
                        onFocusRow = onFocusChannel,
                        onProgramme = { row, programme -> recordTarget = row to programme },
                        onToggleFavourite = { viewModel.toggleFavourite(it) },
                        onExitLeftFromChannel = onExitLeftChannel,
                        interfaceStyle = interfaceStyle,
                        modifier = Modifier.weight(1f).padding(start = 12.dp, end = 12.dp),
                    )
                }
                if (interfaceStyle == AppSettings.InterfaceStyle.CLASSIC_EPG) {
                    GuidePreview(
                        row = highlightedRow,
                        nowMillis = nowMillis,
                        onWatch = { highlightedRow?.let { goFullscreen(it.primary) } },
                        onRefresh = onRefresh,
                        onAddSource = onAddSource,
                        previewPlayer = null,
                        isRecording = highlightedRow?.primary?.id?.let { id ->
                            activeRecordings.any { it.channelId == id }
                        } == true,
                        onRecord = { recordSelected() },
                        dayLabel = dayLabel,
                        canGoPrevDay = guideDayOffset > 0,
                        onPrevDay = { viewModel.nudgeGuideDay(-1) },
                        onNextDay = { viewModel.nudgeGuideDay(1) },
                        interfaceStyle = interfaceStyle,
                    )
                }
            }
        }
    }

    // The record menu for a programme picked in the grid.
    recordTarget?.let { (targetRow, programme) ->
        val channel = targetRow.primary
        // Which quality actually gets recorded. Defaults to the best variant, but the user can pick
        // (Sky Q's "record HD or SD"). Each variant channel carries its own stream URL, so the pick
        // flows straight through to the capture. Only surfaced when the channel has more than one.
        var chosenVariant by remember(targetRow.primary.id) { mutableStateOf(targetRow.primary) }
        // Which provider records it, when this channel exists on more than one. Recording from a
        // second provider's account is what lets you keep watching on the first — the only real way
        // around a single-connection provider (something even TiviMate can't do).
        var sourceOptions by remember(channel.id) { mutableStateOf<List<Channel>>(emptyList()) }
        LaunchedEffect(channel.id) { sourceOptions = graph.catalogRepository.recordSourceOptions(channel) }
        val multiSource = sourceOptions.size > 1
        LaunchedEffect(sourceOptions) {
            if (sourceOptions.size > 1) {
                chosenVariant = sourceOptions.firstOrNull { it.sourceId == channel.sourceId } ?: sourceOptions.first()
            }
        }
        val liveNow = nowMillis in programme.startUtcMillis until programme.endUtcMillis
        val isPast = programme.endUtcMillis <= nowMillis
        val recordingThis = activeRecordings.firstOrNull { it.channelId == channel.id }
        val firstActionFocusRequester = remember(channel.id, programme.startUtcMillis) { FocusRequester() }
        val firstActionKey = when {
            liveNow -> "live"
            isPast && channel.tvArchive -> "catchup"
            !isPast -> "schedule"
            else -> "series"
        }
        LaunchedEffect(channel.id, programme.startUtcMillis) {
            withFrameNanos { }
            runCatching { firstActionFocusRequester.requestFocus() }
        }
        Dialog(onDismissRequest = { recordTarget = null }) {
            Column(
                Modifier
                    .width(440.dp)
                    .focusRestorer()
                    .focusGroup()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
            ) {
                Text(
                    programme.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatTime(programme.startUtcMillis)}–${formatTime(programme.endUtcMillis)}   ${channel.shownName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                if (multiSource) {
                    // Record from which provider — keeps the other account free to watch on.
                    Text(
                        stringResource(R.string.rec_record_from),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sourceOptions.forEach { opt ->
                            QualityChip(
                                label = sources.firstOrNull { it.id == opt.sourceId }?.name
                                    ?: stringResource(R.string.rec_source_unknown),
                                selected = opt.id == chosenVariant.id,
                                onClick = { chosenVariant = opt },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                } else if (targetRow.variants.size > 1) {
                    Text(
                        stringResource(R.string.rec_quality_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        targetRow.variants.forEach { variant ->
                            QualityChip(
                                label = variant.qualityLabel.ifBlank { stringResource(R.string.rec_quality_sd) },
                                selected = variant.id == chosenVariant.id,
                                onClick = { chosenVariant = variant },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                when {
                    liveNow && recordingThis != null -> RecordActionRow(
                        stringResource(R.string.rec_stop_recording),
                        primary = true,
                        focusRequester = firstActionFocusRequester,
                    ) {
                        graph.recordingEngine.stop(recordingThis.id)
                        Toast.makeText(context, context.getString(R.string.rec_recording_stopped), Toast.LENGTH_SHORT).show()
                        recordTarget = null
                    }
                    liveNow -> RecordActionRow(
                        stringResource(R.string.rec_record_now),
                        primary = true,
                        focusRequester = firstActionFocusRequester,
                    ) {
                        recordScope.launch { graph.recordingEngine.startChannel(chosenVariant, programme) }
                        Toast.makeText(context, context.getString(R.string.rec_recording_channel, channel.shownName), Toast.LENGTH_LONG).show()
                        promptBackgroundIfNeeded()
                        recordTarget = null
                    }
                    // A finished programme on a catch-up channel plays back from the archive.
                    isPast && channel.tvArchive -> RecordActionRow(
                        stringResource(R.string.guide_watch_from_start),
                        primary = true,
                        focusRequester = firstActionFocusRequester,
                    ) {
                        recordScope.launch {
                            val source = graph.sourceRepository.byId(channel.sourceId)
                            if (source == null) {
                                Toast.makeText(context, context.getString(R.string.guide_catchup_link_failed), Toast.LENGTH_SHORT).show()
                            } else {
                                val mins = (programme.durationMillis / 60000).toInt().coerceAtLeast(1)
                                val url = graph.xtreamApi.catchupUrl(source, channel.streamId, programme.startUtcMillis, mins)
                                onPlayCatchup(
                                    "catchup:${channel.id}:${programme.startUtcMillis}",
                                    url,
                                    "${channel.shownName} — ${programme.title}",
                                    source.userAgent,
                                )
                            }
                        }
                        recordTarget = null
                    }
                    !isPast -> RecordActionRow(
                        stringResource(R.string.rec_schedule_recording),
                        primary = true,
                        focusRequester = firstActionFocusRequester,
                    ) {
                        recordScope.launch { graph.recordingEngine.scheduleProgramme(chosenVariant, programme) }
                        Toast.makeText(context, context.getString(R.string.rec_scheduled_title, programme.title), Toast.LENGTH_LONG).show()
                        promptBackgroundIfNeeded()
                        recordTarget = null
                    }
                }

                // Reminders — only for something that hasn't started yet.
                if (!isPast) {
                    var reminderSet by remember(channel.id, programme.startUtcMillis) {
                        mutableStateOf<Boolean?>(null)
                    }
                    LaunchedEffect(channel.id, programme.startUtcMillis) {
                        reminderSet =
                            graph.reminderRepository.forProgramme(channel.id, programme.startUtcMillis) != null
                    }
                    if (reminderSet == true) {
                        RecordActionRow(stringResource(R.string.guide_cancel_reminder)) {
                            recordScope.launch {
                                graph.reminderRepository.forProgramme(channel.id, programme.startUtcMillis)?.let {
                                    ReminderScheduler.cancel(context, it.id)
                                    graph.reminderRepository.delete(it.id)
                                }
                            }
                            Toast.makeText(context, context.getString(R.string.guide_reminder_removed), Toast.LENGTH_SHORT).show()
                            recordTarget = null
                        }
                    } else {
                        RecordActionRow(stringResource(R.string.guide_remind_me)) {
                            recordScope.launch { setReminder(graph, context, channel, programme, autoTune = false) }
                            Toast.makeText(context, context.getString(R.string.guide_reminder_set, programme.title), Toast.LENGTH_LONG).show()
                            recordTarget = null
                        }
                        RecordActionRow(stringResource(R.string.guide_auto_switch)) {
                            recordScope.launch { setReminder(graph, context, channel, programme, autoTune = true) }
                            Toast.makeText(context, context.getString(R.string.guide_will_switch, channel.shownName), Toast.LENGTH_LONG).show()
                            recordTarget = null
                        }
                    }
                }
                RecordActionRow(
                    stringResource(R.string.rec_record_series),
                    focusRequester = firstActionFocusRequester.takeIf { firstActionKey == "series" },
                ) {
                    recordScope.launch {
                        graph.recordingEngine.recordSeries(channel, programme, targetRow.programmes)
                    }
                    Toast.makeText(context, context.getString(R.string.rec_series_recording_set, programme.title), Toast.LENGTH_LONG).show()
                    promptBackgroundIfNeeded()
                    recordTarget = null
                }
                RecordActionRow(stringResource(R.string.guide_watch_channel)) {
                    recordTarget = null
                    requestLive(channel)
                }
                RecordActionRow(stringResource(R.string.common_cancel)) { recordTarget = null }
            }
        }
    }

    // Single-connection guard: confirm before opening a live stream that would fight a recording.
    pendingLiveChannel?.let { liveChannel ->
        val recTitle = activeRecordings.firstOrNull()?.title.orEmpty()
        Dialog(onDismissRequest = { pendingLiveChannel = null }) {
            Column(
                Modifier
                    .width(480.dp)
                    .focusRestorer()
                    .focusGroup()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp),
            ) {
                Text(
                    stringResource(R.string.rec_live_warn_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.rec_live_warn_body, recTitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                RecordActionRow(stringResource(R.string.rec_live_warn_watch)) {
                    pendingLiveChannel = null
                    activeRecordings.forEach { graph.recordingEngine.stop(it.id) }
                    startLive(liveChannel)
                }
                RecordActionRow(stringResource(R.string.rec_live_warn_keep)) { pendingLiveChannel = null }
            }
        }
    }

    // The channel menu — opened by pressing OK on a channel. Watch, record what's on now, schedule
    // a later programme, or record the whole series. A plain vertical list, so it's reliable on any
    // remote — no fiddly timeline navigation needed.
    channelMenu?.let { menuRow ->
        val channel = menuRow.primary
        val nowProg = menuRow.now
        val recordingThis = activeRecordings.firstOrNull { it.channelId == channel.id }
        val upcoming = menuRow.programmes.filter { it.startUtcMillis > nowMillis }.take(8)
        val firstMenuActionRequester = remember(channel.id) { FocusRequester() }
        LaunchedEffect(channel.id) {
            withFrameNanos { }
            runCatching { firstMenuActionRequester.requestFocus() }
        }
        Dialog(onDismissRequest = { channelMenu = null }) {
            Column(
                Modifier
                    .width(480.dp)
                    .focusRestorer()
                    .focusGroup()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp),
            ) {
                Text(
                    channel.shownName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                nowProg?.let {
                    Text(
                        stringResource(R.string.guide_now_title, it.title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (recordingThis != null) {
                    Text(
                        stringResource(R.string.guide_recording_active),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))

                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    RecordActionRow(
                        stringResource(
                            if (recordingThis != null) R.string.guide_watch_recording
                            else R.string.guide_watch,
                        ),
                        focusRequester = firstMenuActionRequester,
                    ) {
                        channelMenu = null
                        if (recordingThis != null) {
                            // Reuse the bytes already being captured. Opening the provider again
                            // would consume a second connection and can stop the recording.
                            onPlayRecording(recordingThis)
                        } else {
                            requestLive(channel)
                        }
                    }
                    RecordActionRow(stringResource(R.string.guide_open_external)) {
                        channelMenu = null
                        externalChannelToConfirm = channel
                    }
                    if (recordingThis != null) {
                        RecordActionRow(stringResource(R.string.rec_stop_recording), primary = true) {
                            graph.recordingEngine.stop(recordingThis.id)
                            Toast.makeText(context, context.getString(R.string.rec_recording_stopped), Toast.LENGTH_SHORT).show()
                            channelMenu = null
                        }
                    } else {
                        RecordActionRow(stringResource(R.string.guide_record_now_playing), primary = true) {
                            recordScope.launch { graph.recordingEngine.startChannel(channel, nowProg) }
                            Toast.makeText(context, context.getString(R.string.rec_recording_started_see_tab, channel.shownName), Toast.LENGTH_LONG).show()
                            promptBackgroundIfNeeded()
                            channelMenu = null
                        }
                    }
                    if (nowProg != null) {
                        RecordActionRow(stringResource(R.string.rec_record_series_named, nowProg.title)) {
                            recordScope.launch {
                                graph.recordingEngine.recordSeries(channel, nowProg, menuRow.programmes)
                            }
                            Toast.makeText(context, context.getString(R.string.rec_series_recording_set, nowProg.title), Toast.LENGTH_LONG).show()
                            promptBackgroundIfNeeded()
                            channelMenu = null
                        }
                    }
                    if (upcoming.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.guide_later_header),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        upcoming.forEach { programme ->
                            RecordActionRow("${formatTime(programme.startUtcMillis)}   ${programme.title}") {
                                // Open the programme dialog so the choice is record, remind or auto-switch —
                                // not a surprise one-tap recording.
                                channelMenu = null
                                recordTarget = menuRow to programme
                            }
                        }
                    }
                    RecordActionRow(stringResource(R.string.common_cancel)) { channelMenu = null }
                }
            }
        }
    }

    externalChannelToConfirm?.let { channel ->
        AlertDialog(
            onDismissRequest = { externalChannelToConfirm = null },
            title = { Text(stringResource(R.string.guide_external_warning_title)) },
            text = { Text(stringResource(R.string.guide_external_warning_body)) },
            confirmButton = {
                TextButton(onClick = {
                    externalChannelToConfirm = null
                    openExternalChannel(channel)
                }) { Text(stringResource(R.string.guide_external_open_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { externalChannelToConfirm = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showBackgroundPrompt) {
        RecordingBackgroundDialog(
            onAllow = {
                showBackgroundPrompt = false
                if (!context.requestIgnoreBatteryOptimizations()) {
                    Toast.makeText(context, R.string.recset_background_open_failed, Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { showBackgroundPrompt = false },
        )
    }
}

/** Inserts a reminder for a future programme and arms its alarm. No-op if one already exists. */
private suspend fun setReminder(
    graph: ServiceLocator.Graph,
    context: android.content.Context,
    channel: Channel,
    programme: Programme,
    autoTune: Boolean,
) {
    if (graph.reminderRepository.forProgramme(channel.id, programme.startUtcMillis) != null) return
    val id = graph.reminderRepository.insert(
        Reminder(
            channelId = channel.id,
            channelName = channel.displayName,
            logoUrl = channel.logoUrl,
            title = programme.title,
            startUtcMillis = programme.startUtcMillis,
            endUtcMillis = programme.endUtcMillis,
            autoTune = autoTune,
            createdAtMillis = System.currentTimeMillis(),
        ),
    )
    ReminderScheduler.set(context, id, programme.startUtcMillis)
}

@Composable
private fun RecordActionRow(
    label: String,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        primary -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        primary -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        color = fg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(10.dp))
                else Modifier,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** A small focusable quality pill (FHD / HD / SD…) for the record dialog's "record HD or SD" choice. */
@Composable
private fun QualityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = fg,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(8.dp))
                else Modifier,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun RailEntry(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    palette: InterfacePalette? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val selectedBackground = palette?.selectedChannel ?: MaterialTheme.colorScheme.primaryContainer
    val selectedText = palette?.onSelected ?: MaterialTheme.colorScheme.onPrimaryContainer
    val unselectedBackground = palette?.surface ?: MaterialTheme.colorScheme.surface
    val secondaryText = palette?.mutedText ?: MaterialTheme.colorScheme.onSurfaceVariant
    val focusAccent = palette?.accent ?: MaterialTheme.colorScheme.primary
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) selectedText else secondaryText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) selectedBackground else unselectedBackground,
            )
            .then(
                if (focused) Modifier.border(2.dp, focusAccent, RoundedCornerShape(8.dp))
                else Modifier,
            )
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun ChannelRow(
    row: ChannelsViewModel.Row,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val background =
        if (isSelected) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.background

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = row.primary.logoUrl,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            // Logo, name, guide — and nothing else. Quality is a playback decision;
            // its switch lives in the player, not as clutter on every row.
            Text(
                row.primary.shownName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = row.now?.let { "${formatTime(it.startUtcMillis)}  ${it.title}" }
                    // Honest rather than blank. A user who sees this on every channel knows
                    // the guide is the problem, not their provider.
                    ?: stringResource(R.string.guide_no_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.now?.let { programme ->
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { programme.progressAt(now) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
            row.next?.let { next ->
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.guide_next_prefix, formatTime(next.startUtcMillis), next.title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(onClick = onToggleFavourite) {
            Icon(
                imageVector = if (row.primary.favourite) Icons.Default.Star
                else Icons.Outlined.StarOutline,
                contentDescription = if (row.primary.favourite) stringResource(R.string.common_remove_favourite) else stringResource(R.string.common_favourite),
            )
        }
    }
}

@Composable
private fun LoadingState(isSyncing: Boolean) {
    val s by app.opentv.core.StatusBus.message.collectAsState()
    val p by app.opentv.core.StatusBus.progress.collectAsState()
    val status = s
    val progress = p
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            if (progress == null) {
                CircularProgressIndicator()
            } else {
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.guide_loading_channels), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    // Actively assembling — this is work in progress, not a failure.
                    status != null -> status
                    isSyncing -> stringResource(R.string.guide_fetching_desc)
                    // Channels are already on the device; the guide is being built from them.
                    // A big provider takes a moment (longer on this debug build) — not a failure.
                    else -> stringResource(R.string.guide_building_desc)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (progress != null) {
                Spacer(Modifier.height(20.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
        }
    }
}

@Composable
private fun NoFavouritesState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.guide_no_favourites_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.guide_no_favourites_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shown when a provider is configured but there are no channels to display and nothing is
 * syncing — i.e. the last catalogue load failed or came back empty. Replaces the endless
 * "Loading your channels" spinner with something the user can act on.
 */
@Composable
private fun ChannelsErrorState(onRetry: () -> Unit, onEditProvider: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            Text(
                stringResource(R.string.guide_load_failed_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.guide_load_failed_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.Button(onClick = onRetry) {
                    Text(stringResource(R.string.common_try_again))
                }
                androidx.compose.material3.OutlinedButton(onClick = onEditProvider) {
                    Text(stringResource(R.string.guide_edit_provider))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onAddSource: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.guide_no_channels_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.guide_add_provider_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(onClick = onAddSource) { Text(stringResource(R.string.guide_add_provider_button)) }
        }
    }
}

/** UTC in the database, device zone on screen. Converted here and nowhere else. */
private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTime(utcMillis: Long): String = timeFormat.format(Date(utcMillis))
