package io.github.caffelatte23.futaba.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.caffelatte23.futaba.core.data.NoteRepository
import io.github.caffelatte23.futaba.core.data.UndoBus
import io.github.caffelatte23.futaba.core.model.Label
import io.github.caffelatte23.futaba.core.model.Note
import io.github.caffelatte23.futaba.feature.labels.LabelSheetSession
import io.github.caffelatte23.futaba.feature.labels.LabelSheetUi
import io.github.caffelatte23.futaba.feature.labels.labelChecks
import io.github.caffelatte23.futaba.feature.stream.LabelNames
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DetailEvent {
    /** The note was moved to the trash; the screen closes and the stream offers undo. */
    data class Deleted(
        val id: String,
    ) : DetailEvent
}

data class DetailUiState(
    val note: Note? = null,
    val labelNames: LabelNames = LabelNames(emptyMap()),
    val labels: List<Label> = emptyList(),
    /** False until the note has been read once; the editor stays disabled and its callbacks are ignored. */
    val ready: Boolean = false,
    val sheet: LabelSheetUi? = null,
)

private const val AUTOSAVE_DELAY_MS = 500L
private const val STOP_TIMEOUT_MS = 5_000L

/** Owns the editor text (like the stream's composer): hydrated once, it outlives screen recreation. */
@HiltViewModel(assistedFactory = DetailViewModel.Factory::class)
class DetailViewModel
    @AssistedInject
    constructor(
        private val repo: NoteRepository,
        private val undoBus: UndoBus,
        @Assisted private val noteId: String,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(noteId: String): DetailViewModel
        }

        private val noteFlow = repo.notes.map { all -> all.firstOrNull { it.id == noteId } }
        private val eventChannel = Channel<DetailEvent>(Channel.BUFFERED)
        val events: Flow<DetailEvent> = eventChannel.receiveAsFlow()
        private val ready = MutableStateFlow(false)

        /** The label sheet for this note; its baseline is the note's current labels. */
        val labelSheet =
            LabelSheetSession(
                scope = viewModelScope,
                createLabel = repo::createLabel,
                baseline = { labelChecks(listOfNotNull(state.value.note), state.value.labels) },
                commit = { add, remove -> repo.setLabels(listOf(noteId), add, remove) },
            )

        val state: StateFlow<DetailUiState> =
            combine(noteFlow, repo.labels, ready, labelSheet.state) { note, labels, isReady, sheet ->
                // The sheet belongs to the note: if it disappears, drop the session so it cannot return stale.
                if (note == null && sheet != null) labelSheet.reset()
                DetailUiState(note, LabelNames(labels.associate { it.id to it.name }), labels, isReady, sheet)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DetailUiState())

        /** The editor's authoritative text; may be blank. Only meaningful once ready. */
        private var text = ""

        /** Last text handed to the repository (or the note's text at open). */
        private var saved = ""
        private var pending = false
        private var autosave: Job? = null
        private var finished = false

        init {
            viewModelScope.launch {
                // Editor text and baseline come from the same emission.
                val first = noteFlow.filterNotNull().first()
                text = first.text
                saved = first.text
                ready.value = true
            }
        }

        /** The text a (re)created field starts from. */
        fun textNow(): String = text

        fun onTextChange(newText: String) {
            if (!ready.value || finished) return
            text = newText
            pending = true
            autosave?.cancel()
            autosave =
                viewModelScope.launch {
                    delay(AUTOSAVE_DELAY_MS)
                    write()
                }
        }

        /** Adopts [fieldText] and writes it now (screen stopped). Blank text is kept but never saved. */
        fun flush(fieldText: String?) {
            if (!ready.value || finished || fieldText == null) return
            text = fieldText
            autosave?.cancel()
            write()
        }

        /** Screen stopped: saves the editor and discards the label sheet so stale choices cannot outlive it. */
        fun onScreenStop(fieldText: String?) {
            flush(fieldText)
            labelSheet.reset()
        }

        /** Leaving the screen: saves [fieldText], or soft-deletes the note when it was cleared. True when deleted. */
        fun leave(fieldText: String?): Boolean {
            if (!ready.value || finished || fieldText == null) return false
            val cleared = fieldText.isBlank()
            if (cleared) delete(fieldText) else flush(fieldText)
            return cleared
        }

        /** Saves a non-blank [fieldText] first so undo restores the latest body, then moves the note to the trash. */
        fun delete(fieldText: String?) {
            if (!ready.value || finished || fieldText == null) return
            flush(fieldText)
            finished = true
            autosave?.cancel()
            repo.delete(listOf(noteId))
            undoBus.postDeleted(listOf(noteId))
            eventChannel.trySend(DetailEvent.Deleted(noteId))
        }

        fun togglePin() {
            state.value.note?.let { repo.setPinned(noteId, !it.pinned) }
        }

        private fun write() {
            pending = false
            if (text.isBlank() || text == saved) return
            saved = text
            repo.updateText(noteId, text)
        }

        // Back gestures bypass leave(): persist the last edit that the debounce has not written yet.
        override fun onCleared() {
            if (pending && !finished) write()
        }
    }
