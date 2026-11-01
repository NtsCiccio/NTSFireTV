// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Memory meter under the clock: bar and low-memory threshold. */
class MemoryMeterTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    @Test
    fun `percentuale usata`() {
        assertEquals(75, MemoryMeter.usedPercent(available = 512 * mb, total = 2 * gb))
        assertEquals(0, MemoryMeter.usedPercent(available = 2 * gb, total = 2 * gb))
        assertEquals(100, MemoryMeter.usedPercent(available = 0, total = 2 * gb))
    }

    @Test
    fun `totale sconosciuto`() {
        assertEquals(0, MemoryMeter.usedPercent(available = 100, total = 0))
        assertFalse(MemoryMeter.isLow(available = 100, total = 0, lowMemory = false))
    }

    @Test
    fun `memoria scarsa sotto il 15 per cento`() {
        assertFalse(MemoryMeter.isLow(available = 560 * mb, total = 1800 * mb, lowMemory = false))
        assertTrue(MemoryMeter.isLow(available = 250 * mb, total = 1800 * mb, lowMemory = false))
    }

    @Test
    fun `memoria scarsa se lo dice Android`() {
        assertTrue(MemoryMeter.isLow(available = 900 * mb, total = 1800 * mb, lowMemory = true))
    }
}
