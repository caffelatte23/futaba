package io.github.caffelatte23.futaba.feature.login

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.caffelatte23.futaba.core.data.AuthRepository
import io.github.caffelatte23.futaba.core.data.LinkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginState(
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class LoginViewModel(
    val signedIn: StateFlow<Boolean>,
    private val signInWithGoogle: suspend (Context) -> LinkResult,
) : ViewModel() {
    @Inject
    constructor(auth: AuthRepository) : this(auth.signedIn, auth::signInWithGoogle)

    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state

    fun signIn(context: Context) {
        if (_state.value.busy) return
        _state.value = LoginState(busy = true)
        viewModelScope.launch {
            val result = signInWithGoogle(context)
            _state.value = LoginState(message = (result as? LinkResult.Failed)?.message)
        }
    }
}
