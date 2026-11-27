package io.github.caffelatte23.futaba.feature.stream

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.caffelatte23.futaba.core.model.Label
import io.github.caffelatte23.futaba.core.model.NoteFilter

/** Drawer content: the highlighted filter and the label rows; wrapped so Compose can skip recomposition. */
@Immutable
data class DrawerContent(
    val current: NoteFilter,
    val labels: List<DrawerLabel>,
)

data class DrawerLabel(
    val label: Label,
    val count: Int,
)

/** Labels in their `order`, each with its live-note count (0 when unused). */
fun drawerLabels(
    labels: List<Label>,
    usage: Map<String, Int>,
): List<DrawerLabel> = labels.sortedBy(Label::order).map { DrawerLabel(it, usage[it.id] ?: 0) }

@Composable
fun FutabaDrawer(
    content: DrawerContent,
    onSelect: (NoteFilter) -> Unit,
    onManageLabels: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = content.current
    ModalDrawerSheet(modifier, drawerContainerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Futaba", modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
            DrawerRow("すべて", current == NoteFilter.All) { onSelect(NoteFilter.All) }
            DrawerRow("ピン留め", current == NoteFilter.Pinned) { onSelect(NoteFilter.Pinned) }
            DrawerRow("ラベルなし", current == NoteFilter.Unlabeled) { onSelect(NoteFilter.Unlabeled) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "ラベル",
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content.labels.forEach { (label, count) ->
                DrawerRow("${label.name}  $count", current == NoteFilter.ByLabel(label.id)) {
                    onSelect(NoteFilter.ByLabel(label.id))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            DrawerRow("ラベルの管理", false, onManageLabels)
            DrawerRow("設定", false, onSettings)
        }
    }
}

@Composable
private fun DrawerRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(text) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
        colors =
            NavigationDrawerItemDefaults.colors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.onPrimary,
                unselectedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
    )
}
