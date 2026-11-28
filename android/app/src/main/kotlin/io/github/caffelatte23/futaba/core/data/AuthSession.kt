package io.github.caffelatte23.futaba.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class StoredUser(
    val uid: String,
    val isAnonymous: Boolean,
    val email: String?,
)

/** The signed-in state, seeded synchronously from the persisted user (no auth-listener round trip). */
internal class AuthSession(
    user: StoredUser?,
) {
    private val _uid = MutableStateFlow<String?>(null)
    val uid: StateFlow<String?> = _uid
    private val _account = MutableStateFlow<AuthAccount?>(null)
    val account: StateFlow<AuthAccount?> = _account
    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn

    init {
        publish(user)
    }

    fun publish(user: StoredUser?) {
        _uid.value = user?.uid
        _account.value = user?.let { AuthAccount(it.isAnonymous, it.email) }
        _signedIn.value = user != null && !user.isAnonymous
    }
}
