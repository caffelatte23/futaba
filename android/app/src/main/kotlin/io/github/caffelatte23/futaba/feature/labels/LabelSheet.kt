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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp

/**
 * Swipe, scrim and Back apply the staged choices like 完了; キャンセル discards them.
 * All state lives in [session] (see [LabelSheetSession]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelSheet(
    content: LabelSheetContent,
    ui: LabelSheetUi,
    session: LabelSheetSession,
) {
    val base = remember(content) { labelChecks(content.targets, content.labels) }
    // The sheet cannot be swiped or tapped away while a create runs (the session also ignores it).
    val creating by rememberUpdatedState(ui.creating)
    ModalBottomSheet(
        onDismissRequest = session::finish,
        sheetState =
            rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { it != SheetValue.Hidden || !creating },
            ),
    ) {
        Column(
            Modifier.navigationBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = session::cancel) { Text("キャンセル") }
                Text("ラベルを付ける", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = session::finish) { Text("完了") }
            }
            OutlinedTextField(
                value = ui.query,
                onValueChange = session::setQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("ラベルを検索・作成") },
                singleLine = true,
                isError = ui.createFailed,
                supportingText = if (ui.createFailed) ({ Text("作成できませんでした。もう一度お試しください") }) else null,
            )
            LazyColumn {
                creatableName(ui.query, content.labels)?.let { name ->
                    item(key = "create") {
                        Text(
                            if (ui.creating) "作成中…" else "「$name」を作成",
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !ui.creating) { session.create(name) }
                                .padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                items(content.labels.filter { it.name.contains(ui.query.trim(), ignoreCase = true) }, key = { it.id }) {
                    val shown = ui.overrides[it.id] ?: base[it.id] ?: LabelCheck.None
                    LabelRow(it.name, shown) { session.toggle(it.id, shown) }
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
