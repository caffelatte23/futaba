package io.github.caffelatte23.futaba.feature.labels

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.caffelatte23.futaba.R
import io.github.caffelatte23.futaba.core.designsystem.LabelPalette
import io.github.caffelatte23.futaba.core.model.Label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelEditScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LabelEditViewModel = hiltViewModel(),
) {
    val content by viewModel.content.collectAsStateWithLifecycle()
    val newLabel by viewModel.newLabel.collectAsStateWithLifecycle()
    val edits by viewModel.edits.collectAsStateWithLifecycle()
    val undo by viewModel.pendingUndo.collectAsStateWithLifecycle()
    val failed by viewModel.operationFailed.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    LabelEditSnackbar(undo, failed, snackbar, viewModel::undoDelete, viewModel::clearOperationError)
    Scaffold(
        modifier = modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.label_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        focus.clearFocus()
                        onBack()
                    }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.settings_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LabelList(
            content = content,
            entries = LabelEntries(newLabel, edits),
            actions =
                LabelActions(
                    viewModel::changeNewName,
                    viewModel::addLabel,
                    viewModel::changeName,
                    viewModel::commitName,
                    viewModel::setColor,
                    viewModel::deleteLabel,
                    viewModel::reorder,
                ),
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun LabelEditSnackbar(
    undo: PendingLabelUndo?,
    failed: Boolean,
    snackbar: SnackbarHostState,
    onUndo: (PendingLabelUndo) -> Unit,
    onClearError: () -> Unit,
) {
    val deleted = stringResource(R.string.label_deleted)
    val action = stringResource(R.string.label_undo)
    val failure = stringResource(R.string.label_operation_failed)
    val performUndo by rememberUpdatedState(onUndo)
    val clearError by rememberUpdatedState(onClearError)
    LaunchedEffect(undo, failed, deleted, action, failure) {
        val offer = undo
        if (offer != null) {
            val result = snackbar.showSnackbar(deleted, action, duration = SnackbarDuration.Indefinite)
            if (result == SnackbarResult.ActionPerformed) performUndo(offer)
        } else if (failed) {
            snackbar.showSnackbar(failure)
            clearError()
        }
    }
}

@Immutable
private data class LabelEntries(
    val newLabel: LabelInput,
    val edits: Map<String, LabelInput>,
)

@Suppress("LongParameterList") // UI events for the add field and the label rows.
private data class LabelActions(
    val changeNewName: (String) -> Unit,
    val add: () -> Unit,
    val changeName: (String, String) -> Unit,
    val commitName: (String) -> Unit,
    val color: (String, String) -> Unit,
    val delete: (String) -> Unit,
    val reorder: (List<String>) -> Unit,
)

@Composable
private fun LabelList(
    content: LabelEditContent,
    entries: LabelEntries,
    actions: LabelActions,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val drag = remember { LabelReorderState() }
    val sourceOrder = remember(content.labels) { content.labels.map(Label::id) }
    val latestOrder = rememberUpdatedState(sourceOrder)
    val reorder = rememberUpdatedState(actions.reorder)
    val labelsById = remember(content.labels) { content.labels.associateBy(Label::id) }
    val order = drag.ids ?: sourceOrder
    val labels = remember(order, labelsById) { order.mapNotNull(labelsById::get) }
    ScrollWhileDragging(drag, listState)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { AddLabelRow(entries.newLabel, actions.changeNewName, actions.add) }
        itemsIndexed(labels, key = { _, label -> label.id }) { index, label ->
            val up = stringResource(R.string.label_move_up)
            val down = stringResource(R.string.label_move_down)
            val moveActions = moveActions(index, order, up, down, actions.reorder)
            LabelRow(
                row =
                    LabelRowContent(
                        label,
                        content.usage[label.id] ?: 0,
                        entries.edits[label.id] ?: LabelInput(label.name),
                    ),
                actions = actions,
                modifier = Modifier.fillMaxWidth().offsetDraggedRow(label.id, drag, listState),
            ) {
                Box(
                    Modifier
                        .reorderHandle(label.id, drag, listState, latestOrder, reorder)
                        .semantics { customActions = moveActions }
                        .size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_label_drag),
                        stringResource(R.string.label_drag, label.name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Text(
                stringResource(R.string.label_edit_footer),
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AddLabelRow(
    input: LabelInput,
    onChange: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NameField(
            input = input,
            label = stringResource(R.string.label_new_name),
            onChange = onChange,
            onDone = onAdd,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onAdd, enabled = !input.saving, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.label_add))
        }
    }
}

@Composable
private fun ScrollWhileDragging(
    drag: LabelReorderState,
    state: LazyListState,
) {
    val density = LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    val maxStep = with(density) { 10.dp.toPx() }
    LaunchedEffect(drag.draggedId, drag, state, edge, maxStep) {
        while (drag.draggedId != null) {
            withFrameNanos { }
            val step = drag.edgeScroll(state.layoutInfo, edge, maxStep)
            if (step != 0f && state.scrollBy(step) != 0f) drag.move(0f, state.layoutInfo)
        }
    }
}

private data class LabelRowContent(
    val label: Label,
    val count: Int,
    val input: LabelInput,
)

@Composable
private fun LabelRow(
    row: LabelRowContent,
    actions: LabelActions,
    modifier: Modifier = Modifier,
    dragHandle: @Composable () -> Unit,
) {
    val (label, count, input) = row
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            dragHandle()
            NameField(
                input = input,
                label = stringResource(R.string.label_name),
                onChange = { actions.changeName(label.id, it) },
                onDone = {
                    actions.commitName(label.id)
                    focusManager.clearFocus()
                },
                modifier =
                    Modifier.weight(1f).onFocusChanged {
                        if (focused && !it.isFocused) actions.commitName(label.id)
                        focused = it.isFocused
                    },
            )
            Text(
                stringResource(R.string.label_usage, count),
                modifier = Modifier.padding(start = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            LabelColorMenu(label.color, onColor = { actions.color(label.id, it) })
            IconButton(onClick = { actions.delete(label.id) }) {
                Icon(
                    painterResource(R.drawable.ic_label_delete),
                    stringResource(R.string.label_delete, label.name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NameField(
    input: LabelInput,
    label: String,
    onChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val error =
        when (input.error) {
            LabelEditError.Blank -> R.string.label_name_blank
            LabelEditError.Duplicate -> R.string.label_name_duplicate
            LabelEditError.SaveFailed -> R.string.label_save_failed
            null -> null
        }
    OutlinedTextField(
        value = input.text,
        onValueChange = onChange,
        modifier = modifier,
        readOnly = input.saving,
        label = { Text(label) },
        isError = error != null,
        supportingText =
            if (error != null) {
                { Text(stringResource(error)) }
            } else {
                null
            },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
}

@Composable
private fun LabelColorMenu(
    color: String,
    onColor: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected =
        LabelPalette.entries.firstOrNull { it.stored.equals(color, ignoreCase = true) } ?: LabelPalette.Default
    val description = stringResource(R.string.label_color, stringResource(selected.title))
    Box(modifier) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = description }) {
            LabelSwatch(selected)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LabelPalette.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(stringResource(choice.title)) },
                    leadingIcon = { LabelSwatch(choice) },
                    onClick = {
                        expanded = false
                        onColor(choice.stored)
                    },
                )
            }
        }
    }
}

@Composable
private fun LabelSwatch(
    color: LabelPalette,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(16.dp)
            .background(color.swatch ?: MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
    )
}
