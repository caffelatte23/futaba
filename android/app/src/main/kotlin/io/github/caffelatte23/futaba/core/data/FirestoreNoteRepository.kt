package io.github.caffelatte23.futaba.core.data

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.WriteBatch
import com.google.firebase.firestore.snapshots
import io.github.caffelatte23.futaba.core.domain.parseHashtags
import io.github.caffelatte23.futaba.core.domain.resolveLabels
import io.github.caffelatte23.futaba.core.model.Label
import io.github.caffelatte23.futaba.core.model.Note
import io.github.caffelatte23.futaba.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.tasks.await
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NoteRepository"
private const val BATCH_LIMIT = 500
private const val TRASH_DAYS = 30L
private val TRASH_RETENTION: Duration = Duration.ofDays(TRASH_DAYS)

private fun Instant.toTimestamp() = Timestamp(epochSecond, nano)

// A missing field (hand-edited document) falls back to a neutral value instead of crashing the listener.
private fun DocumentSnapshot.toNote(): Note {
    val labelIds = (get("labelIds") as? List<*>).orEmpty().filterIsInstance<String>()
    val createdAt = getTimestamp("createdAt")?.toInstant() ?: Instant.EPOCH
    return Note(
        id = id,
        text = getString("text").orEmpty(),
        labelIds = labelIds,
        pinned = getBoolean("pinned") ?: false,
        createdAt = createdAt,
        updatedAt = getTimestamp("updatedAt")?.toInstant() ?: createdAt,
        deletedAt = getTimestamp("deletedAt")?.toInstant(),
    )
}

private fun DocumentSnapshot.toLabel() =
    Label(
        id = id,
        name = getString("name").orEmpty(),
        color = getString("color") ?: DEFAULT_LABEL_COLOR,
        order = getLong("order")?.toInt() ?: 0,
    )

/**
 * Firestore-backed [NoteRepository] over `users/{uid}/{notes,labels}`; re-subscribes whenever the uid changes.
 * Writes need the signed-in uid; without one they are logged and dropped (see [NoteRepository]).
 *
 * Fire-and-forget writes read existing labels/notes from an eagerly collected local copy of the snapshots, so a
 * write issued in the same instant as a previous one may not see it.
 * ponytail: fine at typing speed; a pending-label overlay would close the window.
 */
@Suppress("TooManyFunctions") // implements the NoteRepository contract plus small private helpers
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreNoteRepository
    @Inject
    constructor(
        private val db: FirebaseFirestore,
        private val auth: AuthRepository,
        @ApplicationScope scope: CoroutineScope,
    ) : NoteRepository {
        private fun <T> perUser(
            none: T,
            block: (DocumentReference) -> Flow<T>,
        ): Flow<T> = auth.uid.flatMapLatest { uid -> if (uid == null) flowOf(none) else block(userDoc(uid)) }

        private fun userDoc(uid: String) = db.collection("users").document(uid)

        private fun DocumentReference.notes() = collection("notes")

        private fun DocumentReference.labels() = collection("labels")

        override val notes: Flow<List<Note>> =
            perUser(emptyList()) { u -> u.notes().snapshots().map { s -> s.documents.map { it.toNote() } } }

        override val labels: Flow<List<Label>> =
            perUser(emptyList()) { u ->
                u.labels().snapshots().map { s -> s.documents.map { it.toLabel() }.sortedBy(Label::order) }
            }

        override val pendingWrites: Flow<Int> =
            perUser(0) { u ->
                u.notes().snapshots(MetadataChanges.INCLUDE).map { s ->
                    s.documents.count { it.metadata.hasPendingWrites() }
                }
            }

        private val notesCache: StateFlow<List<Note>> = notes.stateIn(scope, SharingStarted.Eagerly, emptyList())
        private val labelsCache: StateFlow<List<Label>> = labels.stateIn(scope, SharingStarted.Eagerly, emptyList())

        override fun add(
            text: String,
            extraLabelIds: List<String>,
        ): String {
            val uid = requireUid("add") ?: return UUID.randomUUID().toString()
            val user = userDoc(uid)
            val note = user.notes().document()
            commit { batch ->
                val now = Instant.now().toTimestamp()
                batch.set(
                    note,
                    mapOf(
                        "text" to text,
                        "labelIds" to (resolveIds(batch, user, text) + extraLabelIds).distinct(),
                        "pinned" to false,
                        "createdAt" to now,
                        "updatedAt" to now,
                        "deletedAt" to null,
                    ),
                )
            }
            return note.id
        }

        /** Label ids for the `#` names in [text]; creates missing labels in [batch]. */
        private fun resolveIds(
            batch: WriteBatch,
            user: DocumentReference,
            text: String,
        ): List<String> {
            val resolved = resolveLabels(parseHashtags(text), labelsCache.value)
            val firstOrder = nextLabelOrder()
            val created =
                resolved.newNames.mapIndexed { i, name ->
                    val ref = user.labels().document()
                    batch.set(ref, newLabelFields(name, firstOrder + i))
                    ref.id
                }
            return resolved.existingIds + created
        }

        private fun nextLabelOrder() = (labelsCache.value.maxOfOrNull(Label::order) ?: -1) + 1

        private fun newLabelFields(
            name: String,
            order: Int,
        ) = mapOf("name" to name, "color" to DEFAULT_LABEL_COLOR, "order" to order)

        override fun updateText(
            id: String,
            text: String,
        ) {
            val uid = requireUid("updateText") ?: return
            val old = notesCache.value.firstOrNull { it.id == id }
            // Manual labels = ids on the note that its old text's `#` tags do not explain.
            val fromOldText =
                old
                    ?.let {
                        resolveLabels(
                            parseHashtags(it.text),
                            labelsCache.value,
                        ).existingIds
                    }.orEmpty()
            val manual = old?.labelIds.orEmpty() - fromOldText.toSet()
            val user = userDoc(uid)
            commit { batch ->
                val ids = (resolveIds(batch, user, text) + manual).distinct()
                batch.update(
                    user.notes().document(id),
                    mapOf("text" to text, "labelIds" to ids, "updatedAt" to Instant.now().toTimestamp()),
                )
            }
        }

        override fun setLabels(
            ids: List<String>,
            add: List<String>,
            remove: List<String>,
        ) {
            val uid = requireUid("setLabels") ?: return
            val now = Instant.now().toTimestamp()
            val removed = remove.toSet()
            commitAll(
                notesCache.value.filter { it.id in ids }.map { n ->
                    val labelIds = (n.labelIds + add).filterNot { it in removed }.distinct()
                    userDoc(uid).notes().document(n.id) to mapOf("labelIds" to labelIds, "updatedAt" to now)
                },
            )
        }

        override fun setPinned(
            id: String,
            pinned: Boolean,
        ) = updateNotes(
            listOf(id),
            "setPinned",
        ) { mapOf("pinned" to pinned, "updatedAt" to Instant.now().toTimestamp()) }

        override fun delete(ids: List<String>) =
            updateNotes(ids, "delete") {
                mapOf("deletedAt" to Instant.now().toTimestamp())
            }

        override fun restore(ids: List<String>) = updateNotes(ids, "restore") { mapOf("deletedAt" to null) }

        override suspend fun purgeTrash(now: Instant) {
            val uid = requireUid("purgeTrash") ?: return
            userDoc(uid)
                .notes()
                .whereLessThan("deletedAt", now.minus(TRASH_RETENTION).toTimestamp())
                .get()
                .await()
                .documents
                .map { it.reference }
                .chunked(BATCH_LIMIT)
                .forEach { chunk ->
                    db
                        .batch()
                        .also { b -> chunk.forEach(b::delete) }
                        .commit()
                        .await()
                }
        }

        override fun createLabel(name: String): String {
            val existing = labelsCache.value.firstOrNull { it.name.equals(name, ignoreCase = true) }
            val uid = requireUid("createLabel")
            if (existing != null || uid == null) return existing?.id ?: UUID.randomUUID().toString()
            val ref = userDoc(uid).labels().document()
            ref.set(newLabelFields(name, nextLabelOrder()))
            return ref.id
        }

        override fun renameLabel(
            id: String,
            name: String,
        ) = updateLabel(id, "renameLabel", mapOf("name" to name))

        override fun setLabelColor(
            id: String,
            color: String,
        ) = updateLabel(id, "setLabelColor", mapOf("color" to color))

        override fun reorderLabels(idsInOrder: List<String>) {
            val uid = requireUid("reorderLabels") ?: return
            commitAll(idsInOrder.mapIndexed { i, id -> userDoc(uid).labels().document(id) to mapOf("order" to i) })
        }

        override fun deleteLabel(id: String) {
            val uid = requireUid("deleteLabel") ?: return
            val user = userDoc(uid)
            user.labels().document(id).delete()
            commitAll(
                notesCache.value
                    .filter { id in it.labelIds }
                    .map { user.notes().document(it.id) to mapOf("labelIds" to FieldValue.arrayRemove(id)) },
            )
        }

        private fun updateNotes(
            ids: List<String>,
            op: String,
            fields: () -> Map<String, Any?>,
        ) {
            val uid = requireUid(op) ?: return
            val values = fields()
            commitAll(ids.map { userDoc(uid).notes().document(it) to values })
        }

        private fun updateLabel(
            id: String,
            op: String,
            fields: Map<String, Any?>,
        ) {
            val uid = requireUid(op) ?: return
            userDoc(uid).labels().document(id).update(fields)
        }

        private fun requireUid(op: String): String? =
            auth.uid.value ?: run {
                Log.w(TAG, "$op dropped: not signed in")
                null
            }

        private fun commit(block: (WriteBatch) -> Unit) {
            db.batch().also(block).commit()
        }

        /** Applies updates in batches of at most [BATCH_LIMIT]; not awaited. */
        private fun commitAll(updates: List<Pair<DocumentReference, Map<String, Any?>>>) {
            updates.chunked(BATCH_LIMIT).forEach { chunk ->
                commit { b -> chunk.forEach { (ref, fields) -> b.update(ref, fields) } }
            }
        }
    }
