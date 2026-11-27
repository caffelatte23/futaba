package io.github.caffelatte23.futaba.core.domain

import java.util.TreeMap
import java.util.UUID

private const val MERGED_FROM_UID = "mergedFromUid"

internal data class MergeDocuments(
    val notes: Map<String, Map<String, Any?>>,
    val labels: Map<String, Map<String, Any?>>,
)

internal data class AccountMergePlan(
    val notes: Map<String, Map<String, Any?>>,
    val labels: Map<String, Map<String, Any?>>,
    val noteCount: Int,
)

internal fun planAccountMerge(
    sourceUid: String,
    source: MergeDocuments,
    destination: MergeDocuments,
): AccountMergePlan {
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
    val noteIds = source.notes.keys.associateBy { mergedId(sourceUid, it) }
    val alreadyCopied =
        destination.notes.count { (id, fields) -> id in noteIds || fields[MERGED_FROM_UID] == sourceUid }
    val newNotes =
        buildMap {
            for ((id, sourceId) in noteIds) {
                if (id in destination.notes) continue
                val fields = source.notes.getValue(sourceId)
                val oldIds = (fields["labelIds"] as? List<*>).orEmpty().filterIsInstance<String>()
                put(
                    id,
                    fields + ("labelIds" to oldIds.mapNotNull(labelIds::get).distinct()) +
                        (MERGED_FROM_UID to sourceUid),
                )
            }
        }
    return AccountMergePlan(newNotes, newLabels, newNotes.size + alreadyCopied)
}

// Stable across retries, including when only some of the destination batches committed.
private fun mergedId(
    uid: String,
    id: String,
): String = "merged-" + UUID.nameUUIDFromBytes("$uid/$id".encodeToByteArray())
