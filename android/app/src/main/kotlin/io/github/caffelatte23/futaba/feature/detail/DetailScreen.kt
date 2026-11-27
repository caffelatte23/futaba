package io.github.caffelatte23.futaba.feature.detail

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.caffelatte23.futaba.R
import io.github.caffelatte23.futaba.core.designsystem.timestampStyle
import io.github.caffelatte23.futaba.core.model.Note
import io.github.caffelatte23.futaba.feature.stream.LabelChip
import io.github.caffelatte23.futaba.feature.stream.LabelNames
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(
    noteId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetailViewModel =
        hiltViewModel<DetailViewModel, DetailViewModel.Factory>(creationCallback = { it.create(noteId) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val note = state.note
    // The ViewModel owns the text; the field is (re)created from it once the note has been read.
    val editor = remember(state.ready) { SeededField(viewModel.textNow(), state.ready) }
    val field = editor.state
    val leave = {
        if (!viewModel.leave(editor.textIfSeeded())) onBack()
    }

    BackHandler(onBack = leave)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flush(editor.textIfSeeded()) }
    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(viewModel) { viewModel.events.collect { currentOnBack() } }
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.drop(1).collect(viewModel::onTextChange)
    }

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        DetailTopBar(
            note = note,
            fieldText = { field.text.toString() },
            onBack = leave,
            onTogglePin = viewModel::togglePin,
            onDelete = { viewModel.delete(editor.textIfSeeded()) },
        )
        if (note != null && state.ready) {
            DetailBody(note, field, state.labelNames, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DetailTopBar(
    note: Note?,
    fieldText: () -> String,
    onBack: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "戻る") }
        Text(
            note?.let { detailHeader(it.createdAt, it.updatedAt) }.orEmpty(),
            modifier = Modifier.weight(1f),
            style = timestampStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onTogglePin, enabled = note != null) {
            Icon(
                painterResource(R.drawable.ic_push_pin),
                contentDescription = if (note?.pinned == true) "ピン留めを外す" else "ピン留め",
                tint =
                    if (note?.pinned ==
                        true
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        DetailMenu(fieldText, onDelete)
    }
}

@Composable
private fun DetailMenu(
    fieldText: () -> String,
    onDelete: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "メニュー")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("テキストをコピー") },
                onClick = {
                    open = false
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("note", fieldText()))) }
                },
            )
            DropdownMenuItem(
                text = { Text("他のアプリに送る") },
                onClick = {
                    open = false
                    val send =
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, fieldText())
                    context.startActivity(Intent.createChooser(send, null))
                },
            )
            DropdownMenuItem(
                text = { Text("削除") },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun DetailBody(
    note: Note,
    field: TextFieldState,
    labelNames: LabelNames,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        // The field fills the body so tapping anywhere below the text focuses it; long text scrolls.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val fill = maxHeight
            BasicTextField(
                state = field,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = fill),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            )
        }
        FlowRow(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            note.labelIds.mapNotNull(labelNames.byId::get).forEach { LabelChip(it) }
            // TODO(Task 11): open the label sheet.
            AddLabelChip(onClick = {})
        }
    }
}

@Composable
private fun AddLabelChip(onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
        Text(
            "＋ ラベル",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The text field plus whether it was created from the ViewModel's hydrated text (not the empty loading field). */
private class SeededField(
    initial: String,
    private val seeded: Boolean,
) {
    val state = TextFieldState(initial)

    /** The field content, or null while it is only the empty placeholder shown before the note loaded. */
    fun textIfSeeded(): String? = if (seeded) state.text.toString() else null
}
