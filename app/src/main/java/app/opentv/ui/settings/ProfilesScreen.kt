/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.ui.ProfilesViewModel
import app.opentv.ui.FullTvCard
import app.opentv.ui.FullTvChrome
import app.opentv.ui.FullTvSectionHeader
import app.opentv.ui.fullTvFocusRing
import androidx.compose.ui.ExperimentalComposeUiApi
import app.opentv.ui.channels.OnScreenKeyboard

/**
 * Who's watching. Add, rename, remove and switch between local profiles; the active one decides
 * whose continue-watching and watched state is in play across the app.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ProfilesScreen(
    onBack: () -> Unit,
    viewModel: ProfilesViewModel = viewModel(),
) {
    val profiles by viewModel.profiles.collectAsState()
    val activeId by viewModel.activeProfileId.collectAsState()

    // null = list; NEW = adding; a positive id = renaming that profile.
    var editing by remember { mutableStateOf<Long?>(null) }
    BackHandler {
        if (editing != null) editing = null else onBack()
    }

    editing?.let { target ->
        NameEntry(
            initial = if (target == NEW) "" else profiles.firstOrNull { it.id == target }?.name.orEmpty(),
            heading = if (target == NEW) stringResource(R.string.profiles_new_heading) else stringResource(R.string.profiles_rename_heading),
            onCancel = { editing = null },
            onSave = { name ->
                if (target == NEW) viewModel.addProfile(name) else viewModel.rename(target, name)
                editing = null
            },
        )
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageInset = if (maxWidth < 600.dp) FullTvChrome.compactPageInset else FullTvChrome.pageInset
        val compactLayout = maxWidth < 600.dp
        Column(
            Modifier
                .fillMaxSize()
                .focusRestorer()
                .focusGroup()
                .padding(horizontal = pageInset, vertical = FullTvChrome.pageInset),
        ) {
        FullTvSectionHeader(
            title = stringResource(R.string.profiles_title),
            trailingContent = {
                ProfileActionButton(stringResource(R.string.profiles_add), onClick = { editing = NEW })
                ProfileActionButton(stringResource(R.string.common_done), onClick = onBack, outlined = true)
            },
        )

        Spacer(Modifier.height(FullTvChrome.sectionGap))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(FullTvChrome.cardGap),
            modifier = Modifier.widthIn(max = 760.dp).focusRestorer().focusGroup(),
        ) {
            items(profiles, key = { it.id }) { profile ->
                val active = profile.id == activeId
                FullTvCard(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            if (active) {
                                Text(
                                    stringResource(R.string.profiles_active),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (compactLayout) {
                            Column(horizontalAlignment = Alignment.End) {
                                if (!active) ProfileTextAction(stringResource(R.string.profiles_use)) { viewModel.select(profile.id) }
                                ProfileTextAction(stringResource(R.string.profiles_rename)) { editing = profile.id }
                                if (profile.id != 1L) ProfileTextAction(stringResource(R.string.common_remove)) { viewModel.remove(profile.id) }
                            }
                        } else {
                            if (!active) ProfileTextAction(stringResource(R.string.profiles_use)) { viewModel.select(profile.id) }
                            ProfileTextAction(stringResource(R.string.profiles_rename)) { editing = profile.id }
                            if (profile.id != 1L) ProfileTextAction(stringResource(R.string.common_remove)) { viewModel.remove(profile.id) }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun ProfileActionButton(
    label: String,
    onClick: () -> Unit,
    outlined: Boolean = false,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val modifier = Modifier
        .onFocusChanged { focused = it.isFocused }
        .fullTvFocusRing(focused, MaterialTheme.colorScheme.primary)
    if (outlined) {
        OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) { Text(label) }
    } else {
        Button(onClick = onClick, modifier = modifier, enabled = enabled) { Text(label) }
    }
}

@Composable
private fun ProfileTextAction(label: String, onClick: () -> Unit) {
    var focused by remember(label) { mutableStateOf(false) }
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .fullTvFocusRing(focused, MaterialTheme.colorScheme.primary),
    ) { Text(label) }
}

@Composable
private fun NameEntry(
    initial: String,
    heading: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(heading, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(20.dp))
            Text(
                name.ifEmpty { "…" },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            ProfileActionButton(
                label = stringResource(R.string.common_save),
                onClick = { onSave(name) },
                enabled = name.isNotBlank(),
            )
            Spacer(Modifier.width(12.dp))
            ProfileActionButton(
                label = stringResource(R.string.common_cancel),
                onClick = onCancel,
                outlined = true,
            )
        }
        Spacer(Modifier.height(24.dp))
        OnScreenKeyboard(
            onKey = { if (name.length < 24) name += it },
            onSpace = { if (name.length < 24) name += " " },
            onBackspace = { name = name.dropLast(1) },
            onClear = { name = "" },
        )
    }
}

private const val NEW = -1L
