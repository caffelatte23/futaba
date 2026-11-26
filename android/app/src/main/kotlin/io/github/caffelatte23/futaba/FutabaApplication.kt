package io.github.caffelatte23.futaba

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.HiltAndroidApp
import io.github.caffelatte23.futaba.core.data.AuthRepository
import io.github.caffelatte23.futaba.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FutabaApplication : Application() {
    @Inject lateinit var authRepository: AuthRepository

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        appScope.launch { authRepository.ensureSignedIn() }
        // Retry anonymous sign-in when the network appears or becomes validated while we still have no uid.
        // ensureSignedIn is serialized, so overlapping triggers are safe.
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = retryIfSignedOut()

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    if (networkCapabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                        )
                    ) {
                        retryIfSignedOut()
                    }
                }
            },
        )
    }

    private fun retryIfSignedOut() {
        if (authRepository.uid.value == null) appScope.launch { authRepository.ensureSignedIn() }
    }
}
