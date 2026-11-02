/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** Temporarily sets FLAG_SECURE for a screen that displays one-time or bearer secrets. */
@Composable
fun ProtectSensitiveScreen() {
    val window = LocalContext.current.findActivity()?.window
    DisposableEffect(window) {
        if (window == null) return@DisposableEffect onDispose { }
        val wasSecure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!wasSecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    repeat(16) {
        when (val context = current) {
            is Activity -> return context
            is ContextWrapper -> {
                if (context.baseContext === context) return null
                current = context.baseContext
            }
            else -> return null
        }
    }
    return null
}
