package com.megagoglio.rfidinventory.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cobre a conversão pura de [EpcFilterSpec] (caracteres hex, como a tela pensa)
 * para os parâmetros de Select do SDK da TSL (bits) em [EpcFilterSelectParams].
 */
class EpcFilterTest {

    @Test
    fun `offsetBits soma o offset base de 32 bits do banco EPC ao inicio em nibbles`() {
        val spec = EpcFilterSpec(startChar = 0, lengthChar = 4, valueHex = "ABCD")
        assertEquals(32, EpcFilterSelectParams.offsetBits(spec))
    }

    @Test
    fun `offsetBits avanca 4 bits por caractere hex de inicio`() {
        val spec = EpcFilterSpec(startChar = 8, lengthChar = 4, valueHex = "1234")
        assertEquals(32 + 8 * 4, EpcFilterSelectParams.offsetBits(spec))
    }

    @Test
    fun `lengthBits converte caracteres hex para bits (4 bits por nibble)`() {
        val spec = EpcFilterSpec(startChar = 0, lengthChar = 6, valueHex = "ABCDEF")
        assertEquals(24, EpcFilterSelectParams.lengthBits(spec))
    }

    @Test
    fun `selectData mantem valor hex ja alinhado a byte (comprimento par)`() {
        val spec = EpcFilterSpec(startChar = 0, lengthChar = 4, valueHex = "ABCD")
        assertEquals("ABCD", EpcFilterSelectParams.selectData(spec))
    }

    @Test
    fun `selectData faz padding com zero a direita quando o hex tem comprimento impar`() {
        val spec = EpcFilterSpec(startChar = 0, lengthChar = 3, valueHex = "ABC")
        assertEquals("ABC0", EpcFilterSelectParams.selectData(spec))
    }

    @Test
    fun `caso completo espelhando a tela EPC Filter`() {
        // Base EPC de exemplo, filtro nos 4 nibbles a partir da posição 4.
        val baseEpc = "E28011700000020F4A8C1234"
        val start = 4
        val length = 4
        val pattern = baseEpc.substring(start, start + length)
        val spec = EpcFilterSpec(start, length, pattern)

        assertEquals("1170", pattern)
        assertEquals(32 + 16, EpcFilterSelectParams.offsetBits(spec))
        assertEquals(16, EpcFilterSelectParams.lengthBits(spec))
        assertEquals("1170", EpcFilterSelectParams.selectData(spec))
    }
}
