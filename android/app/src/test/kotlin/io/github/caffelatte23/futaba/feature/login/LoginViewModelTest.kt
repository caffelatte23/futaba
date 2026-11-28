package io.github.caffelatte23.futaba.feature.login

import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import io.github.caffelatte23.futaba.core.data.LinkResult
import io.github.caffelatte23.futaba.core.data.SingleFlight
import io.github.caffelatte23.futaba.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class LoginViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val context = ContextWrapper(null)
    private val gate = SingleFlight()

    @Test
    fun failedSignInShowsMessageAndKeepsButtonUsable() =
        runTest {
            var calls = 0
            val vm =
                LoginViewModel(MutableStateFlow(true), gate.active) {
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
            val vm =
                LoginViewModel(
                    MutableStateFlow(true),
                    gate.active,
                ) { gate.run(LinkResult.Cancelled) { LinkResult.Cancelled } }
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
                LoginViewModel(online, gate.active) {
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

    @Test
    fun noSecondSignInWhileFirstIsPending() =
        runTest {
            val pending = CompletableDeferred<LinkResult>()
            var calls = 0
            val vm =
                LoginViewModel(MutableStateFlow(true), gate.active) {
                    this@LoginViewModelTest.gate.run(LinkResult.Cancelled) {
                        calls++
                        pending.await()
                    }
                }
            vm.signIn(context)
            assertThat(vm.state.value.busy).isTrue()
            vm.signIn(context)
            assertThat(calls).isEqualTo(1)
            pending.complete(LinkResult.Cancelled)
            assertThat(vm.state.value.busy).isFalse()
            vm.signIn(context)
            assertThat(calls).isEqualTo(2)
        }

    @Test
    fun recreatedViewModelCannotStartAnotherSignInWhilePending() =
        runTest {
            val gate = SingleFlight()
            val release = CompletableDeferred<LinkResult>()
            var calls = 0
            val signIn: suspend (android.content.Context) -> LinkResult = {
                gate.run(LinkResult.Cancelled) {
                    calls++
                    release.await()
                }
            }
            LoginViewModel(MutableStateFlow(true), gate.active, signIn).signIn(context)
            val recreated = LoginViewModel(MutableStateFlow(true), gate.active, signIn)
            assertThat(recreated.state.value.busy).isTrue()
            recreated.signIn(context)
            assertThat(calls).isEqualTo(1)
            release.complete(LinkResult.Cancelled)
            assertThat(recreated.state.value.busy).isFalse()
            recreated.signIn(context)
            assertThat(calls).isEqualTo(2)
        }
}
