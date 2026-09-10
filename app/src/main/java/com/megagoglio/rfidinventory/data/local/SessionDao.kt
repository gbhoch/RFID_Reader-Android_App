package com.megagoglio.rfidinventory.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insertSession(session: SessionEntity)

    @Insert
    suspend fun insertTags(tags: List<TagReadEntity>)

    /** Grava a sessão e suas tags de uma vez: ou tudo entra, ou nada entra. */
    @Transaction
    suspend fun saveSession(session: SessionEntity, tags: List<TagReadEntity>) {
        insertSession(session)
        insertTags(tags)
    }

    @Transaction
    @Query("SELECT * FROM sessions WHERE status = :status ORDER BY finishedAt ASC")
    suspend fun sessionsWithStatus(status: SessionStatus): List<SessionWithTags>

    @Query("SELECT COUNT(*) FROM sessions WHERE status = :status")
    fun countWithStatus(status: SessionStatus): Flow<Int>

    /** Sessões que ainda não chegaram ao servidor: alimenta o aviso da barra superior. */
    @Query("SELECT COUNT(*) FROM sessions WHERE status IN (:statuses)")
    fun countWithStatusIn(statuses: List<SessionStatus>): Flow<Int>

    /**
     * Impede concluir um setor cuja coleta ainda não subiu.
     *
     * Sem valor default no parâmetro: o Room gera a implementação do método e os
     * argumentos default do Kotlin criam uma ponte sintética que ele não cobre.
     */
    @Query("SELECT COUNT(*) FROM sessions WHERE sectorId = :sectorId AND status != :sent")
    fun countUnsentForSector(sectorId: String, sent: SessionStatus): Flow<Int>

    @Query("UPDATE sessions SET status = :status, attemptCount = attemptCount + 1, lastError = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: SessionStatus, error: String?)

    /**
     * Devolve as sessões travadas à fila. Usado quando o gestor reabre o inventário:
     * o app não tem como saber disso sozinho, então é o operador que pede a retentativa.
     */
    @Query("UPDATE sessions SET status = :to WHERE status = :from")
    suspend fun requeue(from: SessionStatus, to: SessionStatus)

    @Query("SELECT * FROM sessions ORDER BY finishedAt DESC LIMIT 50")
    fun recentSessions(): Flow<List<SessionEntity>>

    // ---- Cache de setores ----

    @Query("SELECT * FROM sectors ORDER BY name ASC")
    fun sectors(): Flow<List<SectorEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSectors(sectors: List<SectorEntity>)

    @Query("DELETE FROM sectors WHERE id NOT IN (:keep)")
    suspend fun deleteSectorsMissingFrom(keep: List<String>)

    /** Espelha a lista do servidor: insere/atualiza e remove o que sumiu do cadastro. */
    @Transaction
    suspend fun replaceSectors(sectors: List<SectorEntity>) {
        if (sectors.isEmpty()) return
        upsertSectors(sectors)
        deleteSectorsMissingFrom(sectors.map { it.id })
    }
}
