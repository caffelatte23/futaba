package io.github.caffelatte23.futaba.feature.login

import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.core.data.LinkResult
import io.github.caffelatte23.futaba.testing.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class LoginViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val context = ContextWrapper(null)

    @Test
    fun signedInUserStartsOnStream() =
        runTest {
            val vm = LoginViewModel(MutableStateFlow(true)) { LinkResult.Cancelled }
            // The very first value is already "signed in": no waiting state in between.
            assertThat(vm.signedIn.value).isTrue()
        }

    @Test
    fun signInOfflineShowsMessage() =
        runTest {
            var calls = 0
            val vm =
                LoginViewModel(MutableStateFlow(false)) {
                    calls++
                    LinkResult.Failed("offline")
                }
            vm.signIn(context)
            assertThat(vm.state.value.message).isEqualTo("offline")
            assertThat(vm.state.value.busy).isFalse()
            vm.signIn(context)
            assertThat(calls).isEqualTo(2)
        }

    @Test
    fun cancelledShowsNoMessage() =
        runTest {
            val vm = LoginViewModel(MutableStateFlow(false)) { LinkResult.Cancelled }
            vm.signIn(context)
            assertThat(vm.state.value.message).isNull()
            assertThat(vm.state.value.busy).isFalse()
        }
}
