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
import io.github.caffelatte23.futaba.di.ApplicationScope
import io.github.caffelatte23.futaba.di.CurrentUid
import kotlinx.coroutines.CoroutineScope
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
    /** False until the saved draft has been read; the text field stays disabled until then. */
    val draftLoaded: Boolean = false,
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
        @ApplicationScope private val appScope: CoroutineScope,
        @Assisted private val filter: NoteFilter,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(filter: NoteFilter): StreamViewModel
        }

        private val draft = MutableStateFlow("")
        private val loaded = MutableStateFlow(false)
        private var startupFocusTaken = false

        // The saved draft is read once, before input is accepted; only then do edits get persisted.
        init {
            viewModelScope.launch {
                draft.value = prefs.draft.first()
                loaded.value = true
                draft.collectLatest {
                    delay(DRAFT_SAVE_DELAY_MS)
                    prefs.setDraft(it)
                }
            }
        }

        val state: StateFlow<StreamUiState> =
            combine(repo.notes, repo.labels, draft, uid, loaded) { all, labels, text, id, ready ->
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
                    draftLoaded = ready,
                    labelNames = LabelNames(labels.associate { it.id to it.name }),
                    usage = usage,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StreamUiState())

        /** Focus the composer and show the keyboard now? Once per launch, if forced or enabled in settings. */
        @Suppress("RedundantSuspendModifier") // detekt false positive: Flow.first is a suspend call
        suspend fun wantsStartupFocus(forced: Boolean): Boolean =
            takeStartupFocus() && (forced || prefs.openKeyboardOnLaunch.first())

        /** Current draft, for a recreated text field to start from. */
        fun draftNow(): String = draft.value

        /** True only the first time: the startup keyboard request must not repeat when returning to the screen. */
        fun takeStartupFocus(): Boolean = !startupFocusTaken.also { startupFocusTaken = true }

        fun onDraftChange(text: String) {
            if (loaded.value) draft.value = text
        }

        /** Persists the current draft now, in a scope that outlives the screen (the debounce may not have fired). */
        fun flushDraft() {
            if (loaded.value) appScope.launch { prefs.setDraft(draft.value) }
        }

        override fun onCleared() = flushDraft()

        /** Sends [text] (the field content); returns whether it was accepted. The draft is cleared only then. */
        fun send(text: String): Boolean {
            val body = text.trim()
            if (body.isEmpty() || uid.value == null || !loaded.value) return false
            repo.add(body, (filter as? NoteFilter.ByLabel)?.let { listOf(it.labelId) }.orEmpty())
            draft.value = ""
            flushDraft()
            return true
        }
    }
