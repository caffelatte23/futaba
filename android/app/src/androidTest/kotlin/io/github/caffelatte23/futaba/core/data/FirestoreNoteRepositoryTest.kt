package io.github.caffelatte23.futaba.core.data

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import io.github.caffelatte23.futaba.core.domain.filterNotes
import io.github.caffelatte23.futaba.core.model.NoteFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
            repo = FirestoreNoteRepository(db, AuthRepository(auth), scope)
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

        val notes = repo.notes.await { it.isNotEmpty() }
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

        val notes = repo.notes.await { it.size == 2 }
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
    fun deleteLabelDetachesFromNotes() {
        repo.add("idea #foo")
        val label = repo.labels.await { it.size == 1 }.single()
        repo.notes.await { it.size == 1 }

        repo.deleteLabel(label.id)

        val note = repo.notes.await { n -> n.single().labelIds.isEmpty() }.single()
        assertThat(note.text).isEqualTo("idea #foo")
        assertThat(repo.labels.await { it.isEmpty() }).isEmpty()
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

        private fun clearEmulatorData() {
            val url = URL("http://$HOST:8080/emulator/v1/projects/$PROJECT/databases/(default)/documents")
            (url.openConnection() as HttpURLConnection).run {
                requestMethod = "DELETE"
                responseCode
                disconnect()
            }
        }
    }
}
