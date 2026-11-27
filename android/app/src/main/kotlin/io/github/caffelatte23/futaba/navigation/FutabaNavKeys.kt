package io.github.caffelatte23.futaba.navigation

import androidx.navigation3.runtime.NavKey
import io.github.caffelatte23.futaba.core.model.NoteFilter
import kotlinx.serialization.Serializable

@Serializable
data class Stream(
    val filter: NoteFilter = NoteFilter.All,
) : NavKey

@Serializable
data class Detail(
    val noteId: String,
) : NavKey

@Serializable
data object Search : NavKey

@Serializable
data object Settings : NavKey

@Serializable
data object LabelEdit : NavKey

@Serializable
data object Trash : NavKey
