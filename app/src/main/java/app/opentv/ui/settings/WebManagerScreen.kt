/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.pairing.ManagerServer
import app.opentv.pairing.QrCodes
import app.opentv.ui.ProtectSensitiveScreen
import app.opentv.ui.WebManagerViewModel

/**
 * "Manage your channels from your phone or laptop."
 *
 * Shows a QR code and a short URL pointing at a web server running on this television. Opening it on
 * a phone or laptop on the same wifi gives a proper page — real keyboard and mouse — to browse,
 * hide, favourite, rename and reorder channels, writing straight to this device's database. See
 * [ManagerServer] for why it works locally rather than through a website, and for the security
 * model (192-bit URL token, LAN-only, only ever shown on this screen).
 */
@Composable
fun WebManagerScreen(
    onBack: () -> Unit,
    viewModel: WebManagerViewModel = viewModel(),
) {
    ProtectSensitiveScreen()
    val state by viewModel.state.collectAsState()

    DisposableEffect(Unit) {
        viewModel.start()
        onDispose {
            // Never leave a socket listening on someone's home network after they've left.
            viewModel.stop()
        }
    }

    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        when (val current = state) {
            is ManagerServer.State.Listening -> Listening(current.session, onBack)

            is ManagerServer.State.Failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.webmanager_stopped),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    current.reason,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { viewModel.start() }) { Text(stringResource(R.string.common_try_again)) }
                    OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
                }
            }

            ManagerServer.State.Idle -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun Listening(session: ManagerServer.Session, onBack: () -> Unit) {
    // Generated once per session — encoding is not free and the content does not change.
    val qr = remember(session.url) { QrCodes.render(session.url, QR_SIZE_PX) }
    val (manualPrefix, token) = remember(session.url) {
        val marker = "?t="
        val markerIndex = session.url.indexOf(marker)
        if (markerIndex >= 0) {
            session.url.substring(0, markerIndex + marker.length) to
                session.url.substring(markerIndex + marker.length)
        } else {
            session.url to ""
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        if (qr != null) {
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = stringResource(R.string.webmanager_qr_desc),
                modifier = Modifier
                    .size(300.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(12.dp),
            )
        }

        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.webmanager_title), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.webmanager_desc),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.webmanager_open_browser),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            // Keep the required token visibly separate from the short IP:port address. The token
            // is split into lines only for display; joining them without spaces reconstructs URL.
            Text(
                manualPrefix,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                stringResource(R.string.webmanager_token_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                token.chunked(12).joinToString("\n"),
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            )

            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.lan_tls_browser_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.lan_tls_certificate_fingerprint),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                session.certificateSha256.chunked(24).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )

            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.webmanager_local_only),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }
    }
}

private const val QR_SIZE_PX = 600
