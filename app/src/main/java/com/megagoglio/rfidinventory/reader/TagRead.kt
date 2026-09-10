package com.megagoglio.rfidinventory.reader

/**
 * Uma tag vista pelo leitor.
 *
 * Emitida uma vez por leitura fisica: a mesma tag gera varios [TagRead] durante um scan.
 * A deduplicacao por EPC acontece na camada de cima (ver InventoryViewModel).
 */
data class TagRead(
    val epc: String,
    val tid: String?,
    val rssi: Int,
    /** Protocol Control word, quando solicitado ao leitor. */
    val pc: Int,
    val crc: Int,
    val seenAt: Long,
    /** Preenchido quando o leitor reporta erro de acesso ou de backscatter para esta tag. */
    val error: String?,
)

/** Uma tag consolidada dentro da sessao atual: varias leituras colapsadas em uma linha. */
data class TagAggregate(
    val epc: String,
    val tid: String?,
    val rssi: Int,
    val readCount: Int,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

/** Colapsa uma nova leitura sobre o agregado existente (ou cria o primeiro). */
fun TagAggregate?.merge(read: TagRead): TagAggregate =
    if (this == null) {
        TagAggregate(
            epc = read.epc,
            tid = read.tid,
            rssi = read.rssi,
            readCount = 1,
            firstSeenAt = read.seenAt,
            lastSeenAt = read.seenAt,
        )
    } else {
        copy(
            // O TID so vem em algumas leituras; nao perder o valor ja capturado.
            tid = read.tid ?: tid,
            rssi = read.rssi,
            readCount = readCount + 1,
            lastSeenAt = read.seenAt,
        )
    }
