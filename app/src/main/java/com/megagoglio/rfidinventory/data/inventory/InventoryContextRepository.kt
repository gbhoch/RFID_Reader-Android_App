package com.megagoglio.rfidinventory.data.inventory

import android.content.Context
import android.util.Log
import com.megagoglio.rfidinventory.data.local.SectorEntity
import com.megagoglio.rfidinventory.data.local.SessionDao
import com.megagoglio.rfidinventory.data.remote.InventoryApi
import com.megagoglio.rfidinventory.data.remote.InventoryDto
import com.megagoglio.rfidinventory.data.remote.SectorDto
import com.megagoglio.rfidinventory.data.remote.SelectSectorRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Setor onde o operador está inventariando agora. */
data class ActiveSector(val id: String, val name: String)

/**
 * Contexto da coleta: qual inventário está aberto no servidor e em que setor o
 * operador está.
 *
 * O inventário e a lista de setores vêm da API, mas ficam em cache: o operador
 * entra no galpão com o contexto já carregado e continua trocando de setor sem
 * rede. O setor escolhido sobrevive ao fechamento do app — reabrir o app no meio
 * de um corredor não pode significar recomeçar.
 */
class InventoryContextRepository(
    context: Context,
    private val api: InventoryApi,
    private val dao: SessionDao,
) {

    private val prefs = context.getSharedPreferences("rfid-context", Context.MODE_PRIVATE)

    private val _inventory = MutableStateFlow(cachedInventory())
    val inventory: StateFlow<InventoryDto?> = _inventory.asStateFlow()

    private val _sector = MutableStateFlow(cachedSector())
    val sector: StateFlow<ActiveSector?> = _sector.asStateFlow()

    /** Setores para o seletor. Vem do Room, então funciona offline. */
    val sectors: Flow<List<SectorEntity>> = dao.sectors()

    private val _sectorsLoading = MutableStateFlow(false)

    /** Busca de setores no servidor em andamento. */
    val sectorsLoading: StateFlow<Boolean> = _sectorsLoading.asStateFlow()

    private val _sectorsFromCache = MutableStateFlow(false)

    /**
     * A última tentativa de buscar setores falhou: o que está na tela é o cache do
     * aparelho, possivelmente desatualizado.
     */
    val sectorsFromCache: StateFlow<Boolean> = _sectorsFromCache.asStateFlow()

    /** Evita duas buscas paginadas simultâneas (onResume + abrir o seletor). */
    private val sectorsMutex = Mutex()

    /**
     * Recarrega inventário e setores do servidor. Falha em silêncio quando não há
     * rede: o cache continua valendo, que é o ponto de existir cache.
     *
     * As duas buscas são INDEPENDENTES: antes, qualquer falha ao ler o inventário
     * (inclusive "nenhum inventário aberto") abortava a atualização dos setores.
     *
     * @return null em caso de sucesso, ou a mensagem do erro.
     */
    suspend fun refresh(): String? {
        val inventoryError = refreshInventory()
        val sectorsError = refreshSectors()
        return inventoryError ?: sectorsError
    }

    private suspend fun refreshInventory(): String? = try {
        val response = api.currentInventory()
        if (response.isSuccessful) {
            // Corpo vazio = nenhum inventário aberto no momento.
            setInventory(response.body())
            null
        } else {
            "Não foi possível ler o inventário (HTTP ${response.code()})"
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Falha ao atualizar o inventário; seguindo com o cache", e)
        errorMessage(e)
    }

    /**
     * Baixa TODOS os setores do servidor (percorrendo as páginas) e substitui o
     * cache por completo. Sem rede, o cache fica como está e [sectorsFromCache]
     * passa a true.
     *
     * @return null em caso de sucesso, ou a mensagem do erro.
     */
    suspend fun refreshSectors(): String? = sectorsMutex.withLock {
        _sectorsLoading.value = true
        try {
            val all = fetchAllSectors()
            dao.replaceSectors(all.map { SectorEntity(it.id, it.name, it.acronym) })
            _sectorsFromCache.value = false
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao atualizar os setores; seguindo com o cache", e)
            _sectorsFromCache.value = true
            errorMessage(e)
        } finally {
            _sectorsLoading.value = false
        }
    }

    /**
     * Percorre `GET sectors` até cobrir `total`. Deduplica por id: o servidor ordena
     * por data de criação, e um setor criado durante a paginação desloca as páginas.
     */
    private suspend fun fetchAllSectors(): List<SectorDto> {
        val byId = LinkedHashMap<String, SectorDto>()
        var skip = 0
        while (true) {
            val response = api.sectors(skip = skip, take = SECTOR_PAGE_SIZE)
            val page = response.body()
            if (!response.isSuccessful || page == null) {
                throw IllegalStateException("Não foi possível ler os setores (HTTP ${response.code()})")
            }
            page.data.forEach { byId[it.id] = it }
            skip += page.data.size
            if (page.data.isEmpty() || skip >= page.total) break
        }
        return byId.values.toList()
    }

    private fun errorMessage(e: Exception): String =
        if (e is IOException) OFFLINE_MESSAGE else e.message ?: OFFLINE_MESSAGE

    /**
     * Marca o setor atual como concluído no servidor.
     *
     * Resolve a visita antes (selectSector é idempotente) porque o app guarda
     * sectorId, não o id da visita. Exige rede de propósito: concluir setor é
     * uma decisão deliberada do operador, e a UI já impede fazê-lo enquanto
     * houver coleta daquele setor sem subir.
     */
    suspend fun completeCurrentSector(): Result<Unit> = runCatching {
        val inv = _inventory.value ?: error("Nenhum inventário aberto.")
        val current = _sector.value ?: error("Nenhum setor selecionado.")

        val visitResponse = api.selectSector(inv.id, SelectSectorRequest(current.id))
        val visit = visitResponse.body()
            ?: error("Não foi possível abrir o setor no servidor (HTTP ${visitResponse.code()}).")

        val response = api.completeSector(inv.id, visit.id)
        if (!response.isSuccessful) {
            error("Não foi possível concluir o setor (HTTP ${response.code()}).")
        }
        clearSector()
    }

    fun selectSector(sector: ActiveSector) {
        _sector.value = sector
        prefs.edit()
            .putString(KEY_SECTOR_ID, sector.id)
            .putString(KEY_SECTOR_NAME, sector.name)
            .apply()
    }

    fun clearSector() {
        _sector.value = null
        prefs.edit().remove(KEY_SECTOR_ID).remove(KEY_SECTOR_NAME).apply()
    }

    private fun setInventory(dto: InventoryDto?) {
        _inventory.value = dto
        prefs.edit().apply {
            if (dto == null) {
                remove(KEY_INV_ID); remove(KEY_INV_CODE); remove(KEY_INV_STATUS)
                // Sem inventário aberto, o setor escolhido perde o sentido.
                remove(KEY_SECTOR_ID); remove(KEY_SECTOR_NAME)
            } else {
                putString(KEY_INV_ID, dto.id)
                putString(KEY_INV_CODE, dto.code)
                putString(KEY_INV_STATUS, dto.status)
            }
        }.apply()
        if (dto == null) _sector.value = null
    }

    private fun cachedInventory(): InventoryDto? {
        val id = prefs.getString(KEY_INV_ID, null) ?: return null
        return InventoryDto(
            id = id,
            code = prefs.getString(KEY_INV_CODE, "").orEmpty(),
            status = prefs.getString(KEY_INV_STATUS, "in_progress").orEmpty(),
            description = null,
        )
    }

    private fun cachedSector(): ActiveSector? {
        val id = prefs.getString(KEY_SECTOR_ID, null) ?: return null
        return ActiveSector(id, prefs.getString(KEY_SECTOR_NAME, "").orEmpty())
    }

    private companion object {
        const val TAG = "InventoryContext"
        const val SECTOR_PAGE_SIZE = 100
        const val OFFLINE_MESSAGE = "Sem conexão — usando os dados salvos no aparelho."
        const val KEY_INV_ID = "inventory_id"
        const val KEY_INV_CODE = "inventory_code"
        const val KEY_INV_STATUS = "inventory_status"
        const val KEY_SECTOR_ID = "sector_id"
        const val KEY_SECTOR_NAME = "sector_name"
    }
}
