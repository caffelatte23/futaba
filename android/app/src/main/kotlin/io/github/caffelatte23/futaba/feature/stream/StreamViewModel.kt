package io.github.caffelatte23.futaba.feature.stream

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.caffelatte23.futaba.core.data.NoteRepository
import io.github.caffelatte23.futaba.core.data.UserPreferencesRepository
import io.github.caffelatte23.futaba.core.domain.filterNotes
import io.github.caffelatte23.futaba.core.domain.groupByDay
import io.github.caffelatte23.futaba.core.domain.labelUsage
import io.github.caffelatte23.futaba.core.domain.suggestLabels
import io.github.caffelatte23.futaba.core.model.Label
import io.github.caffelatte23.futaba.core.model.Note
import io.github.caffelatte23.futaba.core.model.NoteFilter
import io.github.caffelatte23.futaba.di.CurrentUid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class DaySection(
    val date: LocalDate,
    val notes: List<Note>,
)

/** Label id -> name; wrapped so Compose can skip recomposition. */
@Immutable
data class LabelNames(
    val byId: Map<String, String>,
)

data class StreamUiState(
    val sections: List<DaySection> = emptyList(),
    val pinned: List<Note> = emptyList(),
    val count: Int = 0,
    val draft: String = "",
    val suggestions: List<Label> = emptyList(),
    val canSend: Boolean = false,
    /** Label id -> name, to render chips. */
    val labelNames: LabelNames = LabelNames(emptyMap()),
    /** Label id -> number of live notes, shown in suggestions. */
    val usage: Map<String, Int> = emptyMap(),
)

private const val DRAFT_SAVE_DELAY_MS = 300L
private const val STOP_TIMEOUT_MS = 5_000L

/** `#` followed by a (possibly empty) label prefix at the very end of the draft. */
private val trailingHashtag = Regex("""(?<![\p{L}\p{N}/&])[#＃]([\p{L}\p{N}_-]*)$""")

@HiltViewModel(assistedFactory = StreamViewModel.Factory::class)
class StreamViewModel
    @AssistedInject
    constructor(
        private val repo: NoteRepository,
        private val prefs: UserPreferencesRepository,
        @CurrentUid private val uid: StateFlow<String?>,
        @Assisted private val filter: NoteFilter,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(filter: NoteFilter): StreamViewModel
        }

        private val draft = MutableStateFlow("")

        // Saved draft is restored first; only then do edits start being persisted (never overwrite it with "").
        private val restored = CompletableDeferred<String>()

        init {
            viewModelScope.launch {
                val saved = prefs.draft.first()
                draft.update { it.ifEmpty { saved } }
                restored.complete(draft.value)
                draft.collectLatest {
                    delay(DRAFT_SAVE_DELAY_MS)
                    prefs.setDraft(it)
                }
            }
        }

        val state: StateFlow<StreamUiState> =
            combine(repo.notes, repo.labels, draft, uid) { all, labels, text, id ->
                val shown = filterNotes(all, filter)
                val usage = labelUsage(all)
                val prefix = trailingHashtag.find(text)?.groupValues?.get(1)
                StreamUiState(
                    sections =
                        groupByDay(shown.filterNot(Note::pinned), ZoneId.systemDefault())
                            .map { (date, list) -> DaySection(date, list) },
                    pinned = shown.filter(Note::pinned),
                    count = shown.size,
                    draft = text,
                    suggestions = prefix?.let { suggestLabels(it, labels, usage) }.orEmpty(),
                    canSend = text.isNotBlank() && id != null,
                    labelNames = LabelNames(labels.associate { it.id to it.name }),
                    usage = usage,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StreamUiState())

        /** The persisted draft, for the text field to show once on first display. */
        @Suppress("RedundantSuspendModifier") // detekt false positive: Deferred.await is a suspend call
        suspend fun restoredDraft(): String = restored.await()

        @Suppress("RedundantSuspendModifier") // detekt false positive: Flow.first is a suspend call
        suspend fun openKeyboardOnLaunch(): Boolean = prefs.openKeyboardOnLaunch.first()

        fun onDraftChange(text: String) {
            draft.value = text
        }

        fun send() {
            val text = draft.value.trim()
            if (text.isEmpty() || uid.value == null) return
            repo.add(text, (filter as? NoteFilter.ByLabel)?.let { listOf(it.labelId) }.orEmpty())
            draft.value = ""
            viewModelScope.launch { prefs.setDraft("") }
        }
    }
