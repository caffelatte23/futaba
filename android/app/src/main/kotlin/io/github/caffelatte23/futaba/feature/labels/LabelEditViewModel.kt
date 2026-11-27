package io.github.caffelatte23.futaba.feature.labels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.caffelatte23.futaba.core.data.DeletedLabel
import io.github.caffelatte23.futaba.core.data.DuplicateLabelNameException
import io.github.caffelatte23.futaba.core.data.NoteRepository
import io.github.caffelatte23.futaba.core.domain.labelUsage
import io.github.caffelatte23.futaba.core.model.Label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

private const val UNDO_WINDOW_MS = 5_000L

enum class LabelEditError { Blank, Duplicate, SaveFailed }

data class LabelInput(
    val text: String = "",
    val error: LabelEditError? = null,
    val saving: Boolean = false,
)

@Immutable
data class LabelEditContent(
    val labels: List<Label> = emptyList(),
    val usage: Map<String, Int> = emptyMap(),
)

class PendingLabelUndo internal constructor(
    internal val deleted: List<DeletedLabel>,
)

@HiltViewModel
@Suppress("TooManyFunctions") // Screen actions are owned by its navigation-entry ViewModel.
class LabelEditViewModel
    internal constructor(
        private val repo: NoteRepository,
        workContext: CoroutineContext,
    ) : ViewModel() {
        @Inject
        @Suppress("InjectDispatcher") // Counts run off the UI thread; unit tests use the other constructor.
        constructor(repo: NoteRepository) : this(repo, Dispatchers.Default)

        val content =
            combine(repo.labels, repo.notes) { labels, notes ->
                LabelEditContent(labels, labelUsage(notes))
            }.flowOn(workContext).stateIn(viewModelScope, SharingStarted.WhileSubscribed(), LabelEditContent())

        private val _newLabel = MutableStateFlow(LabelInput())
        val newLabel = _newLabel.asStateFlow()

        private val _edits = MutableStateFlow<Map<String, LabelInput>>(emptyMap())
        val edits = _edits.asStateFlow()

        private val _pendingUndo = MutableStateFlow<PendingLabelUndo?>(null)
        val pendingUndo = _pendingUndo.asStateFlow()

        private val _operationFailed = MutableStateFlow(false)
        val operationFailed = _operationFailed.asStateFlow()

        private val deleting = mutableSetOf<String>()

        fun changeNewName(text: String) {
            if (!_newLabel.value.saving) _newLabel.value = LabelInput(text)
        }

        fun addLabel() {
            val input = _newLabel.value
            submitName(input, { _newLabel.value = it ?: LabelInput() }) { name ->
                repo.createLabel(name, reuseExisting = false)
            }
        }

        fun changeName(
            id: String,
            text: String,
        ) {
            if (_edits.value[id]?.saving != true) setEdit(id, LabelInput(text))
        }

        fun commitName(id: String) {
            _edits.value[id]?.let { input ->
                submitName(input, { setEdit(id, it) }) { name -> repo.renameLabel(id, name) }
            }
        }

        private fun setEdit(
            id: String,
            input: LabelInput?,
        ) = _edits.update { if (input == null) it - id else it + (id to input) }

        @Suppress("TooGenericExceptionCaught") // The repository can fail while queuing a local write.
        private fun submitName(
            input: LabelInput,
            update: (LabelInput?) -> Unit,
            save: suspend (String) -> Unit,
        ) {
            if (input.saving) return
            val name = input.text.trim()
            if (name.isBlank()) {
                update(input.copy(error = LabelEditError.Blank))
                return
            }
            update(input.copy(error = null, saving = true))
            viewModelScope.launch {
                try {
                    save(name)
                    update(null)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: DuplicateLabelNameException) {
                    update(input.copy(error = LabelEditError.Duplicate))
                } catch (_: Exception) {
                    update(input.copy(error = LabelEditError.SaveFailed))
                }
            }
        }

        fun setColor(
            id: String,
            color: String,
        ) = repo.setLabelColor(id, color)

        fun reorder(ids: List<String>) = repo.reorderLabels(ids)

        fun deleteLabel(id: String) {
            if (!deleting.add(id)) return
            runLabelOperation {
                try {
                    val snapshot = repo.deleteLabelForUndo(id)
                    setEdit(id, null)
                    val undo = PendingLabelUndo(_pendingUndo.value?.deleted.orEmpty() + snapshot)
                    _pendingUndo.value = undo
                    viewModelScope.launch {
                        delay(UNDO_WINDOW_MS)
                        _pendingUndo.compareAndSet(undo, null)
                    }
                } finally {
                    deleting.remove(id)
                }
            }
        }

        fun undoDelete(undo: PendingLabelUndo) {
            if (!_pendingUndo.compareAndSet(undo, null)) return
            runLabelOperation {
                undo.deleted.forEach { repo.restoreLabel(it) }
            }
        }

        fun clearOperationError() {
            _operationFailed.value = false
        }

        @Suppress("TooGenericExceptionCaught") // The repository can fail while queuing a local write.
        private fun runLabelOperation(operation: suspend () -> Unit) {
            viewModelScope.launch {
                try {
                    operation()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _operationFailed.value = true
                }
            }
        }
    }
