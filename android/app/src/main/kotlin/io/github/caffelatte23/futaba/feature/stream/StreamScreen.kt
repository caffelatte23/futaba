package io.github.caffelatte23.futaba.feature.stream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import io.github.caffelatte23.futaba.R
import io.github.caffelatte23.futaba.core.designsystem.FutabaMono
import io.github.caffelatte23.futaba.core.domain.dayHeader
import io.github.caffelatte23.futaba.core.model.NoteFilter
import io.github.caffelatte23.futaba.navigation.Detail
import io.github.caffelatte23.futaba.navigation.Search
import kotlinx.coroutines.flow.first
import java.time.LocalDate

@Composable
fun StreamScreen(
    filter: NoteFilter,
    focusComposer: Boolean,
    onNavigate: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StreamViewModel =
        hiltViewModel<StreamViewModel, StreamViewModel.Factory>(creationCallback = { it.create(filter) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The field is (re)created only after the draft is hydrated, starting from the current draft.
    val composer = remember(state.draftLoaded) { ComposerState(viewModel.draftNow(), state.draftLoaded) }
    val keyboard = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { composer.textIfReady()?.let(viewModel::flushDraft) }
    LaunchedEffect(composer) {
        if (!composer.enabled) return@LaunchedEffect
        snapshotFlow { composer.text.text.toString() }.collect { text ->
            // The initial field value and the programmatic clear already match the ViewModel.
            if (text != viewModel.draftNow()) viewModel.onDraftChange(text)
        }
    }
    LaunchedEffect(composer) {
        if (composer.enabled && viewModel.wantsStartupFocus(focusComposer)) {
            // The IME ignores show requests until the window has focus (cold start).
            snapshotFlow { windowInfo.isWindowFocused }.first { it }
            composer.focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        TopBar(count = state.count, onSearch = { onNavigate(Search) })
        StreamList(state, { onNavigate(Detail(it)) }, Modifier.weight(1f))
        Composer(
            composer = composer,
            canSend = state.canSend,
            suggestions = remember(state.suggestions, state.usage) { Suggestions(state.suggestions, state.usage) },
            onSend = {
                if (viewModel.send(composer.text.text.toString())) {
                    composer.text.clearText()
                    composer.focusRequester.requestFocus()
                }
            },
        )
    }
}

@Composable
private fun TopBar(
    count: Int,
    onSearch: () -> Unit,
) {
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // Menu opens the drawer in Task 10.
        IconButton(onClick = {}) { Icon(painterResource(R.drawable.ic_menu), contentDescription = "メニュー") }
        Text("Futaba", style = MaterialTheme.typography.titleLarge)
        Text(
            "$count",
            modifier = Modifier.padding(start = 8.dp).weight(1f),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FutabaMono),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onSearch) { Icon(painterResource(R.drawable.ic_search), contentDescription = "検索") }
    }
}

@Composable
private fun StreamList(
    state: StreamUiState,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now() }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.pinned.isNotEmpty()) {
            item(key = "pinned-header") { SectionHeader("ピン留め ${state.pinned.size}件") }
            items(state.pinned, key = { it.id }) { NoteCard(it, state.labelNames, { onOpenNote(it.id) }) }
        }
        state.sections.forEach { section ->
            item(key = "day-${section.date}") { SectionHeader(dayHeader(section.date, today)) }
            items(section.notes, key = { it.id }) { NoteCard(it, state.labelNames, { onOpenNote(it.id) }) }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
