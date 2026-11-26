package io.github.caffelatte23.futaba.core.data

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository
    @Inject
    constructor(
        private val auth: FirebaseAuth,
    ) {
        private val _uid = MutableStateFlow(auth.currentUser?.uid)
        val uid: StateFlow<String?> = _uid

        val isAnonymous: Boolean get() = auth.currentUser?.isAnonymous ?: false
        val email: String? get() = auth.currentUser?.email

        init {
            auth.addAuthStateListener { _uid.value = it.currentUser?.uid }
        }

        /**
         * Returns the current uid, signing in anonymously if needed; null when that fails (e.g. offline).
         * Suppressions: Firebase throws varied exceptions (any failure means "no uid yet");
         * detekt wrongly flags suspend.
         */
        @Suppress("TooGenericExceptionCaught", "RedundantSuspendModifier")
        suspend fun ensureSignedIn(): String? =
            auth.currentUser?.uid ?: try {
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
