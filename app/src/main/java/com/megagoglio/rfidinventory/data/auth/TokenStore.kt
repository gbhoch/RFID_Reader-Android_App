package com.megagoglio.rfidinventory.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Guarda os tokens da sessão do operador.
 *
 * Usa [EncryptedSharedPreferences] porque o refresh token vale 7 dias e o coletor
 * é um aparelho compartilhado de galpão — daqueles que se perdem. Se a camada de
 * criptografia falhar (keystore corrompido depois de restore de backup, um caso
 * real em aparelhos baratos), cai para preferências comuns em vez de derrubar o
 * app: perder a sessão é recuperável, não abrir o app não é.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "rfid-credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ) as SharedPreferences
    }.getOrElse { error ->
        Log.e(TAG, "Falha ao abrir o armazenamento cifrado; usando preferências simples", error)
        context.getSharedPreferences("rfid-credentials-fallback", Context.MODE_PRIVATE)
    }

    @Volatile
    var accessToken: String? = prefs.getString(KEY_ACCESS, null)
        private set

    @Volatile
    var refreshToken: String? = prefs.getString(KEY_REFRESH, null)
        private set

    /** Login do operador autenticado, para exibir na tela. */
    var operatorLogin: String?
        get() = prefs.getString(KEY_LOGIN, null)
        private set(value) { prefs.edit().putString(KEY_LOGIN, value).apply() }

    val isLoggedIn: Boolean get() = refreshToken != null

    fun save(access: String, refresh: String, login: String? = null) {
        accessToken = access
        refreshToken = refresh
        prefs.edit().apply {
            putString(KEY_ACCESS, access)
            putString(KEY_REFRESH, refresh)
            login?.let { putString(KEY_LOGIN, it) }
        }.apply()
    }

    fun clear() {
        accessToken = null
        refreshToken = null
        prefs.edit().clear().apply()
    }

    private companion object {
        const val TAG = "TokenStore"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_LOGIN = "operator_login"
    }
}
