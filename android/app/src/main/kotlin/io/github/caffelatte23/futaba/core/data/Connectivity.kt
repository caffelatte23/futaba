package io.github.caffelatte23.futaba.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the default network is validated. The callback is registered before the snapshot is read, and later
 * values come from the callback's own arguments, so a stale snapshot can't override a newer change.
 */
fun onlineFlow(context: Context): Flow<Boolean> =
    callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val tracker = OnlineTracker { trySend(it) }
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) = tracker.capabilities(
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                )

                override fun onLost(network: Network) = tracker.lost()
            }
        manager.registerDefaultNetworkCallback(callback)
        val active = manager.getNetworkCapabilities(manager.activeNetwork)
        tracker.initial(active?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

/** Merges the initial snapshot with callback updates without letting a stale snapshot win. */
internal class OnlineTracker(
    private val emit: (Boolean) -> Unit,
) {
    private var seen = false

    @Synchronized
    fun initial(validated: Boolean) {
        if (!seen) emit(validated)
    }

    @Synchronized
    fun capabilities(validated: Boolean) {
        seen = true
        emit(validated)
    }

    @Synchronized
    fun lost() {
        seen = true
        emit(false)
    }
}
