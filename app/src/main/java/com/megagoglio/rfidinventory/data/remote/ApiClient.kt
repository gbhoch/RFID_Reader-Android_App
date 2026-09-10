package com.megagoglio.rfidinventory.data.remote

import android.util.Log
import com.megagoglio.rfidinventory.BuildConfig
import com.megagoglio.rfidinventory.data.auth.TokenStore
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP do sistema de gestão.
 *
 * São DOIS clientes de propósito:
 *  - [authApi] roda num OkHttp "cru", sem Bearer e sem Authenticator. É o que
 *    quebra a recursão: renovar o token não pode passar pelo mesmo caminho que
 *    dispara a renovação.
 *  - [inventoryApi] leva o Bearer e renova sozinho em 401.
 *
 * [onSessionExpired] é chamado quando o refresh é recusado pelo servidor (token
 * expirado ou revogado) — nunca por falha de rede, em que a sessão continua
 * válida e só falta conectividade.
 */
class ApiClient(
    baseUrl: String = BuildConfig.API_BASE_URL,
    private val tokens: TokenStore,
    private val onSessionExpired: () -> Unit = {},
) {

    private fun logging(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().setLevel(
            // NUNCA BODY em release: o corpo do login carrega a senha do operador.
            if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
            else HttpLoggingInterceptor.Level.BASIC
        )

    /** Cliente sem autenticação, usado só pelas rotas de auth. */
    private val bareClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(logging())
        .build()

    val authApi: AuthApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(bareClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AuthApi::class.java)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(BearerInterceptor(tokens))
        .authenticator(TokenAuthenticator(tokens, authApi, onSessionExpired))
        .addInterceptor(logging())
        .build()

    val inventoryApi: InventoryApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(InventoryApi::class.java)
}

/** Injeta `Authorization: Bearer` em toda requisição que tenha token. */
private class BearerInterceptor(private val tokens: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokens.accessToken ?: return chain.proceed(chain.request())
        return chain.proceed(chain.request().withToken(token))
    }
}

/**
 * Renova o access token quando o servidor responde 401.
 *
 * Serializado num lock porque o SyncWorker e a UI podem tomar 401 ao mesmo tempo,
 * e o refresh do backend é ROTACIONADO (`auth.service.ts` revoga o token usado a
 * cada renovação): dois refreshes em paralelo fariam o segundo apresentar um
 * token já revogado e derrubariam a sessão inteira. Quem chega depois do lock
 * encontra o token já renovado e só repete a requisição.
 */
private class TokenAuthenticator(
    private val tokens: TokenStore,
    private val authApi: AuthApi,
    private val onSessionExpired: () -> Unit,
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Já tentamos renovar uma vez para esta requisição: desistir evita laço.
        if (response.priorResponse != null) return null

        val tokenUsedInRequest = response.request.header(HEADER)?.removePrefix(PREFIX)

        synchronized(lock) {
            val current = tokens.accessToken
            // Outra thread renovou enquanto esperávamos o lock.
            if (current != null && current != tokenUsedInRequest) {
                return response.request.withToken(current)
            }

            val refresh = tokens.refreshToken ?: return null
            val attempt = runCatching { authApi.refresh(RefreshRequest(refresh)).execute() }

            val refreshResponse = attempt.getOrElse { error ->
                // Falha de rede: a sessão continua válida, só falta conectividade.
                // Não limpar os tokens — o SyncWorker tentará de novo depois.
                Log.w(TAG, "Falha de rede ao renovar o token", error)
                return null
            }

            val pair = refreshResponse.body()
            if (!refreshResponse.isSuccessful || pair == null) {
                Log.w(TAG, "Refresh recusado (HTTP ${refreshResponse.code()}) — encerrando sessão")
                tokens.clear()
                onSessionExpired()
                return null
            }

            tokens.save(pair.accessToken, pair.refreshToken)
            return response.request.withToken(pair.accessToken)
        }
    }

    private companion object {
        const val TAG = "TokenAuthenticator"
    }
}

private const val HEADER = "Authorization"
private const val PREFIX = "Bearer "

private fun Request.withToken(token: String): Request =
    newBuilder().header(HEADER, PREFIX + token).build()
