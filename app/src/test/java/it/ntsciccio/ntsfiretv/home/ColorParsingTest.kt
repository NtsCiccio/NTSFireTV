// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Parsing of JSON theme colors (`#RRGGBB` / `#AARRGGBB`). */
class ColorParsingTest {

    @Test
    fun `colore a 6 cifre diventa opaco`() {
        assertEquals(0xFF88C0D0.toInt(), parseColorOrNull("#88C0D0"))
    }

    @Test
    fun `colore a 8 cifre mantiene l'opacità`() {
        assertEquals(0x80FF8FB3.toInt(), parseColorOrNull("#80FF8FB3"))
        assertEquals(0x00000000, parseColorOrNull("#00000000"))
    }

    @Test
    fun `minuscole, spazi e cancelletto mancante sono accettati`() {
        assertEquals(0xFFABCDEF.toInt(), parseColorOrNull("  #abcdef "))
        assertEquals(0xFFABCDEF.toInt(), parseColorOrNull("ABCDEF"))
    }

    @Test
    fun `valori non validi danno null`() {
        assertNull(parseColorOrNull(null))
        assertNull(parseColorOrNull(""))
        assertNull(parseColorOrNull("#12345"))
        assertNull(parseColorOrNull("#GGGGGG"))
        assertNull(parseColorOrNull("rosso"))
    }

    @Test
    fun `colorToHex e withAlpha`() {
        assertEquals("#80FF8FB3", colorToHex(0x80FF8FB3.toInt()))
        assertEquals(0x40FFFFFF, withAlpha(0xFFFFFFFF.toInt(), 0x40))
        assertEquals(0xFF123456.toInt(), withAlpha(0x00123456, 999))
        assertEquals(parseColorOrNull(colorToHex(0x12345678)), 0x12345678)
    }

    @Test
    fun `lighten schiarisce senza superare il bianco`() {
        assertEquals(0xFF142434.toInt(), lighten(0x00001020))
        assertEquals(0xFFFFFFFF.toInt(), lighten(0xFFF5F5F5.toInt()))
    }

    @Test
    fun `darken porta i canali al 75 per cento e rende opaco`() {
        assertEquals(0xFF604830.toInt(), darken(0x80806040.toInt()))
        assertEquals(0xFF000000.toInt(), darken(0xFF000000.toInt()))
    }
}
