package com.megagoglio.rfidinventory.reader

import android.util.Log
import com.uk.tsl.rfid.asciiprotocol.AsciiCommander
import com.uk.tsl.rfid.asciiprotocol.device.ConnectionState
import com.uk.tsl.rfid.asciiprotocol.device.Reader
import com.uk.tsl.rfid.asciiprotocol.device.ReaderManager
import com.uk.tsl.rfid.asciiprotocol.device.TransportType
import com.uk.tsl.utils.Observable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado da conexão com o leitor, pronto para a UI observar. */
data class ReaderState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val readerName: String? = null,
    val serialNumber: String? = null,
    val reason: String? = null,
) {
    val isConnected: Boolean get() = connectionState == ConnectionState.CONNECTED
    val isConnecting: Boolean get() = connectionState == ConnectionState.CONNECTING
}

/**
 * Ciclo de vida da conexão com o leitor TSL.
 *
 * Portado de InventoryActivity.java (SDK da TSL), com o ramo de USB/ePop-Loq removido:
 * os leitores Série 1000/2000 conectam por Bluetooth Classic e a escolha do leitor é
 * sempre explícita do usuário, via DeviceListActivity.
 */
class ReaderConnectionManager {

    private val commander: AsciiCommander get() = AsciiCommander.sharedInstance()
    private val readerManager: ReaderManager get() = ReaderManager.sharedInstance()

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    /** O leitor em uso. Null quando nenhum foi escolhido ou o escolhido sumiu da lista. */
    var currentReader: Reader? = null
        private set

    /**
     * True enquanto a DeviceListActivity está aberta. Sem essa flag, o onPause disparado
     * ao abrir aquela tela desconectaria o leitor que o usuário acabou de escolher.
     */
    var isSelectingReader: Boolean = false

    private var observersRegistered = false

    // ---------------------------------------------------------------- eventos

    private val stateChangedObserver = Observable.Observer<String> { _, reason ->
        onConnectionStateChanged(reason)
    }

    private val readerAddedObserver = Observable.Observer<Reader> { _, _ ->
        reconnectIfPossible()
    }

    private val readerUpdatedObserver = Observable.Observer<Reader> { _, _ ->
        publishState(_state.value.reason)
    }

    private val readerRemovedObserver = Observable.Observer<Reader> { _, reader ->
        // O leitor em uso saiu da lista (desligado, desemparelhado): parar de usá-lo.
        if (reader == currentReader) {
            currentReader = null
            commander.reader = null
            publishState(reason = null)
        }
    }

    /** Chamar uma única vez, quando a Activity principal é criada. */
    fun registerObservers() {
        if (observersRegistered) return
        observersRegistered = true

        commander.stateChangedEvent().addObserver(stateChangedObserver)

        val readerList = readerManager.readerList
        readerList.readerAddedEvent().addObserver(readerAddedObserver)
        readerList.readerUpdatedEvent().addObserver(readerUpdatedObserver)
        readerList.readerRemovedEvent().addObserver(readerRemovedObserver)
    }

    /** Chamar em onDestroy da Activity principal. */
    fun unregisterObservers() {
        if (!observersRegistered) return
        observersRegistered = false

        commander.stateChangedEvent().removeObserver(stateChangedObserver)

        val readerList = readerManager.readerList
        readerList.readerAddedEvent().removeObserver(readerAddedObserver)
        readerList.readerUpdatedEvent().removeObserver(readerUpdatedObserver)
        readerList.readerRemovedEvent().removeObserver(readerRemovedObserver)
    }

    // ------------------------------------------------------------ ciclo de vida

    /**
     * @param canUseBluetooth false enquanto as permissões de Bluetooth não foram concedidas.
     *
     * O [ReaderManager] exige que onResume e onPause venham sempre aos pares, então
     * onResume é chamado incondicionalmente. Só a varredura da lista fica condicionada à
     * permissão — em API 31+ ela lança SecurityException sem BLUETOOTH_CONNECT.
     */
    fun onResume(canUseBluetooth: Boolean) {
        readerManager.onResume()

        if (canUseBluetooth) {
            // O app pode iniciar com um leitor já conectado por outro app: atualizar a lista
            // dispara os eventos de adição e traz esse leitor para dentro.
            readerManager.updateList()
            reconnectIfPossible()
        }
        isSelectingReader = false
    }

    /**
     * Chamar quando as permissões de Bluetooth forem concedidas já com a Activity ativa.
     * Faz só a parte que ficou de fora do [onResume] — não mexe no par onResume/onPause
     * do [ReaderManager].
     */
    fun onBluetoothPermissionsGranted() {
        readerManager.updateList()
        reconnectIfPossible()
    }

    fun onPause() {
        // Soltar o leitor para que outros apps possam usá-lo — exceto quando a pausa
        // veio de abrir a tela de seleção de leitor.
        if (!isSelectingReader) {
            currentReader?.disconnect()
        }
        readerManager.onPause()
    }

    // ---------------------------------------------------------------- conexão

    /**
     * Reconecta ao leitor já escolhido, se houver e se ele estiver ocioso.
     * Não escolhe leitor sozinho: em Bluetooth a escolha é sempre do usuário.
     */
    private fun reconnectIfPossible() {
        val reader = currentReader ?: return
        if (reader.isConnecting) return

        val transport = reader.activeTransport
        val idle = transport == null ||
            transport.connectionStatus().value() == ConnectionState.DISCONNECTED
        if (!idle) return

        val started = if (reader.allowMultipleTransports() || reader.lastTransportType == null) {
            reader.connect()
        } else {
            // Leitores que aceitam um único transporte ativo devem reconectar pelo último usado.
            reader.connect(reader.lastTransportType)
        }
        Log.d(TAG, "reconnectIfPossible: ${reader.displayName} -> $started")
    }

    /** Passa a usar o leitor no índice devolvido pela DeviceListActivity. */
    fun useReaderAt(index: Int) {
        val reader = readerManager.readerList.list().getOrNull(index) ?: return

        if (currentReader != null && currentReader !== reader) {
            currentReader?.disconnect()
        }

        currentReader = reader
        commander.reader = reader

        if (!reader.isConnected && !reader.isConnecting) {
            reader.connect(TransportType.BLUETOOTH)
        }
        publishState(reason = null)
    }

    /** Índice do leitor atual na lista do ReaderManager, ou -1. */
    fun currentReaderIndex(): Int {
        val reader = currentReader ?: return -1
        return readerManager.readerList.list().indexOf(reader)
    }

    fun disconnect() {
        currentReader?.disconnect()
        currentReader = null
        commander.reader = null
        publishState(reason = null)
    }

    // ----------------------------------------------------------------- estado

    private fun onConnectionStateChanged(reason: String?) {
        val connectionState = commander.connectionState
        Log.d(TAG, "AsciiCommander: $connectionState (conectado=${commander.isConnected}) $reason")

        if (connectionState == ConnectionState.DISCONNECTED) {
            // Uma desconexão manual já limpou currentReader. Se sobrou um leitor aqui e a
            // última tentativa falhou, ele não serve — forçar nova escolha pelo usuário.
            currentReader?.let { reader ->
                if (!reader.wasLastConnectSuccessful()) {
                    currentReader = null
                }
            }
        }
        publishState(reason)
    }

    private fun publishState(reason: String?) {
        val reader = currentReader
        _state.value = ReaderState(
            connectionState = commander.connectionState ?: ConnectionState.DISCONNECTED,
            readerName = reader?.displayName,
            serialNumber = reader?.serialNumber,
            reason = reason,
        )
    }

    private companion object {
        const val TAG = "ReaderConnection"
    }
}
