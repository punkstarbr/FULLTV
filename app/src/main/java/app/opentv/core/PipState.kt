/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared picture-in-picture state, bridging the Compose player and the host Activity.
 *
 * The Activity owns PiP (only it can call enterPictureInPictureMode and hear the mode change), but
 * the player screen is what knows whether a video is actually on. These flags let the Activity
 * pause full-screen playback when the user leaves, while preserving an explicitly opened PiP.
 */
object PipState {
    /** True only while the full-screen player is active and can receive leave-app actions. */
    @Volatile var eligible: Boolean = false

    /** Whether playback is currently going — only active playback needs a leave-app action. */
    @Volatile var isPlaying: Boolean = false

    @Volatile private var pauseOnLeaveHandler: (() -> Unit)? = null

    fun setPauseOnLeaveHandler(handler: (() -> Unit)?) {
        pauseOnLeaveHandler = handler
    }

    fun requestPauseOnLeave() {
        pauseOnLeaveHandler?.invoke()
    }

    /** Set by onPictureInPictureModeChanged; the player hides its chrome on it. */
    private val _inPip = MutableStateFlow(false)
    val inPip: StateFlow<Boolean> = _inPip.asStateFlow()

    fun setInPip(value: Boolean) {
        _inPip.value = value
    }
}
