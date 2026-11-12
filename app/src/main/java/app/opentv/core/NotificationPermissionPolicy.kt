/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/** Pure policy for deciding whether the app can deliver notifications on this Android version. */
object NotificationPermissionPolicy {
    enum class Access {
        AVAILABLE,
        REQUEST_RUNTIME_PERMISSION,
        APP_NOTIFICATIONS_DISABLED,
    }

    /**
     * API 33 introduced POST_NOTIFICATIONS. On earlier releases the runtime grant is irrelevant,
     * but app-level notification settings still control whether notifications can be delivered.
     */
    fun evaluate(
        apiLevel: Int,
        runtimePermissionGranted: Boolean,
        appNotificationsEnabled: Boolean,
    ): Access = when {
        apiLevel >= 33 && !runtimePermissionGranted -> Access.REQUEST_RUNTIME_PERMISSION
        !appNotificationsEnabled -> Access.APP_NOTIFICATIONS_DISABLED
        else -> Access.AVAILABLE
    }
}
