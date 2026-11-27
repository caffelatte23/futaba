package io.github.caffelatte23.futaba.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.caffelatte23.futaba.core.data.NoteRepository
import io.github.caffelatte23.futaba.core.domain.filterNotes
import io.github.caffelatte23.futaba.core.model.NoteFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

@HiltViewModel
class TrashViewModel
    internal constructor(
        private val repo: NoteRepository,
        workContext: CoroutineContext,
    ) : ViewModel() {
        @Inject
        @Suppress("InjectDispatcher") // Filtering runs off the UI thread; tests use the other constructor.
        constructor(repo: NoteRepository) : this(repo, Dispatchers.Default)

        val notes =
            repo.notes
                .map { filterNotes(it, NoteFilter.Trash) }
                .flowOn(workContext)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

        fun restore(id: String) = repo.restore(listOf(id))
    }
