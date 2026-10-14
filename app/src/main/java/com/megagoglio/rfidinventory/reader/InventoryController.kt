package com.megagoglio.rfidinventory.reader

import android.util.Log
import com.uk.tsl.rfid.asciiprotocol.AsciiCommander
import com.uk.tsl.rfid.asciiprotocol.commands.AbortCommand
import com.uk.tsl.rfid.asciiprotocol.commands.AlertCommand
import com.uk.tsl.rfid.asciiprotocol.commands.FactoryDefaultsCommand
import com.uk.tsl.rfid.asciiprotocol.commands.InventoryCommand
import com.uk.tsl.rfid.asciiprotocol.commands.ReadTransponderCommand
import com.uk.tsl.rfid.asciiprotocol.enumerations.AlertDuration
import com.uk.tsl.rfid.asciiprotocol.enumerations.Databank
import com.uk.tsl.rfid.asciiprotocol.enumerations.QuerySelect
import com.uk.tsl.rfid.asciiprotocol.enumerations.SelectAction
import com.uk.tsl.rfid.asciiprotocol.enumerations.SelectTarget
import com.uk.tsl.rfid.asciiprotocol.enumerations.TriState
import com.uk.tsl.rfid.asciiprotocol.parameters.IQueryParameters
import com.uk.tsl.rfid.asciiprotocol.parameters.ISelectControlParameters
import com.uk.tsl.rfid.asciiprotocol.parameters.ISelectMaskParameters
import com.uk.tsl.rfid.asciiprotocol.parameters.SelectControlParameters
import com.uk.tsl.rfid.asciiprotocol.parameters.SelectMaskParameters
import com.uk.tsl.rfid.asciiprotocol.responders.ICommandResponseLifecycleDelegate
import com.uk.tsl.rfid.asciiprotocol.responders.ITransponderReceivedDelegate
import com.uk.tsl.rfid.asciiprotocol.responders.TransponderData
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
import kotlinx.coroutines.withContext

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

    private val _tidMode = MutableStateFlow(false)

    /** Varredura contínua lendo também o banco TID de cada tag (mais lenta). */
    val tidMode: StateFlow<Boolean> = _tidMode.asStateFlow()

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

    /**
     * Emite a varredura do modo "inventário com TID" (ver [tidMode]). É um comando de
     * LEITURA (`.rd`), não de inventário: além de singularizar cada tag ele lê o banco
     * TID dela, o que custa uma operação extra por tag — daí o modo ser opcional.
     */
    private val tidScanCommand = ReadTransponderCommand().apply {
        setResetParameters(TriState.YES)
        useAlert = TriState.NO
        setIncludeEpc(TriState.YES)
        setIncludeTransponderRssi(TriState.YES)
        setIncludeChecksum(TriState.YES)
        setIncludePC(TriState.YES)
        setIncludeDateTime(TriState.YES)
        setBank(Databank.TRANSPONDER_IDENTIFIER)
        setOffset(0)
        setLength(TID_WORDS_EXTENDED)
    }

    /** Registrado como responder: captura as respostas da varredura com TID. */
    private val tidScanResponder = ReadTransponderCommand()

    private val alertCommand = AlertCommand().apply {
        duration = AlertDuration.SHORT
    }

    private var respondersAttached = false

    init {
        // Captura também as respostas que não vieram de comandos do app — é isso que faz
        // o gatilho físico do leitor funcionar.
        inventoryResponder.setCaptureNonLibraryResponses(true)

        inventoryResponder.transponderReceivedDelegate =
            ITransponderReceivedDelegate { transponder, _ -> publish(transponder) }

        inventoryResponder.responseLifecycleDelegate = object : ICommandResponseLifecycleDelegate {
            override fun responseBegan() {
                anyTagSeenInPass = false
            }

            override fun responseEnded() {
                if (anyTagSeenInPass) {
                    beep()
                }
                // Cada responder só realimenta a SUA varredura: o de inventário reemite o
                // `.iv`, o de TID reemite o `.rd`. Sem essa checagem os dois disputariam o
                // leitor ao alternar de modo.
                if (continuousScanEnabled && !_tidMode.value) {
                    // O scan contínuo é o app reemitindo o comando a cada resposta concluída.
                    commander.executeCommand(inventoryCommand)
                } else if (!continuousScanEnabled) {
                    inventoryCommand.takeNoAction = TriState.NO
                }
            }
        }

        tidScanResponder.setCaptureNonLibraryResponses(true)

        tidScanResponder.transponderReceivedDelegate =
            ITransponderReceivedDelegate { transponder, _ -> publish(transponder) }

        tidScanResponder.responseLifecycleDelegate = object : ICommandResponseLifecycleDelegate {
            override fun responseBegan() {
                anyTagSeenInPass = false
            }

            override fun responseEnded() {
                if (anyTagSeenInPass) {
                    beep()
                }
                if (continuousScanEnabled && _tidMode.value) {
                    commander.executeCommand(tidScanCommand)
                }
            }
        }
    }

    /**
     * Converte uma resposta do leitor em [TagRead] e a entrega ao fluxo.
     *
     * O TID pode chegar por dois campos distintos do SDK, e é por isso que os dois são
     * consultados aqui: `tidData` é preenchido pelo Monza FastID (prefixo ASCII `TD`) e
     * `readData` pelo resultado de um comando de leitura de banco (prefixo `RD`), que é
     * o caminho que este app usa. Ler só `tidData`, como era antes, garantia TID nulo.
     */
    private fun publish(transponder: TransponderData) {
        val epc = transponder.epc ?: return
        anyTagSeenInPass = true

        val errors = listOfNotNull(
            transponder.accessErrorCode?.description,
            transponder.backscatterErrorCode?.description,
        ).joinToString("; ").ifEmpty { null }

        val tidBytes = transponder.tidData ?: transponder.readData

        val read = TagRead(
            epc = epc,
            tid = tidBytes?.takeIf { it.isNotEmpty() }?.let { HexEncoding.bytesToString(it) },
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

    // ------------------------------------------------------------- responders

    fun attach() {
        if (respondersAttached) return
        respondersAttached = true
        commander.addResponder(inventoryResponder)
        commander.addResponder(tidScanResponder)
    }

    fun detach() {
        if (!respondersAttached) return
        respondersAttached = false
        if (continuousScanEnabled) scanStop()
        commander.removeResponder(inventoryResponder)
        commander.removeResponder(tidScanResponder)
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

        // A varredura com TID tem o seu próprio jogo de parâmetros e precisa ser limpa
        // junto, senão o filtro continuaria valendo só nela.
        SelectMaskParameters.setDefaultParametersFor(tidScanCommand)
        SelectControlParameters.setDefaultParametersFor(tidScanCommand)
        tidScanCommand.setQuerySelect(QuerySelect.ALL)

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

        // O mesmo filtro precisa valer na varredura com TID, senão ligar o modo de
        // auditoria faria o filtro sumir sem aviso.
        writeEpcSelect(tidScanCommand, spec)
        tidScanCommand.setInventoryOnly(TriState.NO)
    }

    /**
     * Escreve uma máscara de Select positiva sobre qualquer comando que aceite esses
     * parâmetros — é o mesmo jogo usado pelo filtro de EPC e pela singularização de uma
     * tag na leitura de TID.
     */
    private fun <T> writeEpcSelect(command: T, spec: EpcFilterSpec)
        where T : ISelectMaskParameters, T : ISelectControlParameters, T : IQueryParameters {
        command.setSelectBank(Databank.ELECTRONIC_PRODUCT_CODE)
        command.setSelectOffset(EpcFilterSelectParams.offsetBits(spec))
        command.setSelectLength(EpcFilterSelectParams.lengthBits(spec))
        command.setSelectData(EpcFilterSelectParams.selectData(spec))
        command.setSelectAction(SelectAction.ASSERT_SET_A_NOT_DEASSERT_SET_B)
        command.setSelectTarget(SelectTarget.SELECTED)
        command.setQuerySelect(QuerySelect.SELECTED)
    }

    // ------------------------------------------------------------------- TID

    /**
     * O TID mora num banco de memória separado do EPC e, ao contrário dele, não vem de
     * graça na varredura: exige um comando de leitura (`.rd`) que singulariza a tag e lê
     * o banco `tid`, ou seja, uma operação a mais por tag. Por isso o padrão do app é
     * ler SOB DEMANDA — o inventário contínuo segue rápido, só com EPC, e o operador
     * pede o TID da tag que interessa.
     *
     * O SDK tem um atalho que traria o TID junto com o EPC sem custo
     * (`InventoryCommand.setUsefastId`, o Monza FastID), mas o Javadoc o marca como
     * "Impinj Specific": em chips de outros fabricantes o campo volta vazio, em
     * silêncio. Numa auditoria de clonagem isso seria um falso negativo, então o modo
     * contínuo ([tidMode]) usa o caminho universal do [ReadTransponderCommand] e assume
     * o custo de velocidade.
     *
     * Offset e comprimento aqui são em WORDS DE 16 BITS, não em bits — é o que diz o
     * Javadoc de `IDatabankParameters` ("the offset, in 16 bit words"). Não confundir
     * com o Select do filtro de EPC, esse sim em bits.
     */
    suspend fun readTid(epc: String): Result<String> = withContext(Dispatchers.IO) {
        if (!commander.isConnected) {
            return@withContext Result.failure(IllegalStateException("Leitor desconectado."))
        }
        // O `.rd` e a varredura contínua disputariam o mesmo leitor: o comando síncrono
        // ficaria preso esperando uma resposta que pertence ao inventário em curso.
        if (continuousScanEnabled) {
            return@withContext Result.failure(
                IllegalStateException("Pare a leitura antes de ler o TID de uma tag."),
            )
        }
        // Tenta primeiro o TID estendido, que é o único que carrega o serial único e
        // portanto o único que serve para anticlonagem. Chips sem TID estendido recusam
        // a leitura de 6 words; nesses cai para os 32 bits obrigatórios do Gen2, que
        // identificam fabricante e modelo mas não a peça.
        readTidWords(epc, TID_WORDS_EXTENDED)
            .recoverCatching { readTidWords(epc, TID_WORDS_MINIMUM).getOrThrow() }
    }

    private fun readTidWords(epc: String, words: Int): Result<String> {
        var captured: String? = null
        var reportedError: String? = null

        val command = ReadTransponderCommand.synchronousCommand().apply {
            setIncludeEpc(TriState.YES)
            setBank(Databank.TRANSPONDER_IDENTIFIER)
            setOffset(0)
            setLength(words)
            // Singulariza esta tag usando o EPC inteiro como máscara de Select: só ela
            // responde à leitura, mesmo com outras tags no campo.
            writeEpcSelect(this, EpcFilterSpec(startChar = 0, lengthChar = epc.length, valueHex = epc))
            // Sem isto o leitor pula o Select e leria o TID de qualquer tag que
            // aparecesse primeiro — o mesmo detalhe que quebrava o filtro de EPC.
            setInventoryOnly(TriState.NO)
            transponderReceivedDelegate = ITransponderReceivedDelegate { transponder, _ ->
                transponder.readData
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { captured = HexEncoding.bytesToString(it) }
                reportedError = listOfNotNull(
                    transponder.accessErrorCode?.description,
                    transponder.backscatterErrorCode?.description,
                ).joinToString("; ").ifEmpty { null }
            }
        }

        return runCatching { commander.executeCommand(command) }
            .mapCatching {
                captured ?: throw IllegalStateException(
                    reportedError ?: "A tag não respondeu. Aproxime o leitor e tente de novo.",
                )
            }
            .onFailure { Log.w(TAG, "Falha ao ler o TID de $epc com $words words", it) }
    }

    /**
     * Liga/desliga o modo "inventário com TID". Para a varredura em curso: trocar o
     * comando que alimenta o leitor no meio de uma rodada deixaria os dois responders
     * disputando o canal.
     */
    fun setTidMode(enabled: Boolean) {
        if (_tidMode.value == enabled) return
        if (continuousScanEnabled) scanStop()
        _tidMode.value = enabled
    }

    // ------------------------------------------------------------------ scan

    /** Inicia o scan contínuo por software (alternativa ao gatilho físico). */
    fun scanStart() = scope.launch {
        if (!commander.isConnected) return@launch
        continuousScanEnabled = true
        _isScanning.value = true
        if (_tidMode.value) {
            runCatching { commander.executeCommand(tidScanCommand) }
                .onFailure { Log.e(TAG, "Falha ao iniciar o scan com TID", it) }
        } else {
            inventoryCommand.takeNoAction = TriState.NO
            runCatching { commander.executeCommand(inventoryCommand) }
                .onFailure { Log.e(TAG, "Falha ao iniciar o scan", it) }
        }
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

        /** TID estendido: 96 bits = 6 words. É o trecho que carrega o serial único da peça. */
        const val TID_WORDS_EXTENDED = 6

        /** TID mínimo do Gen2: 32 bits = 2 words (classe, fabricante e modelo, sem serial). */
        const val TID_WORDS_MINIMUM = 2
    }
}
