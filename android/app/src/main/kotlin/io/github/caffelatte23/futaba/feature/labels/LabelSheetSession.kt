package io.github.caffelatte23.futaba.feature.labels

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import io.github.caffelatte23.futaba.core.model.Label
import io.github.caffelatte23.futaba.core.model.Note
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LabelCheck { All, Some, None }

/** For each label: whether all, some or none of [notes] carry it. */
fun labelChecks(
    notes: List<Note>,
    labels: List<Label>,
): Map<String, LabelCheck> =
    labels.associate { label ->
        val have = notes.count { label.id in it.labelIds }
        label.id to
            when {
                notes.isNotEmpty() && have == notes.size -> LabelCheck.All
                have > 0 -> LabelCheck.Some
                else -> LabelCheck.None
            }
    }

/** (add, remove): only explicit choices that differ from [base]; an untouched Some stays as it is. */
fun labelChanges(
    overrides: Map<String, LabelCheck>,
    base: Map<String, LabelCheck>,
): Pair<List<String>, List<String>> {
    val changed = overrides.filter { (id, c) -> c != (base[id] ?: LabelCheck.None) }
    return changed.filterValues { it == LabelCheck.All }.keys.toList() to
        changed.filterValues { it == LabelCheck.None }.keys.toList()
}

/** The name to offer 「作成」 for: typed text that matches no existing label (case-insensitive). */
fun creatableName(
    query: String,
    labels: List<Label>,
): String? = query.trim().takeIf { q -> q.isNotEmpty() && labels.none { it.name.equals(q, ignoreCase = true) } }

/** What the open sheet shows: the user's explicit choices (over live membership), search text, create status. */
@Immutable
data class LabelSheetUi(
    val overrides: Map<String, LabelCheck> = emptyMap(),
    val query: String = "",
    val creating: Boolean = false,
    val createFailed: Boolean = false,
)

/**
 * One label-sheet session, owned by a ViewModel so it survives recomposition and ends with the selection.
 * Untouched labels always reflect the targets' current membership; only explicit choices are staged.
 * [finish] waits for a running create so the new label is never left unattached.
 */
@Stable
class LabelSheetSession(
    private val scope: CoroutineScope,
    private val createLabel: suspend (String) -> String,
    private val baseline: () -> Map<String, LabelCheck>,
    private val commit: (add: List<String>, remove: List<String>) -> Unit,
) {
    private val ui = MutableStateFlow<LabelSheetUi?>(null)
    val state: StateFlow<LabelSheetUi?> = ui
    private var pending: Job? = null

    fun open() {
        if (ui.value == null) ui.value = LabelSheetUi()
    }

    /** Ends the session and drops staged edits (also used when the selection empties). */
    fun reset() {
        pending?.cancel()
        pending = null
        ui.value = null
    }

    fun cancel() = reset()

    fun setQuery(query: String) = ui.update { it?.copy(query = query, createFailed = false) }

    /** Tapping what is [shown] as All stages None; anything else (None, Some) stages All. */
    fun toggle(
        id: String,
        shown: LabelCheck,
    ) = ui.update {
        it?.copy(overrides = it.overrides + (id to if (shown == LabelCheck.All) LabelCheck.None else LabelCheck.All))
    }

    // Any repository failure (signed out, uid changed) becomes an inline error.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun create(name: String) {
        if (ui.value?.creating != false) return
        ui.update { it?.copy(creating = true, createFailed = false) }
        pending =
            scope.launch {
                try {
                    val id = createLabel(name)
                    ui.update { it?.copy(overrides = it.overrides + (id to LabelCheck.All), query = "") }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ui.update { it?.copy(createFailed = true) }
                } finally {
                    ui.update { it?.copy(creating = false) }
                }
            }
    }

    /** Applies the staged choices as one [commit] (after a running create), then closes. */
    fun finish() {
        if (ui.value == null) return
        scope.launch {
            val create = pending?.takeIf { it.isActive }
            create?.join()
            // A create that failed while finishing keeps the sheet (error, query, staged edits) for retry.
            if (create != null && ui.value?.createFailed == true) return@launch
            applyStaged()
        }
    }

    private fun applyStaged() {
        ui.value?.let { staged ->
            val (add, remove) = labelChanges(staged.overrides, baseline())
            ui.value = null
            if (add.isNotEmpty() || remove.isNotEmpty()) commit(add, remove)
        }
    }
}

/** The notes being labeled and every label; wrapped so Compose can skip recomposition. */
@Immutable
data class LabelSheetContent(
    val targets: List<Note>,
    val labels: List<Label>,
)
