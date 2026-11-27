package io.github.caffelatte23.futaba.core.domain

import java.util.TreeMap
import java.util.UUID

internal data class MergeDocuments(
    val notes: Map<String, Map<String, Any?>>,
    val labels: Map<String, Map<String, Any?>>,
)

internal fun planAccountMerge(
    sourceUid: String,
    source: MergeDocuments,
    destination: MergeDocuments,
): MergeDocuments {
    val labelIdsByName = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
    destination.labels.forEach { (id, fields) ->
        (fields["name"] as? String)?.let { labelIdsByName.putIfAbsent(it, id) }
    }
    val newLabels = mutableMapOf<String, Map<String, Any?>>()
    val labelIds = mutableMapOf<String, String>()
    for ((id, fields) in source.labels) {
        val name = fields["name"] as? String ?: continue
        labelIds[id] =
            labelIdsByName.getOrPut(name) {
                mergedId(sourceUid, id).also { newLabels[it] = fields }
            }
    }
    val newNotes =
        buildMap {
            for ((id, fields) in source.notes) {
                val newId = mergedId(sourceUid, id)
                if (newId in destination.notes) continue
                val oldIds = (fields["labelIds"] as? List<*>).orEmpty().filterIsInstance<String>()
                put(newId, fields + ("labelIds" to oldIds.mapNotNull(labelIds::get).distinct()))
            }
        }
    return MergeDocuments(newNotes, newLabels)
}

// Stable across retries, including when only some of the destination batches committed.
private fun mergedId(
    uid: String,
    id: String,
): String = "merged-" + UUID.nameUUIDFromBytes("$uid/$id".encodeToByteArray())
