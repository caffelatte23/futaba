package io.github.caffelatte23.futaba.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.caffelatte23.futaba.BuildConfig
import javax.inject.Singleton

private const val EMULATOR_HOST = "10.0.2.2"
private const val FIRESTORE_PORT = 8080
private const val AUTH_PORT = 9099
private val EMULATOR = BuildConfig.DEBUG && BuildConfig.USE_EMULATOR

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {
    @Provides
    @Singleton
    fun provideAuth(): FirebaseAuth =
        FirebaseAuth.getInstance().apply {
            if (EMULATOR) useEmulator(EMULATOR_HOST, AUTH_PORT)
        }

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore =
        FirebaseFirestore.getInstance().apply {
            if (EMULATOR) useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
        }
}

internal fun configureMergeEmulators(
    auth: FirebaseAuth,
    firestore: FirebaseFirestore,
    enabled: Boolean = EMULATOR,
) {
    if (enabled) {
        auth.useEmulator(EMULATOR_HOST, AUTH_PORT)
        firestore.useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
    }
}
