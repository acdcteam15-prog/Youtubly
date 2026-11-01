package com.youtubly.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live online/offline state, shared app-wide. `isOnline` starts as an optimistic `true` so
 * the UI never flashes an offline state before the first real callback arrives, then reflects
 * ConnectivityManager's view of whether the active network can actually reach the internet
 * (not just "a network is connected" — that alone doesn't rule out a captive portal or a
 * Wi-Fi with no working uplink).
 */
@Singleton
class ConnectivityObserver @Inject constructor(
    @ApplicationContext context: Context
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val isOnline: StateFlow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            private val validated = mutableSetOf<Network>()

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val ok = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (ok) validated += network else validated -= network
                trySend(validated.isNotEmpty())
            }

            override fun onLost(network: Network) {
                validated -= network
                trySend(validated.isNotEmpty())
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivityManager?.registerNetworkCallback(request, callback) }
        awaitClose { runCatching { connectivityManager?.unregisterNetworkCallback(callback) } }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, true)

    /**
     * True while the default network is metered (mobile data, metered hotspot). Starts from
     * ConnectivityManager's current answer so a video opened right after launch already
     * picks the right quality cap.
     */
    val isMetered: StateFlow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }
        }
        runCatching { connectivityManager?.registerDefaultNetworkCallback(callback) }
        awaitClose { runCatching { connectivityManager?.unregisterNetworkCallback(callback) } }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, currentlyMetered())

    /** Synchronous check of the active network, used at the moment a video loads. */
    fun currentlyMetered(): Boolean = runCatching { connectivityManager?.isActiveNetworkMetered ?: false }.getOrDefault(false)
}
