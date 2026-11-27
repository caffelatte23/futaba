@file:Suppress("RedundantSuspendModifier") // Google Tasks await() suspends.

package io.github.caffelatte23.futaba.core.data

import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import io.github.caffelatte23.futaba.R
import io.github.caffelatte23.futaba.core.domain.MergeDocuments
import io.github.caffelatte23.futaba.di.configureMergeFirestoreEmulator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private const val TAG = "GoogleAccountMigration"
private const val COPY_TIMEOUT_MS = 90_000L
private const val CLEANUP_TIMEOUT_MS = 15_000L
private val REFRESHABLE_CREDENTIAL_ERRORS = setOf("ERROR_INVALID_CREDENTIAL", "ERROR_USER_TOKEN_EXPIRED")

internal class GoogleAccountMigration(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val configureSecondary: (FirebaseFirestore) -> Unit = { configureMergeFirestoreEmulator(it) },
) {
    suspend fun merge(
        anonymous: FirebaseUser,
        credential: AuthCredential,
        refreshCredential: (suspend () -> AuthCredential)?,
    ): LinkResult {
        val app = auth.app
        val secondary =
            FirebaseApp.initializeApp(
                app.applicationContext,
                app.options,
                "futaba-merge-${UUID.randomUUID()}",
            )
        try {
            val mainData = FirestoreAccountMerge(firestore)
            val copied =
                copyToGoogle(anonymous, credential, mainData, secondary)
                    ?: return LinkResult.Failed(app.applicationContext.getString(R.string.settings_sync_failed))
            cleanup(anonymous, mainData, copied.source)
            signInMain(credential, copied.googleUid, FirebaseAuth.getInstance(secondary), refreshCredential)
            return LinkResult.Merged(copied.noteCount)
        } finally {
            withContext(NonCancellable) {
                runCatching { FirebaseAuth.getInstance(secondary).signOut() }
                    .onFailure { Log.w(TAG, "Couldn't sign out of the secondary FirebaseApp", it) }
                runCatching { secondary.delete() }
                    .onFailure { Log.w(TAG, "Couldn't delete the secondary FirebaseApp", it) }
            }
        }
    }

    private fun secondaryFirestore(secondary: FirebaseApp): FirebaseFirestore {
        val secondaryDb = FirebaseFirestore.getInstance(secondary)
        configureSecondary(secondaryDb)
        secondaryDb.firestoreSettings =
            FirebaseFirestoreSettings
                .Builder(secondaryDb.firestoreSettings)
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()
        return secondaryDb
    }

    @Suppress("TooGenericExceptionCaught") // Any copy failure leaves the anonymous account intact.
    private suspend fun copyToGoogle(
        anonymous: FirebaseUser,
        credential: AuthCredential,
        mainData: FirestoreAccountMerge,
        secondary: FirebaseApp,
    ): CopiedMerge? =
        try {
            withTimeoutOrNull(COPY_TIMEOUT_MS) {
                firestore.waitForPendingWrites().await()
                val source = mainData.read(anonymous.uid)
                val secondaryData = FirestoreAccountMerge(secondaryFirestore(secondary))
                val secondaryAuth = FirebaseAuth.getInstance(secondary)
                val googleUid = checkNotNull(secondaryAuth.signInWithCredential(credential).await().user).uid
                check(anonymous.uid != googleUid)
                CopiedMerge(source, googleUid, secondaryData.copy(anonymous.uid, googleUid, source))
            }.also { if (it == null) Log.w(TAG, "Copy to Google timed out") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't copy anonymous data to Google", e)
            null
        }

    private suspend fun signInMain(
        credential: AuthCredential,
        googleUid: String,
        secondaryAuth: FirebaseAuth,
        refreshCredential: (suspend () -> AuthCredential)?,
    ) {
        val result =
            try {
                auth.signInWithCredential(credential).await()
            } catch (e: FirebaseAuthException) {
                // Firebase can report an expired OAuth token as ERROR_INVALID_CREDENTIAL.
                if (refreshCredential == null || e.errorCode !in REFRESHABLE_CREDENTIAL_ERRORS) throw e
                val refreshed = refreshCredential()
                // Credential Manager can return a different Google account. Verify before switching the main app.
                check(
                    secondaryAuth
                        .signInWithCredential(refreshed)
                        .await()
                        .user
                        ?.uid == googleUid,
                )
                auth.signInWithCredential(refreshed).await()
            }
        check(result.user?.uid == googleUid)
    }

    @Suppress("TooGenericExceptionCaught") // Cleanup is best effort after the destination is committed.
    private suspend fun cleanup(
        anonymous: FirebaseUser,
        data: FirestoreAccountMerge,
        source: MergeDocuments,
    ) {
        try {
            val deleted =
                withTimeoutOrNull(CLEANUP_TIMEOUT_MS) {
                    data.delete(anonymous.uid, source)
                    true
                }
            if (deleted == null) {
                Log.w(TAG, "Anonymous document cleanup timed out")
                return
            }
            // The Auth SDK can sign out on delete failure, so let it settle before releasing the sign-in lock.
            withContext(NonCancellable) { anonymous.delete().await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't finish anonymous cleanup", e)
        }
    }

    private data class CopiedMerge(
        val source: MergeDocuments,
        val googleUid: String,
        val noteCount: Int,
    )
}
