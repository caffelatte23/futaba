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

private data class Operation(
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class LoginViewModel(
    online: Flow<Boolean>,
    private val signInWithGoogle: suspend (Context) -> LinkResult,
) : ViewModel() {
    @Inject
    constructor(
        auth: AuthRepository,
        @ApplicationContext context: Context,
    ) :
        this(onlineFlow(context), auth::signInWithGoogle)

    private val operation = MutableStateFlow(Operation())
    private val onlineNow = online.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val state: StateFlow<LoginState> =
        combine(operation, onlineNow) { op, on -> LoginState(op.busy, op.message, !on) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, LoginState())

    fun signIn(context: Context) {
        // Offline: the screen already shows the notice; don't start the account picker.
        if (operation.value.busy || !onlineNow.value) return
        operation.value = Operation(busy = true)
        viewModelScope.launch {
            val result = signInWithGoogle(context)
            operation.value = Operation(message = (result as? LinkResult.Failed)?.message)
        }
    }
}
