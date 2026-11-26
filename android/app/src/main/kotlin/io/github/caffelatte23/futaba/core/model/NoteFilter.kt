package io.github.caffelatte23.futaba.core.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface NoteFilter {
    @Serializable
    data object All : NoteFilter

    @Serializable
    data object Pinned : NoteFilter

    @Serializable
    data object Unlabeled : NoteFilter

    @Serializable
    data class ByLabel(
        val labelId: String,
    ) : NoteFilter

    @Serializable
    data object Trash : NoteFilter
}
