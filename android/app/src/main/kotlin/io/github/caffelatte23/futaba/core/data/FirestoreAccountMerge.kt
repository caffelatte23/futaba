@file:Suppress("RedundantSuspendModifier") // Google Tasks await() suspends.

package io.github.caffelatte23.futaba.core.data

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import io.github.caffelatte23.futaba.core.domain.MergeDocuments
import io.github.caffelatte23.futaba.core.domain.planAccountMerge
import kotlinx.coroutines.tasks.await

private const val BATCH_LIMIT = 500

internal class FirestoreAccountMerge(
    private val firestore: FirebaseFirestore,
) {
    suspend fun read(uid: String): MergeDocuments {
        val user = user(uid)
        return MergeDocuments(user.collection("notes").read(), user.collection("labels").read())
    }

    suspend fun copy(
        sourceUid: String,
        destinationUid: String,
        source: MergeDocuments,
    ): Int {
        val user = user(destinationUid)
        val plan = planAccountMerge(sourceUid, source, read(destinationUid))
        write(user.collection("labels"), plan.labels)
        write(user.collection("notes"), plan.notes)
        return plan.noteCount
    }

    suspend fun delete(
        uid: String,
        source: MergeDocuments,
    ) {
        val user = user(uid)
        val documents =
            source.notes.keys.map(user.collection("notes")::document) +
                source.labels.keys.map(user.collection("labels")::document)
        for (chunk in documents.chunked(BATCH_LIMIT)) {
            val batch = firestore.batch()
            chunk.forEach(batch::delete)
            batch.commit().await()
        }
    }

    private fun user(uid: String): DocumentReference = firestore.collection("users").document(uid)

    private suspend fun CollectionReference.read(): Map<String, Map<String, Any?>> =
        get(Source.SERVER).await().documents.associate { it.id to checkNotNull(it.data) }

    private suspend fun write(
        collection: CollectionReference,
        documents: Map<String, Map<String, Any?>>,
    ) {
        for (chunk in documents.entries.chunked(BATCH_LIMIT)) {
            val batch = firestore.batch()
            chunk.forEach { (id, fields) -> batch.set(collection.document(id), fields) }
            batch.commit().await()
        }
    }
}
