// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Time formatting and the "Riprendi" (Resume) rule. */
class PlaybackTest {

    @Test
    fun `formatDuration con e senza ore`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:09", formatDuration(9_999))
        assertEquals("12:34", formatDuration((12 * 60 + 34) * 1000L))
        assertEquals("1:05:30", formatDuration((3600 + 5 * 60 + 30) * 1000L))
    }

    @Test
    fun `sotto i 10 secondi non si propone Riprendi`() {
        assertFalse(PlaybackPositions.isWorthResuming(9_000, 3_600_000))
        assertTrue(PlaybackPositions.isWorthResuming(10_000, 3_600_000))
    }

    @Test
    fun `vicino alla fine il video conta come finito`() {
        // 1-hour movie: 3% (108 s) is more than the fixed 30 s
        assertTrue(PlaybackPositions.isWorthResuming(3_600_000 - 120_000, 3_600_000))
        assertFalse(PlaybackPositions.isWorthResuming(3_600_000 - 100_000, 3_600_000))
        // 5-minute video: the 30 s applies
        assertTrue(PlaybackPositions.isWorthResuming(300_000 - 40_000, 300_000))
        assertFalse(PlaybackPositions.isWorthResuming(300_000 - 20_000, 300_000))
    }

    @Test
    fun `durata sconosciuta non segna mai il video come finito`() {
        assertTrue(PlaybackPositions.isWorthResuming(5_000_000, 0))
    }

    @Test
    fun `lettura delle posizioni salvate, vecchie e nuove`() {
        assertEquals(Progress(120_000, 3_600_000, 0), PlaybackPositions.parse("120000:3600000"))
        assertEquals(Progress(120_000, 3_600_000, 42), PlaybackPositions.parse("120000:3600000:42"))
        assertEquals(null, PlaybackPositions.parse("rotto"))
        assertEquals(null, PlaybackPositions.parse(null))
    }
}
