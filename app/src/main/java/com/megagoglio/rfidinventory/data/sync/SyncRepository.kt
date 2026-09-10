package com.megagoglio.rfidinventory.data.sync

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.megagoglio.rfidinventory.data.local.SessionDao
import com.megagoglio.rfidinventory.data.local.SessionEntity
import com.megagoglio.rfidinventory.data.local.SessionStatus
import com.megagoglio.rfidinventory.data.local.TagReadEntity
import com.megagoglio.rfidinventory.data.remote.AddReadsRequest
import com.megagoglio.rfidinventory.data.remote.InventoryApi
import com.megagoglio.rfidinventory.data.remote.ReadItem
import com.megagoglio.rfidinventory.data.remote.SelectSectorRequest
import com.megagoglio.rfidinventory.reader.TagAggregate
import kotlinx.coroutines.flow.Flow
import retrofit2.Response
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Fila de coletas e envio para a API.
 *
 * Regra central: a coleta SEMPRE é gravada no Room primeiro e só depois sobe para
 * a API. Enviar direto do callback do leitor perderia a leitura quando a rede
 * caísse — e inventário em galpão acontece justamente onde não há sinal.
 *
 * O envio tem dois passos, nesta ordem:
 *  1. selectSector resolve (ou reabre) a visita de setor. É idempotente no
 *     servidor, e é o que permite ao operador escolher o setor offline: o app
 *     guarda sectorId e descobre o sectorVisitId só aqui.
 *  2. addReads envia as leituras com clientBatchId = session.id, que o servidor
 *     usa como chave de idempotência.
 */
class SyncRepository(
    private val context: Context,
    private val dao: SessionDao,
    private val api: InventoryApi,
) {

    /** Tudo que ainda não chegou ao servidor — inclui o que está travado esperando revisão. */
    val pendingCount: Flow<Int> =
        dao.countWithStatusIn(listOf(SessionStatus.PENDENTE, SessionStatus.BLOQUEADA))

    val blockedCount: Flow<Int> = dao.countWithStatus(SessionStatus.BLOQUEADA)

    val recentSessions: Flow<List<SessionEntity>> = dao.recentSessions()

    /** Coletas daquele setor que ainda não subiram — trava o "Concluir setor". */
    fun unsentCountForSector(sectorId: String): Flow<Int> =
        dao.countUnsentForSector(sectorId, SessionStatus.ENVIADA)

    /** Grava a coleta como PENDENTE e agenda o envio. Devolve o id gerado. */
    suspend fun finishSession(
        tags: Collection<TagAggregate>,
        readerSerial: String?,
        startedAt: Long,
        inventoryId: String,
        inventoryCode: String,
        sectorId: String,
        sectorName: String,
    ): String {
        val sessionId = UUID.randomUUID().toString()
        val session = SessionEntity(
            id = sessionId,
            inventoryId = inventoryId,
            inventoryCode = inventoryCode,
            sectorId = sectorId,
            sectorName = sectorName,
            readerSerial = readerSerial,
            deviceId = deviceId(),
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            status = SessionStatus.PENDENTE,
        )
        val rows = tags.map { tag ->
            TagReadEntity(
                sessionId = sessionId,
                epc = tag.epc,
                tid = tag.tid,
                rssi = tag.rssi,
                readCount = tag.readCount,
                firstSeenAt = tag.firstSeenAt,
                lastSeenAt = tag.lastSeenAt,
            )
        }
        dao.saveSession(session, rows)
        scheduleSync()
        return sessionId
    }

    /** Agenda (ou reaproveita) o worker de envio. */
    fun scheduleSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            SyncWorker.WORK_NAME,
            // KEEP: se já há um envio agendado ou rodando, ele vai pegar as novas sessões
            // pendentes de qualquer forma — não adianta empilhar workers.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Devolve à fila as coletas travadas em BLOQUEADA. O app não tem como saber que
     * o gestor reabriu o inventário; quem pede a retentativa é o operador, pela
     * tela da fila.
     */
    suspend fun retryBlocked() {
        dao.requeue(SessionStatus.BLOQUEADA, SessionStatus.PENDENTE)
        scheduleSync()
    }

    /**
     * Envia todas as coletas pendentes.
     * @return true se todas foram resolvidas (enviadas, travadas ou rejeitadas em definitivo).
     */
    suspend fun syncPending(): Boolean {
        val pending = dao.sessionsWithStatus(SessionStatus.PENDENTE)
        if (pending.isEmpty()) return true

        var allResolved = true

        for (item in pending) {
            val session = item.session

            val outcome = runCatching {
                // 1) Visita de setor (idempotente: devolve a existente, reabre a concluída).
                val visitResponse = api.selectSector(
                    session.inventoryId,
                    SelectSectorRequest(session.sectorId),
                )
                val visit = visitResponse.body()
                if (!visitResponse.isSuccessful || visit == null) {
                    return@runCatching classify(visitResponse, "selecionar setor")
                }

                // 2) Leituras, com o id da coleta como chave de idempotência.
                val readsResponse = api.addReads(
                    session.inventoryId,
                    AddReadsRequest(
                        sectorVisitId = visit.id,
                        clientBatchId = session.id,
                        reads = item.tags.map { tag ->
                            ReadItem(
                                epc = tag.epc,
                                tid = tag.tid,
                                rssi = tag.rssi,
                                readCount = tag.readCount,
                                deviceId = session.deviceId,
                                readAt = tag.lastSeenAt.toIso8601(),
                            )
                        },
                    ),
                )
                classify(readsResponse, "enviar leituras")
            }.getOrElse { error ->
                // Sem rede, DNS, timeout: continua pendente.
                Log.w(TAG, "Falha de rede ao enviar ${session.id}", error)
                Outcome.Retry(error.message)
            }

            when (outcome) {
                is Outcome.Sent ->
                    dao.updateStatus(session.id, SessionStatus.ENVIADA, null)

                is Outcome.Blocked -> {
                    Log.w(TAG, "Coleta ${session.id} travada — ${outcome.reason}")
                    dao.updateStatus(session.id, SessionStatus.BLOQUEADA, outcome.reason)
                }

                is Outcome.Rejected -> {
                    Log.e(TAG, "Coleta ${session.id} rejeitada — ${outcome.reason}")
                    dao.updateStatus(session.id, SessionStatus.ERRO, outcome.reason)
                }

                is Outcome.Retry -> {
                    allResolved = false
                    dao.updateStatus(session.id, SessionStatus.PENDENTE, outcome.reason)
                }
            }
        }

        return allResolved
    }

    /** Desfecho de uma tentativa de envio. */
    private sealed interface Outcome {
        object Sent : Outcome

        /** Erro transitório: tentar de novo depois. */
        data class Retry(val reason: String?) : Outcome

        /** Inventário encerrado: guardar até o gestor reabrir. */
        data class Blocked(val reason: String) : Outcome

        /** Rejeição definitiva: reenviar não muda nada. */
        data class Rejected(val reason: String) : Outcome
    }

    private fun <T> classify(response: Response<T>, step: String): Outcome {
        if (response.isSuccessful) return Outcome.Sent

        val code = response.code()
        val body = runCatching { response.errorBody()?.string() }.getOrNull().orEmpty()
        val detail = "$step — HTTP $code${if (body.isBlank()) "" else ": $body"}"

        return when {
            // 409: o inventário foi encerrado antes desta coleta chegar. A leitura é
            // boa, só chegou tarde — o gestor pode reabrir o inventário e ela sobe.
            code == 409 -> Outcome.Blocked(detail)

            // 401: o Authenticator já tentou renovar o token e não conseguiu. Falta
            // login — não é rejeição da coleta. Descartar aqui apagaria o trabalho do
            // dia porque o refresh expirou de madrugada.
            code == 401 -> Outcome.Retry(detail)

            // Demais 4xx são rejeição definitiva: reenviar não muda nada e manteria o
            // worker girando para sempre. 408/429 são exceção (tempo/limite de taxa).
            code in 400..499 && code !in setOf(408, 429) -> Outcome.Rejected(detail)

            // 5xx e afins: erro transitório.
            else -> Outcome.Retry(detail)
        }
    }

    @Suppress("HardwareIds")
    private fun deviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "desconhecido"

    private companion object {
        const val TAG = "SyncRepository"
    }
}

/**
 * Epoch em millis para ISO-8601 com offset, ex.: 2026-08-21T14:03:12-03:00.
 * Usa java.time (disponível a partir da API 26) em vez de SimpleDateFormat, que não é
 * thread-safe e seria compartilhado entre o worker e a UI.
 */
internal fun Long.toIso8601(): String =
    Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
