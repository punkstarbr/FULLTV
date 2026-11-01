/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Date

/** An optional local-time badge layered above navigation; it has no pointer or focus handlers. */
@Composable
fun PersistentLocalClock(enabled: Boolean) {
    if (!enabled) return

    val context = LocalContext.current
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            nowMillis = now
            val untilNextMinute = 60_000L - Math.floorMod(now, 60_000L)
            delay(untilNextMinute.coerceAtLeast(1_000L))
        }
    }

    val localTime = DateFormat.getTimeFormat(context).format(Date(nowMillis))
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Box(
            Modifier
                .padding(end = 20.dp, bottom = 16.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.68f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                text = localTime,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
    }
}
