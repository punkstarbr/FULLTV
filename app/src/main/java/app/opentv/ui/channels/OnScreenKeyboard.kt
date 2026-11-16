/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.ui.FullTvChrome
import app.opentv.ui.fullTvFocusRing

/**
 * A d-pad-navigable on-screen keyboard, shared by every screen that needs text entry on a TV.
 * Android TV's system text field punts to "type on your phone"; this keeps entry on the remote.
 */
@kotlin.OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun OnScreenKeyboard(
    onKey: (String) -> Unit,
    onSpace: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val keyRows = listOf("1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
    val firstKey = remember { FocusRequester() }
    val context = LocalContext.current

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val keyGap = if (compact) 4.dp else 8.dp
        val maximumKeyWidth = if (compact) 56.dp else 72.dp
        val keyWidth = ((maxWidth - keyGap * 9) / 10f)
            .coerceAtMost(maximumKeyWidth)
            .coerceAtLeast(24.dp)
        val keyHeight = if (compact) 48.dp else 52.dp
        val rowGap = if (compact) 5.dp else 8.dp

        Column(
            Modifier
                .focusRestorer()
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(rowGap),
        ) {
            keyRows.forEachIndexed { rowIndex, line ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        keyGap,
                        Alignment.CenterHorizontally,
                    ),
                ) {
                    line.forEachIndexed { colIndex, ch ->
                        KeyCap(
                            label = ch.toString(),
                            width = keyWidth,
                            height = keyHeight,
                            focusRequester = if (rowIndex == 0 && colIndex == 0) firstKey else null,
                            onClick = { onKey(ch.toString()) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    keyGap,
                    Alignment.CenterHorizontally,
                ),
            ) {
                KeyCap(
                    label = stringResource(R.string.kbd_space),
                    width = keyWidth * 3 + keyGap * 2,
                    height = keyHeight,
                    onClick = onSpace,
                )
                // Paste uses the same callback as letter keys; an empty clipboard remains a no-op
                // apart from its existing localized notice.
                KeyCap(
                    label = stringResource(R.string.kbd_paste),
                    width = keyWidth * 2 + keyGap,
                    height = keyHeight,
                    onClick = {
                        val pasted = readClipboardText(context)
                        if (pasted != null) onKey(pasted)
                        else Toast.makeText(
                            context,
                            context.getString(R.string.kbd_clipboard_empty),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
                KeyCap(
                    label = stringResource(R.string.kbd_del),
                    width = keyWidth * 2 + keyGap,
                    height = keyHeight,
                    icon = true,
                    onClick = onBackspace,
                )
                KeyCap(
                    label = stringResource(R.string.kbd_clear),
                    width = keyWidth * 2 + keyGap,
                    height = keyHeight,
                    onClick = onClear,
                )
            }
        }
    }

    LaunchedEffect(firstKey) { runCatching { firstKey.requestFocus() } }
}

@Composable
private fun KeyCap(
    label: String,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    icon: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = FullTvChrome.controlShape
    val background = if (focused) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (focused) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface

    Box(
        Modifier
            .width(width)
            .height(height)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(background)
            .fullTvFocusRing(
                focused = focused,
                color = MaterialTheme.colorScheme.primary,
                shape = shape,
            )
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon) {
            Icon(Icons.Filled.Backspace, contentDescription = label, tint = foreground)
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = foreground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/**
 * Plain text currently on the system clipboard, trimmed — or null when there's nothing to paste.
 * The on-screen keyboard uses this form; provider credentials use [readClipboardTextRaw] so their
 * leading and trailing spaces are not silently changed.
 */
internal fun readClipboardText(context: Context): String? {
    return readClipboardTextRaw(context)?.trim()?.takeIf { it.isNotEmpty() }
}

/** Reads clipboard text without trimming; provider-password paste uses this to preserve spaces. */
internal fun readClipboardTextRaw(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0)?.coerceToText(context)?.toString()
}
