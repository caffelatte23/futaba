package io.github.caffelatte23.futaba.core.data

import io.github.caffelatte23.futaba.core.model.Label
import io.github.caffelatte23.futaba.core.model.Note
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Color of new labels: empty string, shared with the Chrome extension; Task 12 assigns real colors. */
const val DEFAULT_LABEL_COLOR = ""

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
     * Returns the id of the existing label with that name (case-insensitive) or of the one it just created.
     * Suspends only until the write is queued and resolved, not for the server. Throws [IllegalStateException]
     * when nobody is signed in or the captured uid has changed.
     */
    suspend fun createLabel(name: String): String

    fun renameLabel(
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
}
