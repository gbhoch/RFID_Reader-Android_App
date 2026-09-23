package com.megagoglio.rfidinventory.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

enum class SessionStatus {
    /** Gravada localmente, ainda não aceita pela API. */
    PENDENTE,

    /** Confirmada pela API. */
    ENVIADA,

    /**
     * A API rejeitou de forma definitiva (4xx). Não adianta reenviar sem intervenção.
     * Ex.: setor apagado do cadastro, payload inválido.
     */
    ERRO,

    /**
     * O inventário já foi encerrado quando esta coleta chegou (HTTP 409).
     * NÃO é erro do operador nem dado inválido: a leitura é boa, só chegou tarde.
     * Fica guardada até o gestor reabrir o inventário (`PATCH /inventory/:id/reopen`),
     * e então sobe sozinha no próximo sync. Descartar aqui perderia trabalho de campo.
     */
    BLOQUEADA,
}

/**
 * Uma sessão de leitura: do momento em que o operador começa a inventariar um setor
 * até tocar em "Finalizar leitura".
 *
 * Deixou de ser entidade de negócio — quem manda no modelo é o backend
 * (`Inventory -> InventorySectorVisit -> InventoryRead`). Aqui ela é a unidade da
 * FILA OFFLINE, e o [id] é a chave de idempotência (`clientBatchId`) que permite
 * reenviar o mesmo lote sem duplicar leitura no servidor.
 *
 * Guarda [sectorId] e NÃO o id da visita: a visita é resolvida no envio, chamando
 * `selectSector` (que é idempotente no servidor). É isso que deixa o operador
 * escolher setor sem rede, a partir do cache local.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val inventoryId: String,
    val inventoryCode: String,
    val sectorId: String,
    /** Denormalizado para a fila continuar legível offline. */
    val sectorName: String,
    val readerSerial: String?,
    val deviceId: String,
    val startedAt: Long,
    val finishedAt: Long,
    val status: SessionStatus,
    val attemptCount: Int = 0,
    val lastError: String? = null,
)

@Entity(
    tableName = "tag_reads",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("sessionId")],
)
data class TagReadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val epc: String,
    val tid: String?,
    val rssi: Int,
    val readCount: Int,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

/** Uma sessão com suas tags, como o worker precisa para montar o payload. */
data class SessionWithTags(
    @Embedded val session: SessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val tags: List<TagReadEntity>,
)

/**
 * Cache dos setores do sistema.
 *
 * Existe para o operador conseguir trocar de setor dentro do galpão, onde
 * tipicamente não há rede — inventário acontece justamente onde o sinal não chega.
 */
@Entity(tableName = "sectors")
data class SectorEntity(
    @PrimaryKey val id: String,
    val name: String,
    val acronym: String?,
)

/**
 * Um filtro de EPC salvo pelo usuário para reuso (ex.: "Setor TI", "Notebooks").
 *
 * [startChar]/[lengthChar]/[valueHex] espelham [com.megagoglio.rfidinventory.reader.EpcFilterSpec]
 * — a conversão para bits (unidade que o SDK da TSL realmente usa) acontece só na
 * hora de aplicar o filtro no leitor, não na persistência.
 */
@Entity(tableName = "epc_filters")
data class EpcFilterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val startChar: Int,
    val lengthChar: Int,
    val valueHex: String,
    /** Banco de memória do select. Hoje sempre o banco EPC; guardado para o dia em que outro banco fizer sentido. */
    val bank: String = "EPC",
    val createdAt: Long,
)
