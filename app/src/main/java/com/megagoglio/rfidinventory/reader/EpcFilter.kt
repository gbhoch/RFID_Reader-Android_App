package com.megagoglio.rfidinventory.reader

/**
 * Um filtro de EPC: casa [lengthChar] caracteres hex do EPC a partir da posição
 * [startChar] (ambos contados em caracteres hex — 1 caractere = 1 nibble = 4 bits)
 * contra [valueHex]. É assim que a tela "EPC Filter" trabalha, espelhando o RFID
 * Explorer da TSL.
 */
data class EpcFilterSpec(
    val startChar: Int,
    val lengthChar: Int,
    val valueHex: String,
)

/**
 * Converte um [EpcFilterSpec] (em caracteres hex, como a tela e o usuário pensam)
 * para os parâmetros de Select do SDK da TSL, que são sempre em BITS — confirmado
 * literalmente no Javadoc de `ISelectMaskParameters`:
 * `setSelectLength`: "Sets the length in bits of the select mask"
 * `setSelectOffset`: "Sets the number of bits from the start of the block to the
 * start of the select mask"
 */
object EpcFilterSelectParams {

    /**
     * `setSelectOffset` conta os bits a partir do início ABSOLUTO do banco EPC, que no
     * Gen2 reserva 16 bits de CRC + 16 bits de PC antes do EPC em si. Por isso o EPC
     * visível começa no bit 32 do banco.
     *
     * CONFIRMADO em hardware (TSL 1128, 22/09/2026): com duas tags em campo,
     * `2025030419038A011A40004B` e `000000000000000000008285`, um filtro
     * start=0/length=1/valor "2" (que vira `-so0020`, ou seja 32 bits) reportou apenas a
     * primeira — isto é, casou o PRIMEIRO caractere do EPC. Se a base fosse 0, o mesmo
     * offset teria caído no caractere 8 e a tag casada seria a outra.
     */
    private const val EPC_BASE_OFFSET_BITS = 32

    fun offsetBits(spec: EpcFilterSpec): Int = EPC_BASE_OFFSET_BITS + spec.startChar * 4

    fun lengthBits(spec: EpcFilterSpec): Int = spec.lengthChar * 4

    /**
     * `setSelectData` espera uma string hex "padded to ensure full bytes" (2 caracteres
     * por byte), então um [lengthChar] ímpar é completado com um '0' à direita.
     *
     * CONFIRMADO em hardware (TSL 1128, 22/09/2026): o filtro de 1 nibble valor "2" foi
     * enviado como `-sl04 -sd20` (4 bits de comprimento, dado preenchido para um byte) e
     * casou corretamente só a tag cujo primeiro caractere é '2'. O leitor respeita o
     * comprimento em bits e ignora os bits de padding.
     */
    fun selectData(spec: EpcFilterSpec): String =
        if (spec.valueHex.length % 2 == 0) spec.valueHex else spec.valueHex + "0"
}
