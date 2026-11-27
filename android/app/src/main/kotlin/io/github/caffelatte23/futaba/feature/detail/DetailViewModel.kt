package io.github.caffelatte23.futaba.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.caffelatte23.futaba.core.data.NoteRepository
import io.github.caffelatte23.futaba.core.data.UndoBus
import io.github.caffelatte23.futaba.core.model.Note
import io.github.caffelatte23.futaba.feature.stream.LabelNames
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
)

private const val AUTOSAVE_DELAY_MS = 500L
private const val STOP_TIMEOUT_MS = 5_000L

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

        val state: StateFlow<DetailUiState> =
            combine(noteFlow, repo.labels) { note, labels ->
                DetailUiState(note, LabelNames(labels.associate { it.id to it.name }))
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DetailUiState())

        /** Text last handed to the repository (or the note's text at open); null until the note has loaded. */
        private var saved: String? = null
        private var pending: String? = null
        private var autosave: Job? = null
        private var finished = false

        init {
            viewModelScope.launch { saved = noteFlow.filterNotNull().first().text }
        }

        fun onTextChange(text: String) {
            if (finished) return
            pending = text
            autosave?.cancel()
            autosave =
                viewModelScope.launch {
                    delay(AUTOSAVE_DELAY_MS)
                    pending = null
                    write(text)
                }
        }

        /** Writes [text] now (screen stopped); an empty text stays in the field but is never saved. */
        fun flush(text: String) {
            if (finished) return
            autosave?.cancel()
            pending = null
            write(text)
        }

        /** Leaving the screen: saves [text], or deletes the note when it was cleared. True when deleted. */
        fun leave(text: String): Boolean {
            if (text.isBlank()) {
                delete()
                return true
            }
            flush(text)
            return false
        }

        fun delete() {
            if (finished) return
            finished = true
            autosave?.cancel()
            pending = null
            repo.delete(listOf(noteId))
            undoBus.postDeleted(listOf(noteId))
            eventChannel.trySend(DetailEvent.Deleted(noteId))
        }

        fun togglePin() {
            state.value.note?.let { repo.setPinned(noteId, !it.pinned) }
        }

        private fun write(text: String) {
            if (text.isBlank() || saved == null || text == saved) return
            saved = text
            repo.updateText(noteId, text)
        }

        // Back gestures bypass leave(): persist the last edit that the debounce has not written yet.
        override fun onCleared() {
            pending?.takeUnless { finished }?.let(::write)
        }
    }
