package io.github.caffelatte23.futaba.core.data

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.WriteBatch
import com.google.firebase.firestore.snapshots
import io.github.caffelatte23.futaba.core.domain.parseHashtags
import io.github.caffelatte23.futaba.core.domain.resolveLabels
import io.github.caffelatte23.futaba.core.model.Label
import io.github.caffelatte23.futaba.core.model.Note
import io.github.caffelatte23.futaba.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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

/** Everything known about [uid]'s data; [initialized] flips once the first snapshots of both collections arrived. */
private data class RepoState(
    val uid: String?,
    val notes: List<Note>,
    val labels: List<Label>,
    val initialized: Boolean,
)

private typealias BatchOp = (WriteBatch) -> Unit

/** Working copy of the state for one mutation: edits are applied to [notes]/[labels] and recorded as [ops]. */
private class Draft(
    val user: DocumentReference,
    var notes: List<Note>,
    var labels: List<Label>,
) {
    val ops = mutableListOf<BatchOp>()

    fun patchNote(
        id: String,
        fields: Map<String, Any?>,
        transform: (Note) -> Note,
    ) {
        if (notes.none { it.id == id }) return
        notes = notes.map { if (it.id == id) transform(it) else it }
        ops += { it.update(user.collection("notes").document(id), fields) }
    }

    fun patchLabel(
        id: String,
        fields: Map<String, Any?>,
        transform: (Label) -> Label,
    ) {
        if (labels.none { it.id == id }) return
        labels = labels.map { if (it.id == id) transform(it) else it }.sortedBy(Label::order)
        ops += { it.update(user.collection("labels").document(id), fields) }
    }

    fun nextLabelOrder() = (labels.maxOfOrNull(Label::order) ?: -1) + 1

    fun createLabel(
        name: String,
        id: String = user.collection("labels").document().id,
    ): String {
        val label = Label(id, name, DEFAULT_LABEL_COLOR, nextLabelOrder())
        labels = labels + label
        val fields = mapOf("name" to name, "color" to label.color, "order" to label.order)
        ops += { it.set(user.collection("labels").document(id), fields) }
        return id
    }

    /** Label ids for the `#` names in [text]; creates missing labels (capped). */
    fun resolveIds(text: String): List<String> {
        val resolved = resolveLabels(parseHashtags(text), labels)
        if (resolved.newNames.size > MAX_NEW_LABELS_PER_WRITE) {
            Log.w(TAG, "ignoring ${resolved.newNames.size - MAX_NEW_LABELS_PER_WRITE} new labels over the cap")
        }
        return resolved.existingIds + resolved.newNames.take(MAX_NEW_LABELS_PER_WRITE).map { createLabel(it) }
    }
}

/**
 * Firestore-backed [NoteRepository] over `users/{uid}/{notes,labels}`.
 *
 * Single writer: a per-uid [RepoState] is fed by snapshots (reset when the uid changes, so nothing of the previous
 * account is visible). Every write is queued and run one at a time on the application scope; each waits until the
 * state of the uid it was issued under is initialized, computes from that state, applies the result optimistically
 * to the state, then commits a [WriteBatch] (not awaited). Later snapshots reconcile (they already include pending
 * local writes). A write whose uid changed meanwhile is dropped. Without a uid writes are logged and dropped.
 *
 * Residual windows: a snapshot may overwrite an optimistic state just before its batch reaches the local cache
 * (self-corrects on the next snapshot); [createLabel] decides "already exists" at call time, so the id it returns
 * can miss a label created by a still-queued write. Per write at most [MAX_NEW_LABELS_PER_WRITE] new labels are made.
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
        private val state = MutableStateFlow(RepoState(null, emptyList(), emptyList(), initialized = true))
        private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

        override val notes: Flow<List<Note>> = state.map { it.notes }.distinctUntilChanged()
        override val labels: Flow<List<Label>> = state.map { it.labels }.distinctUntilChanged()

        override val pendingWrites: Flow<Int> =
            auth.uid.flatMapLatest { uid ->
                if (uid == null) {
                    flowOf(0)
                } else {
                    userDoc(uid).collection("notes").snapshots(MetadataChanges.INCLUDE).map { s ->
                        s.documents.count { it.metadata.hasPendingWrites() }
                    }
                }
            }

        init {
            scope.launch {
                auth.uid
                    .flatMapLatest { uid ->
                        if (uid ==
                            null
                        ) {
                            flowOf(RepoState(null, emptyList(), emptyList(), true))
                        } else {
                            listen(uid)
                        }
                    }.catch { Log.w(TAG, "listener failed", it) }
                    .collect { state.value = it }
            }
            scope.launch { for (job in queue) runJob(job) }
        }

        private fun listen(uid: String): Flow<RepoState> =
            flow {
                emit(RepoState(uid, emptyList(), emptyList(), initialized = false))
                val user = userDoc(uid)
                val notesFlow = user.collection("notes").orderBy("createdAt", Query.Direction.DESCENDING).snapshots()
                emitAll(
                    combine(notesFlow, user.collection("labels").snapshots()) { n, l ->
                        RepoState(
                            uid,
                            n.documents.map { it.toNote() },
                            l.documents.map { it.toLabel() }.sortedBy(Label::order),
                            initialized = true,
                        )
                    },
                )
            }

        // Firebase throws varied runtime exceptions; one bad write must not kill the queue consumer.
        @Suppress("TooGenericExceptionCaught")
        private suspend fun runJob(job: suspend () -> Unit) {
            try {
                job()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "write failed", e)
            }
        }

        private fun userDoc(uid: String) = db.collection("users").document(uid)

        /** Queues [block] under the current uid (see the class KDoc); logs and drops it without a uid. */
        private fun enqueue(
            op: String,
            block: Draft.() -> Unit,
        ) {
            val uid = auth.uid.value
            if (uid == null) {
                Log.w(TAG, "$op dropped: not signed in")
                return
            }
            queue.trySend { run(op, uid, block) }
        }

        @Suppress("RedundantSuspendModifier") // detekt misses the suspend call hidden in the destructured first()
        private suspend fun run(
            op: String,
            uid: String,
            block: Draft.() -> Unit,
        ) {
            val (s, current) =
                combine(state, auth.uid) { s, current -> s to current }
                    .first { (s, current) -> current != uid || (s.uid == uid && s.initialized) }
            if (current != uid) {
                Log.w(TAG, "$op dropped: uid changed")
                return
            }
            val draft = Draft(userDoc(uid), s.notes, s.labels).apply(block)
            state.update { if (it.uid == uid) it.copy(notes = draft.notes, labels = draft.labels) else it }
            draft.ops.chunked(BATCH_LIMIT).forEach { chunk ->
                db.batch().also { b -> chunk.forEach { it(b) } }.commit()
            }
        }

        override fun add(
            text: String,
            extraLabelIds: List<String>,
        ): String {
            val id =
                auth.uid.value?.let { userDoc(it).collection("notes").document().id } ?: UUID.randomUUID().toString()
            enqueue("add") {
                val now = Instant.now()
                val note = Note(id, text, (resolveIds(text) + extraLabelIds).distinct(), false, now, now, null)
                notes = listOf(note) + notes
                val ts = now.toTimestamp()
                val fields =
                    mapOf(
                        "text" to text,
                        "labelIds" to note.labelIds,
                        "pinned" to false,
                        "createdAt" to ts,
                        "updatedAt" to ts,
                        "deletedAt" to null,
                    )
                ops += { it.set(user.collection("notes").document(id), fields) }
            }
            return id
        }

        override fun updateText(
            id: String,
            text: String,
        ) {
            enqueue("updateText") {
                notes.firstOrNull { it.id == id }?.let { old ->
                    // Manual labels = ids on the note that its old text's `#` tags do not explain.
                    val manual = old.labelIds - resolveLabels(parseHashtags(old.text), labels).existingIds.toSet()
                    val ids = (resolveIds(text) + manual).distinct()
                    val now = Instant.now()
                    patchNote(id, mapOf("text" to text, "labelIds" to ids, "updatedAt" to now.toTimestamp())) {
                        it.copy(text = text, labelIds = ids, updatedAt = now)
                    }
                }
            }
        }

        override fun setLabels(
            ids: List<String>,
            add: List<String>,
            remove: List<String>,
        ) {
            enqueue("setLabels") {
                val now = Instant.now()
                val removed = remove.toSet()
                notes.filter { it.id in ids }.forEach { n ->
                    val labelIds = (n.labelIds + add).filterNot { it in removed }.distinct()
                    patchNote(n.id, mapOf("labelIds" to labelIds, "updatedAt" to now.toTimestamp())) {
                        it.copy(labelIds = labelIds, updatedAt = now)
                    }
                }
            }
        }

        override fun setPinned(
            id: String,
            pinned: Boolean,
        ) {
            enqueue("setPinned") {
                val now = Instant.now()
                patchNote(id, mapOf("pinned" to pinned, "updatedAt" to now.toTimestamp())) {
                    it.copy(pinned = pinned, updatedAt = now)
                }
            }
        }

        override fun delete(ids: List<String>) {
            enqueue("delete") {
                val now = Instant.now()
                ids.forEach { patchNote(it, mapOf("deletedAt" to now.toTimestamp())) { n -> n.copy(deletedAt = now) } }
            }
        }

        override fun restore(ids: List<String>) {
            enqueue("restore") {
                ids.forEach { patchNote(it, mapOf("deletedAt" to null)) { n -> n.copy(deletedAt = null) } }
            }
        }

        override suspend fun purgeTrash(now: Instant) {
            val uid = auth.uid.value
            if (uid == null) {
                Log.w(TAG, "purgeTrash dropped: not signed in")
                return
            }
            userDoc(uid)
                .collection("notes")
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
            val existing = state.value.labels.firstOrNull { it.name.equals(name, ignoreCase = true) }
            val id =
                existing?.id
                    ?: auth.uid.value?.let { userDoc(it).collection("labels").document().id }
                    ?: UUID.randomUUID().toString()
            if (existing == null) {
                // Re-checked in the queue so two quick calls with the same name create one label.
                enqueue(
                    "createLabel",
                ) { if (labels.none { it.name.equals(name, ignoreCase = true) }) createLabel(name, id) }
            }
            return id
        }

        override fun renameLabel(
            id: String,
            name: String,
        ) {
            enqueue("renameLabel") { patchLabel(id, mapOf("name" to name)) { it.copy(name = name) } }
        }

        override fun setLabelColor(
            id: String,
            color: String,
        ) {
            enqueue("setLabelColor") { patchLabel(id, mapOf("color" to color)) { it.copy(color = color) } }
        }

        override fun reorderLabels(idsInOrder: List<String>) {
            enqueue("reorderLabels") {
                idsInOrder.forEachIndexed { i, id -> patchLabel(id, mapOf("order" to i)) { it.copy(order = i) } }
            }
        }

        override fun deleteLabel(id: String) {
            enqueue("deleteLabel") {
                // The label delete is the first op, so it shares the first batch with the first detachments.
                labels = labels.filterNot { it.id == id }
                ops += { it.delete(user.collection("labels").document(id)) }
                val affected = notes.filter { id in it.labelIds }
                notes = notes.map { if (id in it.labelIds) it.copy(labelIds = it.labelIds - id) else it }
                affected.forEach { n ->
                    ops +=
                        { it.update(user.collection("notes").document(n.id), "labelIds", FieldValue.arrayRemove(id)) }
                }
            }
        }
    }
