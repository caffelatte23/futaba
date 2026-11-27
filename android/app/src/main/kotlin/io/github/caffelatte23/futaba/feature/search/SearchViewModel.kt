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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

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
)

private const val STOP_TIMEOUT_MS = 5_000L

@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        repo: NoteRepository,
    ) : ViewModel() {
        private val query = MutableStateFlow("")
        private val selected = MutableStateFlow<String?>(null)

        val state: StateFlow<SearchUiState> =
            combine(
                repo.notes.onStart { emit(emptyList()) },
                repo.labels.onStart { emit(emptyList()) },
                query,
                selected,
            ) { notes, labels, text, labelId ->
                val usage = labelUsage(notes)
                SearchUiState(
                    query = text,
                    selectedLabelId = labelId,
                    chips =
                        labels
                            .map { LabelChipUi(it.id, it.name, usage[it.id] ?: 0) }
                            .sortedWith(compareByDescending<LabelChipUi> { it.count }.thenBy { it.name }),
                    results =
                        if (text.isBlank() && labelId == null) {
                            emptyList()
                        } else {
                            filterNotes(notes, labelId?.let(NoteFilter::ByLabel) ?: NoteFilter.All, text)
                        },
                    labelNames = LabelNames(labels.associate { it.id to it.name }),
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
