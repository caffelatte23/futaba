package io.github.caffelatte23.futaba.entry

import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.core.data.AuthAccount
import io.github.caffelatte23.futaba.core.data.signedInOf
import org.junit.Test

class ShareRoutingTest {
    @Test
    fun sharesGoToDraftUntilGoogleSessionExists() {
        // A v1 anonymous user has a uid but is still on the login screen (a merge may be running).
        assertThat(shareGoesToDraft(signedInOf(AuthAccount(isAnonymous = true, email = null)))).isTrue()
        assertThat(shareGoesToDraft(signedInOf(null))).isTrue()
        assertThat(shareGoesToDraft(signedInOf(AuthAccount(isAnonymous = false, email = "a@example.com")))).isFalse()
    }
}
