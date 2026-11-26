package io.github.caffelatte23.futaba.core.domain

import io.github.caffelatte23.futaba.core.model.Label
import io.github.caffelatte23.futaba.core.model.Note
import io.github.caffelatte23.futaba.core.model.NoteFilter
import java.time.LocalDate
import java.time.ZoneId

private val queryWhitespace = Regex("""[\s\p{Z}\u0085]+""")

fun filterNotes(
    notes: List<Note>,
    filter: NoteFilter,
    query: String = "",
): List<Note> {
    val terms = query.split(queryWhitespace).filter(String::isNotEmpty)
    return notes
        .asSequence()
        .filter { (it.deletedAt != null) == (filter == NoteFilter.Trash) }
        .filter { it.matches(filter) }
        .filter { note -> terms.all { note.text.contains(it, ignoreCase = true) } }
        .sortedByDescending(Note::createdAt)
        .toList()
}

private fun Note.matches(filter: NoteFilter): Boolean =
    when (filter) {
        NoteFilter.All, NoteFilter.Trash -> true
        NoteFilter.Pinned -> pinned
        NoteFilter.Unlabeled -> labelIds.isEmpty()
        is NoteFilter.ByLabel -> filter.labelId in labelIds
    }

fun labelUsage(notes: List<Note>): Map<String, Int> =
    notes
        .asSequence()
        .filter { it.deletedAt == null }
        .flatMap { it.labelIds.distinct() }
        .groupingBy { it }
        .eachCount()

fun suggestLabels(
    prefix: String,
    labels: List<Label>,
    usage: Map<String, Int>,
): List<Label> =
    labels
        .filter { it.name.startsWith(prefix, ignoreCase = true) }
        .sortedWith(compareByDescending<Label> { usage[it.id] ?: 0 }.thenBy(Label::name))

fun groupByDay(
    notes: List<Note>,
    zone: ZoneId,
): List<Pair<LocalDate, List<Note>>> =
    notes
        .sortedByDescending(Note::createdAt)
        .groupBy { it.createdAt.atZone(zone).toLocalDate() }
        .toList()
        .sortedByDescending { (date, _) -> date }
