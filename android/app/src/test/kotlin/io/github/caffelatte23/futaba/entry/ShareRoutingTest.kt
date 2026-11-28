package io.github.caffelatte23.futaba.entry

import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.core.data.AuthSession
import io.github.caffelatte23.futaba.core.data.StoredUser
import org.junit.Test

class ShareRoutingTest {
    private fun goesToDraft(user: StoredUser?) = shareGoesToDraft(AuthSession(user).signedIn.value)

    @Test
    fun sharesGoToDraftUntilGoogleSessionExists() {
        // A v1 anonymous user has a uid but is still on the login screen (a merge may be running).
        assertThat(goesToDraft(StoredUser("u", isAnonymous = true, email = null))).isTrue()
        assertThat(goesToDraft(null)).isTrue()
        assertThat(goesToDraft(StoredUser("u", isAnonymous = false, email = "a@example.com"))).isFalse()
    }
}
