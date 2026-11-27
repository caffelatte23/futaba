@file:Suppress("RedundantSuspendModifier") // Google Tasks await() suspends.

package io.github.caffelatte23.futaba.core.data

import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import io.github.caffelatte23.futaba.core.domain.MergeDocuments
import io.github.caffelatte23.futaba.di.configureMergeEmulators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private const val TAG = "GoogleAccountMigration"
private const val CLEANUP_TIMEOUT_MS = 15_000L

internal class GoogleAccountMigration(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val configureSecondary: (FirebaseAuth, FirebaseFirestore) -> Unit = { secondaryAuth, secondaryDb ->
        configureMergeEmulators(secondaryAuth, secondaryDb)
    },
) {
    suspend fun merge(
        anonymous: FirebaseUser,
        credential: AuthCredential,
    ): LinkResult.Merged {
        val app = auth.app
        val secondary =
            FirebaseApp.initializeApp(
                app.applicationContext,
                app.options,
                "futaba-merge-${UUID.randomUUID()}",
            )
        val secondaryAuth = FirebaseAuth.getInstance(secondary)
        val secondaryDb = FirebaseFirestore.getInstance(secondary)
        try {
            configureSecondary(secondaryAuth, secondaryDb)
            secondaryDb.firestoreSettings =
                FirebaseFirestoreSettings
                    .Builder(secondaryDb.firestoreSettings)
                    .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                    .build()
            return transfer(anonymous, credential, secondaryAuth, secondaryDb)
        } finally {
            secondaryAuth.signOut()
            secondary.delete()
        }
    }

    private suspend fun transfer(
        anonymous: FirebaseUser,
        credential: AuthCredential,
        secondaryAuth: FirebaseAuth,
        secondaryDb: FirebaseFirestore,
    ): LinkResult.Merged {
        // Each server reads/writes only under the identity that owns the data.
        val google = checkNotNull(secondaryAuth.signInWithCredential(credential).await().user)
        check(anonymous.uid != google.uid)
        firestore.waitForPendingWrites().await()
        val source = FirestoreAccountMerge(firestore).read(anonymous.uid)
        val secondaryData = FirestoreAccountMerge(secondaryDb)
        secondaryData.copy(anonymous.uid, google.uid, source)

        // Move the anonymous session to the secondary app before switching the main app to Google.
        secondaryAuth.updateCurrentUser(anonymous).await()
        auth.updateCurrentUser(google).await()
        val cleanupFinished =
            withTimeoutOrNull(CLEANUP_TIMEOUT_MS) {
                cleanup(checkNotNull(secondaryAuth.currentUser), secondaryData, source)
                true
            }
        if (cleanupFinished == null) Log.w(TAG, "Anonymous cleanup timed out")
        return LinkResult.Merged(source.notes.size)
    }

    @Suppress("TooGenericExceptionCaught") // Cleanup is best effort after the destination is committed.
    private suspend fun cleanup(
        anonymous: FirebaseUser,
        data: FirestoreAccountMerge,
        source: MergeDocuments,
    ) {
        try {
            data.delete(anonymous.uid, source)
            anonymous.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't finish anonymous cleanup", e)
        }
    }
}
