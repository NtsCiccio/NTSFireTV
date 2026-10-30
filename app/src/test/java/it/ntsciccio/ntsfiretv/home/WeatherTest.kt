// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** Descriptions of Open-Meteo's WMO weather codes. */
class WeatherTest {

    @Test
    fun `codici principali`() {
        assertEquals("Sereno", Weather.describe(0))
        assertEquals("Poco nuvoloso", Weather.describe(2))
        assertEquals("Nuvoloso", Weather.describe(3))
        assertEquals("Nebbia", Weather.describe(45))
        assertEquals("Pioggia", Weather.describe(63))
        assertEquals("Neve", Weather.describe(75))
        assertEquals("Rovesci", Weather.describe(81))
        assertEquals("Temporale", Weather.describe(95))
    }

    @Test
    fun `codici sconosciuti danno testo vuoto`() {
        assertEquals("", Weather.describe(-1))
        assertEquals("", Weather.describe(42))
    }
}
