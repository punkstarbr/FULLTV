/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Emits whether Android currently has a default network with Internet capability. */
class NetworkConnectivityObserver(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<Boolean> = callbackFlow {
        fun hasInternet(network: Network?): Boolean =
            network != null && connectivityManager.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(hasInternet(connectivityManager.activeNetwork))
            }

            override fun onLost(network: Network) {
                // A Wi-Fi loss should not trigger a reconnect when Android has already switched to
                // another usable default route, such as mobile data or Ethernet.
                trySend(hasInternet(connectivityManager.activeNetwork))
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network == connectivityManager.activeNetwork) {
                    trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                }
            }
        }

        trySend(hasInternet(connectivityManager.activeNetwork))
        connectivityManager.registerDefaultNetworkCallback(callback)
        awaitClose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()
}
