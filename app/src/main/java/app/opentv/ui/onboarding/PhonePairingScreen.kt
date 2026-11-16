/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opentv.R
import app.opentv.data.model.Source
import app.opentv.pairing.PairingServer
import app.opentv.pairing.QrCodes
import app.opentv.ui.FullTvCard
import app.opentv.ui.FullTvChrome
import app.opentv.ui.FullTvSectionHeader
import app.opentv.ui.ProtectSensitiveScreen
import app.opentv.ui.fullTvFocusRing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * "Set it up from your phone."
 *
 * Shows a QR code pointing at a web server running on this television, plus a six-digit code
 * the phone must echo back. See [PairingServer] for why it works this way rather than through
 * a website.
 */
@Composable
fun PhonePairingScreen(
    onReceived: (Source) -> Unit,
    onCancel: () -> Unit,
) {
    ProtectSensitiveScreen()
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val server = remember { PairingServer(scope) }
    val state by server.state.collectAsState()

    DisposableEffect(Unit) {
        server.start()
        onDispose {
            // Never leave a socket listening on someone's home network after they have
            // navigated away.
            server.stop()
            scope.cancel()
        }
    }

    LaunchedEffect(state) {
        (state as? PairingServer.State.Received)?.let { onReceived(it.draft) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wideLayout = maxWidth >= 760.dp
        val availableWidth = maxWidth
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (wideLayout) FullTvChrome.pageInset else FullTvChrome.compactPageInset,
                    vertical = if (wideLayout) 24.dp else 16.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (val current = state) {
                is PairingServer.State.Listening -> {
                    val qrSize = if (wideLayout) 300.dp else minOf(availableWidth - 32.dp, 240.dp).coerceAtLeast(168.dp)
                    if (wideLayout) {
                        Row(
                            modifier = Modifier.fillMaxWidth().widthIn(max = 1120.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(FullTvChrome.sectionGap),
                        ) {
                            QrCard(current.session, qrSize)
                            PairingInstructions(
                                current.session,
                                onCancel,
                                compact = false,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(FullTvChrome.sectionGap),
                        ) {
                            QrCard(current.session, qrSize)
                            PairingInstructions(current.session, onCancel, compact = true)
                        }
                    }
                }

                is PairingServer.State.Failed -> FullTvCard(
                    Modifier.fillMaxWidth().widthIn(max = 640.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FullTvSectionHeader(title = stringResource(R.string.pairing_stopped))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            current.reason,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        Spacer(Modifier.height(24.dp))
                        if (wideLayout) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PairingButton(
                                    label = stringResource(R.string.common_try_again),
                                    onClick = { server.start() },
                                    primary = true,
                                )
                                PairingButton(
                                    label = stringResource(R.string.pairing_type_here),
                                    onClick = onCancel,
                                )
                            }
                        } else {
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                PairingButton(
                                    label = stringResource(R.string.common_try_again),
                                    onClick = { server.start() },
                                    primary = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                PairingButton(
                                    label = stringResource(R.string.pairing_type_here),
                                    onClick = onCancel,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                is PairingServer.State.Received -> FullTvCard(
                    Modifier.fillMaxWidth().widthIn(max = 640.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.pairing_got_it),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }

                PairingServer.State.Idle -> androidx.compose.material3.CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun QrCard(session: PairingServer.Session, size: androidx.compose.ui.unit.Dp) {
    // Generated once per session rather than on every recomposition — encoding is not free
    // and the content does not change.
    val qr = remember(session.url) { QrCodes.render(session.url, QR_SIZE_PX) }
    if (qr != null) {
        FullTvCard(
            modifier = Modifier.size(size + 24.dp),
            containerColor = Color.White,
        ) {
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = stringResource(R.string.pairing_qr_desc),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun PairingInstructions(
    session: PairingServer.Session,
    onCancel: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    FullTvCard(
        modifier = modifier.fillMaxWidth().widthIn(max = 600.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 18.dp else 24.dp)) {
            FullTvSectionHeader(
                title = stringResource(R.string.pairing_setup_title),
                subtitle = stringResource(R.string.pairing_setup_desc),
            )

            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.pairing_enter_code),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                // Spaced out because it is being read across a room and typed into a phone.
                text = session.confirmCode.toCharArray().joinToString("  "),
                fontSize = if (compact) 40.sp else 52.sp,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = session.confirmCode },
            )

            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.pairing_camera_fail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                session.shortUrl,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(14.dp))
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

            Spacer(Modifier.height(24.dp))
            PairingButton(
                label = stringResource(R.string.pairing_type_on_tv),
                onClick = onCancel,
            )
        }
    }
}

@Composable
private fun PairingButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val buttonModifier = modifier
        .onFocusChanged { focused = it.isFocused }
        .fullTvFocusRing(focused, MaterialTheme.colorScheme.primary)
    if (primary) {
        Button(onClick = onClick, modifier = buttonModifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = buttonModifier) { Text(label) }
    }
}

private const val QR_SIZE_PX = 600
