package com.megagoglio.rfidinventory.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Contrato com a API do sistema de gestão patrimonial (NestJS, `api/v1`).
 *
 * O app é CLIENTE do inventário do backend — não dono do modelo. A hierarquia é
 * `Inventory -> InventorySectorVisit -> InventoryRead`, e é a visita de setor que
 * dá sentido à conciliação: sem saber ONDE a tag foi lida, o servidor não
 * consegue distinguir "sumiu" de "está em outro setor".
 *
 * Dois cuidados que quebram a integração se forem esquecidos:
 *
 * 1. **Os nomes dos campos são os do backend.** O `ValidationPipe` global roda
 *    com `forbidNonWhitelisted`: qualquer campo a mais no corpo devolve 400.
 *
 * 2. **[AddReadsRequest.clientBatchId] é a chave de idempotência.** O
 *    SyncWorker reenvia o mesmo lote quando a resposta se perde; o servidor
 *    ignora a repetição por um índice único (client_batch_id, epc). Mudar esse
 *    id a cada tentativa traria de volta o inventário duplicado.
 */
interface InventoryApi {

    /** Inventário aberto no momento. Corpo vazio quando não há nenhum. */
    @GET("api/v1/inventory/current")
    suspend fun currentInventory(): Response<InventoryDto>

    /** Setores para o operador escolher. Cacheado no Room para uso offline. */
    @GET("api/v1/sectors")
    suspend fun sectors(
        @Query("skip") skip: Int = 0,
        @Query("take") take: Int = 200,
    ): Response<PageDto<SectorDto>>

    /**
     * Abre (ou retoma) a visita ao setor. É IDEMPOTENTE no servidor: devolve a
     * visita existente e reabre a que estava concluída. É por isso que o app
     * guarda `sectorId` na sessão e resolve o `sectorVisitId` só na hora de
     * enviar — o operador escolhe o setor sem rede.
     */
    @POST("api/v1/inventory/{id}/sectors")
    suspend fun selectSector(
        @Path("id") inventoryId: String,
        @Body body: SelectSectorRequest,
    ): Response<SectorVisitDto>

    @POST("api/v1/inventory/{id}/reads")
    suspend fun addReads(
        @Path("id") inventoryId: String,
        @Body body: AddReadsRequest,
    ): Response<AddReadsResultDto>

    @PATCH("api/v1/inventory/{id}/sectors/{visitId}/complete")
    suspend fun completeSector(
        @Path("id") inventoryId: String,
        @Path("visitId") visitId: String,
    ): Response<SectorVisitDto>
}

// ------------------------------------------------------------------ requests

data class SelectSectorRequest(
    @SerializedName("sectorId") val sectorId: String,
)

data class AddReadsRequest(
    @SerializedName("sectorVisitId") val sectorVisitId: String,
    @SerializedName("clientBatchId") val clientBatchId: String,
    @SerializedName("reads") val reads: List<ReadItem>,
)

data class ReadItem(
    @SerializedName("epc") val epc: String,
    @SerializedName("tid") val tid: String?,
    @SerializedName("rssi") val rssi: Int?,
    /** Hits agregados no coletor: leitura sólida vs leitura de borda. */
    @SerializedName("readCount") val readCount: Int,
    @SerializedName("deviceId") val deviceId: String?,
    /** ISO-8601 com offset, ex.: 2026-08-24T14:03:12.123-03:00. */
    @SerializedName("readAt") val readAt: String,
)

// ----------------------------------------------------------------- responses

data class PageDto<T>(
    @SerializedName("data") val data: List<T>,
    @SerializedName("total") val total: Int,
)

data class InventoryDto(
    @SerializedName("id") val id: String,
    @SerializedName("code") val code: String,
    @SerializedName("status") val status: String,
    @SerializedName("description") val description: String?,
)

data class SectorDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("acronym") val acronym: String?,
)

data class SectorVisitDto(
    @SerializedName("id") val id: String,
    @SerializedName("inventoryId") val inventoryId: String,
    @SerializedName("sectorId") val sectorId: String,
    @SerializedName("status") val status: String,
)

data class AddReadsResultDto(
    @SerializedName("received") val received: Int,
    @SerializedName("inserted") val inserted: Int,
)
