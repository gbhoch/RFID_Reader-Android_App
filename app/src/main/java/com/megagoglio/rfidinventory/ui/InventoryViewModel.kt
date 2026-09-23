package com.megagoglio.rfidinventory.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.megagoglio.rfidinventory.RfidApp
import com.megagoglio.rfidinventory.data.inventory.ActiveSector
import com.megagoglio.rfidinventory.data.local.SectorEntity
import com.megagoglio.rfidinventory.data.local.SessionEntity
import com.megagoglio.rfidinventory.data.remote.InventoryDto
import com.megagoglio.rfidinventory.reader.ReaderState
import com.megagoglio.rfidinventory.reader.TagAggregate
import com.megagoglio.rfidinventory.reader.merge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Aviso pontual para a UI mostrar em snackbar. */
data class UiMessage(val id: Long, val text: String)

@OptIn(ExperimentalCoroutinesApi::class)
class InventoryViewModel(application: Application) : AndroidViewModel(application) {

    private val app: RfidApp get() = getApplication()

    val readerState: StateFlow<ReaderState> = app.connection.state

    val isScanning: StateFlow<Boolean> = app.inventory.isScanning

    /** Filtro de EPC em vigor no leitor agora (null = nenhum). */
    val activeEpcFilter = app.inventory.activeFilter

    val isLoggedIn: StateFlow<Boolean> = app.auth.isLoggedIn

    val operatorLogin: String? get() = app.auth.operatorLogin

    // ---- Contexto: inventário aberto e setor atual ----

    val inventory: StateFlow<InventoryDto?> = app.inventoryContext.inventory

    val sector: StateFlow<ActiveSector?> = app.inventoryContext.sector

    val sectors: StateFlow<List<SectorEntity>> = app.inventoryContext.sectors
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Coleta do setor atual que ainda não subiu — impede concluir o setor cedo demais. */
    val unsentForCurrentSector: StateFlow<Int> = sector
        .flatMapLatest { current ->
            if (current == null) flowOf(0) else app.syncRepository.unsentCountForSector(current.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _tags = MutableStateFlow<Map<String, TagAggregate>>(emptyMap())

    /** Tags únicas da sessão, mais recentes primeiro. */
    val tags: StateFlow<List<TagAggregate>> = _tags
        .map { map -> map.values.sortedByDescending { it.lastSeenAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingSessions: StateFlow<Int> = app.syncRepository.pendingCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val blockedSessions: StateFlow<Int> = app.syncRepository.blockedCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val recentSessions: StateFlow<List<SessionEntity>> = app.syncRepository.recentSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _messages.asStateFlow()

    /** Início da sessão atual: primeira tag lida depois do último "Limpar"/"Finalizar". */
    private var sessionStartedAt: Long = System.currentTimeMillis()

    init {
        // A deduplicação por EPC acontece aqui, e não no leitor: manter todas as leituras
        // fluindo permite exibir a contagem por tag, que é o que dá ao operador a noção de
        // leitura sólida (dezenas de hits) versus leitura de borda (dois ou três).
        viewModelScope.launch {
            app.inventory.tagReads.collect { read ->
                _tags.value = _tags.value.toMutableMap().apply {
                    if (isEmpty()) sessionStartedAt = read.seenAt
                    put(read.epc, get(read.epc).merge(read))
                }
            }
        }
    }

    fun onReaderConnected() {
        app.inventory.applyConfiguration()
    }

    /** Recarrega inventário e setores. Chamado ao entrar na tela e ao voltar do background. */
    fun refreshContext(announce: Boolean = false) {
        viewModelScope.launch {
            if (!app.auth.isLoggedIn.value) return@launch
            val error = app.inventoryContext.refresh()
            when {
                error != null -> emit(error)
                announce && app.inventoryContext.inventory.value == null ->
                    emit("Nenhum inventário aberto no sistema. Abra um pelo painel web.")
            }
        }
    }

    fun toggleScan() {
        if (app.inventory.isScanning.value) {
            app.inventory.scanStop()
        } else {
            app.inventory.scanStart()
        }
    }

    fun clearSession() {
        _tags.value = emptyMap()
        sessionStartedAt = System.currentTimeMillis()
    }

    fun selectSector(sector: SectorEntity) {
        app.inventoryContext.selectSector(ActiveSector(sector.id, sector.name))
        emit("Setor: ${sector.name}")
    }

    /** Grava a coleta localmente e agenda o envio à API. */
    fun finishSession() {
        val current = _tags.value.values.toList()
        if (current.isEmpty()) {
            emit("Nenhuma tag lida nesta sessão.")
            return
        }
        // O setor é o que amarra a leitura à visita no servidor. Sem ele a conciliação
        // não distingue "sumiu" de "está em outro setor" — por isso é bloqueio, e não
        // um aviso que dá para ignorar.
        val inv = inventory.value ?: run {
            emit("Nenhum inventário aberto. Conecte-se e atualize antes de finalizar.")
            return
        }
        val sec = sector.value ?: run {
            emit("Escolha o setor antes de finalizar a leitura.")
            return
        }
        val serial = app.connection.currentReader?.serialNumber

        viewModelScope.launch {
            runCatching {
                app.syncRepository.finishSession(
                    tags = current,
                    readerSerial = serial,
                    startedAt = sessionStartedAt,
                    inventoryId = inv.id,
                    inventoryCode = inv.code,
                    sectorId = sec.id,
                    sectorName = sec.name,
                )
            }.onSuccess {
                clearSession()
                emit("${current.size} tags gravadas em ${sec.name}. Envio agendado.")
            }.onFailure { error ->
                emit("Falha ao gravar a coleta: ${error.message}")
            }
        }
    }

    fun completeSector() {
        val sec = sector.value ?: return
        if (unsentForCurrentSector.value > 0) {
            emit("Há coleta de ${sec.name} sem enviar. Aguarde a sincronização.")
            return
        }
        viewModelScope.launch {
            _busy.value = true
            app.inventoryContext.completeCurrentSector()
                .onSuccess { emit("Setor ${sec.name} concluído.") }
                .onFailure { emit(it.message ?: "Falha ao concluir o setor.") }
            _busy.value = false
        }
    }

    /** Devolve à fila as coletas travadas — depois de o gestor reabrir o inventário. */
    fun retryBlocked() {
        viewModelScope.launch {
            app.syncRepository.retryBlocked()
            emit("Coletas devolvidas à fila de envio.")
        }
    }

    fun syncNow() {
        app.syncRepository.scheduleSync()
        emit("Sincronização agendada.")
    }

    fun logout() {
        viewModelScope.launch {
            app.auth.logout()
            clearSession()
        }
    }

    fun consumeMessage() {
        _messages.value = null
    }

    private fun emit(text: String) {
        _messages.value = UiMessage(System.currentTimeMillis(), text)
    }
}
