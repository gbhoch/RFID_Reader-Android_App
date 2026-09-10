package com.megagoglio.rfidinventory.reader

import android.util.Log
import com.uk.tsl.rfid.asciiprotocol.AsciiCommander
import com.uk.tsl.rfid.asciiprotocol.commands.AbortCommand
import com.uk.tsl.rfid.asciiprotocol.commands.AlertCommand
import com.uk.tsl.rfid.asciiprotocol.commands.FactoryDefaultsCommand
import com.uk.tsl.rfid.asciiprotocol.commands.InventoryCommand
import com.uk.tsl.rfid.asciiprotocol.enumerations.AlertDuration
import com.uk.tsl.rfid.asciiprotocol.enumerations.TriState
import com.uk.tsl.rfid.asciiprotocol.responders.ICommandResponseLifecycleDelegate
import com.uk.tsl.rfid.asciiprotocol.responders.ITransponderReceivedDelegate
import com.uk.tsl.utils.HexEncoding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Inventário RFID: configura o leitor, escuta as tags e expõe cada leitura como um [TagRead].
 *
 * Portado de InventoryModel.java (SDK da TSL). Dois detalhes do SDK que não são óbvios e
 * que este arquivo depende:
 *
 * 1. São necessários DOIS [InventoryCommand] distintos. Um *emite* configuração e dispara
 *    scans ([inventoryCommand]); o outro é registrado como responder e apenas *escuta*
 *    ([inventoryResponder]). Usar o mesmo objeto para as duas coisas não funciona.
 *
 * 2. `setCaptureNonLibraryResponses(true)` no responder é obrigatório. As leituras
 *    disparadas pelo GATILHO FÍSICO do leitor não são resposta a nenhum comando que o app
 *    enviou; sem essa flag elas simplesmente não chegam ao app.
 */
class InventoryController(private val commander: AsciiCommander) {

    /**
     * Buffer ilimitado de propósito: uma leitura perdida é um item não inventariado.
     * A UI consome em seu próprio ritmo sem nunca aplicar contrapressão no leitor.
     */
    private val reads = Channel<TagRead>(Channel.UNLIMITED)
    val tagReads: Flow<TagRead> = reads.receiveAsFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /**
     * Comandos síncronos NUNCA podem rodar na thread principal — o SDK bloqueia esperando
     * a resposta do leitor. Ver `runOffUIThread()` em ModelBase.java.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var continuousScanEnabled = false
    private var anyTagSeenInPass = false
    private var lastAlertAtNanos = 0L

    /** Emite configuração e dispara os scans. */
    private val inventoryCommand = InventoryCommand().apply {
        setResetParameters(TriState.YES)
        // O beep é dado pelo app, não pelo leitor, para poder respeitar o intervalo mínimo.
        useAlert = TriState.NO
    }

    /** Registrado como responder: captura as respostas de inventário. */
    private val inventoryResponder = InventoryCommand()

    private val alertCommand = AlertCommand().apply {
        duration = AlertDuration.SHORT
    }

    private var respondersAttached = false

    init {
        // Captura também as respostas que não vieram de comandos do app — é isso que faz
        // o gatilho físico do leitor funcionar.
        inventoryResponder.setCaptureNonLibraryResponses(true)

        inventoryResponder.transponderReceivedDelegate =
            ITransponderReceivedDelegate { transponder, _ ->
                val epc = transponder.epc ?: return@ITransponderReceivedDelegate
                anyTagSeenInPass = true

                val errors = listOfNotNull(
                    transponder.accessErrorCode?.description,
                    transponder.backscatterErrorCode?.description,
                ).joinToString("; ").ifEmpty { null }

                val read = TagRead(
                    epc = epc,
                    tid = transponder.tidData?.let { HexEncoding.bytesToString(it) },
                    rssi = transponder.rssi ?: 0,
                    pc = transponder.pc ?: 0,
                    crc = transponder.crc ?: 0,
                    // Hora do celular, não do leitor: o relógio do leitor pode estar
                    // desacertado e é a hora do host que vale para o sistema.
                    seenAt = System.currentTimeMillis(),
                    error = errors,
                )
                // Canal ilimitado: trySend nunca falha por buffer cheio.
                reads.trySend(read)
            }

        inventoryResponder.responseLifecycleDelegate = object : ICommandResponseLifecycleDelegate {
            override fun responseBegan() {
                anyTagSeenInPass = false
            }

            override fun responseEnded() {
                if (anyTagSeenInPass) {
                    beep()
                }
                if (continuousScanEnabled) {
                    // O scan contínuo é o app reemitindo o comando a cada resposta concluída.
                    commander.executeCommand(inventoryCommand)
                } else {
                    inventoryCommand.takeNoAction = TriState.NO
                }
            }
        }
    }

    // ------------------------------------------------------------- responders

    fun attach() {
        if (respondersAttached) return
        respondersAttached = true
        commander.addResponder(inventoryResponder)
    }

    fun detach() {
        if (!respondersAttached) return
        respondersAttached = false
        if (continuousScanEnabled) scanStop()
        commander.removeResponder(inventoryResponder)
    }

    // ---------------------------------------------------------- configuração

    /**
     * Envia ao leitor a configuração de quais campos devem vir em cada leitura.
     * Chamar sempre que o leitor conectar.
     */
    fun applyConfiguration() = scope.launch {
        if (!commander.isConnected) return@launch
        runCatching {
            inventoryCommand.takeNoAction = TriState.YES

            // Campos que o app persiste e envia ao sistema.
            inventoryCommand.setIncludeTransponderRssi(TriState.YES)
            inventoryCommand.setIncludeChecksum(TriState.YES)
            inventoryCommand.setIncludePC(TriState.YES)
            inventoryCommand.setIncludeDateTime(TriState.YES)

            commander.executeCommand(inventoryCommand)
        }.onFailure { Log.e(TAG, "Falha ao configurar o leitor", it) }
    }

    /** Restaura os parâmetros de fábrica do leitor. */
    fun resetDevice() = scope.launch {
        if (!commander.isConnected) return@launch
        runCatching {
            val command = FactoryDefaultsCommand().apply { setResetParameters(TriState.YES) }
            commander.executeCommand(command)
        }.onFailure { Log.e(TAG, "Falha ao restaurar padrões de fábrica", it) }
    }

    // ------------------------------------------------------------------ scan

    /** Inicia o scan contínuo por software (alternativa ao gatilho físico). */
    fun scanStart() = scope.launch {
        if (!commander.isConnected) return@launch
        continuousScanEnabled = true
        _isScanning.value = true
        inventoryCommand.takeNoAction = TriState.NO
        runCatching { commander.executeCommand(inventoryCommand) }
            .onFailure { Log.e(TAG, "Falha ao iniciar o scan", it) }
    }

    fun scanStop() {
        continuousScanEnabled = false
        _isScanning.value = false
        inventoryCommand.takeNoAction = TriState.YES

        scope.launch {
            if (!commander.isConnected) return@launch
            runCatching { commander.executeCommand(AbortCommand()) }
                .onFailure { Log.e(TAG, "Falha ao abortar o scan", it) }
        }
    }

    /**
     * Nos leitores 11xx, disparar o AlertCommand em sequência rápida trava o buzzer
     * em execução contínua. O intervalo mínimo evita esse defeito de hardware.
     */
    private fun beep() {
        val now = System.nanoTime()
        if (now - lastAlertAtNanos <= ALERT_MIN_INTERVAL_NANOS) return
        lastAlertAtNanos = now
        commander.executeCommand(alertCommand)
    }

    private companion object {
        const val TAG = "InventoryController"
        const val ALERT_MIN_INTERVAL_NANOS = 400L * 1000 * 1000
    }
}
