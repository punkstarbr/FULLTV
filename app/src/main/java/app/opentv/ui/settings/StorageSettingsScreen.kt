/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.content.Context
import android.os.StatFs
import android.text.format.Formatter
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.EpgRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "StorageSettings"
private const val MIN_VACUUM_HEADROOM_BYTES = 64L * 1024 * 1024

@Composable
fun StorageSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val graph = remember(context) { ServiceLocator.get(context) }
    val scope = rememberCoroutineScope()
    var reloadKey by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<StorageSnapshot?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var showOptimizeConfirm by remember { mutableStateOf(false) }
    var showRemoveModelConfirm by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        snapshot = withContext(Dispatchers.IO) { measureStorage(context) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }
        Text(
            stringResource(R.string.storage_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val current = snapshot
        if (current == null) {
            CircularProgressIndicator()
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StorageMetric(context, R.string.storage_apk_files, current.apkBytes)
                    StorageMetric(context, R.string.storage_native_libraries, current.nativeLibraryBytes)
                    StorageMetric(context, R.string.storage_app_data, current.appDataBytes)
                    StorageMetric(context, R.string.storage_database, current.databaseBytes)
                    StorageMetric(
                        context,
                        context.getString(R.string.storage_internal_recordings, current.recordingCount),
                        current.recordingBytes,
                    )
                    StorageMetric(context, R.string.storage_whisper_model, current.whisperModelBytes)
                    StorageMetric(context, R.string.storage_image_cache, current.imageCacheBytes)
                    StorageMetric(context, R.string.storage_free_space, current.freeBytes)
                }
            }
            Text(
                stringResource(R.string.storage_epg_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.storage_recordings_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.storage_translation_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = { reloadKey++ },
                enabled = !isWorking,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.storage_refresh)) }

            Button(
                onClick = { showOptimizeConfirm = true },
                enabled = !isWorking && current.databaseBytes > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isWorking) stringResource(R.string.storage_working) else stringResource(R.string.storage_optimize_database))
            }

            OutlinedButton(
                onClick = { showRemoveModelConfirm = true },
                enabled = !isWorking && current.whisperModelBytes > 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.storage_remove_whisper_model)) }
        }

        if (statusMessage != 0) {
            Text(stringResource(statusMessage), color = MaterialTheme.colorScheme.primary)
        }
    }

    if (showOptimizeConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isWorking) showOptimizeConfirm = false },
            title = { Text(stringResource(R.string.storage_optimize_confirm_title)) },
            text = { Text(stringResource(R.string.storage_optimize_confirm_body)) },
            confirmButton = {
                TextButton(
                    enabled = !isWorking,
                    onClick = {
                        showOptimizeConfirm = false
                        isWorking = true
                        statusMessage = 0
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { compactGuideDatabase(context, graph) }
                            statusMessage = when (result) {
                                OptimizeResult.SUCCESS -> R.string.storage_optimize_success
                                OptimizeResult.NOT_ENOUGH_SPACE -> R.string.storage_not_enough_space
                                OptimizeResult.FAILED -> R.string.storage_optimize_failed
                            }
                            isWorking = false
                            reloadKey++
                        }
                    },
                ) { Text(stringResource(R.string.storage_optimize_database)) }
            },
            dismissButton = {
                TextButton(onClick = { showOptimizeConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showRemoveModelConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isWorking) showRemoveModelConfirm = false },
            title = { Text(stringResource(R.string.storage_remove_model_confirm_title)) },
            text = { Text(stringResource(R.string.storage_remove_model_confirm_body)) },
            confirmButton = {
                TextButton(
                    enabled = !isWorking,
                    onClick = {
                        showRemoveModelConfirm = false
                        isWorking = true
                        scope.launch {
                            val removed = withContext(Dispatchers.IO) {
                                runCatching {
                                    File(context.filesDir, "ai-models/whisper").deleteRecursively()
                                }.getOrDefault(false)
                            }
                            statusMessage = if (removed) R.string.storage_model_removed else R.string.storage_model_not_found
                            isWorking = false
                            reloadKey++
                        }
                    },
                ) { Text(stringResource(R.string.common_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveModelConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun StorageMetric(context: Context, labelId: Int, bytes: Long) =
    StorageMetric(context, stringResource(labelId), bytes)

@Composable
private fun StorageMetric(context: Context, label: String, bytes: Long) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(Formatter.formatFileSize(context, bytes.coerceAtLeast(0L)), style = MaterialTheme.typography.titleMedium)
    }
}

private data class StorageSnapshot(
    val apkBytes: Long,
    val nativeLibraryBytes: Long,
    val appDataBytes: Long,
    val databaseBytes: Long,
    val recordingBytes: Long,
    val recordingCount: Int,
    val whisperModelBytes: Long,
    val imageCacheBytes: Long,
    val freeBytes: Long,
)

private fun measureStorage(context: Context): StorageSnapshot {
    val appInfo = context.applicationInfo
    val apkPaths = listOfNotNull(appInfo.sourceDir) + (appInfo.splitSourceDirs?.toList() ?: emptyList())
    val apkBytes = apkPaths.sumOf { File(it).length() }
    val databaseFile = context.getDatabasePath("opentv.db")
    val databaseBytes = listOf(databaseFile, File(databaseFile.path + "-wal"), File(databaseFile.path + "-shm"))
        .sumOf { if (it.isFile) it.length() else 0L }
    val recordingDir = context.getExternalFilesDir(null)?.resolve("recordings")
    val recordingUsage = recordingDir?.let(::directoryUsage) ?: FileUsage()
    val whisperDir = File(context.filesDir, "ai-models/whisper")
    val imageCacheDir = File(context.cacheDir, "image_cache")
    val externalFiles = context.getExternalFilesDir(null)
    val appDataBytes = directorySize(context.dataDir) + (externalFiles?.let(::directorySize) ?: 0L)
    val nativeLibraryBytes = appInfo.nativeLibraryDir?.let { directorySize(File(it)) } ?: 0L
    val freeBytes = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)

    return StorageSnapshot(
        apkBytes = apkBytes,
        nativeLibraryBytes = nativeLibraryBytes,
        appDataBytes = appDataBytes,
        databaseBytes = databaseBytes,
        recordingBytes = recordingUsage.bytes,
        recordingCount = recordingUsage.fileCount,
        whisperModelBytes = directorySize(whisperDir),
        imageCacheBytes = directorySize(imageCacheDir),
        freeBytes = freeBytes,
    )
}

private data class FileUsage(val bytes: Long = 0L, val fileCount: Int = 0)

private fun directorySize(root: File): Long = directoryUsage(root).bytes

private fun directoryUsage(root: File): FileUsage {
    if (root.isFile) return FileUsage(root.length(), 1)
    if (!root.exists()) return FileUsage()
    var bytes = 0L
    var count = 0
    root.walkTopDown().forEach { file ->
        if (file.isFile) {
            bytes += file.length()
            count++
        }
    }
    return FileUsage(bytes, count)
}

private enum class OptimizeResult { SUCCESS, NOT_ENOUGH_SPACE, FAILED }

private suspend fun compactGuideDatabase(context: Context, graph: app.opentv.core.ServiceLocator.Graph): OptimizeResult {
    val databaseFile = context.getDatabasePath("opentv.db")
    val databaseBytes = listOf(databaseFile, File(databaseFile.path + "-wal"), File(databaseFile.path + "-shm"))
        .sumOf { if (it.isFile) it.length() else 0L }
    val requiredHeadroom = (databaseBytes * 2L).coerceAtLeast(MIN_VACUUM_HEADROOM_BYTES)
    val available = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
    if (available < requiredHeadroom) return OptimizeResult.NOT_ENOUGH_SPACE

    return try {
        val now = System.currentTimeMillis()
        val future = graph.epgRepository.retentionFutureMillis
        graph.database.programmes().deleteOutsideWindow(
            now - EpgRepository.RETENTION_PAST_MILLIS,
            now + future,
        )
        val db = graph.database.openHelper.writableDatabase
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
        db.execSQL("VACUUM")
        OptimizeResult.SUCCESS
    } catch (error: Exception) {
        Log.e(TAG, "Manual EPG database compaction failed", error)
        OptimizeResult.FAILED
    }
}
