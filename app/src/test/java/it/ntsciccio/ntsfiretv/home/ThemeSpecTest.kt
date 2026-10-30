// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JSON themes: save and reload, missing or invalid values, built-in themes. */
class ThemeSpecTest {

    @Test
    fun `ogni tema integrato sopravvive a salvataggio e rilettura`() {
        ThemeSpec.BUILT_IN.forEach { theme ->
            val read = ThemeSpec.fromJson(theme.id, theme.toJson())
            assertEquals("tema ${theme.name}", theme, read)
        }
    }

    @Test
    fun `i temi integrati hanno id unici e un predefinito senza sfondo`() {
        val ids = ThemeSpec.BUILT_IN.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("default", ThemeSpec.BUILT_IN.first().id)
        assertNull(ThemeSpec.DEFAULT.wallpaper)
        assertEquals(listOf("default", "retro"), ids)
        assertTrue(ThemeSpec.BUILT_IN.drop(1).all { it.wallpaper != null })
    }

    @Test
    fun `JSON parziale prende il resto dal tema predefinito`() {
        val theme = ThemeSpec.fromJson("user/mio", """{"name":"Mio","accent":"#4DA3FF","tileStyle":"capsule"}""")
        assertNotNull(theme)
        theme!!
        assertEquals("Mio", theme.name)
        assertEquals(0xFF4DA3FF.toInt(), theme.accent)
        assertEquals("capsule", theme.tileStyle)
        assertEquals(ThemeSpec.DEFAULT.tile, theme.tile)
        assertEquals(ThemeSpec.DEFAULT.clockFont, theme.clockFont)
        assertNull(theme.wallpaper)
        assertFalse(theme.isBuiltIn)
    }

    @Test
    fun `valori non validi tornano a quelli predefiniti o vengono limitati`() {
        val theme = ThemeSpec.fromJson(
            "user/x",
            """{"accent":"blu","tileStyle":"stellare","clockFont":"comic","cornerRadius":500,"clockSize":3}""",
        )!!
        assertEquals(ThemeSpec.DEFAULT.accent, theme.accent)
        assertEquals(ThemeSpec.DEFAULT.tileStyle, theme.tileStyle)
        assertEquals(ThemeSpec.DEFAULT.clockFont, theme.clockFont)
        assertEquals(40, theme.cornerRadiusDp)
        assertEquals(20, theme.clockSizeSp)
    }

    @Test
    fun `senza nome usa il nome del file`() {
        assertEquals("tramonto", ThemeSpec.fromJson("user/tramonto", "{}")!!.name)
    }

    @Test
    fun `JSON non valido da null`() {
        assertNull(ThemeSpec.fromJson("user/rotto", "non è json"))
    }

    @Test
    fun `solo nomi file validi per i temi utente`() {
        assertTrue(ThemeStore.isValidStem("notte-a-tokyo"))
        assertFalse(ThemeStore.isValidStem("../../data/x"))
        assertFalse(ThemeStore.isValidStem("Tema"))
        assertFalse(ThemeStore.isValidStem(""))
    }

    @Test
    fun `nome file dei temi`() {
        assertEquals("notte-a-tokyo", ThemeStore.slugify("Notte a Tokyo!"))
        assertEquals("il-mio-tema-2", ThemeStore.slugify("  Il mio tema #2 "))
        assertEquals("tema", ThemeStore.slugify("***"))
    }
}
