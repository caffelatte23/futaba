@file:Suppress("RedundantSuspendModifier") // Google Tasks await() suspends.

package io.github.caffelatte23.futaba.core.data

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import io.github.caffelatte23.futaba.di.configureMergeEmulators
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

private const val PROJECT = "demo-futaba"
private const val HOST = "10.0.2.2"

class AuthMergeTest {
    @Test
    fun collisionMergesAnonymousNotes() =
        runBlocking {
            withTimeout(45_000L) {
                val id = UUID.randomUUID().toString()
                val app = createTestApp(id)
                val auth = FirebaseAuth.getInstance(app)
                val db = FirebaseFirestore.getInstance(app)
                configureMergeEmulators(auth, db, enabled = true)
                try {
                    verifyCollision(auth, db, id)
                } finally {
                    auth.signOut()
                    app.delete()
                }
            }
        }

    private suspend fun verifyCollision(
        auth: FirebaseAuth,
        db: FirebaseFirestore,
        id: String,
    ) {
        val users = db.collection("users")
        val token = """{"sub":"$id","email":"$id@example.com","email_verified":true}"""
        val credential = GoogleAuthProvider.getCredential(token, null)
        val google = checkNotNull(auth.signInWithCredential(credential).await().user)
        val destination = users.document(google.uid)
        destination.seedLabel("google-idea", "Idea")
        destination.seedNote("existing", "existing note", "google-idea")

        auth.signOut()
        val anonymous = checkNotNull(auth.signInAnonymously().await().user)
        val source = users.document(anonymous.uid)
        source.seedLabel("anonymous-idea", "iDEA")
        source.seedLabel("work", "Work")
        source.seedNote("existing", "first anonymous note", "anonymous-idea")
        source.seedNote("another", "second anonymous note", "anonymous-idea", "work")

        val repository = AuthRepository(auth, db)
        val migration =
            GoogleAccountMigration(auth, db) { secondaryAuth, secondaryDb ->
                configureMergeEmulators(secondaryAuth, secondaryDb, enabled = true)
            }
        assertThat(repository.linkGoogleCredential(credential, migration)).isEqualTo(LinkResult.Merged(2))
        assertThat(repository.uid.value).isEqualTo(google.uid)
        assertMergedDocuments(destination)
        assertAnonymousCollectionsEmpty(anonymous.uid)
    }

    private suspend fun assertMergedDocuments(destination: DocumentReference) {
        val notes =
            destination
                .collection(
                    "notes",
                ).get(Source.SERVER)
                .await()
                .documents
                .associateBy { it.getString("text") }
        val labels =
            destination
                .collection(
                    "labels",
                ).get(Source.SERVER)
                .await()
                .documents
                .associateBy { it.getString("name") }
        assertThat(notes.keys).containsExactly("existing note", "first anonymous note", "second anonymous note")
        assertThat(labels.keys).containsExactly("Idea", "Work")
        assertThat(notes.getValue("existing note").id).isEqualTo("existing")
        assertThat(notes.getValue("first anonymous note").get("labelIds")).isEqualTo(listOf("google-idea"))
        assertThat(
            notes.getValue("second anonymous note").get("labelIds"),
        ).isEqualTo(listOf("google-idea", labels.getValue("Work").id))
    }

    private fun assertAnonymousCollectionsEmpty(uid: String) {
        for (name in listOf("notes", "labels")) {
            val url = "http://$HOST:8080/v1/projects/$PROJECT/databases/(default)/documents/users/$uid/$name"
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            // The Emulator Suite owner credential can inspect the old uid after Auth deletes it.
            connection.setRequestProperty("Authorization", "Bearer owner")
            try {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                assertThat(JSONObject(body).optJSONArray("documents")?.length() ?: 0).isEqualTo(0)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun createTestApp(id: String): FirebaseApp {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options =
            FirebaseOptions
                .Builder()
                .setProjectId(PROJECT)
                .setApplicationId("1:1234567890:android:futaba")
                .setApiKey("fake-api-key")
                .build()
        return FirebaseApp.initializeApp(context, options, "auth-merge-test-$id")
    }
}

private suspend fun DocumentReference.seedLabel(
    id: String,
    name: String,
) {
    collection("labels").document(id).set(mapOf("name" to name, "color" to "", "order" to 0)).await()
}

private suspend fun DocumentReference.seedNote(
    id: String,
    text: String,
    vararg labels: String,
) {
    val now = Timestamp.now()
    val fields =
        mapOf(
            "text" to text,
            "labelIds" to labels.toList(),
            "pinned" to false,
            "createdAt" to now,
            "updatedAt" to now,
            "deletedAt" to null,
        )
    collection("notes").document(id).set(fields).await()
}
