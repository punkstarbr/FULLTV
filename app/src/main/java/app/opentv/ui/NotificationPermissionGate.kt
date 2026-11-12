/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.opentv.R
import app.opentv.core.NotificationPermissionPolicy

/** The user-facing feature for which notification access is being requested. */
enum class NotificationPermissionPurpose {
    RECORDING,
    REMINDER,
}

/**
 * Remembers the permission launcher and owns the explanation/settings dialogs. Calling
 * [NotificationPermissionGate.requireNotifications] is the only way to show either prompt, so
 * composing HomeScreen or PlayerScreen never requests notification access by itself.
 */
@Composable
fun rememberNotificationPermissionGate(): NotificationPermissionGate {
    val context = LocalContext.current
    val currentContext by rememberUpdatedState(context)
    val gate = remember { NotificationPermissionGate() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        gate.onRuntimePermissionResult(currentContext, granted)
    }
    gate.installPermissionLauncher {
        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, gate) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) gate.onSettingsReturned(currentContext)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    NotificationPermissionGateDialogs(gate, context)
    return gate
}

/**
 * Per-screen notification access flow. The supplied callbacks are retained only while the user is
 * deciding what to do with an explicit feature action; they are cleared before invocation.
 */
class NotificationPermissionGate internal constructor() {
    private data class PendingAction(
        val purpose: NotificationPermissionPurpose,
        val onAvailable: () -> Unit,
        val onUnavailable: () -> Unit,
    )

    internal sealed interface DialogState {
        val purpose: NotificationPermissionPurpose

        data class Explanation(override val purpose: NotificationPermissionPurpose) : DialogState
        data class AppNotificationsDisabled(override val purpose: NotificationPermissionPurpose) : DialogState
    }

    private var pendingAction: PendingAction? = null
    private var permissionLauncher: (() -> Unit)? = null
    private var awaitingSettingsReturn = false

    internal var dialogState by mutableStateOf<DialogState?>(null)
        private set

    internal fun installPermissionLauncher(launch: () -> Unit) {
        permissionLauncher = launch
    }

    /** Check access and, if needed, explain before requesting the system permission. */
    fun requireNotifications(
        context: Context,
        purpose: NotificationPermissionPurpose,
        onAvailable: () -> Unit,
        onUnavailable: () -> Unit,
    ) {
        // A modal decision already owns the next action; do not replace its callback if an input
        // device repeats the button event while the permission dialog is being composed.
        if (pendingAction != null) return

        when (access(context)) {
            NotificationPermissionPolicy.Access.AVAILABLE -> onAvailable()
            NotificationPermissionPolicy.Access.REQUEST_RUNTIME_PERMISSION -> {
                pendingAction = PendingAction(purpose, onAvailable, onUnavailable)
                dialogState = DialogState.Explanation(purpose)
            }
            NotificationPermissionPolicy.Access.APP_NOTIFICATIONS_DISABLED -> {
                pendingAction = PendingAction(purpose, onAvailable, onUnavailable)
                dialogState = DialogState.AppNotificationsDisabled(purpose)
            }
        }
    }

    internal fun requestRuntimePermission() {
        if (pendingAction == null) return
        dialogState = null
        val launch = permissionLauncher
        if (launch == null) {
            finishUnavailable()
        } else {
            runCatching { launch() }.onFailure { finishUnavailable() }
        }
    }

    internal fun defer() {
        finishUnavailable()
    }

    internal fun onRuntimePermissionResult(context: Context, granted: Boolean) {
        if (!granted) {
            finishUnavailable()
            return
        }
        when (access(context, runtimePermissionGrantedOverride = true)) {
            NotificationPermissionPolicy.Access.AVAILABLE -> finishAvailable()
            NotificationPermissionPolicy.Access.APP_NOTIFICATIONS_DISABLED -> {
                pendingAction?.let { dialogState = DialogState.AppNotificationsDisabled(it.purpose) }
                    ?: run { dialogState = null }
            }
            // The launcher reported a grant, so this should not occur. Fail closed for reminders;
            // the recording caller still starts capture from its unavailable callback.
            NotificationPermissionPolicy.Access.REQUEST_RUNTIME_PERMISSION -> finishUnavailable()
        }
    }

    internal fun openAppNotificationSettings(context: Context) {
        val action = pendingAction ?: return
        if (action.purpose == NotificationPermissionPurpose.RECORDING) {
            // Notification access is optional for capture. Starting the capture before leaving for
            // Settings guarantees that navigating away cannot turn a denied prompt into a cancel.
            finishUnavailable()
            if (!launchAppNotificationSettings(context)) return
        } else {
            dialogState = null
            awaitingSettingsReturn = true
            if (!launchAppNotificationSettings(context)) {
                awaitingSettingsReturn = false
                finishUnavailable()
            }
        }
    }

    internal fun onSettingsReturned(context: Context) {
        if (!awaitingSettingsReturn) return
        awaitingSettingsReturn = false
        if (access(context) == NotificationPermissionPolicy.Access.AVAILABLE) {
            finishAvailable()
        } else {
            finishUnavailable()
        }
    }

    private fun finishAvailable() {
        val action = pendingAction ?: return
        clearPending()
        action.onAvailable()
    }

    private fun finishUnavailable() {
        val action = pendingAction ?: return
        clearPending()
        action.onUnavailable()
    }

    private fun clearPending() {
        pendingAction = null
        dialogState = null
        awaitingSettingsReturn = false
    }

    private fun access(
        context: Context,
        runtimePermissionGrantedOverride: Boolean? = null,
    ): NotificationPermissionPolicy.Access {
        val runtimePermissionGranted = runtimePermissionGrantedOverride ?: (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            )
        return NotificationPermissionPolicy.evaluate(
            apiLevel = Build.VERSION.SDK_INT,
            runtimePermissionGranted = runtimePermissionGranted,
            appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
    }

    private fun launchAppNotificationSettings(context: Context): Boolean = runCatching {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                Settings.EXTRA_APP_PACKAGE,
                context.packageName,
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                Uri.parse("package:${context.packageName}"),
            )
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess
}

@Composable
private fun NotificationPermissionGateDialogs(
    gate: NotificationPermissionGate,
    context: Context,
) {
    when (val state = gate.dialogState) {
        is NotificationPermissionGate.DialogState.Explanation -> {
            val recording = state.purpose == NotificationPermissionPurpose.RECORDING
            AlertDialog(
                onDismissRequest = gate::defer,
                title = { Text(stringResource(R.string.notification_access_title)) },
                text = {
                    Column {
                        Text(
                            stringResource(
                                if (recording) R.string.notification_access_recording_explanation
                                else R.string.notification_access_reminder_explanation,
                            ),
                        )
                        TextButton(onClick = { gate.openAppNotificationSettings(context) }) {
                            Text(stringResource(R.string.notification_access_open_settings))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = gate::requestRuntimePermission) {
                        Text(stringResource(R.string.notification_access_allow))
                    }
                },
                dismissButton = {
                    TextButton(onClick = gate::defer) {
                        Text(
                            stringResource(
                                if (recording) R.string.notification_access_record_without
                                else R.string.notification_access_not_now,
                            ),
                        )
                    }
                },
            )
        }
        is NotificationPermissionGate.DialogState.AppNotificationsDisabled -> {
            val recording = state.purpose == NotificationPermissionPurpose.RECORDING
            AlertDialog(
                onDismissRequest = gate::defer,
                title = { Text(stringResource(R.string.notification_access_disabled_title)) },
                text = {
                    Text(
                        stringResource(
                            if (recording) R.string.notification_access_disabled_recording
                            else R.string.notification_access_disabled_reminder,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { gate.openAppNotificationSettings(context) }) {
                        Text(stringResource(R.string.notification_access_open_settings))
                    }
                },
                dismissButton = {
                    TextButton(onClick = gate::defer) {
                        Text(
                            stringResource(
                                if (recording) R.string.notification_access_record_without
                                else R.string.notification_access_not_now,
                            ),
                        )
                    }
                },
            )
        }
        null -> Unit
    }
}
