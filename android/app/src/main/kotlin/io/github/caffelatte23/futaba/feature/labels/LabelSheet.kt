package io.github.caffelatte23.futaba.feature.labels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.caffelatte23.futaba.core.model.Label
import io.github.caffelatte23.futaba.core.model.Note
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

/** The sheet's pending choices over [initial]; [changes] is what one `setLabels` call needs. */
class LabelSheetState(
    private val initial: Map<String, LabelCheck>,
) {
    private val chosen = mutableStateMapOf<String, LabelCheck>()

    fun check(id: String): LabelCheck = chosen[id] ?: initial[id] ?: LabelCheck.None

    /** All -> None; None and Some -> All. */
    fun toggle(id: String) {
        chosen[id] = if (check(id) == LabelCheck.All) LabelCheck.None else LabelCheck.All
    }

    fun attach(id: String) {
        chosen[id] = LabelCheck.All
    }

    /** (add, remove): only labels whose state differs from the start; an untouched Some stays as it is. */
    fun changes(): Pair<List<String>, List<String>> {
        val changed = chosen.filter { (id, c) -> c != (initial[id] ?: LabelCheck.None) }
        return changed.filterValues { it == LabelCheck.All }.keys.toList() to
            changed.filterValues { it == LabelCheck.None }.keys.toList()
    }
}

/** The name to offer 「作成」 for: typed text that matches no existing label (case-insensitive). */
fun creatableName(
    query: String,
    labels: List<Label>,
): String? = query.trim().takeIf { q -> q.isNotEmpty() && labels.none { it.name.equals(q, ignoreCase = true) } }

/** The notes being labeled and every label; wrapped so Compose can skip recomposition. */
@Immutable
data class LabelSheetContent(
    val targets: List<Note>,
    val labels: List<Label>,
)

/** Applies once, on 完了 or dismiss, as a single [onApply]; [onCreate] returns the new label's id. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelSheet(
    content: LabelSheetContent,
    onCreate: suspend (String) -> String,
    onApply: (add: List<String>, remove: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val (targets, labels) = content
    val state = remember { LabelSheetState(labelChecks(targets, labels)) }
    val apply by rememberUpdatedState(onApply)
    val dismiss by rememberUpdatedState(onDismiss)
    val create by rememberUpdatedState(onCreate)
    val finish = {
        val (add, remove) = state.changes()
        if (add.isNotEmpty() || remove.isNotEmpty()) apply(add, remove)
        dismiss()
    }
    var query by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = finish,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.navigationBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ラベルを付ける", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = finish) { Text("完了") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("ラベルを検索・作成") },
                singleLine = true,
            )
            LazyColumn {
                creatableName(query, labels)?.let { name ->
                    item(key = "create") {
                        Text(
                            "「$name」を作成",
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        state.attach(create(name))
                                        query = ""
                                    }
                                }.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                items(labels.filter { it.name.contains(query.trim(), ignoreCase = true) }, key = { it.id }) { label ->
                    LabelRow(label.name, state.check(label.id)) { state.toggle(label.id) }
                }
            }
        }
    }
}

@Composable
private fun LabelRow(
    name: String,
    check: LabelCheck,
    onToggle: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        TriStateCheckbox(
            state =
                when (check) {
                    LabelCheck.All -> ToggleableState.On
                    LabelCheck.Some -> ToggleableState.Indeterminate
                    LabelCheck.None -> ToggleableState.Off
                },
            onClick = null,
            modifier = Modifier.padding(12.dp),
        )
        Text("#$name", style = MaterialTheme.typography.bodyLarge)
    }
}
