package io.github.caffelatte23.futaba.core.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deletions waiting for the stream's 「元に戻す」 snackbar. Buffered, so a delete made on another screen
 * (Detail, bulk delete) is shown once the stream is visible again.
 */
@Singleton
class UndoBus
    @Inject
    constructor() {
        private val channel = Channel<List<String>>(Channel.UNLIMITED)

        /** Ids of the deleted notes, one emission per delete action. */
        val deletions: Flow<List<String>> = channel.receiveAsFlow()

        fun postDeleted(ids: List<String>) {
            channel.trySend(ids)
        }
    }
