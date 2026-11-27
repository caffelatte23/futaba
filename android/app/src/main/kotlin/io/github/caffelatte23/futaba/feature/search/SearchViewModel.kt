package io.github.caffelatte23.futaba.feature.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.caffelatte23.futaba.core.data.NoteRepository
import io.github.caffelatte23.futaba.core.domain.filterNotes
import io.github.caffelatte23.futaba.core.domain.labelUsage
import io.github.caffelatte23.futaba.core.model.Note
import io.github.caffelatte23.futaba.core.model.NoteFilter
import io.github.caffelatte23.futaba.feature.stream.LabelNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

data class LabelChipUi(
    val id: String,
    val name: String,
    val count: Int,
)

@Immutable
data class SearchUiState(
    val query: String = "",
    val selectedLabelId: String? = null,
    /** All labels, most used first. */
    val chips: List<LabelChipUi> = emptyList(),
    /** Empty while there is neither a query nor a chip. */
    val results: List<Note> = emptyList(),
    val labelNames: LabelNames = LabelNames(emptyMap()),
    /** True when there is a real term or a chip; false shows the hint instead of results. */
    val searching: Boolean = false,
)

private const val STOP_TIMEOUT_MS = 5_000L
private const val DEBOUNCE_MS = 150L

/** One filtering outcome: the query and label that produced [notes] travel with them. */
private data class Filtered(
    val query: String,
    val labelId: String?,
    val notes: List<Note>,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel
    internal constructor(
        repo: NoteRepository,
        workContext: CoroutineContext,
    ) : ViewModel() {
        @Inject
        @Suppress("InjectDispatcher") // fixed Default dispatcher; tests use the other constructor
        constructor(repo: NoteRepository) : this(repo, Dispatchers.Default)

        private val query = MutableStateFlow("")
        private val selected = MutableStateFlow<String?>(null)

        // One upstream subscription shared by the chip and result branches.
        private val notes =
            repo.notes
                .onStart { emit(emptyList()) }
                .shareIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)
        private val labels =
            repo.labels
                .onStart { emit(emptyList()) }
                .shareIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)

        // Chip metadata depends only on repository updates, never on keystrokes.
        private val chipData =
            combine(notes, labels) { notes, labels ->
                val usage = labelUsage(notes)
                labels
                    .map { LabelChipUi(it.id, it.name, usage[it.id] ?: 0) }
                    .sortedWith(compareByDescending<LabelChipUi> { it.count }.thenBy { it.name }) to
                    LabelNames(labels.associate { it.id to it.name })
            }.flowOn(workContext)

        private val results =
            combine(notes, query.debounce(DEBOUNCE_MS), selected) { notes, text, labelId ->
                // Same parsed terms as the matcher: a separator-only query is not a search.
                val found =
                    if (queryTerms(text).isEmpty() && labelId == null) {
                        emptyList()
                    } else {
                        filterNotes(notes, labelId?.let(NoteFilter::ByLabel) ?: NoteFilter.All, text)
                    }
                Filtered(text, labelId, found)
            }.flowOn(workContext)

        val state: StateFlow<SearchUiState> =
            combine(chipData, results) { (chips, names), found ->
                SearchUiState(
                    query = found.query,
                    selectedLabelId = found.labelId,
                    chips = chips,
                    results = found.notes,
                    labelNames = names,
                    searching = queryTerms(found.query).isNotEmpty() || found.labelId != null,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SearchUiState())

        fun queryNow(): String = query.value

        fun onQueryChange(text: String) {
            query.value = text
        }

        /** Single-select: tapping the selected chip clears it. */
        fun toggleLabel(id: String) {
            selected.value = id.takeIf { it != selected.value }
        }
    }
