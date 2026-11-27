package io.github.caffelatte23.futaba.feature.stream

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import io.github.caffelatte23.futaba.core.data.PendingUndo
import io.github.caffelatte23.futaba.core.data.SharedSnippet
import io.github.caffelatte23.futaba.core.designsystem.FutabaMono
import io.github.caffelatte23.futaba.core.domain.dayHeader
import io.github.caffelatte23.futaba.core.model.NoteFilter
import io.github.caffelatte23.futaba.navigation.Detail
import io.github.caffelatte23.futaba.navigation.LabelEdit
import io.github.caffelatte23.futaba.navigation.Search
import io.github.caffelatte23.futaba.navigation.Settings
import io.github.caffelatte23.futaba.navigation.Stream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

// Drawer selection flushes the (global) draft before this entry and its ViewModel are replaced.
@Composable
fun StreamScreen(
    filter: NoteFilter,
    onNavigate: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StreamViewModel =
        hiltViewModel<StreamViewModel, StreamViewModel.Factory>(creationCallback = { it.create(filter) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The field is (re)created only after the draft is hydrated, starting from the current draft.
    val composer = remember(viewModel, state.draftLoaded) { viewModel.newComposer(state.draftLoaded) }
    ComposerEffects(
        composer,
        viewModel.sharedSnippets,
        viewModel::syncComposer,
        viewModel::flushComposer,
        viewModel.focusRequested,
    )
    val snackbar = remember { SnackbarHostState() }
    val undo by viewModel.pendingUndo.collectAsStateWithLifecycle()
    UndoSnackbarEffect(undo, snackbar, viewModel::now, viewModel::restore, viewModel::clearUndo)
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navigate: (NavKey) -> Unit = {
        viewModel.flushComposer(composer)
        onNavigate(it)
    }
    DrawerBackAndFallback(drawer, state.filterMissing, onFallback = { navigate(Stream(NoteFilter.All)) })
    ModalNavigationDrawer(
        drawerState = drawer,
        modifier = modifier,
        drawerContent = {
            StreamDrawer(
                content = remember(filter, state.drawerLabels) { DrawerContent(filter, state.drawerLabels) },
                drawer = drawer,
                onReselect = { viewModel.flushComposer(composer) },
                onNavigate = navigate,
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            TopBar(
                title = streamTitle(filter, state.labelNames),
                count = state.count,
                onMenu = { scope.launch { drawer.open() } },
                onSearch = { navigate(Search) },
            )
            StreamList(state, { navigate(Detail(it)) }, Modifier.weight(1f))
            SnackbarHost(snackbar)
            Composer(
                composer = composer,
                canSend = state.canSend,
                suggestions = remember(state.suggestions, state.usage) { Suggestions(state.suggestions, state.usage) },
                onSend = { if (viewModel.sendComposer(composer)) composer.focusRequester.requestFocus() },
            )
        }
    }
}

private fun streamTitle(
    filter: NoteFilter,
    names: LabelNames,
) = when (filter) {
    NoteFilter.Pinned -> "ピン留め"
    NoteFilter.Unlabeled -> "ラベルなし"
    is NoteFilter.ByLabel -> names.byId[filter.labelId].orEmpty()
    else -> "Futaba"
}

@Composable
private fun StreamDrawer(
    content: DrawerContent,
    drawer: DrawerState,
    onReselect: () -> Unit,
    onNavigate: (NavKey) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { drawer.snapTo(DrawerValue.Closed) } }
    FutabaDrawer(
        content = content,
        onSelect = { next ->
            if (next == content.current) {
                onReselect()
                scope.launch { drawer.close() }
            } else {
                onNavigate(Stream(next))
            }
        },
        onManageLabels = {
            close()
            onNavigate(LabelEdit)
        },
        onSettings = {
            close()
            onNavigate(Settings)
        },
    )
}

/** Back closes an open drawer first; a deleted active label falls back to the All view. */
@Composable
private fun DrawerBackAndFallback(
    drawer: DrawerState,
    filterMissing: Boolean,
    onFallback: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val fallback by rememberUpdatedState(onFallback)
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    LaunchedEffect(filterMissing) { if (filterMissing) fallback() }
}

@Composable
private fun ComposerEffects(
    composer: ComposerState,
    sharedSnippets: StateFlow<List<SharedSnippet>>,
    syncComposer: (ComposerState) -> Unit,
    flushComposer: (ComposerState) -> Unit,
    focusRequested: Flow<Unit>,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current
    val sync by rememberUpdatedState(syncComposer)
    val flush by rememberUpdatedState(flushComposer)

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { flush(composer) }
    LaunchedEffect(composer, sharedSnippets) {
        if (!composer.enabled) return@LaunchedEffect
        snapshotFlow { composer.text.text.toString() }
            .combine(sharedSnippets) { _, _ -> Unit }
            .collect { sync(composer) }
    }
    LaunchedEffect(composer, focusRequested) {
        if (!composer.enabled) return@LaunchedEffect
        focusRequested.collect {
            // The IME ignores show requests until the window has focus (cold start).
            snapshotFlow { windowInfo.isWindowFocused }.first { it }
            composer.focusRequester.requestFocus()
            keyboard?.show()
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    count: Int,
    onMenu: () -> Unit,
    onSearch: () -> Unit,
) {
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMenu) { Icon(painterResource(R.drawable.ic_menu), contentDescription = "メニュー") }
        Text(title, style = MaterialTheme.typography.titleLarge)
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

/** Shows the pending 「元に戻す」 offer for the time it has left; recreation just resumes it. */
@Composable
private fun UndoSnackbarEffect(
    undo: PendingUndo?,
    snackbar: SnackbarHostState,
    now: () -> Long,
    onRestore: (PendingUndo) -> Unit,
    onClear: (PendingUndo) -> Unit,
) {
    val clock by rememberUpdatedState(now)
    val restore by rememberUpdatedState(onRestore)
    val clear by rememberUpdatedState(onClear)
    LaunchedEffect(undo) {
        val offer = undo ?: return@LaunchedEffect
        // M3 durations are only 4s/10s, so wait out the remaining time ourselves, then drop the offer.
        val left = offer.expiresAt - clock()
        val message = if (offer.ids.size == 1) "削除しました" else "${offer.ids.size}件削除しました"
        val result =
            if (left > 0) {
                withTimeoutOrNull(left) {
                    snackbar.showSnackbar(message, "元に戻す", duration = SnackbarDuration.Indefinite)
                }
            } else {
                null
            }
        if (result == SnackbarResult.ActionPerformed) restore(offer) else clear(offer)
    }
}
