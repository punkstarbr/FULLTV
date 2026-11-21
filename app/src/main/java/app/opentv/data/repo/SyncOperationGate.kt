/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes memory- and network-heavy catalogue/guide sync operations in the app process.
 *
 * WorkManager periodic, startup and manual requests can have different unique-work names. A shared
 * coroutine mutex prevents a large M3U import from competing with XMLTV parsing/Room writes, while
 * the existing per-repository EPG mutex continues to serialize feed snapshots within EPG itself.
 */
internal object SyncOperationGate {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
