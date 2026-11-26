package io.github.caffelatte23.futaba.testing

import io.github.caffelatte23.futaba.core.data.DEFAULT_LABEL_COLOR
import io.github.caffelatte23.futaba.core.data.NoteRepository
import io.github.caffelatte23.futaba.core.domain.parseHashtags
import io.github.caffelatte23.futaba.core.domain.resolveLabels
import io.github.caffelatte23.futaba.core.model.Label
import io.github.caffelatte23.futaba.core.model.Note
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.Instant

/** In-memory [NoteRepository] with the same label semantics as production (`#` parsing, case-insensitive reuse). */
@Suppress("TooManyFunctions") // mirrors the NoteRepository contract
class FakeNoteRepository(
    private val clock: () -> Instant = Instant::now,
) : NoteRepository {
    private val noteState = MutableStateFlow<List<Note>>(emptyList())
    private val labelState = MutableStateFlow<List<Label>>(emptyList())
    private var nextId = 0

    override val notes: Flow<List<Note>> = noteState
    override val labels: Flow<List<Label>> = labelState.map { it.sortedBy(Label::order) }
    override val pendingWrites: Flow<Int> = flowOf(0)

    private fun newId() = "id${nextId++}"

    private fun resolveIds(text: String): List<String> {
        val resolved = resolveLabels(parseHashtags(text), labelState.value)
        return resolved.existingIds + resolved.newNames.map(::createLabel)
    }

    private fun mapNotes(
        ids: List<String>,
        transform: (Note) -> Note,
    ) = noteState.update { list -> list.map { if (it.id in ids) transform(it) else it } }

    override fun add(
        text: String,
        extraLabelIds: List<String>,
    ): String {
        val id = newId()
        val now = clock()
        val labelIds = (resolveIds(text) + extraLabelIds).distinct()
        noteState.update { listOf(Note(id, text, labelIds, false, now, now, null)) + it }
        return id
    }

    override fun updateText(
        id: String,
        text: String,
    ) {
        noteState.value.firstOrNull { it.id == id }?.let { old ->
            val manual = old.labelIds - resolveLabels(parseHashtags(old.text), labelState.value).existingIds.toSet()
            val labelIds = (resolveIds(text) + manual).distinct()
            mapNotes(listOf(id)) { it.copy(text = text, labelIds = labelIds, updatedAt = clock()) }
        }
    }

    override fun setLabels(
        ids: List<String>,
        add: List<String>,
        remove: List<String>,
    ) = mapNotes(ids) { n ->
        n.copy(
            labelIds =
                (n.labelIds + add)
                    .filterNot {
                        it in remove
                    }.distinct(),
            updatedAt = clock(),
        )
    }

    override fun setPinned(
        id: String,
        pinned: Boolean,
    ) = mapNotes(listOf(id)) { it.copy(pinned = pinned, updatedAt = clock()) }

    override fun delete(ids: List<String>) = mapNotes(ids) { it.copy(deletedAt = clock()) }

    override fun restore(ids: List<String>) = mapNotes(ids) { it.copy(deletedAt = null) }

    override suspend fun purgeTrash(now: Instant) {
        val limit = now.minus(Duration.ofDays(TRASH_DAYS))
        noteState.update { list -> list.filterNot { n -> n.deletedAt?.isBefore(limit) == true } }
    }

    override fun createLabel(name: String): String {
        labelState.value.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it.id }
        val id = newId()
        val order = (labelState.value.maxOfOrNull(Label::order) ?: -1) + 1
        labelState.update { it + Label(id, name, DEFAULT_LABEL_COLOR, order) }
        return id
    }

    private fun mapLabel(
        id: String,
        transform: (Label) -> Label,
    ) = labelState.update { list -> list.map { if (it.id == id) transform(it) else it } }

    override fun renameLabel(
        id: String,
        name: String,
    ) = mapLabel(id) { it.copy(name = name) }

    override fun setLabelColor(
        id: String,
        color: String,
    ) = mapLabel(id) { it.copy(color = color) }

    override fun reorderLabels(idsInOrder: List<String>) =
        labelState.update { list ->
            list.map { l ->
                l.copy(
                    order =
                        idsInOrder.indexOf(l.id).takeIf { it >= 0 } ?: l.order,
                )
            }
        }

    override fun deleteLabel(id: String) {
        labelState.update { list -> list.filterNot { it.id == id } }
        noteState.update { list -> list.map { it.copy(labelIds = it.labelIds - id) } }
    }

    private companion object {
        const val TRASH_DAYS = 30L
    }
}
