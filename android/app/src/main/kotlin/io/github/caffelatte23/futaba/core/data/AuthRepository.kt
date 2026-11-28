package io.github.caffelatte23.futaba.core.data

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
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
private const val LINK_TIMEOUT_MS = 30_000L

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
        private val _signedIn = MutableStateFlow(signedInOf(auth.currentUser?.toAccount()))

        /** True for a non-anonymous user. Seeded synchronously from the persisted user, so no waiting UI is needed. */
        val signedIn: StateFlow<Boolean> = _signedIn

        // A task whose wait timed out may still complete in the SDK; auth-changing work waits for it.
        private var pending: Task<*>? = null

        init {
            auth.addAuthStateListener { publishUser() }
        }

        private fun publishUser() {
            val user = auth.currentUser
            _uid.value = user?.uid
            _account.value = user?.toAccount()
            _signedIn.value = signedInOf(user?.toAccount())
        }

        @Suppress("TooGenericExceptionCaught") // Credentials and Firebase expose different failure types.
        suspend fun signInWithGoogle(context: Context): LinkResult =
            try {
                signInGoogleCredential(getGoogleCredential(context)) { getGoogleCredential(context) }
            } catch (_: GetCredentialCancellationException) {
                LinkResult.Cancelled
            } catch (_: NoCredentialException) {
                LinkResult.Failed(context.getString(R.string.login_no_google_account))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Google sign-in failed", e)
                LinkResult.Failed(context.getString(R.string.login_failed))
            }

        private val googleMigration = GoogleAccountMigration(auth, firestore)

        /** Links when an anonymous (v1) user exists so its notes carry over; otherwise a plain sign-in. */
        internal suspend fun signInGoogleCredential(
            credential: AuthCredential,
            migration: GoogleAccountMigration = googleMigration,
            refreshCredential: (suspend () -> AuthCredential)? = null,
        ): LinkResult =
            signInLock.withLock {
                try {
                    awaitPending()
                    val user = auth.currentUser
                    if (user?.isAnonymous == true) {
                        linkOrMerge(user, credential, migration, refreshCredential)
                    } else {
                        signInPlain(credential)
                    }
                } finally {
                    publishUser()
                }
            }

        @Suppress("RedundantSuspendModifier") // Google Tasks await() suspends.
        private suspend fun linkOrMerge(
            user: FirebaseUser,
            credential: AuthCredential,
            migration: GoogleAccountMigration,
            refreshCredential: (suspend () -> AuthCredential)?,
        ): LinkResult =
            try {
                val linked = user.linkWithCredential(credential).awaitWithinTimeout()
                if (linked != null) {
                    LinkResult.Linked
                } else {
                    Log.w(TAG, "Google linking timed out")
                    LinkResult.Failed(auth.app.applicationContext.getString(R.string.login_failed))
                }
            } catch (_: FirebaseAuthUserCollisionException) {
                migration.merge(user, credential, refreshCredential)
            }

        @Suppress("RedundantSuspendModifier") // Google Tasks await() suspends.
        private suspend fun signInPlain(credential: AuthCredential): LinkResult =
            if (auth.signInWithCredential(credential).awaitWithinTimeout() != null) {
                LinkResult.Linked
            } else {
                Log.w(TAG, "Google sign-in timed out")
                LinkResult.Failed(auth.app.applicationContext.getString(R.string.login_failed))
            }

        /** Signs out only; never creates an anonymous user. The account's notes stay on the server. */
        suspend fun signOut() =
            signInLock.withLock {
                awaitPending()
                auth.signOut()
                publishUser()
            }

        @Suppress("RedundantSuspendModifier") // Google Tasks await() suspends.
        private suspend fun <T> Task<T>.awaitWithinTimeout(): T? {
            pending = this
            return withTimeoutOrNull(LINK_TIMEOUT_MS) { await() }
        }

        @Suppress("TooGenericExceptionCaught", "SwallowedException", "RedundantSuspendModifier")
        private suspend fun awaitPending() {
            val task = pending ?: return
            if (!task.isComplete) {
                try {
                    task.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Its own failure was already reported (or timed out); we only need it to be finished.
                }
            }
            pending = null
        }
    }

private fun FirebaseUser.toAccount() = AuthAccount(isAnonymous, email)

@Suppress("RedundantSuspendModifier") // Credential Manager's getCredential() suspends.
private suspend fun getGoogleCredential(context: Context): AuthCredential {
    val clientId = context.getString(R.string.default_web_client_id)
    check(clientId.isNotBlank())
    // The explicit sign-in button flow: lets the user add an account or re-authenticate.
    val option = GetSignInWithGoogleOption.Builder(clientId).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val credential = CredentialManager.create(context).getCredential(context, request).credential
    check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
    return GoogleAuthProvider.getCredential(GoogleIdTokenCredential.createFrom(credential.data).idToken, null)
}

/** True only for a Google (non-anonymous) account. */
internal fun signedInOf(account: AuthAccount?): Boolean = account != null && !account.isAnonymous
