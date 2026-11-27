package io.github.caffelatte23.futaba.feature.stream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.caffelatte23.futaba.core.designsystem.FutabaMono
import io.github.caffelatte23.futaba.core.model.Note
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private const val BODY_MAX_LINES = 8

@Composable
fun NoteCard(
    note: Note,
    labelNames: LabelNames,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    body: AnnotatedString = AnnotatedString(note.text),
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = BODY_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                note.createdAt.atZone(ZoneId.systemDefault()).format(timeFormat),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FutabaMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val names = note.labelIds.mapNotNull(labelNames.byId::get)
            if (names.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    names.forEach { LabelChip(it) }
                }
            }
        }
    }
}

@Composable
fun LabelChip(
    name: String,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = CircleShape, color = MaterialTheme.colorScheme.background) {
        Text(
            "#$name",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
