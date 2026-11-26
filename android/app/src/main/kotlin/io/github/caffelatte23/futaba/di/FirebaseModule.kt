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

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {
    @Provides
    @Singleton
    fun provideAuth(): FirebaseAuth =
        FirebaseAuth.getInstance().apply {
            if (BuildConfig.USE_EMULATOR) useEmulator(EMULATOR_HOST, AUTH_PORT)
        }

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore =
        FirebaseFirestore.getInstance().apply {
            if (BuildConfig.USE_EMULATOR) useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
        }
}
