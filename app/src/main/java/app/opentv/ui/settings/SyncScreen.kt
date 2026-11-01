/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.backup.BackupTransfer
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.sync.SyncServer
import app.opentv.ui.SyncViewModel
import kotlinx.coroutines.launch

/**
 * Local watch-history sync between two OpenTV devices on the same wifi. No servers of ours: one
 * device shares behind a code, the other receives. Only continue-watching positions travel.
 */
@Composable
fun SyncScreen(
    onBack: () -> Unit,
    viewModel: SyncViewModel = viewModel(),
) {
    var mode by remember { mutableStateOf(Mode.SHARE) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_sync_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.sync_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 720.dp),
        )

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeChip(stringResource(R.string.sync_share_from_here), mode == Mode.SHARE) { mode = Mode.SHARE }
            ModeChip(stringResource(R.string.sync_receive), mode == Mode.RECEIVE) { mode = Mode.RECEIVE }
            ModeChip(stringResource(R.string.sync_nas), mode == Mode.NAS) { mode = Mode.NAS }
            ModeChip(stringResource(R.string.sync_backup_title), mode == Mode.BACKUP) { mode = Mode.BACKUP }
        }
        Spacer(Modifier.height(20.dp))

        when (mode) {
            Mode.SHARE -> SharePane(viewModel)
            Mode.RECEIVE -> ReceivePane(viewModel)
            Mode.NAS -> NasPane(viewModel)
            Mode.BACKUP -> BackupPane()
        }
    }
}

private enum class Mode { SHARE, RECEIVE, NAS, BACKUP }

@Composable
private fun BackupPane() {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val transfer = remember { BackupTransfer(context.applicationContext, graph) }
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                busy = true
                status = context.getString(R.string.sync_backup_exporting)
                try {
                    transfer.export(uri, passphrase)
                    status = context.getString(R.string.sync_backup_exported)
                    passphrase = ""
                } catch (_: Exception) {
                    status = context.getString(R.string.sync_backup_failed)
                } finally {
                    busy = false
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                busy = true
                status = context.getString(R.string.sync_backup_restoring)
                try {
                    val result = transfer.restore(uri, passphrase)
                    status = if (result.syncFailures == 0) {
                        context.getString(
                            R.string.sync_backup_restored,
                            result.sourcesImported,
                            result.mergedItems,
                        )
                    } else {
                        context.getString(
                            R.string.sync_backup_restored_warnings,
                            result.sourcesImported,
                            result.syncFailures,
                            result.mergedItems,
                        )
                    }
                    passphrase = ""
                } catch (_: Exception) {
                    status = context.getString(R.string.sync_backup_failed)
                } finally {
                    busy = false
                }
            }
        }
    }

    Card {
        Column(Modifier.padding(20.dp).widthIn(max = 680.dp)) {
            Text(stringResource(R.string.sync_backup_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.sync_backup_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it; status = null },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(stringResource(R.string.sync_backup_passphrase)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !busy && passphrase.length >= 8,
                    onClick = { exportLauncher.launch("fulltv-backup.fulltv") },
                ) { Text(stringResource(R.string.sync_backup_export)) }
                OutlinedButton(
                    enabled = !busy && passphrase.length >= 8,
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                ) { Text(stringResource(R.string.sync_backup_import)) }
            }
            status?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (busy) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SharePane(viewModel: SyncViewModel) {
    val state by viewModel.serverState.collectAsState()
    Card {
        Column(Modifier.padding(20.dp).widthIn(max = 640.dp)) {
            when (val s = state) {
                is SyncServer.State.Sharing -> {
                    Text(stringResource(R.string.sync_ready_to_share), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.sync_on_other_device))
                    Spacer(Modifier.height(12.dp))
                    Field(stringResource(R.string.sync_address), s.session.address)
                    Spacer(Modifier.height(8.dp))
                    Field(stringResource(R.string.sync_code), s.session.code)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.sync_type_in_one_go, s.session.address, s.session.code),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { viewModel.stopSharing() }) { Text(stringResource(R.string.sync_stop_sharing)) }
                }
                is SyncServer.State.Failed -> {
                    Text(s.reason, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.startSharing() }) { Text(stringResource(R.string.common_try_again)) }
                }
                else -> {
                    Text(stringResource(R.string.sync_will_share))
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { viewModel.startSharing() }) { Text(stringResource(R.string.sync_start_sharing)) }
                }
            }
        }
    }
}

@Composable
private fun ReceivePane(viewModel: SyncViewModel) {
    val state by viewModel.receiveState.collectAsState()
    var entry by remember { mutableStateOf("") }

    Row {
        Card {
            Column(Modifier.padding(20.dp).widthIn(max = 520.dp)) {
                Text(stringResource(R.string.sync_type_what_shows), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    entry.ifEmpty { "address:port#code" },
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (entry.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                NumPad(
                    onKey = { if (entry.length < 40) entry += it },
                    onBackspace = { entry = entry.dropLast(1) },
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = entry.contains('#') && state !is SyncViewModel.ReceiveState.Connecting,
                        onClick = {
                            val address = entry.substringBefore('#')
                            val code = entry.substringAfter('#')
                            viewModel.receive(address, code)
                        },
                    ) { Text(stringResource(R.string.sync_connect)) }

                    when (val s = state) {
                        is SyncViewModel.ReceiveState.Connecting -> Text(stringResource(R.string.sync_connecting))
                        is SyncViewModel.ReceiveState.Done -> Text(
                            stringResource(R.string.sync_synced_items, s.merged),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        is SyncViewModel.ReceiveState.Failed -> Text(
                            s.message,
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> Unit
                    }
                }
            }
        }
    }
}

/**
 * Free, server-less "cloud" sync through the user's own NAS: every device drops a small JSON file
 * into a shared folder on the SMB share and reads the others'. No server, no account. Requires the
 * NAS to be set up under Recording settings first.
 */
@Composable
private fun NasPane(viewModel: SyncViewModel) {
    val nasState by viewModel.nasState.collectAsState()
    val autoSync by viewModel.nasAutoSync.collectAsState()
    val smbHost by viewModel.smbHost.collectAsState()
    val configured = smbHost.isNotBlank()

    Card {
        Column(Modifier.padding(20.dp).widthIn(max = 640.dp)) {
            Text(stringResource(R.string.sync_nas_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.sync_nas_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (!configured) {
                Text(
                    stringResource(R.string.sync_nas_needs_setup),
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        enabled = nasState !is SyncViewModel.NasState.Syncing,
                        onClick = { viewModel.syncNas() },
                    ) { Text(stringResource(R.string.sync_nas_sync_now)) }

                    when (val s = nasState) {
                        is SyncViewModel.NasState.Syncing ->
                            Text(stringResource(R.string.sync_nas_syncing))
                        is SyncViewModel.NasState.Done ->
                            Text(s.message, color = MaterialTheme.colorScheme.primary)
                        is SyncViewModel.NasState.Failed ->
                            Text(s.message, color = MaterialTheme.colorScheme.error)
                        else -> Unit
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.sync_nas_auto),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = autoSync, onCheckedChange = { viewModel.setNasAutoSync(it) })
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row {
        Text("$label:  ", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        color = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun NumPad(onKey: (String) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf("123", "456", "789", ".0:", "#⌫")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                line.forEach { ch ->
                    NumKey(ch.toString()) {
                        if (ch == '⌫') onBackspace() else onKey(ch.toString())
                    }
                }
            }
        }
    }
}

@Composable
private fun NumKey(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(54.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                else Modifier,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = fg)
    }
}
