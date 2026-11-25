/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.opentv.R

/** Shared fail-closed PIN gate for actions that reveal or play protected live content. */
@Composable
fun ParentalPinGate(
    onCancel: () -> Unit,
    onSubmit: (String) -> Boolean,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var submitFocused by remember { mutableStateOf(false) }
    var cancelFocused by remember { mutableStateOf(false) }
    val firstDigit = remember { FocusRequester() }

    BackHandler { onCancel() }
    LaunchedEffect(firstDigit) { runCatching { firstDigit.requestFocus() } }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.parental_enter_pin), style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(
                value = pin,
                onValueChange = { },
                readOnly = true,
                label = { Text(stringResource(R.string.parental_pin)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.focusProperties { canFocus = false },
            )
            if (error) {
                Text(stringResource(R.string.parental_wrong_pin), color = MaterialTheme.colorScheme.error)
            }
            listOf("123", "456", "789").forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEachIndexed { columnIndex, digit ->
                        PinKey(
                            label = digit.toString(),
                            onClick = { if (pin.length < 4) { pin += digit; error = false } },
                            focusRequester = if (rowIndex == 0 && columnIndex == 0) firstDigit else null,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PinKey(
                    label = stringResource(R.string.parental_pin_backspace),
                    visibleLabel = "⌫",
                    onClick = { pin = pin.dropLast(1); error = false },
                )
                PinKey(
                    label = "0",
                    onClick = { if (pin.length < 4) { pin += "0"; error = false } },
                )
                PinKey(
                    label = stringResource(R.string.parental_pin_clear),
                    visibleLabel = "C",
                    onClick = { pin = ""; error = false },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = pin.length == 4,
                    modifier = Modifier
                        .onFocusChanged { submitFocused = it.isFocused }
                        .fullTvFocusRing(submitFocused, MaterialTheme.colorScheme.primary),
                    onClick = {
                        if (onSubmit(pin)) {
                            pin = ""
                            error = false
                        } else {
                            pin = ""
                            error = true
                        }
                    },
                ) { Text(stringResource(R.string.parental_unlock)) }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .onFocusChanged { cancelFocused = it.isFocused }
                        .fullTvFocusRing(cancelFocused, MaterialTheme.colorScheme.primary),
                ) { Text(stringResource(R.string.common_back)) }
            }
        }
    }
}

@Composable
private fun PinKey(
    label: String,
    onClick: () -> Unit,
    visibleLabel: String = label,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember(label) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 48.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .fullTvFocusRing(focused, MaterialTheme.colorScheme.primary, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            visibleLabel,
            style = MaterialTheme.typography.titleLarge,
            color = if (focused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}
