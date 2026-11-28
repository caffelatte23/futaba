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
        private val _signedIn = MutableStateFlow(auth.currentUser.isGoogleUser())

        /** True for a non-anonymous user. Seeded synchronously from the persisted user, so no waiting UI is needed. */
        val signedIn: StateFlow<Boolean> = _signedIn

        init {
            auth.addAuthStateListener { publishUser() }
        }

        private fun publishUser() {
            val user = auth.currentUser
            _uid.value = user?.uid
            _account.value = user?.toAccount()
            _signedIn.value = user.isGoogleUser()
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
                val linked = withTimeoutOrNull(LINK_TIMEOUT_MS) { user.linkWithCredential(credential).await() }
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
            if (withTimeoutOrNull(LINK_TIMEOUT_MS) { auth.signInWithCredential(credential).await() } != null) {
                LinkResult.Linked
            } else {
                Log.w(TAG, "Google sign-in timed out")
                LinkResult.Failed(auth.app.applicationContext.getString(R.string.login_failed))
            }

        /** Signs out only; never creates an anonymous user. The account's notes stay on the server. */
        fun signOut() {
            auth.signOut()
            publishUser()
        }
    }

private fun FirebaseUser?.isGoogleUser() = this != null && !isAnonymous

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
