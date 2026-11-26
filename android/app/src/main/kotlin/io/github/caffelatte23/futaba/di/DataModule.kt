package io.github.caffelatte23.futaba.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.caffelatte23.futaba.core.data.FirestoreNoteRepository
import io.github.caffelatte23.futaba.core.data.NoteRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {
    @Binds
    @Singleton
    fun bindNoteRepository(impl: FirestoreNoteRepository): NoteRepository
}
