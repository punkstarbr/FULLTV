/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.data.model.shownName
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.FullTvCard
import app.opentv.ui.FullTvChrome
import app.opentv.ui.FullTvSectionHeader
import app.opentv.ui.InterfacePalette
import app.opentv.ui.fullTvFocusRing
import app.opentv.ui.interfacePalette
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Channel detail pane paired with the guide. The preview player remains caller-owned and is only
 * rendered when supplied; opening this surface never starts playback on its own.
 */
@OptIn(markerClass = [UnstableApi::class])
@kotlin.OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun GuidePreview(
    row: ChannelsViewModel.Row?,
    nowMillis: Long,
    onWatch: () -> Unit,
    onRefresh: () -> Unit,
    onAddSource: () -> Unit,
    previewPlayer: ExoPlayer?,
    isRecording: Boolean = false,
    onRecord: () -> Unit = {},
    dayLabel: String = "",
    canGoPrevDay: Boolean = false,
    onPrevDay: () -> Unit = {},
    onNextDay: () -> Unit = {},
    modifier: Modifier = Modifier,
    interfaceStyle: AppSettings.InterfaceStyle = AppSettings.InterfaceStyle.CURRENT,
) {
    val palette = interfacePalette(interfaceStyle, MaterialTheme.colorScheme)
    val compactBottomPanel = interfaceStyle == AppSettings.InterfaceStyle.CLASSIC_EPG
    val hybridPreview = interfaceStyle == AppSettings.InterfaceStyle.OI_HYBRID
    val watchLabel = stringResource(R.string.guide_watch_label)
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .then(if (interfaceStyle == AppSettings.InterfaceStyle.CURRENT) Modifier else Modifier.background(palette.surface)),
    ) {
        val compactLayout = maxWidth < 640.dp
        val veryCompactLayout = maxWidth < 460.dp
        val baseHeight = when {
            compactBottomPanel && !compactLayout -> 132f
            compactBottomPanel -> 220f
            hybridPreview && !compactLayout -> 300f
            hybridPreview -> 252f
            compactLayout -> 244f
            else -> 224f
        }
        val previewHeight = guideFontScaledHeightDp(baseHeight, density.fontScale).dp
        val horizontalInset = if (compactLayout) FullTvChrome.compactPageInset / 2 else FullTvChrome.pageInset / 2

        FullTvCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewHeight)
                .padding(horizontal = horizontalInset, vertical = 4.dp)
                .focusRestorer()
                .focusGroup(),
            containerColor = palette.surface,
            contentColor = palette.text,
        ) {
            when {
                compactBottomPanel && !compactLayout -> {
                    Row(
                        Modifier.fillMaxSize().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GuidePreviewMedia(
                            row = row,
                            previewPlayer = previewPlayer,
                            watchLabel = watchLabel,
                            onWatch = onWatch,
                            modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f),
                            accent = palette.accent,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        ) {
                            GuidePreviewControls(
                                isRecording = isRecording,
                                showRecord = row != null,
                                dayLabel = dayLabel,
                                canGoPrevDay = canGoPrevDay,
                                onPrevDay = onPrevDay,
                                onNextDay = onNextDay,
                                onRecord = onRecord,
                                onRefresh = onRefresh,
                                onAddSource = onAddSource,
                                compact = true,
                                palette = palette,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.nav_live_tv),
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.accent,
                            )
                            GuidePreviewDetails(
                                row = row,
                                nowMillis = nowMillis,
                                compact = true,
                                palette = palette,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                compactLayout -> {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FullTvSectionHeader(title = stringResource(R.string.nav_live_tv))
                        if (veryCompactLayout) {
                            if (dayLabel.isNotEmpty()) {
                                GuideDayControls(dayLabel, canGoPrevDay, onPrevDay, onNextDay, palette)
                            }
                            GuideActionControls(
                                isRecording = isRecording,
                                showRecord = row != null,
                                onRecord = onRecord,
                                onRefresh = onRefresh,
                                onAddSource = onAddSource,
                                palette = palette,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            GuidePreviewControls(
                                isRecording = isRecording,
                                showRecord = row != null,
                                dayLabel = dayLabel,
                                canGoPrevDay = canGoPrevDay,
                                onPrevDay = onPrevDay,
                                onNextDay = onNextDay,
                                onRecord = onRecord,
                                onRefresh = onRefresh,
                                onAddSource = onAddSource,
                                compact = true,
                                palette = palette,
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GuidePreviewMedia(
                                row = row,
                                previewPlayer = previewPlayer,
                                watchLabel = watchLabel,
                                onWatch = onWatch,
                                modifier = Modifier.widthIn(min = 96.dp, max = 144.dp).fillMaxHeight().aspectRatio(16f / 9f),
                                accent = palette.accent,
                            )
                            Spacer(Modifier.width(10.dp))
                            GuidePreviewDetails(
                                row = row,
                                nowMillis = nowMillis,
                                compact = true,
                                palette = palette,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
                else -> {
                    Row(
                        Modifier.fillMaxSize().padding(FullTvChrome.compactPageInset),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GuidePreviewMedia(
                            row = row,
                            previewPlayer = previewPlayer,
                            watchLabel = watchLabel,
                            onWatch = onWatch,
                            modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f),
                            accent = palette.accent,
                        )
                        Spacer(Modifier.width(FullTvChrome.cardGap))
                        Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        ) {
                            FullTvSectionHeader(
                                title = stringResource(R.string.nav_live_tv),
                                trailingContent = {
                                    GuidePreviewControls(
                                        isRecording = isRecording,
                                        showRecord = row != null,
                                        dayLabel = dayLabel,
                                        canGoPrevDay = canGoPrevDay,
                                        onPrevDay = onPrevDay,
                                        onNextDay = onNextDay,
                                        onRecord = onRecord,
                                        onRefresh = onRefresh,
                                        onAddSource = onAddSource,
                                        compact = false,
                                        palette = palette,
                                    )
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                            GuidePreviewDetails(
                                row = row,
                                nowMillis = nowMillis,
                                compact = false,
                                palette = palette,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(markerClass = [UnstableApi::class])
@Composable
private fun GuidePreviewMedia(
    row: ChannelsViewModel.Row?,
    previewPlayer: ExoPlayer?,
    watchLabel: String,
    onWatch: () -> Unit,
    modifier: Modifier,
    accent: Color,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(FullTvChrome.cardShape)
            .background(Color.Black)
            .onFocusChanged { focused = it.isFocused }
            .fullTvFocusRing(focused, accent, FullTvChrome.cardShape)
            .semantics(mergeDescendants = true) {
                contentDescription = watchLabel
            }
            .clickable(role = Role.Button, onClick = onWatch),
        contentAlignment = Alignment.Center,
    ) {
        if (row != null) {
            AsyncImage(
                model = row.primary.logoUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(0.6f),
            )
        }

        if (previewPlayer != null && row != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        player = previewPlayer
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update = { it.player = previewPlayer },
            )
        }

        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.78f), FullTvChrome.controlShape)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(watchLabel, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun GuidePreviewDetails(
    row: ChannelsViewModel.Row?,
    nowMillis: Long,
    compact: Boolean,
    palette: InterfacePalette,
    modifier: Modifier = Modifier,
) {
    if (row == null) {
        Box(modifier, contentAlignment = Alignment.CenterStart) {
            Text(
                stringResource(R.string.guide_highlight_hint),
                color = palette.mutedText,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium,
                maxLines = if (compact) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.primary.shownName,
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.headlineSmall,
                color = palette.text,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (row.variants.size > 1) {
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.guide_qualities_count, row.variants.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.accent,
                    maxLines = 1,
                )
            }
        }

        val nowProg = row.now
        if (nowProg != null) {
            Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
            Text(
                "${formatTime(nowProg.startUtcMillis)}–${formatTime(nowProg.endUtcMillis)}   ${nowProg.title}",
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium,
                color = palette.text,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { nowProg.progressAt(nowMillis) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = palette.progress,
            )
            nowProg.description?.takeIf { it.isNotBlank() }?.let { synopsis ->
                Spacer(Modifier.height(5.dp))
                Text(
                    synopsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.mutedText,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.guide_no_info_channel),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.mutedText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        row.next?.let { next ->
            Spacer(Modifier.height(5.dp))
            Text(
                stringResource(R.string.guide_next_prefix, formatTime(next.startUtcMillis), next.title),
                style = MaterialTheme.typography.bodySmall,
                color = palette.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GuidePreviewControls(
    isRecording: Boolean,
    showRecord: Boolean,
    dayLabel: String,
    canGoPrevDay: Boolean,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onRecord: () -> Unit,
    onRefresh: () -> Unit,
    onAddSource: () -> Unit,
    compact: Boolean,
    palette: InterfacePalette,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 2.dp),
    ) {
        if (dayLabel.isNotEmpty()) {
            GuideIconAction(
                image = Icons.Default.KeyboardArrowLeft,
                description = stringResource(R.string.guide_prev_day),
                enabled = canGoPrevDay,
                onClick = onPrevDay,
                palette = palette,
            )
            Text(
                dayLabel,
                style = MaterialTheme.typography.labelMedium,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (compact) 112.dp else 138.dp),
            )
            GuideIconAction(
                image = Icons.Default.KeyboardArrowRight,
                description = stringResource(R.string.guide_next_day),
                onClick = onNextDay,
                palette = palette,
            )
        }
        Spacer(Modifier.weight(1f, fill = false))
        GuideActionControls(
            isRecording = isRecording,
            showRecord = showRecord,
            onRecord = onRecord,
            onRefresh = onRefresh,
            onAddSource = onAddSource,
            palette = palette,
        )
    }
}

@Composable
private fun GuideDayControls(
    dayLabel: String,
    canGoPrevDay: Boolean,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    palette: InterfacePalette,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GuideIconAction(
            image = Icons.Default.KeyboardArrowLeft,
            description = stringResource(R.string.guide_prev_day),
            enabled = canGoPrevDay,
            onClick = onPrevDay,
            palette = palette,
        )
        Text(
            dayLabel,
            style = MaterialTheme.typography.labelMedium,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        GuideIconAction(
            image = Icons.Default.KeyboardArrowRight,
            description = stringResource(R.string.guide_next_day),
            onClick = onNextDay,
            palette = palette,
        )
    }
}

@Composable
private fun GuideActionControls(
    isRecording: Boolean,
    showRecord: Boolean,
    onRecord: () -> Unit,
    onRefresh: () -> Unit,
    onAddSource: () -> Unit,
    palette: InterfacePalette,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (showRecord) {
            GuideIconAction(
                image = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                description = stringResource(
                    if (isRecording) R.string.guide_cd_stop_recording else R.string.guide_cd_record_now,
                ),
                tint = Color(0xFFE53935),
                onClick = onRecord,
                palette = palette,
            )
        }
        GuideIconAction(
            image = Icons.Default.Refresh,
            description = stringResource(R.string.guide_cd_refresh),
            onClick = onRefresh,
            palette = palette,
        )
        GuideIconAction(
            image = Icons.Default.Add,
            description = stringResource(R.string.guide_cd_add_source),
            onClick = onAddSource,
            palette = palette,
        )
    }
}

@Composable
private fun GuideIconAction(
    image: ImageVector,
    description: String,
    onClick: () -> Unit,
    palette: InterfacePalette,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(40.dp)
            .clip(FullTvChrome.controlShape)
            .onFocusChanged { focused = it.isFocused }
            .fullTvFocusRing(focused, palette.accent, FullTvChrome.controlShape),
    ) {
        Icon(image, contentDescription = description, tint = tint ?: palette.text)
    }
}

private val previewTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTime(utcMillis: Long): String = previewTimeFormat.format(Date(utcMillis))
