package com.megagoglio.rfidinventory.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Autenticação contra o sistema de gestão (NestJS).
 *
 * Contrato em `modules/auth/presentation/auth.controller.ts`. O access token dura
 * 15 min e o refresh 7 dias, com ROTAÇÃO: cada refresh revoga o token usado. Por
 * isso a renovação precisa ser serializada — ver [ApiClient.TokenAuthenticator].
 */
interface AuthApi {

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<TokenPair>

    /** Chamado do Authenticator (thread de rede do OkHttp), por isso é síncrono. */
    @POST("api/v1/auth/refresh")
    fun refresh(@Body body: RefreshRequest): retrofit2.Call<TokenPair>

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: RefreshRequest): Response<Unit>
}

data class LoginRequest(
    @SerializedName("login") val login: String,
    @SerializedName("password") val password: String,
)

data class RefreshRequest(
    @SerializedName("refreshToken") val refreshToken: String,
)

data class TokenPair(
    @SerializedName("accessToken") val accessToken: String,
    @SerializedName("refreshToken") val refreshToken: String,
)
