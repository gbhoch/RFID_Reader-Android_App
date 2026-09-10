package com.megagoglio.rfidinventory.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.megagoglio.rfidinventory.RfidApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val login: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = !busy && login.isNotBlank() && password.isNotBlank()
}

/**
 * Login do operador. A leitura fica atribuída à PESSOA (o backend grava
 * `operator_id` a partir do JWT), não ao aparelho — é o que dá trilha de
 * auditoria de quem inventariou o quê.
 */
class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val app: RfidApp get() = getApplication()

    private val _state = MutableStateFlow(LoginUiState(login = app.auth.operatorLogin.orEmpty()))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onLoginChange(value: String) {
        _state.value = _state.value.copy(login = value, error = null)
    }

    fun onPasswordChange(value: String) {
        _state.value = _state.value.copy(password = value, error = null)
    }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        viewModelScope.launch {
            _state.value = current.copy(busy = true, error = null)
            app.auth.login(current.login, current.password)
                .onSuccess {
                    // A senha não fica em memória depois de autenticar.
                    _state.value = LoginUiState(login = current.login)
                }
                .onFailure { error ->
                    _state.value = current.copy(
                        busy = false,
                        password = "",
                        error = error.message ?: "Não foi possível entrar.",
                    )
                }
        }
    }
}
