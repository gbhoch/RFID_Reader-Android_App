package com.megagoglio.rfidinventory.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.megagoglio.rfidinventory.RfidApp

/**
 * Envia as sessões pendentes para a API.
 *
 * Roda com a restrição NetworkType.CONNECTED e backoff exponencial: é o que permite o
 * operador inventariar em área sem sinal e ter o envio acontecendo sozinho quando a rede
 * voltar.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = (applicationContext as RfidApp).syncRepository

        return runCatching { repository.syncPending() }
            .fold(
                onSuccess = { allResolved ->
                    // retry() reagenda com o backoff exponencial configurado no request.
                    if (allResolved) Result.success() else Result.retry()
                },
                onFailure = { error ->
                    Log.e(TAG, "Erro inesperado na sincronização", error)
                    Result.retry()
                },
            )
    }

    companion object {
        const val WORK_NAME = "sync-inventory-sessions"
        private const val TAG = "SyncWorker"
    }
}
