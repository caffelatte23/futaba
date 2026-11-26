package io.github.caffelatte23.futaba

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
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
        // Retry anonymous sign-in whenever the network comes back and we still have no uid.
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (authRepository.uid.value == null) appScope.launch { authRepository.ensureSignedIn() }
                }
            },
        )
    }
}
