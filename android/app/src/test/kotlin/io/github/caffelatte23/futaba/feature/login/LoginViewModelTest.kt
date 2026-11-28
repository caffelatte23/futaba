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
    fun failedSignInShowsMessageAndKeepsButtonUsable() =
        runTest {
            var calls = 0
            val vm =
                LoginViewModel(MutableStateFlow(true)) {
                    calls++
                    LinkResult.Failed("failed")
                }
            vm.signIn(context)
            assertThat(vm.state.value.message).isEqualTo("failed")
            assertThat(vm.state.value.busy).isFalse()
            vm.signIn(context)
            assertThat(calls).isEqualTo(2)
        }

    @Test
    fun cancelledShowsNoMessage() =
        runTest {
            val vm = LoginViewModel(MutableStateFlow(true)) { LinkResult.Cancelled }
            vm.signIn(context)
            assertThat(vm.state.value.message).isNull()
            assertThat(vm.state.value.busy).isFalse()
        }

    @Test
    fun offlineShowsNoticeAndDoesNotLaunchAuth() =
        runTest {
            val online = MutableStateFlow(false)
            var calls = 0
            val vm =
                LoginViewModel(online) {
                    calls++
                    LinkResult.Cancelled
                }
            assertThat(vm.state.value.offline).isTrue()
            vm.signIn(context)
            assertThat(calls).isEqualTo(0)
            assertThat(vm.state.value.busy).isFalse()
            online.value = true
            assertThat(vm.state.value.offline).isFalse()
            vm.signIn(context)
            assertThat(calls).isEqualTo(1)
        }
}
