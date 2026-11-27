package io.github.caffelatte23.futaba.core.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide ordering for draft writes. Callers take a [nextSeq] at the moment of intent; [write] drops any write
 * whose seq is older than one already written, so a late flush (from any ViewModel or scope) can never
 * overwrite a newer draft or the clear after send.
 */
@Singleton
class DraftStore
    @Inject
    constructor(
        private val prefs: UserPreferencesRepository,
    ) {
        private val seq = AtomicLong()
        private val lock = Mutex()
        private var lastWritten = 0L

        fun nextSeq(): Long = seq.incrementAndGet()

        suspend fun write(
            text: String,
            seq: Long,
        ) = lock.withLock {
            if (seq > lastWritten) {
                lastWritten = seq
                prefs.setDraft(text)
            }
        }
    }
