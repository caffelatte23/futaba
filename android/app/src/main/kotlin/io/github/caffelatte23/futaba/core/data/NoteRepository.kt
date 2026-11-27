package io.github.caffelatte23.futaba.core.data

import io.github.caffelatte23.futaba.core.model.Label
import io.github.caffelatte23.futaba.core.model.Note
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Unset label color, shared with the Chrome extension. */
const val DEFAULT_LABEL_COLOR = ""

class DuplicateLabelNameException : IllegalArgumentException()

/** A locally deleted label and the cached notes it was attached to, including notes in trash. */
class DeletedLabel(
    val label: Label,
    val noteIds: List<String>,
    internal val uid: String? = null,
)

/**
 * Notes and labels of the signed-in user.
 *
 * Local writes update the cache immediately and sync later. [createLabel] waits for its local result, and
 * [purgeTrash] uses server transactions. When nobody is signed in, queued writes are logged and dropped
 * (`add` still returns a fresh id). Callers must disable writing without a uid.
 */
@Suppress("TooManyFunctions") // one cohesive repository contract; splitting would only scatter label semantics
interface NoteRepository {
    /** All notes including trash (filter with `filterNotes`), with label ids resolved against known labels. */
    val notes: Flow<List<Note>>

    /** Labels ordered by `order` ascending. */
    val labels: Flow<List<Label>>

    /** Number of notes with unsent local writes. */
    val pendingWrites: Flow<Int>

    /** Parses `#labels` in [text] (reusing existing ones case-insensitively), creates the rest; returns the id. */
    fun add(
        text: String,
        extraLabelIds: List<String> = emptyList(),
    ): String

    /** Replaces text; labels become the text's `#` labels plus the note's manually attached ones. */
    fun updateText(
        id: String,
        text: String,
    )

    fun setLabels(
        ids: List<String>,
        add: List<String>,
        remove: List<String>,
    )

    fun setPinned(
        id: String,
        pinned: Boolean,
    )

    fun delete(ids: List<String>)

    fun restore(ids: List<String>)

    /** Permanently deletes notes trashed more than 30 days before [now]. */
    suspend fun purgeTrash(now: Instant)

    /**
     * Returns the id of the new label. A case-insensitive match returns the existing id when
     * [reuseExisting] is true and throws [DuplicateLabelNameException] otherwise.
     * Suspends only until the write is queued and resolved, not for the server. Throws [IllegalStateException]
     * when nobody is signed in or the captured uid has changed.
     */
    suspend fun createLabel(
        name: String,
        reuseExisting: Boolean = true,
    ): String

    /** Saves a name locally, rejecting case-insensitive matches with another label. */
    suspend fun renameLabel(
        id: String,
        name: String,
    )

    fun setLabelColor(
        id: String,
        color: String,
    )

    fun reorderLabels(idsInOrder: List<String>)

    /** Deletes the label and detaches it from cached notes while preserving their text. */
    fun deleteLabel(id: String)

    /** Snapshots and deletes locally in the same queued operation. */
    suspend fun deleteLabelForUndo(id: String): DeletedLabel

    /** Recreates the label at its original id and reattaches it to surviving notes. */
    suspend fun restoreLabel(deleted: DeletedLabel)
}
