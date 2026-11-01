/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.shownName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun PlayerTopInfoOverlay(
    modifier: Modifier,
    channel: Channel,
    current: Programme?,
    next: Programme?,
    clockMillis: Long,
    videoWidth: Int,
    videoHeight: Int,
    onOpenChannels: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenAudio: () -> Unit,
) {
    Column(
        modifier.fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.84f))
            .padding(horizontal = 26.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val channelNumber = channel.number?.let { "#$it · " }.orEmpty()
                Text(
                    channelNumber + channel.shownName,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    current?.title ?: stringResource(R.string.player_info_no_epg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.84f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                current?.let {
                    Text(
                        "${formatPlayerTime(it.startUtcMillis)}–${formatPlayerTime(it.endUtcMillis)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.68f),
                    )
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.player_info_next),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.68f),
                )
                Text(
                    next?.title ?: stringResource(R.string.player_info_no_epg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(24.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(clockMillis)),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.75f),
                )
                Text(
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(clockMillis)),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                val resolution = if (videoWidth > 0 && videoHeight > 0) {
                    stringResource(R.string.player_info_resolution, videoWidth, videoHeight)
                } else stringResource(R.string.player_info_resolution_unknown)
                Text(resolution, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onOpenChannels) {
                Text(stringResource(R.string.common_channels), color = Color.White)
            }
            TextButton(onClick = onOpenSubtitles) {
                Text(stringResource(R.string.player_subtitles), color = Color.White)
            }
            TextButton(onClick = onOpenAudio) {
                Text(stringResource(R.string.player_audio), color = Color.White)
            }
        }
    }
}

@Composable
internal fun PlayerBottomInfoOverlay(
    modifier: Modifier,
    channel: Channel,
    current: Programme?,
    next: Programme?,
    clockMillis: Long,
    videoWidth: Int,
    videoHeight: Int,
    sourceHost: String?,
    accent: Color,
    showProgress: Boolean,
) {
    Column(
        modifier.widthIn(max = 1_180.dp)
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.86f))
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val number = channel.number?.let { "#$it" } ?: stringResource(R.string.player_info_live)
            Text(number, color = accent, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    channel.shownName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    current?.title ?: stringResource(R.string.player_info_no_epg),
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(horizontalAlignment = Alignment.End) {
                val resolution = if (videoWidth > 0 && videoHeight > 0) {
                    stringResource(R.string.player_info_resolution, videoWidth, videoHeight)
                } else stringResource(R.string.player_info_resolution_unknown)
                Text(resolution, color = Color.White, style = MaterialTheme.typography.labelMedium)
                sourceHost?.let {
                    Text(it, color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (showProgress && current != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { current.progressAt(clockMillis) },
                modifier = Modifier.fillMaxWidth(),
                color = accent,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${stringResource(R.string.player_info_now)} ${formatPlayerTime(current.startUtcMillis)}–${formatPlayerTime(current.endUtcMillis)}",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.width(18.dp))
                Text(
                    "${stringResource(R.string.player_info_next)}: ${next?.title ?: stringResource(R.string.player_info_no_epg)}",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatPlayerTime(epochMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))
