package io.github.caffelatte23.futaba.core.data

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.snapshots
import io.github.caffelatte23.futaba.core.domain.filterNotes
import io.github.caffelatte23.futaba.core.model.NoteFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant

private const val HOST = "10.0.2.2"
private const val PROJECT = "demo-futaba"
private const val TIMEOUT_MS = 15_000L
private const val HTTP_TIMEOUT_MS = 5_000
private val HTTP_OK = 200..299

/**
 * Runs against the Firebase emulators (`pnpm check:device`) with a dedicated `demo-futaba` FirebaseApp, so the real
 * project is never touched.
 */
class FirestoreNoteRepositoryTest {
    @Suppress("InjectDispatcher") // test scope; nothing to inject
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repo: FirestoreNoteRepository

    @Before
    fun setUp() =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                clearEmulatorData()
                auth.signOut()
                auth.signInAnonymously().await()
            }
            repo = FirestoreNoteRepository(db, AuthRepository(auth, db), scope)
        }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun <T> Flow<T>.await(predicate: (T) -> Boolean): T =
        runBlocking {
            withTimeout(TIMEOUT_MS) { first(predicate) }
        }

    @Test
    fun addCreatesNoteAndNewLabels() {
        val id = repo.add("案 #アプリ案")

        val notes = repo.notes.await { it.size == 1 && it.single().labelIds.isNotEmpty() }
        val labels = repo.labels.await { it.isNotEmpty() }
        assertThat(notes.map { it.id }).containsExactly(id)
        assertThat(labels.map { it.name }).containsExactly("アプリ案")
        assertThat(notes.single().labelIds).containsExactly(labels.single().id)
    }

    @Test
    fun addReusesExistingLabelCaseInsensitive() {
        repo.add("one #Idea")
        val first = repo.labels.await { it.size == 1 }.single()

        repo.add("two #idea")

        val notes = repo.notes.await { it.size == 2 && it.all { note -> note.labelIds.isNotEmpty() } }
        assertThat(repo.labels.await { true }).hasSize(1)
        assertThat(notes.flatMap { it.labelIds }).containsExactly(first.id, first.id)
    }

    @Test
    fun deleteThenRestoreKeepsNote() {
        val id = repo.add("keep me")
        repo.notes.await { it.size == 1 }

        repo.delete(listOf(id))
        val trashed = repo.notes.await { n -> n.single().deletedAt != null }
        assertThat(filterNotes(trashed, NoteFilter.All)).isEmpty()

        runBlocking { db.disableNetwork().await() }
        try {
            repo.restore(listOf(id))
            val restored = repo.notes.await { n -> n.single().deletedAt == null }
            assertThat(filterNotes(restored, NoteFilter.All).map { it.id }).containsExactly(id)
        } finally {
            runBlocking { db.enableNetwork().await() }
        }
    }

    @Test
    fun purgeRemovesOnlyOlderThan30Days() {
        val now = Instant.now()
        val old = repo.add("old")
        val recent = repo.add("recent")
        val live = repo.add("live")
        repo.notes.await { it.size == 3 }
        val notesRef = db.collection("users").document(auth.currentUser!!.uid).collection("notes")
        runBlocking {
            notesRef.document(old).update("deletedAt", now.minus(Duration.ofDays(31)).toTimestamp()).await()
            notesRef.document(recent).update("deletedAt", now.minus(Duration.ofDays(29)).toTimestamp()).await()
            repo.purgeTrash(now)
        }

        val ids = repo.notes.await { it.size == 2 }.map { it.id }
        assertThat(ids).containsExactly(recent, live)
    }

    @Test
    fun backToBackAddsShareOneLabel() {
        repo.add("a #Idea")
        repo.add("b #idea")

        val notes = repo.notes.await { it.size == 2 && it.all { note -> note.labelIds.isNotEmpty() } }
        val labels = repo.labels.await { it.isNotEmpty() }
        assertThat(labels).hasSize(1)
        assertThat(notes.flatMap { it.labelIds }).containsExactly(labels.single().id, labels.single().id)
    }

    @Test
    fun updateTextRightAfterSetLabelsKeepsManualLabel() {
        val id = repo.add("t #Foo")
        val manual = runBlocking { repo.createLabel("Manual") }
        repo.setLabels(listOf(id), add = listOf(manual), remove = emptyList())
        repo.updateText(id, "t2 #Foo")

        val note =
            repo.notes
                .await { notes ->
                    val updated = notes.singleOrNull()
                    updated?.text == "t2 #Foo" && updated.labelIds.size == 2
                }.single()
        val names =
            repo.labels
                .await { it.size == 2 }
                .filter { it.id in note.labelIds }
                .map { it.name }
        assertThat(names).containsExactly("Foo", "Manual")
    }

    @Test
    fun switchingUserDoesNotLeakPreviousAccount() {
        repo.add("mine #Old")
        repo.notes.await { it.size == 1 }
        runBlocking {
            auth.signOut()
            auth.signInAnonymously().await()
        }
        repo.notes.await { it.isEmpty() }
        repo.add("new #Fresh")

        val note = repo.notes.await { it.size == 1 && it.single().labelIds.isNotEmpty() }.single()
        val labels = repo.labels.await { it.isNotEmpty() }
        assertThat(labels.map { it.name }).containsExactly("Fresh")
        assertThat(note.labelIds).containsExactly(labels.single().id)
    }

    @Test
    fun concurrentCreateLabelReturnsOneId() {
        val ids = runBlocking { listOf(async { repo.createLabel("X") }, async { repo.createLabel("x") }).awaitAll() }

        assertThat(ids.toSet()).hasSize(1)
        assertThat(repo.labels.await { it.isNotEmpty() }.map { it.id }).containsExactly(ids.first())
    }

    @Test
    fun restoreThenPurgeKeepsRestoredNote() {
        val now = Instant.now()
        val id = repo.add("restored")
        repo.notes.await { it.size == 1 }
        val ref =
            db
                .collection("users")
                .document(auth.currentUser!!.uid)
                .collection("notes")
                .document(id)
        runBlocking {
            ref.update("deletedAt", now.minus(Duration.ofDays(31)).toTimestamp()).await()
            repo.restore(listOf(id))
            withTimeout(TIMEOUT_MS) { repo.notes.first { it.size == 1 && it.single().deletedAt == null } }
            repo.purgeTrash(now)
        }

        val snapshot = runBlocking { ref.get(Source.SERVER).await() }
        assertThat(snapshot.exists()).isTrue()
        assertThat(snapshot.get("deletedAt")).isNull()
    }

    @Test
    fun restoreRestoresOnlyExistingIds() {
        val id = repo.add("keep")
        repo.notes.await { it.size == 1 }
        repo.delete(listOf(id))
        repo.notes.await { n -> n.single().deletedAt != null }

        repo.restore(listOf("bogus", id))

        assertThat(repo.notes.await { n -> n.single().deletedAt == null }.map { it.id }).containsExactly(id)
    }

    @Test
    fun bulkRestoreWithDeletedIdStillRestoresOthers() =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val stale = repo.add("deleted elsewhere")
                val keep = listOf(repo.add("first"), repo.add("second"))
                val uid = auth.currentUser!!.uid
                val notesRef = db.collection("users").document(uid).collection("notes")
                repo.notes.first { it.size == 3 }
                repo.delete(listOf(stale) + keep)
                repo.notes.first { it.size == 3 && it.all { note -> note.deletedAt != null } }
                db.waitForPendingWrites().await()

                db.disableNetwork().await()
                try {
                    deleteNoteOnServer(uid, stale)
                    assertThat(
                        notesRef
                            .document(stale)
                            .get(Source.CACHE)
                            .await()
                            .exists(),
                    ).isTrue()
                    repo.restore(listOf(stale) + keep)
                    repo.notes.first { it.size == 3 && it.all { note -> note.deletedAt == null } }
                } finally {
                    db.enableNetwork().await()
                }

                db.waitForPendingWrites().await()
                val server = notesRef.get(Source.SERVER).await()
                assertThat(server.documents.map { it.id }).containsExactlyElementsIn(keep)
                assertThat(server.documents.filter { it.getTimestamp("deletedAt") != null }).isEmpty()
            }
        }

    @Test
    fun purgeSkipsNoteRestoredOnAnotherDevice() {
        val now = Instant.now()
        val id = repo.add("restored elsewhere")
        repo.notes.await { it.size == 1 }
        val ref =
            db
                .collection("users")
                .document(auth.currentUser!!.uid)
                .collection("notes")
                .document(id)
        runBlocking { ref.update("deletedAt", now.minus(Duration.ofDays(31)).toTimestamp()).await() }
        repo.notes.await { n -> n.single().deletedAt != null }
        clearDeletedAtOnServer(auth.currentUser!!.uid, id)

        runBlocking { repo.purgeTrash(now) }

        val restored = runBlocking { ref.get(Source.SERVER).await() }
        assertThat(restored.exists()).isTrue()
        assertThat(restored.getTimestamp("deletedAt")).isNull()
    }

    @Test
    fun purgeTransactionSkipsNoteRestoredConcurrently() =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val now = Instant.now()
                val cutoff = now.minus(Duration.ofDays(30)).toTimestamp()
                val id = repo.add("restored after candidate selection")
                val uid = auth.currentUser!!.uid
                val ref =
                    db
                        .collection("users")
                        .document(uid)
                        .collection("notes")
                        .document(id)
                repo.notes.first { it.size == 1 }
                ref.update("deletedAt", now.minus(Duration.ofDays(31)).toTimestamp()).await()
                val candidate =
                    ref.parent
                        .whereLessThan("deletedAt", cutoff)
                        .get(Source.SERVER)
                        .await()
                        .single()
                clearDeletedAtOnServer(uid, id)

                repo.purgeCandidate(uid, candidate.reference, cutoff)

                val restored = ref.get(Source.SERVER).await()
                assertThat(restored.exists()).isTrue()
                assertThat(restored.getTimestamp("deletedAt")).isNull()
            }
        }

    @Test
    fun addDropsUnknownExtraLabelId() {
        val id = repo.add("x", listOf("bogus"))

        assertThat(
            repo.notes
                .await { it.isNotEmpty() }
                .single()
                .labelIds,
        ).isEmpty()

        val ref =
            db
                .collection("users")
                .document(auth.currentUser!!.uid)
                .collection("notes")
                .document(id)
        runBlocking { ref.update(mapOf("text" to "synced", "labelIds" to listOf("dangling"))).await() }
        assertThat(
            repo.notes
                .await { it.singleOrNull()?.text == "synced" }
                .single()
                .labelIds,
        ).isEmpty()

        repo.setLabels(listOf(id), add = listOf("bogus"), remove = emptyList())
        ref.snapshots().await { it.get("labelIds") == emptyList<String>() }
    }

    @Test
    fun rapidWritesWhileOfflineKeepManualLabel() {
        runBlocking { db.disableNetwork().await() }
        try {
            val id = repo.add("t #Foo")
            val manual = runBlocking { repo.createLabel("M") }
            repo.setLabels(listOf(id), add = listOf(manual), remove = emptyList())
            repo.updateText(id, "t2 #Foo")

            val note =
                repo.notes
                    .await { notes ->
                        val updated = notes.singleOrNull()
                        updated?.text == "t2 #Foo" && updated.labelIds.size == 2
                    }.single()
            val names =
                repo.labels
                    .await { it.size == 2 }
                    .filter { it.id in note.labelIds }
                    .map { it.name }
            assertThat(names).containsExactly("Foo", "M")
        } finally {
            runBlocking { db.enableNetwork().await() }
        }
    }

    @Test
    fun deleteLabelDetachesFromNotes() {
        val id = repo.add("idea #foo")
        val label = repo.labels.await { it.size == 1 }.single()
        repo.notes.await { it.size == 1 && label.id in it.single().labelIds }

        repo.deleteLabel(label.id)

        val note = repo.notes.await { n -> n.single().labelIds.isEmpty() }.single()
        assertThat(note.text).isEqualTo("idea #foo")
        assertThat(repo.labels.await { it.isEmpty() }).isEmpty()
        val ref =
            db
                .collection("users")
                .document(auth.currentUser!!.uid)
                .collection("notes")
                .document(id)
        val stored = runBlocking { ref.get(Source.CACHE).await() }
        assertThat(stored.get("labelIds")).isEqualTo(emptyList<String>())
    }

    @Test
    fun deleteLabelOfflineDetachesCachedNotes() =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val ids = listOf(repo.add("first #foo"), repo.add("second #foo"))
                val label = repo.labels.first { it.size == 1 }.single()
                repo.notes.first { it.size == 2 && it.all { note -> label.id in note.labelIds } }
                db.waitForPendingWrites().await()
                val user = db.collection("users").document(auth.currentUser!!.uid)

                db.disableNetwork().await()
                try {
                    repo.deleteLabel(label.id)

                    repo.labels.first { it.isEmpty() }
                    repo.notes.first { it.size == 2 && it.all { note -> note.labelIds.isEmpty() } }
                    ids.forEach { id ->
                        val note =
                            user
                                .collection("notes")
                                .document(id)
                                .get(Source.CACHE)
                                .await()
                        assertThat(note.get("labelIds")).isEqualTo(emptyList<String>())
                    }
                    assertThat(
                        user
                            .collection("labels")
                            .get(Source.CACHE)
                            .await()
                            .isEmpty,
                    ).isTrue()
                } finally {
                    db.enableNetwork().await()
                }
            }
        }

    private fun Instant.toTimestamp() = Timestamp(epochSecond, nano)

    companion object {
        private lateinit var auth: FirebaseAuth
        private lateinit var db: FirebaseFirestore

        @BeforeClass
        @JvmStatic
        fun connect() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val options =
                FirebaseOptions
                    .Builder()
                    .setProjectId(PROJECT)
                    .setApplicationId("1:1:android:0")
                    .setApiKey("fake-api-key")
                    .build()
            val app =
                FirebaseApp.getApps(context).firstOrNull { it.name == PROJECT }
                    ?: FirebaseApp.initializeApp(context, options, PROJECT)
            auth = FirebaseAuth.getInstance(app)
            db = FirebaseFirestore.getInstance(app)
            auth.useEmulator(HOST, 9099)
            db.useEmulator(HOST, 8080)
        }

        /** Simulates another device: clears deletedAt through the emulator REST API (admin token bypasses rules). */
        private fun clearDeletedAtOnServer(
            uid: String,
            noteId: String,
        ) {
            val path = "users/$uid/notes/$noteId"
            val base = "http://$HOST:8080/v1/projects/$PROJECT/databases/(default)/documents"
            val url = URL("$base/$path?updateMask.fieldPaths=deletedAt")
            (url.openConnection() as HttpURLConnection).run {
                requestMethod = "PATCH"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer owner")
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write("""{"fields":{"deletedAt":{"nullValue":null}}}""".toByteArray()) }
                check(responseCode in HTTP_OK) { "emulator patch failed: $responseCode" }
                disconnect()
            }
        }

        private fun deleteNoteOnServer(
            uid: String,
            noteId: String,
        ) {
            val base = "http://$HOST:8080/v1/projects/$PROJECT/databases/(default)/documents"
            val url = URL("$base/users/$uid/notes/$noteId")
            (url.openConnection() as HttpURLConnection).run {
                requestMethod = "DELETE"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer owner")
                check(responseCode in HTTP_OK) { "emulator delete failed: $responseCode" }
                disconnect()
            }
        }

        private fun clearEmulatorData() {
            val url = URL("http://$HOST:8080/emulator/v1/projects/$PROJECT/databases/(default)/documents")
            (url.openConnection() as HttpURLConnection).run {
                requestMethod = "DELETE"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                check(responseCode in HTTP_OK) { "emulator clear failed: $responseCode" }
                disconnect()
            }
        }
    }
}
