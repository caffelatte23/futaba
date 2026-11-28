package io.github.caffelatte23.futaba.feature.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.caffelatte23.futaba.R
import io.github.caffelatte23.futaba.core.data.AuthAccount
import io.github.caffelatte23.futaba.core.data.ThemeMode

private data class SettingsContent(
    val account: AuthAccount?,
    val counts: SettingsNoteCounts?,
    val pendingWrites: Int,
    val theme: ThemeMode,
    val openKeyboard: Boolean,
    val busy: Boolean,
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onTrash: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val counts by viewModel.noteCounts.collectAsStateWithLifecycle()
    val pending by viewModel.pendingWrites.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val openKeyboard by viewModel.openKeyboardOnLaunch.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val export =
        rememberLauncherForActivityResult(CreateDocument("text/markdown")) { uri -> uri?.let(viewModel::export) }

    val message = operation.message?.let { stringResource(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.clearMessage()
        }
    }
    BackHandler(enabled = operation.busy) {}

    Box(modifier) {
        Scaffold(
            topBar = { SettingsTopBar(operation.busy, onBack) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            SettingsRows(
                content = SettingsContent(account, counts, pending, theme, openKeyboard, operation.busy),
                padding = padding,
                onExport = { export.launch(viewModel.exportFilename()) },
                onTrash = onTrash,
                onTheme = viewModel::setTheme,
                onKeyboard = viewModel::setOpenKeyboardOnLaunch,
                onSignOut = viewModel::signOut,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(
    busy: Boolean,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.settings_title)) },
        navigationIcon = {
            IconButton(onClick = onBack, enabled = !busy) {
                Icon(painterResource(R.drawable.ic_settings_back), stringResource(R.string.settings_back))
            }
        },
    )
}

@Composable
@Suppress("LongParameterList") // Screen content combines UI state with navigation and picker callbacks.
private fun SettingsRows(
    content: SettingsContent,
    padding: PaddingValues,
    onExport: () -> Unit,
    onTrash: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onKeyboard: (Boolean) -> Unit,
    onSignOut: () -> Unit,
) {
    Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
        SettingsSection(stringResource(R.string.settings_sync))
        AccountRow(content.account?.email, content.pendingWrites)
        HorizontalDivider()
        SettingsSection(stringResource(R.string.settings_data))
        SettingsRow(
            text = stringResource(R.string.settings_export),
            subtitle = content.counts?.let { stringResource(R.string.settings_count, it.active) },
            enabled = !content.busy && content.counts != null,
            onClick = onExport,
        )
        SettingsRow(
            text = stringResource(R.string.settings_trash),
            subtitle = content.counts?.let { stringResource(R.string.settings_trash_count, it.trashed) },
            enabled = !content.busy,
            onClick = onTrash,
        )
        HorizontalDivider()
        SettingsSection(stringResource(R.string.settings_display))
        ThemeSetting(content.theme, onTheme)
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_open_keyboard)) },
            trailingContent = {
                Switch(
                    checked = content.openKeyboard,
                    onCheckedChange = null,
                )
            },
            modifier =
                Modifier.toggleable(
                    value = content.openKeyboard,
                    role = Role.Switch,
                    onValueChange = onKeyboard,
                ),
        )
        HorizontalDivider()
        TextButton(onClick = onSignOut, enabled = !content.busy, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.settings_sign_out), color = MaterialTheme.colorScheme.error)
        }
        Text(
            stringResource(R.string.settings_sign_out_description),
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AccountRow(
    email: String?,
    pending: Int,
) {
    val status =
        if (pending == 0) {
            stringResource(R.string.settings_sent)
        } else {
            stringResource(R.string.settings_pending, pending)
        }
    ListItem(
        headlineContent = { Text(email ?: stringResource(R.string.settings_google_account)) },
        supportingContent = { Text(status) },
    )
}

@Composable
private fun SettingsRow(
    text: String,
    subtitle: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(text) },
        supportingContent = subtitle?.let { { Text(it) } },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun ThemeSetting(
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_theme)) },
            trailingContent = { Text(stringResource(theme.titleRes())) },
            modifier = Modifier.clickable { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThemeMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(stringResource(mode.titleRes())) },
                    onClick = {
                        onTheme(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun ThemeMode.titleRes(): Int =
    when (this) {
        ThemeMode.System -> R.string.settings_theme_system
        ThemeMode.Light -> R.string.settings_theme_light
        ThemeMode.Dark -> R.string.settings_theme_dark
    }
