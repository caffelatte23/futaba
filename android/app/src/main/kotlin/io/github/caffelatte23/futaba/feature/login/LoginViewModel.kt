package io.github.caffelatte23.futaba.feature.login

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.caffelatte23.futaba.core.data.AuthRepository
import io.github.caffelatte23.futaba.core.data.LinkResult
import io.github.caffelatte23.futaba.core.data.onlineFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginState(
    val busy: Boolean = false,
    val message: String? = null,
    val offline: Boolean = false,
)

/**
 * [signingIn] is owned by the singleton repository, so a recreated ViewModel still sees an
 * earlier sign-in that hasn't finished.
 */
@HiltViewModel
class LoginViewModel(
    online: Flow<Boolean>,
    private val signingIn: StateFlow<Boolean>,
    private val signInWithGoogle: suspend (Context) -> LinkResult,
) : ViewModel() {
    @Inject
    constructor(
        auth: AuthRepository,
        @ApplicationContext context: Context,
    ) :
        this(onlineFlow(context), auth.signingIn, auth::signInWithGoogle)

    private val message = MutableStateFlow<String?>(null)
    private val onlineNow = online.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val state: StateFlow<LoginState> =
        combine(signingIn, message, onlineNow) { busy, msg, on -> LoginState(busy, msg, !on) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, LoginState())

    fun signIn(context: Context) {
        // Offline: the screen already shows the notice; don't start the account picker.
        if (signingIn.value || !onlineNow.value) return
        message.value = null
        viewModelScope.launch {
            val result = signInWithGoogle(context)
            message.value = (result as? LinkResult.Failed)?.message
        }
    }
}
