package io.github.caffelatte23.futaba.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.caffelatte23.futaba.core.data.AuthRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Qualifier

/** The signed-in uid as a plain flow, so ViewModels can be tested without Firebase. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CurrentUid

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {
    @Provides
    @CurrentUid
    fun provideUid(auth: AuthRepository): StateFlow<String?> = auth.uid
}
