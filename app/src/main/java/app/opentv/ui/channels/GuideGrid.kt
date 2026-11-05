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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.shownName
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.InterfacePalette
import app.opentv.ui.interfacePalette
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The programme guide: channels down the left, a scrolling time-line to the right, with
 * each programme drawn as a block whose width is its duration. This is the "grid" a TV guide
 * is supposed to be — you can see what is on now, what is next, and read across the evening.
 *
 * ## How it lays out without a custom Layout
 *
 * Every row and the time header share one [horizontalScroll] state, so scrolling any of them
 * scrolls all of them in lock-step and the columns stay time-aligned. Within a row, blocks
 * are placed left to right at [MINUTE] width per minute; a leading spacer covers any gap
 * before the first programme, and gaps between programmes get their own spacer. No absolute
 * positioning, no measuring pass — just widths, which is cheap enough for a lazy list of
 * hundreds of channels on a weak TV box.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun GuideGrid(
    rows: List<ChannelsViewModel.Row>,
    windowStartMillis: Long,
    highlightedKey: Any?,
    onSelectRow: (ChannelsViewModel.Row) -> Unit,
    onFocusRow: (ChannelsViewModel.Row) -> Unit,
    onProgramme: (ChannelsViewModel.Row, Programme) -> Unit = { _, _ -> },
    onToggleFavourite: (ChannelsViewModel.Row) -> Unit = {},
    // Returns true if it handled the key (rail was hidden, so consume it); false to let normal
    // left-navigation carry focus into the already-visible rail.
    onExitLeftFromChannel: () -> Boolean = { false },
    dayOffset: Int = 0,
    nowMillis: Long = System.currentTimeMillis(),
    density: AppSettings.GuideDensity = AppSettings.GuideDensity.NORMAL,
    modifier: Modifier = Modifier,
    interfaceStyle: AppSettings.InterfaceStyle = AppSettings.InterfaceStyle.CURRENT,
) {
    val palette = interfacePalette(interfaceStyle, MaterialTheme.colorScheme)
    val scroll = rememberScrollState()
    val rowListState = rememberLazyListState()
    var pendingProgrammeFocusKey by remember { mutableStateOf<String?>(null) }
    val navigationScope = rememberCoroutineScope()
    var programmeNavigationJob by remember { mutableStateOf<Job?>(null) }
    val programmeSlots = remember(rows) {
        rows.map { row ->
            row.programmes.map { programme ->
                GuideProgrammeSlot(
                    key = programmeFocusIdentity(programme),
                    startUtcMillis = programme.startUtcMillis,
                    endUtcMillis = programme.endUtcMillis,
                )
            }
        }
    }
    val now = nowMillis
    val metrics = remember(density) { guideMetrics(density) }
    // Jumping to another day resets the horizontal scroll to that day's start; a within-day time
    // drift (dayOffset unchanged) leaves the user's scroll position untouched.
    androidx.compose.runtime.LaunchedEffect(dayOffset) { scroll.scrollTo(0) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = (maxWidth - metrics.channelColumn - 3.dp).coerceAtLeast(120.dp)
        val unit = LocalDensity.current
        val viewportPx = with(unit) { viewport.toPx() }
        val bucketWidth = metrics.halfHourWidth.value
        // Reading scroll.value directly would recompose every row on every scrolled pixel. Snap it
        // to half-hour buckets instead: the timeline only re-lays out when a new bucket comes into
        // range, which keeps a weak box responsive while dragging through the guide.
        val bucket by remember(bucketWidth) {
            derivedStateOf { (scroll.value / unit.density / bucketWidth).toInt() }
        }
        val visibleStart = bucket * bucketWidth

        Column(Modifier.fillMaxSize()) {
            // No pager buttons: the programme blocks are d-pad focusable, so moving right along a row
            // scrolls the whole timeline (header and every row in lock-step) on its own.
            TimeHeader(windowStartMillis, scroll, metrics, palette)

            LazyColumn(
                state = rowListState,
                modifier = Modifier.focusRestorer().focusGroup(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(rows, key = { _, row -> row.key }, contentType = { _, _ -> "guide-row" }) { rowIndex, row ->
                    GuideRow(
                        row = row,
                        windowStartMillis = windowStartMillis,
                        nowMillis = now,
                        scroll = scroll,
                        metrics = metrics,
                        visibleStart = visibleStart,
                        viewport = viewport,
                        isHighlighted = row.key == highlightedKey,
                        onSelect = { onSelectRow(row) },
                        onFocus = { onFocusRow(row) },
                        onProgramme = { programme -> onProgramme(row, programme) },
                        pendingFocusKey = pendingProgrammeFocusKey,
                        onPendingFocusConsumed = { key ->
                            if (pendingProgrammeFocusKey == key) pendingProgrammeFocusKey = null
                        },
                        onMoveProgramme = { programme, direction ->
                            val currentIndex = row.programmes.indexOfFirst {
                                programmeFocusIdentity(it) == programmeFocusIdentity(programme)
                            }
                            val target = guideProgrammeTarget(programmeSlots, rowIndex, currentIndex, direction)
                                ?: return@GuideRow false
                            val targetRow = rows.getOrNull(target.rowIndex) ?: return@GuideRow false
                            val targetProgramme = targetRow.programmes.getOrNull(target.programmeIndex)
                                ?: return@GuideRow false
                            val targetPlacement = placementsFor(
                                targetRow.programmes,
                                windowStartMillis,
                                metrics,
                            ).getOrNull(target.programmeIndex) ?: return@GuideRow false
                            val targetKey = guideProgrammeFocusKey(
                                targetRow.key.toString(),
                                programmeFocusIdentity(targetProgramme),
                            )
                            pendingProgrammeFocusKey = targetKey
                            programmeNavigationJob?.cancel()
                            programmeNavigationJob = navigationScope.launch {
                                rowListState.animateScrollToItem(target.rowIndex)
                                val leftPx = with(unit) { targetPlacement.left.dp.toPx() }
                                val rightPx = with(unit) { (targetPlacement.left + targetPlacement.width).dp.toPx() }
                                val pad = with(unit) { 24.dp.toPx() }
                                val desired = when {
                                    leftPx < scroll.value + pad -> leftPx - pad
                                    rightPx > scroll.value + viewportPx - pad -> rightPx - viewportPx + pad
                                    else -> scroll.value.toFloat()
                                }
                                scroll.animateScrollTo(desired.toInt().coerceIn(0, scroll.maxValue))
                            }
                            true
                        },
                        onToggleFavourite = { onToggleFavourite(row) },
                        onExitLeft = onExitLeftFromChannel,
                        palette = palette,
                    )
                }
            }
        }
    }
}

/**
 * The live channels as a plain vertical list instead of the time-grid — one channel per row with
 * its logo, name, and now/next line. This is the "list mode instead of tiles" some users prefer on
 * a busy line-up. It keeps the exact same focus/key contract as [GuideGrid] so [HomeScreen] can
 * swap between the two with nothing else changing: focus follows the d-pad (updating the preview),
 * OK opens the channel menu, and LEFT from a row asks the host to reopen the category rail.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ChannelList(
    rows: List<ChannelsViewModel.Row>,
    highlightedKey: Any?,
    onSelectRow: (ChannelsViewModel.Row) -> Unit,
    onFocusRow: (ChannelsViewModel.Row) -> Unit,
    onToggleFavourite: (ChannelsViewModel.Row) -> Unit = {},
    onExitLeftFromChannel: () -> Boolean = { false },
    modifier: Modifier = Modifier,
    interfaceStyle: AppSettings.InterfaceStyle = AppSettings.InterfaceStyle.CURRENT,
) {
    val palette = interfacePalette(interfaceStyle, MaterialTheme.colorScheme)
    val now = System.currentTimeMillis()
    LazyColumn(
        modifier = modifier.fillMaxSize().focusRestorer().focusGroup(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            ChannelListRow(
                row = row,
                nowMillis = now,
                isHighlighted = row.key == highlightedKey,
                onSelect = { onSelectRow(row) },
                onFocus = { onFocusRow(row) },
                onToggleFavourite = { onToggleFavourite(row) },
                onExitLeft = onExitLeftFromChannel,
                palette = palette,
            )
        }
    }
}

@Composable
private fun ChannelListRow(
    row: ChannelsViewModel.Row,
    nowMillis: Long,
    isHighlighted: Boolean,
    onSelect: () -> Unit,
    onFocus: () -> Unit,
    onToggleFavourite: () -> Unit,
    onExitLeft: () -> Boolean,
    palette: InterfacePalette,
) {
    var focused by remember { mutableStateOf(false) }
    var channelCellDirectlyFocused by remember { mutableStateOf(false) }
    val nowProg = row.now
    val channelAccessibility = stringResource(
        R.string.guide_channel_accessibility,
        row.primary.shownName,
        nowProg?.title ?: stringResource(R.string.guide_no_info),
    )
    val previewState = stringResource(R.string.guide_channel_preview_state)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isHighlighted) palette.selectedChannel else palette.surface,
            )
            .then(
                if (focused) Modifier.border(2.dp, palette.accent, RoundedCornerShape(8.dp))
                else Modifier,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = channelAccessibility
                if (isHighlighted) stateDescription = previewState
            }
            // LEFT from a row reopens the collapsed rail, exactly like the grid's channel column.
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft &&
                    channelCellDirectlyFocused
                ) onExitLeft() else false
            }
            .onFocusChanged {
                // The nested favourite button is a separate focus target. Track focus anywhere
                // inside the channel cell so the highlighted row and preview do not go stale.
                channelCellDirectlyFocused = it.isFocused
                focused = it.hasFocus
                if (it.hasFocus) onFocus()
            }
            .clickable(role = Role.Button, onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        row.primary.number?.let { num ->
            Text(
                "$num",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.width(30.dp),
            )
        }
        AsyncImage(
            model = row.primary.logoUrl,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.primary.shownName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.variants.size > 1) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.guide_qualities_count, row.variants.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = nowProg?.let { "${clockFormat.format(Date(it.startUtcMillis))}  ${it.title}" }
                    ?: stringResource(R.string.guide_no_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (nowProg != null) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(nowProg.progressAt(nowMillis).coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            row.next?.let { next ->
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.guide_next_prefix, clockFormat.format(Date(next.startUtcMillis)), next.title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onToggleFavourite, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (row.primary.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = if (row.primary.favourite) stringResource(R.string.common_remove_favourite)
                else stringResource(R.string.common_favourite),
                tint = if (row.primary.favourite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimeHeader(
    windowStartMillis: Long,
    scroll: androidx.compose.foundation.ScrollState,
    metrics: GuideMetrics,
    palette: InterfacePalette,
) {
    Row(Modifier.fillMaxWidth().height(28.dp)) {
        // Empty corner above the channel column.
        Box(Modifier.width(metrics.channelColumn))
        Row(
            Modifier
                .horizontalScroll(scroll)
                .background(palette.surface),
        ) {
            // A label every half hour across the whole window.
            repeat(HOURS_IN_WINDOW * 2) { i ->
                val slotStart = windowStartMillis + i * HALF_HOUR_MS
                Box(
                    Modifier.width(metrics.halfHourWidth).height(28.dp).padding(start = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        clockFormat.format(Date(slotStart)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.mutedText,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    row: ChannelsViewModel.Row,
    windowStartMillis: Long,
    nowMillis: Long,
    scroll: androidx.compose.foundation.ScrollState,
    metrics: GuideMetrics,
    visibleStart: Float,
    viewport: Dp,
    isHighlighted: Boolean,
    onSelect: () -> Unit,
    onFocus: () -> Unit,
    onProgramme: (Programme) -> Unit,
    pendingFocusKey: String?,
    onPendingFocusConsumed: (String) -> Unit,
    onMoveProgramme: (Programme, GuideProgrammeDirection) -> Boolean,
    onToggleFavourite: () -> Unit = {},
    onExitLeft: () -> Boolean = { false },
    palette: InterfacePalette,
) {
    // The highlighted row drives the live preview; OK opens its action menu.
    var focused by remember { mutableStateOf(false) }
    var channelCellDirectlyFocused by remember { mutableStateOf(false) }
    val channelAccessibility = stringResource(
        R.string.guide_channel_accessibility,
        row.primary.shownName,
        row.now?.title ?: stringResource(R.string.guide_no_info),
    )
    val previewState = stringResource(R.string.guide_channel_preview_state)
    val scope = rememberCoroutineScope()
    val unit = LocalDensity.current
    val viewportPx = with(unit) { viewport.toPx() }

    Row(Modifier.fillMaxWidth().height(metrics.rowHeight)) {

        // ---- Fixed channel cell (does not scroll) --------------------------------------
        Row(
            Modifier
                .width(metrics.channelColumn)
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isHighlighted) palette.selectedChannel else palette.surface,
                )
                .then(
                    if (focused) Modifier.border(
                        2.dp, palette.accent, RoundedCornerShape(8.dp),
                    ) else Modifier,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = channelAccessibility
                    if (isHighlighted) stateDescription = previewState
                }
                // LEFT from this leftmost column asks the host to reopen the collapsed category
                // rail; onExitLeft consumes the key only when it handled it (rail was hidden), so
                // moving left onto programme blocks and into an already-visible rail still works.
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft &&
                        channelCellDirectlyFocused
                    ) onExitLeft()
                    else false
                }
                .onFocusChanged {
                    // A favourite IconButton is nested in this cell; its focus must still update
                    // the row highlight and the live preview.
                    channelCellDirectlyFocused = it.isFocused
                    focused = it.hasFocus
                    if (it.hasFocus) onFocus()
                }
                .clickable(role = Role.Button, onClick = onSelect)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            row.primary.number?.let { num ->
                Text(
                    "$num",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.width(28.dp),
                )
            }
            AsyncImage(
                model = row.primary.logoUrl,
                contentDescription = null,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.primary.shownName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.variants.size > 1) {
                    Text(
                        stringResource(R.string.guide_qualities_count, row.variants.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
            // Favourite toggle. Phone/tablet users had no visible way to favourite from the guide
            // (only the Channel Manager) — this puts a tappable star on every row; it stays d-pad
            // focusable so it works from a TV remote too.
            IconButton(
                onClick = onToggleFavourite,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (row.primary.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (row.primary.favourite) stringResource(R.string.common_remove_favourite)
                    else stringResource(R.string.common_favourite),
                    tint = if (row.primary.favourite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.width(3.dp))

        // ---- Scrolling programme strip -------------------------------------------------
        Row(Modifier.horizontalScroll(scroll)) {
            val programmes = row.programmes
            if (programmes.isEmpty()) {
                // Honest placeholder spanning the window, so a guide-less channel reads as
                // "no guide" rather than as a suspiciously empty gap.
                Box(
                    Modifier
                        .width(metrics.windowWidth)
                        .fillMaxSize()
                        .padding(end = 3.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(palette.surfaceVariant.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        stringResource(R.string.guide_no_info),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.mutedText,
                    )
                }
            } else {
                // Lay the row out once, in plain numbers, then only compose the blocks near the
                // visible part of the timeline. The old code composed every programme for all
                // twenty-four hours of every visible channel, which is what made the guide crawl.
                val placements = remember(programmes, windowStartMillis, metrics) {
                    placementsFor(programmes, windowStartMillis, metrics)
                }
                val margin = metrics.halfHourWidth.value * 2f
                val from = visibleStart - margin
                val to = visibleStart + viewport.value + margin
                var drawn = 0f
                for (placement in placements) {
                    val right = placement.left + placement.width
                    if (right < from) continue
                    if (placement.left > to) break
                    if (placement.left > drawn) {
                        Spacer(Modifier.width((placement.left - drawn).dp))
                        drawn = placement.left
                    }
                    val programme = placement.programme
                    val isNow = nowMillis in programme.startUtcMillis until programme.endUtcMillis
                    val programmeAccessibility = stringResource(
                        R.string.guide_programme_accessibility,
                        programme.title,
                        row.primary.shownName,
                        clockFormat.format(Date(programme.startUtcMillis)),
                        clockFormat.format(Date(programme.endUtcMillis)),
                    )
                    val programmeState = stringResource(
                        if (isNow) R.string.guide_programme_current_state
                        else R.string.guide_programme_upcoming_state,
                    )
                    val focusKey = guideProgrammeFocusKey(
                        row.key.toString(),
                        programmeFocusIdentity(programme),
                    )
                    ProgrammeBlock(
                        title = programme.title,
                        width = placement.width.dp,
                        isNow = isNow,
                        progress = if (isNow) programme.progressAt(nowMillis) else 0f,
                        focusKey = focusKey,
                        requestFocus = pendingFocusKey == focusKey,
                        onFocusRequestConsumed = onPendingFocusConsumed,
                        onMove = { direction -> onMoveProgramme(programme, direction) },
                        accessibilityDescription = programmeAccessibility,
                        accessibilityState = programmeState,
                        palette = palette,
                        onFocus = {
                            onFocus()
                            // D-pad focus does not scroll a plain Row on its own. Nudge the shared
                            // timeline so the newly focused block is never left off-screen.
                            val leftPx = with(unit) { placement.left.dp.toPx() }
                            val rightPx = with(unit) { (placement.left + placement.width).dp.toPx() }
                            val pad = with(unit) { 24.dp.toPx() }
                            val target = when {
                                leftPx < scroll.value + pad -> leftPx - pad
                                rightPx > scroll.value + viewportPx - pad -> rightPx - viewportPx + pad
                                else -> null
                            }
                            if (target != null) {
                                scope.launch {
                                    scroll.animateScrollTo(target.toInt().coerceIn(0, scroll.maxValue))
                                }
                            }
                        },
                        onClick = { onProgramme(programme) },
                    )
                    drawn = right
                }
                // Keep every row's scroll extent identical to the header, even while only a slice of
                // its blocks is composed.
                val total = maxOf(metrics.windowWidth.value, drawn)
                if (total > drawn) Spacer(Modifier.width((total - drawn).dp))
            }
        }
    }
}

@Composable
private fun ProgrammeBlock(
    title: String,
    width: Dp,
    isNow: Boolean,
    progress: Float,
    focusKey: String,
    requestFocus: Boolean,
    onFocusRequestConsumed: (String) -> Unit,
    onMove: (GuideProgrammeDirection) -> Boolean,
    accessibilityDescription: String,
    accessibilityState: String,
    palette: InterfacePalette,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember(focusKey) { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(requestFocus) {
        if (requestFocus) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
            onFocusRequestConsumed(focusKey)
        }
    }
    Box(
        Modifier
            .width(width)
            .fillMaxSize()
            .padding(end = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    focused -> palette.focusedBlock
                    isNow -> palette.currentProgramme
                    else -> palette.surfaceVariant
                },
            )
            .then(
                if (focused || isNow) Modifier.border(
                    if (focused) 2.dp else 1.dp,
                    palette.accent,
                    RoundedCornerShape(6.dp),
                ) else Modifier,
            )
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val direction = when (event.key) {
                    Key.DirectionLeft -> GuideProgrammeDirection.LEFT
                    Key.DirectionRight -> GuideProgrammeDirection.RIGHT
                    Key.DirectionUp -> GuideProgrammeDirection.UP
                    Key.DirectionDown -> GuideProgrammeDirection.DOWN
                    else -> null
                }
                direction?.let(onMove) ?: false
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .semantics {
                contentDescription = accessibilityDescription
                stateDescription = accessibilityState
            }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                focused -> palette.onSelected
                else -> palette.text
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp),
        )
        // How far through the current programme we are — a live fill along the bottom edge.
        if (isNow && progress > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(palette.progress),
            )
        }
    }
}

/** One programme's laid-out position along a row, in guide dp from the window's left edge. */
private data class Placement(val programme: Programme, val left: Float, val width: Float)

/** Stable within the EPG row even when the same channel/programme appears in several providers. */
private fun programmeFocusIdentity(programme: Programme): String =
    "${programme.feedId}:${programme.epgChannelId}:${programme.startUtcMillis}:${programme.endUtcMillis}"

/**
 * Position every programme in a row. A short show would otherwise be a sliver too thin to read,
 * so each block is floored at [GuideMetrics.minBlock] and the borrowed width is carried as debt
 * and repaid from the following gaps — keeping later blocks aligned with the time header.
 */
private fun placementsFor(
    programmes: List<Programme>,
    windowStartMillis: Long,
    metrics: GuideMetrics,
): List<Placement> {
    val out = ArrayList<Placement>(programmes.size)
    var cursor = windowStartMillis
    var left = 0f
    var debt = 0f
    for (programme in programmes) {
        // Clamp to the window's left edge; a programme that started earlier is drawn from
        // `windowStart` so its block does not push everything right.
        val start = programme.startUtcMillis.coerceAtLeast(windowStartMillis)
        val gap = widthFor(cursor, start, metrics.minute).value
        val repaid = minOf(debt, gap)
        debt -= repaid
        left += gap - repaid
        val end = programme.endUtcMillis
        val trueWidth = widthFor(start, end, metrics.minute).value
        val drawnWidth = maxOf(trueWidth, metrics.minBlock.value)
        debt += drawnWidth - trueWidth
        out += Placement(programme, left, drawnWidth)
        left += drawnWidth
        cursor = end
    }
    return out
}

/** Metrics for the three guide densities: time scale, row height and channel column width. */
private data class GuideMetrics(
    val minute: Float,
    val rowHeight: Dp,
    val channelColumn: Dp,
    val minBlock: Dp,
) {
    val halfHourWidth: Dp get() = (30 * minute).dp
    val windowWidth: Dp get() = (HOURS_IN_WINDOW * 60 * minute).dp
}

private fun guideMetrics(density: AppSettings.GuideDensity): GuideMetrics = when (density) {
    AppSettings.GuideDensity.COMPACT ->
        GuideMetrics(minute = 3f, rowHeight = 54.dp, channelColumn = 190.dp, minBlock = 56.dp)
    AppSettings.GuideDensity.NORMAL ->
        GuideMetrics(minute = 4f, rowHeight = 64.dp, channelColumn = 220.dp, minBlock = 72.dp)
    AppSettings.GuideDensity.COMFORTABLE ->
        GuideMetrics(minute = 5.5f, rowHeight = 78.dp, channelColumn = 260.dp, minBlock = 96.dp)
}

/** Minutes between two instants, as guide width. Never negative; tiny slots stay legible. */
private fun widthFor(fromMillis: Long, toMillis: Long, minuteDp: Float): Dp {
    val minutes = ((toMillis - fromMillis) / 60_000L).coerceAtLeast(0)
    return (minutes * minuteDp).dp
}

private const val HOURS_IN_WINDOW = 24
private const val HALF_HOUR_MS = 30 * 60 * 1000L

private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
