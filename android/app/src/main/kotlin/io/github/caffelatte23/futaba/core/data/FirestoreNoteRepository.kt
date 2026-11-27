@file:Suppress("TooManyFunctions") // one file holds the repository plus its small mapping/op helpers

package io.github.caffelatte23.futaba.core.data

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.WriteBatch
import com.google.firebase.firestore.snapshots
import io.github.caffelatte23.futaba.core.domain.parseHashtags
import io.github.caffelatte23.futaba.core.domain.resolveLabels
import io.github.caffelatte23.futaba.core.model.Label
import io.github.caffelatte23.futaba.core.model.Note
import io.github.caffelatte23.futaba.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NoteRepository"
private const val BATCH_LIMIT = 500
private const val MAX_NEW_LABELS_PER_WRITE = 100 // 1 note + 100 labels stays far below BATCH_LIMIT
private const val TRASH_DAYS = 30L
private val TRASH_RETENTION: Duration = Duration.ofDays(TRASH_DAYS)
private const val RETRY_BASE_MS = 1_000L
private const val RETRY_MAX_MS = 30_000L
private const val RETRY_MAX_SHIFT = 5

private typealias BatchOp = (WriteBatch) -> Unit

private fun updateOp(
    ref: DocumentReference,
    fields: Map<String, Any?>,
): BatchOp = { it.update(ref, fields) }

private fun setOp(
    ref: DocumentReference,
    fields: Map<String, Any?>,
): BatchOp = { it.set(ref, fields) }

private fun deleteOp(ref: DocumentReference): BatchOp = { it.delete(ref) }

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

private fun labelFields(
    name: String,
    order: Int,
) = mapOf("name" to name, "color" to DEFAULT_LABEL_COLOR, "order" to order)

/** What a note text resolves to: label [ids], the [known] ids of that user, and the writes creating new labels. */
private class LabelPlan(
    val ids: List<String>,
    val known: Set<String>,
    val ops: List<BatchOp>,
)

private fun planLabels(
    user: DocumentReference,
    existing: List<Label>,
    text: String,
): LabelPlan {
    val resolved = resolveLabels(parseHashtags(text), existing)
    if (resolved.newNames.size > MAX_NEW_LABELS_PER_WRITE) {
        Log.w(TAG, "ignoring ${resolved.newNames.size - MAX_NEW_LABELS_PER_WRITE} new labels over the cap")
    }
    val firstOrder = (existing.maxOfOrNull(Label::order) ?: -1) + 1
    val ops = mutableListOf<BatchOp>()
    val created =
        resolved.newNames.take(MAX_NEW_LABELS_PER_WRITE).mapIndexed { i, name ->
            val ref = user.collection("labels").document()
            ops += { it.set(ref, labelFields(name, firstOrder + i)) }
            ref.id
        }
    return LabelPlan(resolved.existingIds + created, existing.map(Label::id).toSet() + created, ops)
}

// The SDK applies commits to its local view immediately and handles client operations in order, so a later
// Source.CACHE read in the next queued job sees everything an earlier job committed, acked or not.
@Suppress("RedundantSuspendModifier") // detekt false positive: await() is a suspend call (same as AuthRepository)
private suspend fun DocumentReference.cachedLabels() =
    collection("labels").get(Source.CACHE).await().documents.map {
        it.toLabel()
    }

@Suppress("RedundantSuspendModifier") // see cachedLabels
private suspend fun DocumentReference.cachedNotes() =
    collection("notes").get(Source.CACHE).await().documents.map {
        it.toNote()
    }

private fun <T> Flow<T>.retrying(): Flow<T> =
    retryWhen { cause, attempt ->
        Log.w(TAG, "listener failed, retrying", cause)
        delay(minOf(RETRY_BASE_MS shl attempt.toInt().coerceAtMost(RETRY_MAX_SHIFT), RETRY_MAX_MS))
        true
    }

/**
 * Firestore-backed [NoteRepository] over `users/{uid}/{notes,labels}`.
 *
 * Reads ([notes], [labels], [pendingWrites]) are snapshot listeners per uid; they emit an empty list when the uid
 * changes (nothing of the previous account lingers) and retry with backoff after a listener error. They never gate
 * writes.
 *
 * Local writes are queued and run one at a time on the application scope under the uid captured at call time.
 * Jobs are dropped and logged if the uid is null or has changed. Each job reads what it needs from Firestore's
 * local cache (`Source.CACHE`) and calls `commit()` without awaiting the server, so the next job sees the result.
 * Label writes drop unknown ids. Trash purge runs separately with server transactions.
 *
 * Known limitation: on a brand-new device before the first sync the cache may lack the user's remote labels, so a
 * new `#tag` can duplicate an existing remote label. Per write at most [MAX_NEW_LABELS_PER_WRITE] labels are created.
 * ponytail: whole-collection cache reads per write are fine for a personal notebook; index by id if it grows.
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
        private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

        init {
            scope.launch { for (job in queue) job() }
        }

        private fun userDoc(uid: String) = db.collection("users").document(uid)

        private fun <T> perUser(
            empty: T,
            query: (DocumentReference) -> Flow<T>,
        ): Flow<T> =
            flow {
                var first = true
                emitAll(
                    auth.uid.flatMapLatest { uid ->
                        flow {
                            // The collector's own first value needs no reset; later uid switches do.
                            if (uid == null || !first) emit(empty)
                            first = false
                            if (uid != null) emitAll(query(userDoc(uid)).retrying())
                        }
                    },
                )
            }

        // Snapshot decoding and label reconciliation never run on the collector's (UI) thread.
        @Suppress("InjectDispatcher") // one fixed Default dispatcher, as for the application scope
        private val decodeContext = Dispatchers.Default

        override val notes: Flow<List<Note>> =
            perUser(emptyList()) { u ->
                val noteSnapshots = u.collection("notes").orderBy("createdAt", Query.Direction.DESCENDING).snapshots()
                combine(noteSnapshots, u.collection("labels").snapshots()) { noteSnapshot, labelSnapshot ->
                    val known = labelSnapshot.documents.map(DocumentSnapshot::getId).toSet()
                    noteSnapshot.documents.map { doc ->
                        val note = doc.toNote()
                        note.copy(labelIds = note.labelIds.filter { it in known })
                    }
                }.flowOn(decodeContext)
            }

        override val labels: Flow<List<Label>> =
            perUser(emptyList()) { u ->
                u
                    .collection("labels")
                    .snapshots()
                    .map { s ->
                        s.documents.map { it.toLabel() }.sortedBy(Label::order)
                    }.flowOn(decodeContext)
            }

        override val pendingWrites: Flow<Int> =
            perUser(0) { u ->
                u
                    .collection("notes")
                    .snapshots(MetadataChanges.INCLUDE)
                    .map { s ->
                        s.documents.count { it.metadata.hasPendingWrites() }
                    }.flowOn(decodeContext)
            }

        init {
            // The combined listener keeps both collections warm even when the UI is closed.
            notes.launchIn(scope)
        }

        /**
         * Queues [block] under the uid current at call time. The result is null when dropped (no uid, uid changed) or
         * when the job failed; failures are logged so one bad write never stops the queue (Firebase throws varied
         * runtime exceptions, hence the broad catch).
         */
        @Suppress("TooGenericExceptionCaught")
        private fun <T> enqueue(
            op: String,
            uid: String? = auth.uid.value,
            block: suspend (DocumentReference) -> T,
        ): CompletableDeferred<T?> {
            val result = CompletableDeferred<T?>()
            if (uid == null) {
                Log.w(TAG, "$op dropped: not signed in")
                result.complete(null)
                return result
            }
            queue.trySend {
                try {
                    if (auth.uid.value == uid) {
                        result.complete(block(userDoc(uid)))
                    } else {
                        Log.w(TAG, "$op dropped: uid changed")
                        result.complete(null)
                    }
                } catch (e: CancellationException) {
                    result.cancel(e)
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "$op failed", e)
                    result.complete(null)
                }
            }
            return result
        }

        private fun isCurrent(uid: String): Boolean {
            val current = auth.uid.value == uid
            if (!current) Log.w(TAG, "write dropped: uid changed while preparing it")
            return current
        }

        /** Submits one unawaited batch; false if [user] is no longer current. */
        private fun commitBatch(
            user: DocumentReference,
            ops: List<BatchOp>,
        ): Boolean {
            val batch = db.batch().also { b -> ops.forEach { it(b) } }
            if (!isCurrent(user.id)) return false
            batch.commit().addOnFailureListener { Log.w(TAG, "batch commit failed", it) }
            return true
        }

        private fun commitIndividually(
            user: DocumentReference,
            ops: List<BatchOp>,
        ) {
            for (op in ops) {
                if (!commitBatch(user, listOf(op))) break
            }
        }

        override fun add(
            text: String,
            extraLabelIds: List<String>,
        ): String {
            val uid = auth.uid.value
            val id = uid?.let { userDoc(it).collection("notes").document().id } ?: UUID.randomUUID().toString()
            enqueue("add", uid) { user ->
                val plan = planLabels(user, user.cachedLabels(), text)
                val labelIds = (plan.ids + extraLabelIds.filter { it in plan.known }).distinct()
                val now = Instant.now().toTimestamp()
                val fields =
                    mapOf(
                        "text" to text,
                        "labelIds" to labelIds,
                        "pinned" to false,
                        "createdAt" to now,
                        "updatedAt" to now,
                        "deletedAt" to null,
                    )
                commitBatch(user, plan.ops + setOp(user.collection("notes").document(id), fields))
            }
            return id
        }

        override fun updateText(
            id: String,
            text: String,
        ) {
            enqueue("updateText") { user ->
                val labels = user.cachedLabels()
                val old = user.cachedNotes().firstOrNull { it.id == id }
                if (old == null) {
                    Log.w(TAG, "updateText dropped: note not found")
                    return@enqueue
                }
                // Manual labels = existing ids on the note that its old text's `#` tags do not explain.
                val fromOldText = resolveLabels(parseHashtags(old.text), labels).existingIds.toSet()
                val known = labels.map(Label::id).toSet()
                val manual = old.labelIds.filter { it in known && it !in fromOldText }
                val plan = planLabels(user, labels, text)
                val fields =
                    mapOf(
                        "text" to text,
                        "labelIds" to (plan.ids + manual).distinct(),
                        "updatedAt" to Instant.now().toTimestamp(),
                    )
                commitIndividually(user, plan.ops + updateOp(user.collection("notes").document(id), fields))
            }
        }

        override fun setLabels(
            ids: List<String>,
            add: List<String>,
            remove: List<String>,
        ) {
            enqueue("setLabels") { user ->
                val known = user.cachedLabels().map(Label::id).toSet()
                val removed = remove.toSet()
                val now = Instant.now().toTimestamp()
                commitIndividually(
                    user,
                    user.cachedNotes().filter { it.id in ids }.map { n ->
                        val labelIds = (n.labelIds + add).filter { it in known && it !in removed }.distinct()
                        updateOp(
                            user.collection("notes").document(n.id),
                            mapOf(
                                "labelIds" to labelIds,
                                "updatedAt" to now,
                            ),
                        )
                    },
                )
            }
        }

        override fun setPinned(
            id: String,
            pinned: Boolean,
        ) = updateNotes(
            "setPinned",
            listOf(id),
        ) { mapOf("pinned" to pinned, "updatedAt" to Instant.now().toTimestamp()) }

        override fun delete(ids: List<String>) =
            updateNotes("delete", ids) {
                mapOf("deletedAt" to Instant.now().toTimestamp())
            }

        override fun restore(ids: List<String>) = updateNotes("restore", ids) { mapOf("deletedAt" to null) }

        private fun updateNotes(
            op: String,
            ids: List<String>,
            fields: () -> Map<String, Any?>,
        ) {
            val values = fields()
            enqueue(op) { user ->
                val present = user.cachedNotes().map(Note::id).toSet()
                commitIndividually(
                    user,
                    ids.filter { it in present }.map { id ->
                        updateOp(user.collection("notes").document(id), values)
                    },
                )
            }
        }

        override suspend fun purgeTrash(now: Instant) {
            val uid = auth.uid.value ?: return
            val notes = userDoc(uid).collection("notes")
            val limit = now.minus(TRASH_RETENTION).toTimestamp()
            val candidates =
                try {
                    notes
                        .whereLessThan("deletedAt", limit)
                        .get(Source.SERVER)
                        .await()
                        .documents
                } catch (e: FirebaseFirestoreException) {
                    Log.w(TAG, "purgeTrash skipped: server query failed", e)
                    emptyList()
                }
            for (candidate in candidates) {
                if (!isCurrent(uid)) break
                try {
                    val cached = notes.whereEqualTo(FieldPath.documentId(), candidate.id).get(Source.CACHE).await()
                    // A local restore can still be waiting to reach the server.
                    if (cached.documents.none { it.getTimestamp("deletedAt") == null }) {
                        purgeCandidate(uid, candidate.reference, limit)
                    }
                } catch (e: FirebaseFirestoreException) {
                    Log.w(TAG, "purgeTrash failed for ${candidate.id}", e)
                }
            }
        }

        @Suppress("RedundantSuspendModifier") // await() suspends until the transaction finishes
        internal suspend fun purgeCandidate(
            uid: String,
            ref: DocumentReference,
            limit: Timestamp,
        ) {
            if (!isCurrent(uid)) return
            db
                .runTransaction { transaction ->
                    val deletedAt = transaction.get(ref).getTimestamp("deletedAt")
                    if (deletedAt != null && deletedAt < limit && isCurrent(uid)) {
                        transaction.delete(ref)
                    }
                }.await()
        }

        override suspend fun createLabel(name: String): String {
            val uid = checkNotNull(auth.uid.value) { "createLabel failed: not signed in" }
            val id =
                enqueue("createLabel", uid) { user ->
                    val labels = user.cachedLabels()
                    val existing = labels.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
                    existing
                        ?: user
                            .collection("labels")
                            .document()
                            .also { ref ->
                                val order = (labels.maxOfOrNull(Label::order) ?: -1) + 1
                                check(commitBatch(user, listOf(setOp(ref, labelFields(name, order))))) { "uid changed" }
                            }.id
                }.await()
            check(auth.uid.value == uid) { "createLabel failed: uid changed" }
            return checkNotNull(id) { "createLabel failed: write dropped" }
        }

        override fun renameLabel(
            id: String,
            name: String,
        ) = updateLabels("renameLabel", mapOf(id to mapOf("name" to name)))

        override fun setLabelColor(
            id: String,
            color: String,
        ) = updateLabels("setLabelColor", mapOf(id to mapOf("color" to color)))

        override fun reorderLabels(idsInOrder: List<String>) =
            updateLabels("reorderLabels", idsInOrder.mapIndexed { i, id -> id to mapOf("order" to i) }.toMap())

        private fun updateLabels(
            op: String,
            fieldsById: Map<String, Map<String, Any?>>,
        ) {
            enqueue(op) { user ->
                val present = user.cachedLabels().map(Label::id).toSet()
                commitIndividually(
                    user,
                    fieldsById.filterKeys { it in present }.map { (id, fields) ->
                        updateOp(user.collection("labels").document(id), fields)
                    },
                )
            }
        }

        override fun deleteLabel(id: String) {
            enqueue("deleteLabel") { user ->
                val affected =
                    user
                        .collection(
                            "notes",
                        ).whereArrayContains("labelIds", id)
                        .get(Source.CACHE)
                        .await()
                        .documents
                // The label delete is the first op, so it shares the first batch with the first detachments.
                val ops =
                    listOf(deleteOp(user.collection("labels").document(id))) +
                        affected.map { n -> updateOp(n.reference, mapOf("labelIds" to FieldValue.arrayRemove(id))) }
                for (chunk in ops.chunked(BATCH_LIMIT)) {
                    if (!commitBatch(user, chunk)) break
                }
            }
        }
    }
