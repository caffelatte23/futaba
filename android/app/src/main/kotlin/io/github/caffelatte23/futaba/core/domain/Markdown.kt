package io.github.caffelatte23.futaba.core.domain

import io.github.caffelatte23.futaba.core.model.Label
import io.github.caffelatte23.futaba.core.model.Note
import java.time.ZoneId
import java.util.TreeSet

fun toMarkdown(
    notes: List<Note>,
    labels: List<Label>,
    zone: ZoneId,
): String {
    val labelsById = labels.associateBy(Label::id)
    val days =
        notes
            .filter { it.deletedAt == null }
            .sortedByDescending(Note::createdAt)
            .groupBy { it.createdAt.atZone(zone).toLocalDate() }
            .toSortedMap(reverseOrder())
    return days.entries.joinToString("\n\n", postfix = if (days.isEmpty()) "" else "\n") { (day, dayNotes) ->
        "## $day\n\n" +
            dayNotes.joinToString("\n\n---\n\n") { note ->
                val seen = TreeSet(String.CASE_INSENSITIVE_ORDER).apply { addAll(parseHashtags(note.text)) }
                val missing = note.labelIds.mapNotNull { labelsById[it]?.name }.filter(seen::add)
                if (missing.isEmpty()) {
                    note.text
                } else {
                    val separator = if (note.text.lastOrNull()?.isWhitespace() == false) " " else ""
                    note.text + missing.joinToString(" ", prefix = separator) { "#$it" }
                }
            }
    }
}
