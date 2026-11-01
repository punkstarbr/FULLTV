/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** Starts the APK-bundled Python runtime once, regardless of which feature reaches it first. */
object PythonRuntime {
    private val startLock = Any()

    fun get(context: Context): Python {
        if (!Python.isStarted()) {
            synchronized(startLock) {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context.applicationContext))
                }
            }
        }
        return Python.getInstance()
    }
}
