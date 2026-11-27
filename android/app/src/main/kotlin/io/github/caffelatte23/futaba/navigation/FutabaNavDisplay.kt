package io.github.caffelatte23.futaba.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.caffelatte23.futaba.feature.stream.StreamScreen

@Composable
fun FutabaNavDisplay(
    modifier: Modifier = Modifier,
    focusComposer: Boolean = false,
) {
    val backStack = rememberNavBackStack(Stream())
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        entryProvider =
            entryProvider {
                entry<Stream> { key ->
                    StreamScreen(
                        filter = key.filter,
                        focusComposer = focusComposer,
                        onNavigate = backStack::add,
                    )
                }
                // Placeholders: Tasks 8-13 replace these with real screens.
                entry<Detail> { Placeholder("Detail ${it.noteId}") }
                entry<Search> { Placeholder("Search") }
                entry<Settings> { Placeholder("Settings") }
                entry<LabelEdit> { Placeholder("LabelEdit") }
                entry<Trash> { Placeholder("Trash") }
            },
    )
}

@Composable
private fun Placeholder(name: String) {
    Text(name)
}
