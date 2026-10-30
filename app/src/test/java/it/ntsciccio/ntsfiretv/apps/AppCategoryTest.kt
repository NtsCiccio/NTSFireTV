// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.apps

import org.junit.Assert.assertEquals
import org.junit.Test

/** Category guessed from the package name. */
class AppCategoryTest {

    @Test
    fun `pacchetti noti`() {
        assertEquals(AppCategory.VIDEO, AppCategory.guess("com.netflix.ninja"))
        assertEquals(AppCategory.VIDEO, AppCategory.guess("org.videolan.vlc"))
        assertEquals(AppCategory.MUSIC, AppCategory.guess("com.spotify.tv.android"))
        assertEquals(AppCategory.GAMES, AppCategory.guess("com.limelight"))
        assertEquals(AppCategory.TOOLS, AppCategory.guess("com.phlox.tvwebbrowser"))
    }

    @Test
    fun `prefisso dentro il nome del pacchetto`() {
        assertEquals(AppCategory.MUSIC, AppCategory.guess("com.deezer.android.tv"))
    }

    @Test
    fun `pacchetti sconosciuti in Altre`() {
        assertEquals(AppCategory.OTHER, AppCategory.guess("it.esempio.app"))
        // "rai" must not match packages that happen to contain it
        assertEquals(AppCategory.OTHER, AppCategory.guess("com.trainer.app"))
    }
}
