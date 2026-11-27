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
import io.github.caffelatte23.futaba.feature.labels.LabelSheetSession
import io.github.caffelatte23.futaba.feature.labels.LabelSheetUi
import io.github.caffelatte23.futaba.feature.labels.labelChecks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    /** Label view only: labels loaded (signed in) and the label exists, so notes may be sent here. */
    val labelViewReady: Boolean = false,
    /** Ids of the selected notes (selection mode while non-empty); only notes still shown. */
    val selection: Set<String> = emptySet(),
    val selectedNotes: List<Note> = emptyList(),
    /** The open label sheet, if any; ends with the selection. */
    val sheet: LabelSheetUi? = null,
    /** All labels in `order`, for the label sheet. */
    val labels: List<Label> = emptyList(),
    /** False while only the placeholder empty list has been seen (selection is not pruned then). */
    val notesLoaded: Boolean = false,
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
        val sharedSnippets = drafts.sharedSnippets

        init {
            // A different account must not inherit the previous one's selection or staged label edits.
            viewModelScope.launch {
                uid.drop(1).collect {
                    selected.value = emptySet()
                    labelSheet.reset()
                }
            }
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

        // Start with an empty placeholder so the composer never waits for Firestore's first emission;
        // only emissions flagged true are real data (the placeholder must not prune the selection).
        private val notesOrEmpty = repo.notes.map { it to true }.onStart { emit(emptyList<Note>() to false) }

        // Labels count as loaded only for a signed-in user, after an emission for that user.
        @OptIn(ExperimentalCoroutinesApi::class)
        private val labelsOrEmpty =
            uid.flatMapLatest { id ->
                if (id == null) {
                    flowOf(emptyList<Label>() to false)
                } else {
                    repo.labels.map { it to true }.onStart { emit(emptyList<Label>() to false) }
                }
            }

        private val selected = MutableStateFlow<Set<String>>(emptySet())

        /** The label sheet over the selection; its baseline is always the current selection and labels. */
        val labelSheet =
            LabelSheetSession(
                scope = viewModelScope,
                createLabel = repo::createLabel,
                baseline = { labelChecks(state.value.selectedNotes, state.value.labels) },
                commit = ::applyLabels,
            )

        private val baseState: Flow<StreamUiState> =
            combine(
                notesOrEmpty,
                labelsOrEmpty,
                draft,
                uid,
                loaded,
            ) { notesPair, (labels, labelsLoaded), text, id, ready ->
                val (all, notesLoaded) = notesPair
                val shown = filterNotes(all, filter)
                val usage = labelUsage(all)
                val labelViewReady = labelsLoaded && labels.any { (filter as? NoteFilter.ByLabel)?.labelId == it.id }
                val prefix = trailingHashtag.find(text)?.groupValues?.get(1)
                StreamUiState(
                    sections =
                        groupByDay(shown.filterNot(Note::pinned), ZoneId.systemDefault())
                            .map { (date, list) -> DaySection(date, list) },
                    pinned = shown.filter(Note::pinned),
                    count = shown.size,
                    draft = text,
                    suggestions = prefix?.let { suggestLabels(it, labels, usage) }.orEmpty(),
                    canSend = text.isNotBlank() && id != null && (filter !is NoteFilter.ByLabel || labelViewReady),
                    draftLoaded = ready,
                    labelNames = LabelNames(labels.associate { it.id to it.name }),
                    usage = usage,
                    drawerLabels = drawerLabels(labels, usage),
                    filterMissing = labelsLoaded && filter is NoteFilter.ByLabel && !labelViewReady,
                    labelViewReady = labelViewReady,
                    labels = labels.sortedBy(Label::order),
                    notesLoaded = notesLoaded,
                )
            }

        // Selection is cut down to the notes still shown, so deleted or moved notes drop out by themselves.
        val state: StateFlow<StreamUiState> =
            combine(baseState, selected, labelSheet.state) { base, picked, sheet ->
                val notes = (base.pinned + base.sections.flatMap(DaySection::notes)).filter { it.id in picked }
                if (!base.notesLoaded) {
                    // Placeholder emission (e.g. after a resubscribe): keep selection and sheet untouched.
                    return@combine base.copy(selection = picked, sheet = sheet)
                }
                // Stored ids are cut to what is shown, so a note that vanishes and returns is not re-selected.
                if (notes.size != picked.size) selected.value = notes.mapTo(LinkedHashSet(), Note::id)
                if (notes.isEmpty()) labelSheet.reset()
                base.copy(
                    selection = notes.mapTo(LinkedHashSet(), Note::id),
                    selectedNotes = notes,
                    sheet = sheet.takeIf { notes.isNotEmpty() },
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StreamUiState())

        fun toggleSelect(id: String) {
            val now = state.value.selection
            selected.value = if (id in now) now - id else now + id
        }

        fun clearSelection() {
            selected.value = emptySet()
        }

        /** One `setLabels` for the whole selection, then leaves selection mode. */
        fun applyLabels(
            add: List<String>,
            remove: List<String>,
        ) {
            val ids = state.value.selection.toList()
            if (ids.isNotEmpty() && (add.isNotEmpty() || remove.isNotEmpty())) repo.setLabels(ids, add, remove)
            clearSelection()
        }

        fun deleteSelected() {
            val ids = state.value.selection.toList()
            if (ids.isNotEmpty()) {
                repo.delete(ids)
                undoBus.postDeleted(ids)
            }
            clearSelection()
        }

        /** Unpins when every selected note is pinned, otherwise pins them all. */
        fun pinSelected() {
            val notes = state.value.selectedNotes
            val pin = !notes.all(Note::pinned)
            notes.forEach { repo.setPinned(it.id, pin) }
            clearSelection()
        }

        /** Emits when a newer focus request arrives (and was not already served by another screen). */
        val focusRequested: Flow<Unit> =
            focusRequests.requests.filter(focusRequests::serve).map { }

        /** The pending 「元に戻す」 offer (from Detail or bulk delete); survives screen recreation. */
        val pendingUndo = undoBus.pending

        fun now() = undoBus.now()

        fun clearUndo(undo: PendingUndo) = undoBus.clear(undo)

        fun restore(undo: PendingUndo) {
            repo.restore(undo.ids)
            undoBus.clear(undo)
        }

        fun draftNow(): String = draft.value

        fun newComposer(enabled: Boolean): ComposerState {
            val initial = drafts.latestIntent.value
            return ComposerState(initial?.text ?: draftNow(), enabled, hydratedThrough = initial?.seq ?: 0L)
        }

        fun syncComposer(composer: ComposerState) {
            if (!composer.enabled) return
            val edit = composer.takeDraftEdit(sharedSnippets.value)
            if (edit != null) {
                onDraftChange(edit)
            } else {
                // A remount may have hydrated from a share that arrived while the screen was away.
                draft.value = composer.text.text.toString()
            }
            drafts.consumeSharedSnippets(composer.sharedThrough)
        }

        fun flushComposer(composer: ComposerState) {
            syncComposer(composer)
            flushDraft(null)
        }

        fun onDraftChange(text: String) {
            if (loaded.value) recordDraft(text)
        }

        /**
         * Persists the most recent local edit in a scope that outlives the screen.
         * [fieldText] carries any edit that the field observer has yet to deliver.
         */
        fun flushDraft(fieldText: String?) {
            if (!loaded.value) return
            val pending = fieldText?.let(::recordDraft) ?: pendingDraft.value
            pending?.let(::persistInAppScope)
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

        fun sendComposer(composer: ComposerState): Boolean {
            syncComposer(composer)
            val accepted = composer.textIfReady()?.let(::send) ?: false
            if (accepted) composer.clearAfterSend()
            return accepted
        }

        /** Sends [text] (the field content); returns whether it was accepted. The draft is cleared only then. */
        fun send(text: String): Boolean {
            val body = text.trim()
            // A label view only sends once its label is known to exist (not deleted, not still loading).
            val blocked =
                uid.value == null || !loaded.value || (filter is NoteFilter.ByLabel && !state.value.labelViewReady)
            if (body.isEmpty() || blocked) return false
            repo.add(body, (filter as? NoteFilter.ByLabel)?.let { listOf(it.labelId) }.orEmpty())
            flushDraft("")
            return true
        }
    }
