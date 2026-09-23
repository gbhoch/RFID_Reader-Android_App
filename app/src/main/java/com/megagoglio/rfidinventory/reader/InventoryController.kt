package com.megagoglio.rfidinventory.reader

import android.util.Log
import com.uk.tsl.rfid.asciiprotocol.AsciiCommander
import com.uk.tsl.rfid.asciiprotocol.commands.AbortCommand
import com.uk.tsl.rfid.asciiprotocol.commands.AlertCommand
import com.uk.tsl.rfid.asciiprotocol.commands.FactoryDefaultsCommand
import com.uk.tsl.rfid.asciiprotocol.commands.InventoryCommand
import com.uk.tsl.rfid.asciiprotocol.enumerations.AlertDuration
import com.uk.tsl.rfid.asciiprotocol.enumerations.Databank
import com.uk.tsl.rfid.asciiprotocol.enumerations.QuerySelect
import com.uk.tsl.rfid.asciiprotocol.enumerations.SelectAction
import com.uk.tsl.rfid.asciiprotocol.enumerations.SelectTarget
import com.uk.tsl.rfid.asciiprotocol.enumerations.TriState
import com.uk.tsl.rfid.asciiprotocol.parameters.SelectControlParameters
import com.uk.tsl.rfid.asciiprotocol.parameters.SelectMaskParameters
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

    private val _activeFilter = MutableStateFlow<EpcFilterSpec?>(null)

    /** Filtro de EPC em vigor no leitor (null = todas as tags são reportadas). */
    val activeFilter: StateFlow<EpcFilterSpec?> = _activeFilter.asStateFlow()

    /**
     * Comandos síncronos NUNCA podem rodar na thread principal — o SDK bloqueia esperando
     * a resposta do leitor. Ver `runOffUIThread()` em ModelBase.java.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var continuousScanEnabled = false
    private var anyTagSeenInPass = false
    private var lastAlertAtNanos = 0L

    /** Emite configuração e dispara os scans. */
    private val inventoryCommand = LoggingInventoryCommand().apply {
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

            // O filtro de EPC não sobrevive a uma desconexão do leitor — se havia um
            // ativo, reenviá-lo aqui é o que evita o operador ter de reconfigurar
            // manualmente toda vez que o leitor cai e reconecta.
            _activeFilter.value?.let { writeSelectFields(it) }

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

    // ------------------------------------------------------------ filtro de EPC

    /**
     * Aplica um filtro de EPC no leitor: só tags cujo trecho casar com [spec] passam
     * a ser reportadas. Os campos são propriedades do próprio [inventoryCommand] —
     * não é preciso reexecutar nada aqui: se o scan contínuo já estiver rodando,
     * `responseLifecycleDelegate.responseEnded()` reexecuta esse mesmo objeto a cada
     * rodada e a rodada seguinte já sai com o filtro; se não estiver escaneando, o
     * filtro entra em vigor no próximo [scanStart].
     */
    fun applyEpcFilter(spec: EpcFilterSpec): Result<Unit> {
        if (!commander.isConnected) return Result.failure(IllegalStateException("Leitor desconectado"))
        return runCatching {
            writeSelectFields(spec)
            _activeFilter.value = spec
        }.onFailure { Log.e(TAG, "Falha ao aplicar filtro de EPC", it) }
    }

    /** Remove o filtro de EPC: o leitor volta a reportar todas as tags. */
    fun clearEpcFilter() {
        // Confirmado em campo (22/09/2026): voltar aos defaults do select + QuerySelect.ALL
        // é suficiente — as tags reaparecem imediatamente, sem flag SL presa em nenhuma
        // delas. Não é preciso mexer nas sessões Gen2 S0-S3.
        SelectMaskParameters.setDefaultParametersFor(inventoryCommand)
        SelectControlParameters.setDefaultParametersFor(inventoryCommand)
        inventoryCommand.setQuerySelect(QuerySelect.ALL)
        // Sem filtro não há Select a executar: voltar ao inventário puro evita uma
        // operação por rodada à toa. Ver a explicação em writeSelectFields.
        inventoryCommand.setInventoryOnly(TriState.YES)
        _activeFilter.value = null
    }

    /** Converte [spec] (caracteres hex) para os campos de select do SDK (bits). */
    private fun writeSelectFields(spec: EpcFilterSpec) {
        inventoryCommand.setSelectBank(Databank.ELECTRONIC_PRODUCT_CODE)
        inventoryCommand.setSelectOffset(EpcFilterSelectParams.offsetBits(spec))
        inventoryCommand.setSelectLength(EpcFilterSelectParams.lengthBits(spec))
        inventoryCommand.setSelectData(EpcFilterSelectParams.selectData(spec))

        // Semântica do filtro positivo ("só reporta quem casa"), confirmada no Javadoc
        // e no bytecode do SDK da TSL (Rfid.AsciiProtocol-Library/doc/):
        //
        // - ASSERT_SET_A_NOT_DEASSERT_SET_B é a ação 000 do Gen2. O próprio SDK a
        //   descreve como "Match: Assert Select / Set Session A — Non Match: Deassert
        //   Select / Set Session B": quem casa fica com a flag SL asserted, quem não
        //   casa fica deasserted.
        // - SelectTarget.SELECTED (argumento "sl") faz o Select agir sobre a flag SL,
        //   e não sobre uma das sessões S0-S3.
        // - QuerySelect.SELECTED ("sl" = "Selected transponders only") faz a rodada de
        //   inventário responder apenas às tags com SL asserted.
        //
        // As três acima já estavam corretas. O que faltava era a linha abaixo:
        //
        // setInventoryOnly(YES) significa, no Javadoc do InventoryCommand, "the select
        // operation is NOT performed before the inventory". O app nunca setava esse
        // campo, então o switch -io jamais era enviado e valia o default do leitor —
        // que pula o Select. Com -ql sl aplicado e nenhum Select executado, NENHUMA tag
        // energiza com SL asserted e o inventário reporta zero, para qualquer máscara.
        // Foi exatamente o que se mediu em campo. Como este comando também manda
        // -x (setResetParameters(YES)) a cada rodada do scan contínuo, o valor precisa
        // ser reenviado explicitamente em todas elas — e é o que acontece, porque os
        // campos ficam no objeto que é reexecutado.
        inventoryCommand.setSelectAction(SelectAction.ASSERT_SET_A_NOT_DEASSERT_SET_B)
        inventoryCommand.setSelectTarget(SelectTarget.SELECTED)
        inventoryCommand.setQuerySelect(QuerySelect.SELECTED)
        inventoryCommand.setInventoryOnly(TriState.NO)
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

    /**
     * TEMPORÁRIO — diagnóstico do Select. Remover quando o filtro estiver validado.
     *
     * O SDK da TSL não expõe nenhum trace do que é enviado ao leitor, mas
     * `buildCommandLine` é protected e não-final, então dá para ler a linha ASCII já
     * montada sem reimplementar a serialização. Como o scan contínuo reexecuta o mesmo
     * objeto a cada `responseEnded()`, cada rodada gera uma linha — é assim que se vê
     * se os parâmetros sobrevivem ao `-x` da 2ª rodada em diante.
     *
     * Acompanhar com: `adb logcat -s InventoryController`
     */
    private class LoggingInventoryCommand : InventoryCommand() {
        override fun buildCommandLine(sb: StringBuilder) {
            super.buildCommandLine(sb)
            Log.d(TAG, "TX: $sb")
        }
    }

    private companion object {
        const val TAG = "InventoryController"
        const val ALERT_MIN_INTERVAL_NANOS = 400L * 1000 * 1000
    }
}
