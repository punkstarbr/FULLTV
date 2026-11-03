/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * The [Activity] behind a Compose [Context], unwrapping the `ContextWrapper` chain Compose hands
 * you. Needed for the handful of things that are genuinely window-level — like holding the screen
 * awake during playback — where a view flag isn't enough on every TV box.
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Whether the OS is currently exempting OpenTV from battery optimisation ("Doze"). When it isn't,
 * Android TV / Fire TV are free to freeze the app while the box is in standby, which stops an
 * in-progress or scheduled recording dead — the single biggest reason captures don't survive the
 * device going to sleep. Pre-Marshmallow there is no Doze, so it is always treated as exempt.
 */
fun Context.isIgnoringBatteryOptimizations(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(packageName)
}

/**
 * Opens a system screen where the user can allow background execution. Fire OS builds vary: some
 * expose the direct request dialog, some only expose the global battery list or app details, and
 * some expose none. Return false when every route is unavailable so the UI can explain the limit.
 */
@SuppressLint("BatteryLife")
fun Context.requestIgnoreBatteryOptimizations(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isIgnoringBatteryOptimizations()) return true

    val packageUri = Uri.parse("package:$packageName")
    val candidates = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
    )
    for (intent in candidates) {
        val launched = runCatching {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
        if (launched) return true
    }
    return false
}
