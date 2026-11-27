package io.github.caffelatte23.futaba.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.caffelatte23.futaba.R
import io.github.caffelatte23.futaba.core.data.LinkResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncSheet(
    noteCount: Int?,
    operation: SettingsOperation,
    onLink: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val result = operation.linkResult
    val done = result is LinkResult.Linked || result is LinkResult.Merged
    val sheet =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { next -> !operation.busy || next != SheetValue.Hidden },
        )
    ModalBottomSheet(
        onDismissRequest = { if (!operation.busy) onDismiss() },
        modifier = modifier,
        sheetState = sheet,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.settings_sync_title), style = MaterialTheme.typography.headlineSmall)
            if (!done) {
                if (noteCount != null) {
                    Text(stringResource(R.string.settings_sync_description, noteCount))
                }
                SyncDetails()
                val button = if (operation.busy) R.string.settings_sync_working else R.string.settings_choose_google
                Button(
                    onClick = onLink,
                    enabled = noteCount != null && !operation.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(button))
                }
            }
            SyncFeedback(result)
            TextButton(onClick = onDismiss, enabled = !operation.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (done) R.string.settings_sync_done else R.string.settings_sync_later))
            }
        }
    }
}

@Composable
private fun SyncDetails() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            R.string.settings_sync_private,
            R.string.settings_sync_offline,
            R.string.settings_sync_combines,
        ).forEach { id ->
            Text(
                "• ${stringResource(id)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncFeedback(result: LinkResult?) {
    when (result) {
        LinkResult.Linked -> Text(stringResource(R.string.settings_sync_linked))
        is LinkResult.Merged -> Text(stringResource(R.string.settings_sync_merged, result.count))
        is LinkResult.Failed -> Text(result.message, color = MaterialTheme.colorScheme.error)
        LinkResult.Cancelled, null -> Unit
    }
}
