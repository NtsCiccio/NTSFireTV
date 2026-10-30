// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Generated theme backgrounds in JSON. */
class WallpaperSpecTest {

    @Test
    fun `salvataggio e rilettura conservano macchie e righe`() {
        val spec = WallpaperSpec(
            baseStart = 0xFF282828.toInt(),
            baseEnd = 0xFF1D2021.toInt(),
            angle = 180,
            blobs = listOf(WallpaperSpec.Blob(0xFFFE8019.toInt(), 0.3f, 50f, 120f, 90f)),
            stripes = listOf(0xFFFABD2F.toInt(), 0xFF8EC07C.toInt()),
        )
        assertEquals(spec, WallpaperSpec.fromJson(spec.toJson()))
    }

    @Test
    fun `macchie senza colore vengono scartate e i valori limitati`() {
        val json = JSONObject(
            """{"base":["#000000"],"blobs":[{"alpha":0.5},{"color":"#FF0000","alpha":7,"r":1000}]}""",
        )
        val spec = WallpaperSpec.fromJson(json)!!
        assertEquals(0xFF000000.toInt(), spec.baseEnd) // without a second color the base is uniform
        assertEquals(180, spec.angle)
        assertEquals(1, spec.blobs.size)
        assertEquals(1f, spec.blobs[0].alpha)
        assertEquals(150f, spec.blobs[0].radius)
        assertTrue(spec.stripes.isEmpty())
    }

    @Test
    fun `senza base non c'è sfondo`() {
        assertNull(WallpaperSpec.fromJson(JSONObject("""{"angle":90}""")))
        assertNull(WallpaperSpec.fromJson(null))
    }

    @Test
    fun `sfondo dai colori del tema usa base opaca e accento`() {
        val spec = WallpaperSpec.fromColors(tile = 0x99403020.toInt(), accent = 0xFF4DA3FF.toInt())
        assertEquals(0xFF403020.toInt(), spec.baseStart)
        assertEquals(darken(0xFF403020.toInt()), spec.baseEnd)
        assertTrue(spec.blobs.all { it.color == 0xFF4DA3FF.toInt() })
    }

    @Test
    fun `numero di macchie, coordinate e angolo vengono limitati`() {
        val blobs = (1..50).joinToString(",") { """{"color":"#FF0000","x":9999,"y":-9999}""" }
        val spec = WallpaperSpec.fromJson(JSONObject("""{"base":["#000000"],"angle":-90,"blobs":[$blobs]}"""))!!
        assertEquals(16, spec.blobs.size)
        assertEquals(150f, spec.blobs[0].x)
        assertEquals(-50f, spec.blobs[0].y)
        assertEquals(270, spec.angle)
    }
}
