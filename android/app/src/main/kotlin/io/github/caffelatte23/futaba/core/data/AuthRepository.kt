package io.github.caffelatte23.futaba.core.data

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import io.github.caffelatte23.futaba.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AuthRepository"
private const val LINK_TIMEOUT_MS = 90_000L

sealed interface LinkResult {
    data object Linked : LinkResult

    data class Merged(
        val count: Int,
    ) : LinkResult

    data object Cancelled : LinkResult

    data class Failed(
        val message: String,
    ) : LinkResult
}

data class AuthAccount(
    val isAnonymous: Boolean,
    val email: String?,
)

@Singleton
class AuthRepository
    @Inject
    constructor(
        private val auth: FirebaseAuth,
        private val firestore: FirebaseFirestore,
    ) {
        private val signInLock = Mutex()
        private val _uid = MutableStateFlow(auth.currentUser?.uid)
        val uid: StateFlow<String?> = _uid
        private val _account = MutableStateFlow(auth.currentUser?.toAccount())
        val account: StateFlow<AuthAccount?> = _account

        val isAnonymous: Boolean get() = auth.currentUser?.isAnonymous ?: false
        val email: String? get() = auth.currentUser?.email

        init {
            auth.addAuthStateListener { publishUser() }
        }

        private fun publishUser() {
            val user = auth.currentUser
            _uid.value = user?.uid
            _account.value = user?.toAccount()
        }

        @Suppress("TooGenericExceptionCaught") // Credentials and Firebase expose different failure types.
        suspend fun linkGoogle(context: Context): LinkResult =
            try {
                linkGoogleCredential(getGoogleCredential(context))
            } catch (_: GetCredentialCancellationException) {
                LinkResult.Cancelled
            } catch (_: NoCredentialException) {
                LinkResult.Failed(context.getString(R.string.settings_no_google_account))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Google linking failed", e)
                LinkResult.Failed(context.getString(R.string.settings_sync_failed))
            }

        internal suspend fun linkGoogleCredential(
            credential: AuthCredential,
            migration: GoogleAccountMigration = GoogleAccountMigration(auth, firestore),
        ): LinkResult =
            signInLock.withLock {
                try {
                    withTimeoutOrNull(LINK_TIMEOUT_MS) {
                        ensureSignedInLocked()
                        val user = checkNotNull(auth.currentUser)
                        check(user.isAnonymous)
                        linkOrMerge(user, credential, migration)
                    } ?: LinkResult.Failed(auth.app.applicationContext.getString(R.string.settings_sync_failed))
                } finally {
                    publishUser()
                }
            }

        @Suppress("RedundantSuspendModifier") // Google Tasks await() suspends.
        private suspend fun linkOrMerge(
            user: FirebaseUser,
            credential: AuthCredential,
            migration: GoogleAccountMigration,
        ): LinkResult =
            try {
                user.linkWithCredential(credential).await()
                LinkResult.Linked
            } catch (e: FirebaseAuthUserCollisionException) {
                migration.merge(user, e.updatedCredential ?: credential)
            }

        suspend fun signOut(): Boolean =
            signInLock.withLock {
                auth.signOut()
                publishUser()
                val uid = ensureSignedInLocked()
                publishUser()
                uid != null
            }

        /**
         * Returns the current uid, signing in anonymously if needed; null when that fails (e.g. offline).
         * Suppressions: Firebase throws varied exceptions (any failure means "no uid yet");
         * detekt wrongly flags suspend.
         */
        suspend fun ensureSignedIn(): String? =
            auth.currentUser?.uid ?: signInLock.withLock {
                ensureSignedInLocked()
            }

        private suspend fun ensureSignedInLocked(): String? = auth.currentUser?.uid ?: signInAnonymously()

        @Suppress("TooGenericExceptionCaught", "RedundantSuspendModifier") // see ensureSignedIn
        private suspend fun signInAnonymously(): String? =
            try {
                auth
                    .signInAnonymously()
                    .await()
                    .user
                    ?.uid
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
    }

private fun FirebaseUser.toAccount() = AuthAccount(isAnonymous, email)

@Suppress("RedundantSuspendModifier") // Credential Manager's getCredential() suspends.
private suspend fun getGoogleCredential(context: Context): AuthCredential {
    val clientId = context.getString(R.string.default_web_client_id)
    check(clientId.isNotBlank())
    val option =
        GetGoogleIdOption
            .Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val credential = CredentialManager.create(context).getCredential(context, request).credential
    check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
    return GoogleAuthProvider.getCredential(GoogleIdTokenCredential.createFrom(credential.data).idToken, null)
}
