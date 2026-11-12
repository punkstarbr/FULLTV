/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationPermissionPolicyTest {
    @Test
    fun `API 32 ignores runtime permission and uses app notification setting`() {
        assertThat(
            NotificationPermissionPolicy.evaluate(
                apiLevel = 32,
                runtimePermissionGranted = false,
                appNotificationsEnabled = true,
            ),
        ).isEqualTo(NotificationPermissionPolicy.Access.AVAILABLE)
    }

    @Test
    fun `API 32 reports app-level notifications disabled`() {
        assertThat(
            NotificationPermissionPolicy.evaluate(
                apiLevel = 32,
                runtimePermissionGranted = true,
                appNotificationsEnabled = false,
            ),
        ).isEqualTo(NotificationPermissionPolicy.Access.APP_NOTIFICATIONS_DISABLED)
    }

    @Test
    fun `API 33 denied permission requests access only when explicitly needed`() {
        assertThat(
            NotificationPermissionPolicy.evaluate(
                apiLevel = 33,
                runtimePermissionGranted = false,
                appNotificationsEnabled = true,
            ),
        ).isEqualTo(NotificationPermissionPolicy.Access.REQUEST_RUNTIME_PERMISSION)
    }

    @Test
    fun `API 33 granted permission allows notifications when app setting is enabled`() {
        assertThat(
            NotificationPermissionPolicy.evaluate(
                apiLevel = 33,
                runtimePermissionGranted = true,
                appNotificationsEnabled = true,
            ),
        ).isEqualTo(NotificationPermissionPolicy.Access.AVAILABLE)
    }

    @Test
    fun `granted runtime permission still detects app-level notifications disabled`() {
        assertThat(
            NotificationPermissionPolicy.evaluate(
                apiLevel = 33,
                runtimePermissionGranted = true,
                appNotificationsEnabled = false,
            ),
        ).isEqualTo(NotificationPermissionPolicy.Access.APP_NOTIFICATIONS_DISABLED)
    }
}
