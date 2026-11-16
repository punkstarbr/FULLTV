/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared geometry for the refreshed TV-first surfaces. */
object FullTvChrome {
    val pageInset = 24.dp
    val compactPageInset = 16.dp
    val sectionGap = 22.dp
    val cardGap = 12.dp
    val cardShape: Shape = RoundedCornerShape(16.dp)
    val controlShape: Shape = RoundedCornerShape(12.dp)
    val focusWidth = 2.dp
}

/** A consistent section heading with optional supporting copy and trailing actions. */
@Composable
fun FullTvSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailingContent()
    }
}

/** Shared card surface; callers own all actions and state to avoid changing screen behavior. */
@Composable
fun FullTvCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shape: Shape = FullTvChrome.cardShape,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.clip(shape),
        color = containerColor,
        contentColor = contentColor,
        shape = shape,
        tonalElevation = 0.dp,
    ) {
        Column(content = content)
    }
}

/** High-contrast focus ring shared by remote, keyboard and touch navigation. */
fun Modifier.fullTvFocusRing(
    focused: Boolean,
    color: Color,
    shape: Shape = FullTvChrome.controlShape,
    width: Dp = FullTvChrome.focusWidth,
): Modifier = if (focused) border(width, color, shape) else this

/** Convenient gap for rows whose children are focusable controls. */
@Composable
fun FullTvCardGap() {
    Spacer(Modifier.width(FullTvChrome.cardGap))
}
