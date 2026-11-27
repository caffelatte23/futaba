package io.github.caffelatte23.futaba.feature.stream

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.caffelatte23.futaba.R
import io.github.caffelatte23.futaba.core.model.NoteFilter

@Immutable
internal data class SelectionActions(
    val onClose: () -> Unit,
    val onLabel: () -> Unit,
    val onPin: () -> Unit,
    val onDelete: () -> Unit,
)

@Composable
internal fun SelectionBar(
    count: Int,
    allPinned: Boolean,
    enabled: Boolean,
    actions: SelectionActions,
) {
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = actions.onClose,
        ) { Icon(painterResource(R.drawable.ic_close), contentDescription = "選択を解除") }
        Text("${count}件を選択", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        IconButton(onClick = actions.onLabel, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_label), contentDescription = "ラベル")
        }
        IconButton(onClick = actions.onPin, enabled = enabled) {
            Icon(
                painterResource(R.drawable.ic_push_pin),
                contentDescription = if (allPinned) "ピン留めを外す" else "ピン留め",
            )
        }
        IconButton(
            onClick = actions.onDelete,
            enabled = enabled,
        ) { Icon(painterResource(R.drawable.ic_delete), contentDescription = "削除") }
    }
}

@Composable
internal fun StreamComposer(
    composer: ComposerState,
    state: StreamUiState,
    send: () -> Boolean,
) {
    Composer(
        composer = composer,
        canSend = state.canSend,
        suggestions = remember(state.suggestions, state.usage) { Suggestions(state.suggestions, state.usage) },
        onSend = {
            if (send()) composer.focusRequester.requestFocus()
        },
    )
}

internal fun streamTitle(
    filter: NoteFilter,
    names: LabelNames,
) = when (filter) {
    NoteFilter.Pinned -> "ピン留め"
    NoteFilter.Unlabeled -> "ラベルなし"
    is NoteFilter.ByLabel -> names.byId[filter.labelId].orEmpty()
    else -> "Futaba"
}
