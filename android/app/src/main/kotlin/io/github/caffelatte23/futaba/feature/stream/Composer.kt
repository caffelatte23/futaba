package io.github.caffelatte23.futaba.feature.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import io.github.caffelatte23.futaba.R
import io.github.caffelatte23.futaba.core.designsystem.FutabaMono
import io.github.caffelatte23.futaba.core.model.Label

/** Label suggestions with their usage counts; wrapped so Compose can skip recomposition. */
@Immutable
data class Suggestions(
    val labels: List<Label>,
    val usage: Map<String, Int>,
)

/** The text field state plus the focus handle the screen needs to drive it. */
class ComposerState(
    initial: String,
    val enabled: Boolean,
) {
    val text = TextFieldState(initial, TextRange(initial.length))
    val focusRequester = FocusRequester()
}

private const val MAX_LINES = 8
private const val MAX_SUGGESTIONS = 4

@Composable
fun Composer(
    composer: ComposerState,
    canSend: Boolean,
    suggestions: Suggestions,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (suggestions.labels.isNotEmpty()) SuggestionList(suggestions, composer.text::pickSuggestion)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicTextField(
                    state = composer.text,
                    enabled = composer.enabled,
                    modifier = Modifier.weight(1f).focusRequester(composer.focusRequester),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = MAX_LINES),
                    decorator = { inner ->
                        Box(
                            Modifier
                                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            if (composer.text.text.isEmpty()) {
                                Text(
                                    "新しいアイデア",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.size(44.dp),
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.background,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                ) {
                    Icon(painterResource(R.drawable.ic_send), contentDescription = "送信")
                }
            }
            Text(
                "下書き保存済み · Enterで改行",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SuggestionList(
    suggestions: Suggestions,
    onPick: (Label) -> Unit,
) {
    Column {
        Text(
            "ラベル候補 · よく使う順",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        suggestions.labels.take(MAX_SUGGESTIONS).forEach { label ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(label) }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("#${label.name}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${suggestions.usage[label.id] ?: 0}",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FutabaMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Replaces the trailing `#prefix` with the chosen label. */
private fun TextFieldState.pickSuggestion(label: Label) {
    edit {
        val hash = asCharSequence().lastIndexOfAny(charArrayOf('#', '＃'))
        if (hash >= 0) replace(hash, length, "#${label.name} ")
    }
}
