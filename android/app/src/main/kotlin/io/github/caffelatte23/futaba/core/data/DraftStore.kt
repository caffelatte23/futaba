package io.github.caffelatte23.futaba.core.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
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

data class SharedSnippet(
    val seq: Long,
    val text: String,
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
        private val unconsumedShares = MutableStateFlow<List<SharedSnippet>>(emptyList())
        private val lock = Mutex()
        private var lastWritten = 0L

        val latestIntent = intent.asStateFlow()
        val sharedSnippets = unconsumedShares.asStateFlow()

        fun consumeSharedSnippets(through: Long) {
            unconsumedShares.update { snippets -> snippets.dropWhile { it.seq <= through } }
        }

        fun nextSeq(): Long = seq.incrementAndGet()

        fun registerIntent(text: String): DraftIntent {
            val registered = DraftIntent(text, nextSeq())
            intent.update { current ->
                if (current == null || registered.seq > current.seq) registered else current
            }
            return registered
        }

        @Suppress("RedundantSuspendModifier") // Flow.first and withContext suspend.
        suspend fun append(text: String) =
            lock.withLock {
                val saved = intent.value?.text ?: prefs.draft.first()
                withContext(NonCancellable) {
                    val appended =
                        checkNotNull(
                            intent.updateAndGet { current ->
                                val draft = current?.text ?: saved
                                val combined = if (draft.isBlank()) text else "$draft\n\n$text"
                                DraftIntent(combined, nextSeq())
                            },
                        )
                    // Retain each payload even when the flow conflates successive appends.
                    unconsumedShares.update { it + SharedSnippet(appended.seq, text) }
                    persist(appended.text, appended.seq)
                }
            }

        suspend fun write(
            text: String,
            seq: Long,
        ) = lock.withLock {
            if (seq > lastWritten) {
                // Once a write starts, cancellation must not leave disk ahead of lastWritten.
                withContext(NonCancellable) {
                    persist(text, seq)
                }
            }
        }

        private suspend fun persist(
            text: String,
            seq: Long,
        ) {
            prefs.setDraft(text)
            lastWritten = seq
        }
    }
