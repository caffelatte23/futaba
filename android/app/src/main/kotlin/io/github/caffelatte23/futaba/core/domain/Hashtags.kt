package io.github.caffelatte23.futaba.core.domain

import io.github.caffelatte23.futaba.core.model.Label
import java.util.TreeSet

private val hashtagPattern = Regex("""(?<![\p{L}\p{N}/&])[#＃]([\p{L}\p{N}_-]+)""")

data class ResolvedLabels(
    val existingIds: List<String>,
    val newNames: List<String>,
)

fun parseHashtags(text: String): List<String> {
    val seen = TreeSet(String.CASE_INSENSITIVE_ORDER)
    return hashtagPattern
        .findAll(text)
        .map { it.groupValues[1] }
        .filter { seen.add(it) }
        .toList()
}

fun resolveLabels(
    names: List<String>,
    existing: List<Label>,
): ResolvedLabels {
    val existingIds = mutableListOf<String>()
    val newNames = mutableListOf<String>()
    for (name in names) {
        val label = existing.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (label == null) {
            newNames.add(name)
        } else {
            existingIds.add(label.id)
        }
    }
    return ResolvedLabels(existingIds, newNames)
}
