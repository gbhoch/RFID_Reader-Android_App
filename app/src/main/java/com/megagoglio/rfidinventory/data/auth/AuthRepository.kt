package com.megagoglio.rfidinventory.data.auth

import com.megagoglio.rfidinventory.data.remote.AuthApi
import com.megagoglio.rfidinventory.data.remote.LoginRequest
import com.megagoglio.rfidinventory.data.remote.RefreshRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sessão do operador.
 *
 * A leitura acontece amarrada a uma PESSOA, não ao aparelho: o backend grava
 * `operator_id` a partir do JWT, e é isso que dá trilha de auditoria de quem
 * inventariou o quê. Por isso o login é de operador e não uma chave por dispositivo.
 */
class AuthRepository(
    private val api: AuthApi,
    private val tokens: TokenStore,
) {

    private val _isLoggedIn = MutableStateFlow(tokens.isLoggedIn)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    val operatorLogin: String? get() = tokens.operatorLogin

    suspend fun login(login: String, password: String): Result<Unit> = runCatching {
        val response = api.login(LoginRequest(login.trim(), password))
        val pair = response.body()

        if (!response.isSuccessful || pair == null) {
            val message = when (response.code()) {
                401 -> "Login ou senha inválidos."
                403 -> "Usuário inativo ou bloqueado. Procure o administrador."
                else -> "Falha ao entrar (HTTP ${response.code()})."
            }
            throw IllegalStateException(message)
        }

        tokens.save(pair.accessToken, pair.refreshToken, login.trim())
        _isLoggedIn.value = true
    }

    /**
     * Encerra a sessão. Revoga o refresh token no servidor quando dá — mas a
     * limpeza local acontece de qualquer jeito: sair do app não pode depender de
     * ter rede, ou o aparelho ficaria logado no galpão.
     */
    suspend fun logout() {
        val refresh = tokens.refreshToken
        if (refresh != null) {
            runCatching { api.logout(RefreshRequest(refresh)) }
        }
        onSessionEnded()
    }

    /** Chamado também pelo Authenticator quando o refresh é recusado. */
    fun onSessionEnded() {
        tokens.clear()
        _isLoggedIn.value = false
    }
}
