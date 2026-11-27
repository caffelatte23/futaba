package io.github.caffelatte23.futaba.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.caffelatte23.futaba.R
import io.github.caffelatte23.futaba.core.designsystem.timestampStyle
import io.github.caffelatte23.futaba.feature.stream.NoteCard
import kotlinx.coroutines.flow.first

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val field = remember { TextFieldState(viewModel.queryNow(), TextRange(viewModel.queryNow().length)) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current

    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.collect(viewModel::onQueryChange)
    }
    LaunchedEffect(focus) {
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        focus.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "戻る") }
            SearchField(field, focus, Modifier.weight(1f).padding(end = 12.dp))
        }
        if (state.chips.isNotEmpty()) {
            FlowRow(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.chips.forEach { chip ->
                    FilterChipItem(chip, chip.id == state.selectedLabelId) { viewModel.toggleLabel(chip.id) }
                }
            }
        }
        SearchResults(state, onOpenNote, Modifier.weight(1f))
    }
}

@Composable
private fun SearchField(
    field: TextFieldState,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        state = field,
        modifier = modifier.focusRequester(focus),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
        lineLimits = TextFieldLineLimits.SingleLine,
        decorator = { inner ->
            Box(
                Modifier
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (field.text.isEmpty()) {
                    Text(
                        "検索",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun FilterChipItem(
    chip: LabelChipUi,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("#${chip.name}", style = MaterialTheme.typography.labelLarge)
            Text(chip.count.toString(), style = timestampStyle())
        }
    }
}

@Composable
private fun SearchResults(
    state: SearchUiState,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.query.isBlank() && state.selectedLabelId == null) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "ラベルは本文に #ことば と書くと自動で付きます",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val mark = SpanStyle(background = MaterialTheme.colorScheme.primary, color = MaterialTheme.colorScheme.onPrimary)
    val summary =
        listOfNotNull(
            queryTerms(state.query).takeIf { it.isNotEmpty() }?.joinToString(" ") { "「$it」" },
            state.selectedLabelId?.let { "#${state.labelNames.byId[it].orEmpty()}" },
        ).joinToString(" · ") + " — ${state.results.size}件"
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "summary") {
            Text(
                summary,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(state.results, key = { it.id }) { note ->
            val body = remember(note.text, state.query, mark) { highlight(note.text, state.query, mark) }
            NoteCard(note, state.labelNames, { onOpenNote(note.id) }, body = body)
        }
    }
}
