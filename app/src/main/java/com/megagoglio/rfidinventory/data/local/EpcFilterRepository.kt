package com.megagoglio.rfidinventory.data.local

import com.megagoglio.rfidinventory.reader.EpcFilterSpec
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Camada fina sobre [EpcFilterDao]: filtros de EPC salvos pelo usuário para reuso. */
class EpcFilterRepository(private val dao: EpcFilterDao) {

    fun list(): Flow<List<EpcFilterEntity>> = dao.all()

    suspend fun save(name: String, spec: EpcFilterSpec, id: String = UUID.randomUUID().toString()) {
        dao.upsert(
            EpcFilterEntity(
                id = id,
                name = name,
                startChar = spec.startChar,
                lengthChar = spec.lengthChar,
                valueHex = spec.valueHex,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun delete(id: String) = dao.delete(id)
}

fun EpcFilterEntity.toSpec(): EpcFilterSpec = EpcFilterSpec(startChar, lengthChar, valueHex)
