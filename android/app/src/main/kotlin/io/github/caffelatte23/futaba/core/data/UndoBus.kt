package io.github.caffelatte23.futaba.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Notes just deleted that the stream offers to bring back until [expiresAt] (epoch millis). */
data class PendingUndo(
    val ids: List<String>,
    val expiresAt: Long,
) {
    fun isExpired(now: Long) = now >= expiresAt
}

private const val UNDO_WINDOW_MS = 5_000L

/**
 * The 「元に戻す」 offer for the latest deletion (Detail, bulk delete). Lives in a singleton, outside
 * composition, so rotating or navigating while the snackbar is up does not lose it.
 * ponytail: only the latest deletion is kept; a newer delete replaces an older offer.
 */
@Singleton
class UndoBus(
    private val clock: () -> Long,
) {
    @Inject
    constructor() : this(System::currentTimeMillis)

    private val state = MutableStateFlow<PendingUndo?>(null)
    val pending: StateFlow<PendingUndo?> = state

    fun postDeleted(ids: List<String>) {
        state.value = PendingUndo(ids, clock() + UNDO_WINDOW_MS)
    }

    /** Drops [undo] once acted on or expired; a newer offer stays. */
    fun clear(undo: PendingUndo) {
        state.update { if (it == undo) null else it }
    }

    fun now() = clock()
}
