package com.megagoglio.rfidinventory.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.megagoglio.rfidinventory.RfidApp
import com.megagoglio.rfidinventory.data.local.EpcFilterEntity
import com.megagoglio.rfidinventory.data.local.toSpec
import com.megagoglio.rfidinventory.reader.EpcFilterSpec
import com.megagoglio.rfidinventory.reader.ReaderState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Estado e ações da tela "EPC Filter". Não escuta `app.inventory.tagReads`
 * diretamente — é um `Channel` de consumidor único, já consumido pelo
 * [InventoryViewModel]; escutar de novo aqui roubaria leituras da tela de
 * inventário. Em vez disso a tela recebe as tags já agregadas (e o
 * scan/toggleScan compartilhado) como parâmetros vindos da `MainActivity`.
 */
class EpcFilterViewModel(application: Application) : AndroidViewModel(application) {

    private val app: RfidApp get() = getApplication()

    val readerState: StateFlow<ReaderState> = app.connection.state

    /** Filtro em vigor no leitor agora (null = nenhum — todas as tags aparecem). */
    val activeFilter: StateFlow<EpcFilterSpec?> = app.inventory.activeFilter

    val savedFilters: StateFlow<List<EpcFilterEntity>> = app.epcFilters.list()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _baseEpc = MutableStateFlow("")
    val baseEpc: StateFlow<String> = _baseEpc.asStateFlow()

    private val _start = MutableStateFlow(0)
    val start: StateFlow<Int> = _start.asStateFlow()

    private val _length = MutableStateFlow(4)
    val length: StateFlow<Int> = _length.asStateFlow()

    private val _messages = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _messages.asStateFlow()

    fun setBaseEpc(value: String) {
        _baseEpc.value = value.trim().uppercase()
        // Reencaixa start/length dentro do novo tamanho de EPC.
        setStart(_start.value)
        setLength(_length.value)
    }

    /** Usado tanto por "Escanear tag" quanto por escolher um item da lista de leituras recentes. */
    fun pickEpc(epc: String) = setBaseEpc(epc)

    fun incStart() = setStart(_start.value + 1)
    fun decStart() = setStart(_start.value - 1)

    fun incLength() = setLength(_length.value + 1)
    fun decLength() = setLength(_length.value - 1)

    private fun setStart(value: Int) {
        val epcLen = _baseEpc.value.length
        val newStart = value.coerceAtLeast(0)
        _start.value = if (epcLen > 0) newStart.coerceAtMost((epcLen - _length.value).coerceAtLeast(0)) else newStart
    }

    private fun setLength(value: Int) {
        val epcLen = _baseEpc.value.length
        val newLength = value.coerceAtLeast(1)
        _length.value = if (epcLen > 0) newLength.coerceAtMost((epcLen - _start.value).coerceAtLeast(1)) else newLength
    }

    /** Trecho do EPC base que o filtro vai casar (destacado na UI e usado como valor do filtro). */
    fun currentPattern(): String? {
        val epc = _baseEpc.value
        val s = _start.value
        val l = _length.value
        if (s < 0 || l <= 0 || s + l > epc.length) return null
        return epc.substring(s, s + l)
    }

    fun enableFilter() {
        val pattern = currentPattern()
        if (pattern == null) {
            emit("Ajuste início/comprimento dentro do tamanho do EPC base.")
            return
        }
        if (!HEX_REGEX.matches(pattern)) {
            emit("O trecho selecionado precisa ser hexadecimal.")
            return
        }
        app.inventory.applyEpcFilter(EpcFilterSpec(_start.value, _length.value, pattern))
            .onSuccess { emit("Filtro aplicado: $pattern") }
            .onFailure { emit(it.message ?: "Falha ao aplicar o filtro.") }
    }

    fun disableFilter() {
        app.inventory.clearEpcFilter()
        emit("Filtro removido — todas as tags voltam a aparecer.")
    }

    fun saveCurrentAs(name: String) {
        val pattern = currentPattern()
        if (pattern == null) {
            emit("Ajuste início/comprimento antes de salvar.")
            return
        }
        viewModelScope.launch {
            app.epcFilters.save(name, EpcFilterSpec(_start.value, _length.value, pattern))
            emit("Filtro \"$name\" salvo.")
        }
    }

    /** Traz os campos de um filtro salvo para a edição atual, sem aplicar no leitor ainda. */
    fun editSaved(entity: EpcFilterEntity) {
        _start.value = entity.startChar
        _length.value = entity.lengthChar
        // Sem um EPC base próprio guardado, o valor salvo serve de referência visual.
        _baseEpc.value = entity.valueHex
    }

    /** Aplica um filtro salvo direto no leitor e traz seus campos para a edição atual. */
    fun applySaved(entity: EpcFilterEntity) {
        editSaved(entity)
        app.inventory.applyEpcFilter(entity.toSpec())
            .onSuccess { emit("Filtro \"${entity.name}\" aplicado.") }
            .onFailure { emit(it.message ?: "Falha ao aplicar o filtro.") }
    }

    fun deleteSaved(entity: EpcFilterEntity) {
        viewModelScope.launch {
            app.epcFilters.delete(entity.id)
            emit("Filtro \"${entity.name}\" excluído.")
        }
    }

    fun consumeMessage() {
        _messages.value = null
    }

    private fun emit(text: String) {
        _messages.value = UiMessage(System.currentTimeMillis(), text)
    }

    private companion object {
        val HEX_REGEX = Regex("^[0-9A-Fa-f]+$")
    }
}
