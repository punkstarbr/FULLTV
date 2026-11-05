/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

/** The four programme-grid D-pad moves; channel/rail boundaries remain owned by Compose. */
internal enum class GuideProgrammeDirection { LEFT, RIGHT, UP, DOWN }

/** Stable, UI-independent projection of a programme used by the D-pad policy. */
internal data class GuideProgrammeSlot(
    val key: String,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
)

internal data class GuideProgrammeTarget(val rowIndex: Int, val programmeIndex: Int)

/**
 * Pick the next programme for an explicit D-pad move. Horizontal moves follow the schedule order,
 * including across large gaps; vertical moves choose the programme whose time centre is closest to
 * the currently focused programme. Returning null at an edge deliberately leaves the move to
 * Compose (for example, LEFT from the first programme may enter the fixed channel column).
 */
internal fun guideProgrammeTarget(
    rows: List<List<GuideProgrammeSlot>>,
    rowIndex: Int,
    programmeIndex: Int,
    direction: GuideProgrammeDirection,
): GuideProgrammeTarget? {
    val currentRow = rows.getOrNull(rowIndex) ?: return null
    val current = currentRow.getOrNull(programmeIndex) ?: return null
    return when (direction) {
        GuideProgrammeDirection.LEFT -> (programmeIndex - 1)
            .takeIf { it >= 0 }
            ?.let { GuideProgrammeTarget(rowIndex, it) }
        GuideProgrammeDirection.RIGHT -> (programmeIndex + 1)
            .takeIf { it < currentRow.size }
            ?.let { GuideProgrammeTarget(rowIndex, it) }
        GuideProgrammeDirection.UP, GuideProgrammeDirection.DOWN -> {
            val neighborRowIndex = rowIndex + if (direction == GuideProgrammeDirection.UP) -1 else 1
            val neighbor = rows.getOrNull(neighborRowIndex).orEmpty()
            if (neighbor.isEmpty()) return null
            val currentCenter = current.startUtcMillis + (current.endUtcMillis - current.startUtcMillis) / 2
            val closestIndex = neighbor.indices.minWithOrNull(
                compareBy<Int> {
                    val candidate = neighbor[it]
                    val center = candidate.startUtcMillis + (candidate.endUtcMillis - candidate.startUtcMillis) / 2
                    kotlin.math.abs(center - currentCenter)
                }.thenBy { it },
            ) ?: return null
            GuideProgrammeTarget(neighborRowIndex, closestIndex)
        }
    }
}

internal fun guideProgrammeFocusKey(rowKey: String, programmeKey: String): String =
    "$rowKey::$programmeKey"
