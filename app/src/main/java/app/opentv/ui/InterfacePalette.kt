/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import app.opentv.core.AppSettings

/** Color tokens for selectable Live TV/player interface presets. */
data class InterfacePalette(
    val screenBackground: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val selectedChannel: Color,
    val focusedBlock: Color,
    val currentProgramme: Color,
    val text: Color,
    val mutedText: Color,
    val onSelected: Color,
    val accent: Color,
    val progress: Color,
)

fun interfacePalette(
    style: AppSettings.InterfaceStyle,
    defaults: ColorScheme,
): InterfacePalette = when (style) {
    AppSettings.InterfaceStyle.CURRENT,
    AppSettings.InterfaceStyle.CATEGORY_PORTAL,
    AppSettings.InterfaceStyle.LINEAR_AUTOHIDE,
    -> InterfacePalette(
        screenBackground = defaults.background,
        surface = defaults.surface,
        surfaceVariant = defaults.surfaceVariant,
        selectedChannel = defaults.primaryContainer,
        focusedBlock = defaults.primary,
        currentProgramme = defaults.primaryContainer,
        text = defaults.onSurface,
        mutedText = defaults.onSurfaceVariant,
        onSelected = defaults.onPrimaryContainer,
        accent = defaults.primary,
        progress = defaults.primary,
    )
    AppSettings.InterfaceStyle.PLAYER_ZAPPING,
    AppSettings.InterfaceStyle.PLAYER_INFO_TOP,
    -> InterfacePalette(
        screenBackground = defaults.background,
        surface = defaults.surface,
        surfaceVariant = defaults.surfaceVariant,
        selectedChannel = defaults.primaryContainer,
        focusedBlock = defaults.primary,
        currentProgramme = defaults.primaryContainer,
        text = defaults.onSurface,
        mutedText = defaults.onSurfaceVariant,
        onSelected = defaults.onPrimaryContainer,
        accent = Color(0xFF00C8E8),
        progress = Color(0xFF00D7F5),
    )
    AppSettings.InterfaceStyle.CLASSIC_EPG -> InterfacePalette(
        screenBackground = Color(0xFF071B25),
        surface = Color(0xFF0C2631),
        surfaceVariant = Color(0xFF1A3540),
        selectedChannel = Color(0xFF203D47),
        focusedBlock = Color(0xFFB7E41D),
        currentProgramme = Color(0xFF2D4A54),
        text = Color(0xFFF3F6F7),
        mutedText = Color(0xFFACBDC2),
        onSelected = Color(0xFF102027),
        accent = Color(0xFFB7E41D),
        progress = Color(0xFFB7E41D),
    )
    AppSettings.InterfaceStyle.MODERN_EPG,
    AppSettings.InterfaceStyle.EPG_FULLSCREEN,
    -> InterfacePalette(
        screenBackground = Color(0xFF07111A),
        surface = Color(0xFF101D27),
        surfaceVariant = Color(0xFF1D2D38),
        selectedChannel = Color(0xFF10384A),
        focusedBlock = Color(0xFF087CA1),
        currentProgramme = Color(0xFF16485C),
        text = Color(0xFFF4F8FA),
        mutedText = Color(0xFFA6B5BF),
        onSelected = Color(0xFFF5FBFD),
        accent = Color(0xFF28C4E8),
        progress = Color(0xFF28C4E8),
    )
    AppSettings.InterfaceStyle.RETRO_EPG -> InterfacePalette(
        screenBackground = Color(0xFF070A2B),
        surface = Color(0xFF111943),
        surfaceVariant = Color(0xFF252F5B),
        selectedChannel = Color(0xFF293A73),
        focusedBlock = Color(0xFFFFD22E),
        currentProgramme = Color(0xFF30447D),
        text = Color(0xFFF5F6FF),
        mutedText = Color(0xFFB3BDD9),
        onSelected = Color(0xFF070A2B),
        accent = Color(0xFFFFD22E),
        progress = Color(0xFFFFD22E),
    )
    AppSettings.InterfaceStyle.OI_HYBRID -> InterfacePalette(
        screenBackground = Color.Transparent,
        surface = Color(0xCC0C2631),
        surfaceVariant = Color(0xD91A3540),
        selectedChannel = Color(0xDD203D47),
        focusedBlock = Color(0xFFFFC72C),
        currentProgramme = Color(0xCC2D4A54),
        text = Color(0xFFF3F6F7),
        mutedText = Color(0xFFACBDC2),
        onSelected = Color(0xFF102027),
        accent = Color(0xFFFFC72C),
        progress = Color(0xFFFFC72C),
    )
    AppSettings.InterfaceStyle.BOTTOM_INFO_DRAWER -> InterfacePalette(
        screenBackground = defaults.background,
        surface = defaults.surface,
        surfaceVariant = defaults.surfaceVariant,
        selectedChannel = defaults.primaryContainer,
        focusedBlock = defaults.primary,
        currentProgramme = defaults.primaryContainer,
        text = defaults.onSurface,
        mutedText = defaults.onSurfaceVariant,
        onSelected = defaults.onPrimaryContainer,
        accent = Color(0xFFFFB74D),
        progress = Color(0xFFFFB74D),
    )
}
