package io.github.caffelatte23.futaba.core.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AuthSessionTest {
    @Test
    fun storedGoogleUserIsSignedInImmediately() {
        val session = AuthSession(StoredUser("u1", isAnonymous = false, email = "a@example.com"))
        assertThat(session.signedIn.value).isTrue()
        assertThat(session.uid.value).isEqualTo("u1")
        assertThat(session.account.value).isEqualTo(AuthAccount(isAnonymous = false, email = "a@example.com"))
    }

    @Test
    fun anonymousOrNoStoredUserIsNotSignedIn() {
        assertThat(AuthSession(StoredUser("u1", isAnonymous = true, email = null)).signedIn.value).isFalse()
        assertThat(AuthSession(null).signedIn.value).isFalse()
    }

    @Test
    fun publishFollowsSignOut() {
        val session = AuthSession(StoredUser("u1", isAnonymous = false, email = null))
        session.publish(null)
        assertThat(session.signedIn.value).isFalse()
        assertThat(session.uid.value).isNull()
    }
}
