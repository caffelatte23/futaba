package io.github.caffelatte23.futaba.entry.widget

import io.github.caffelatte23.futaba.core.model.Note

internal const val MAX_ROWS = 3

/** The repository supplies notes in descending creation order. */
internal fun captureWidgetRows(notes: List<Note>): List<String> =
    notes
        .asSequence()
        .filter { it.deletedAt == null }
        .take(MAX_ROWS)
        .map { it.text.lineSequence().first() }
        .toList()
