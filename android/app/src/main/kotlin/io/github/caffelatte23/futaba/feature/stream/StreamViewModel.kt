package io.github.caffelatte23.futaba.feature.stream

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.caffelatte23.futaba.core.data.ComposerFocusRequests
import io.github.caffelatte23.futaba.core.data.DraftIntent
import io.github.caffelatte23.futaba.core.data.DraftStore
import io.github.caffelatte23.futaba.core.data.NoteRepository
import io.github.caffelatte23.futaba.core.data.PendingUndo
import io.github.caffelatte23.futaba.core.data.UndoBus
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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
    /** Drawer label list: in `order`, with counts. */
    val drawerLabels: List<DrawerLabel> = emptyList(),
    /** Labels loaded and the active label filter is gone (deleted): the screen falls back to All. */
    val filterMissing: Boolean = false,
)

private const val DRAFT_SAVE_DELAY_MS = 300L
private const val STOP_TIMEOUT_MS = 5_000L

/** `#` followed by a (possibly empty) label prefix at the very end of the draft. */
private val trailingHashtag = Regex("""(?<![\p{L}\p{N}/&])[#＃]([\p{L}\p{N}_-]*)$""")

@Suppress("LongParameterList", "TooManyFunctions") // injected collaborators; one screen's state holder
@HiltViewModel(assistedFactory = StreamViewModel.Factory::class)
class StreamViewModel
    @AssistedInject
    constructor(
        private val repo: NoteRepository,
        private val prefs: UserPreferencesRepository,
        private val drafts: DraftStore,
        @CurrentUid private val uid: StateFlow<String?>,
        @ApplicationScope private val appScope: CoroutineScope,
        private val undoBus: UndoBus,
        private val focusRequests: ComposerFocusRequests,
        @Assisted private val filter: NoteFilter,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(filter: NoteFilter): StreamViewModel
        }

        private val draft = MutableStateFlow("")
        private val pendingDraft = MutableStateFlow<DraftIntent?>(null)
        private val loaded = MutableStateFlow(false)

        init {
            viewModelScope.launch {
                draft.value = drafts.latestIntent.value?.text ?: prefs.draft.first().let { saved ->
                    // A different ViewModel can register an intent while the disk read is suspended.
                    drafts.latestIntent.value?.text ?: saved
                }
                loaded.value = true
                pendingDraft.collectLatest { intent ->
                    if (intent == null) return@collectLatest
                    delay(DRAFT_SAVE_DELAY_MS)
                    drafts.write(intent.text, intent.seq)
                }
            }
        }

        // Start with empty lists so the composer never waits for Firestore's first emission.
        private val notesOrEmpty = repo.notes.onStart { emit(emptyList()) }
        private val labelsOrEmpty = repo.labels.map { it to true }.onStart { emit(emptyList<Label>() to false) }

        val state: StateFlow<StreamUiState> =
            combine(notesOrEmpty, labelsOrEmpty, draft, uid, loaded) { all, (labels, labelsLoaded), text, id, ready ->
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
                    drawerLabels = drawerLabels(labels, usage),
                    filterMissing =
                        labelsLoaded && filter is NoteFilter.ByLabel && labels.none { it.id == filter.labelId },
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StreamUiState())

        /** Focus the composer now? Consumes the app-wide one-shot request (forced, or per the setting). */
        @Suppress("RedundantSuspendModifier") // detekt false positive: Flow.first is a suspend call
        suspend fun wantsStartupFocus(): Boolean {
            val forced = focusRequests.take() ?: return false
            return forced || prefs.openKeyboardOnLaunch.first()
        }

        /** The pending 「元に戻す」 offer (from Detail or bulk delete); survives screen recreation. */
        val pendingUndo = undoBus.pending

        fun now() = undoBus.now()

        fun clearUndo(undo: PendingUndo) = undoBus.clear(undo)

        fun restore(undo: PendingUndo) {
            repo.restore(undo.ids)
            undoBus.clear(undo)
        }

        /** Current draft, for a recreated text field to start from. */
        fun draftNow(): String = draft.value

        fun onDraftChange(text: String) {
            if (loaded.value) recordDraft(text)
        }

        /**
         * Adopts [fieldText] (the field's content, which may be newer than what [onDraftChange] has delivered)
         * and persists it in a scope that outlives the screen.
         */
        fun flushDraft(fieldText: String) {
            if (!loaded.value) return
            persistInAppScope(recordDraft(fieldText))
        }

        private fun recordDraft(text: String): DraftIntent {
            val intent = drafts.registerIntent(text)
            draft.value = text
            pendingDraft.value = intent
            return intent
        }

        private fun persistInAppScope(intent: DraftIntent) {
            appScope.launch { drafts.write(intent.text, intent.seq) }
        }

        override fun onCleared() {
            pendingDraft.value?.let(::persistInAppScope)
        }

        /** Sends [text] (the field content); returns whether it was accepted. The draft is cleared only then. */
        fun send(text: String): Boolean {
            val body = text.trim()
            // A deleted active label must never be attached.
            val blocked = uid.value == null || !loaded.value || state.value.filterMissing
            if (body.isEmpty() || blocked) return false
            repo.add(body, (filter as? NoteFilter.ByLabel)?.let { listOf(it.labelId) }.orEmpty())
            flushDraft("")
            return true
        }
    }
