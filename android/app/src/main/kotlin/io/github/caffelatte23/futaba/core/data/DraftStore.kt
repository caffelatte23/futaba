package io.github.caffelatte23.futaba.core.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class DraftIntent(
    val text: String,
    val seq: Long,
)

/** Tracks the newest draft intent in memory and orders writes across ViewModels. */
@Singleton
class DraftStore
    @Inject
    constructor(
        private val prefs: UserPreferencesRepository,
    ) {
        private val seq = AtomicLong()
        private val intent = MutableStateFlow<DraftIntent?>(null)
        private val lock = Mutex()
        private var lastWritten = 0L

        val latestIntent = intent.asStateFlow()

        fun nextSeq(): Long = seq.incrementAndGet()

        fun registerIntent(text: String): DraftIntent {
            val registered = DraftIntent(text, nextSeq())
            intent.update { current ->
                if (current == null || registered.seq > current.seq) registered else current
            }
            return registered
        }

        suspend fun write(
            text: String,
            seq: Long,
        ) = lock.withLock {
            if (seq > lastWritten) {
                // Once a write starts, cancellation must not leave disk ahead of lastWritten.
                withContext(NonCancellable) {
                    prefs.setDraft(text)
                    lastWritten = seq
                }
            }
        }
    }
