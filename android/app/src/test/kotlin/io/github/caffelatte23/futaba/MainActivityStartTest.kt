package io.github.caffelatte23.futaba

import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.core.data.AuthAccount
import io.github.caffelatte23.futaba.core.data.signedInOf
import org.junit.Test

class MainActivityStartTest {
    private fun first(stored: AuthAccount?) = startScreen(signedInOf(stored))

    @Test
    fun storedGoogleUserStartsOnStream() {
        assertThat(first(AuthAccount(isAnonymous = false, email = "a@example.com"))).isEqualTo(StartScreen.Stream)
    }

    @Test
    fun noUserOrV1AnonymousUserStartsOnLogin() {
        assertThat(first(null)).isEqualTo(StartScreen.Login)
        assertThat(first(AuthAccount(isAnonymous = true, email = null))).isEqualTo(StartScreen.Login)
    }
}
