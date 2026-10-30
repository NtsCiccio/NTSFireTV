// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import android.app.Activity
import android.os.Bundle

/**
 * Base for the screens above the home ("Tutte le app", "Impostazioni", Video, wallpapers, dock, theme editor).
 *
 * In onResume it closes itself if the Home key was pressed ([HomeNavigation]) and is recreated
 * if the theme changed in the meantime: this way every new screen handles both without having to remember.
 * Subclasses that override onResume call `super.onResume()` and then `if (isLeaving) return`.
 */
abstract class OverlayActivity : Activity() {

    private var appliedThemeVersion = 0

    /**
     * `false` for screens with state that recreation would lose (changes in the theme editor,
     * folder open in the file browser): they keep the theme they were opened with.
     */
    protected open val recreateOnThemeChange: Boolean = true

    /** `true` if the screen is closing or being recreated: the rest of onResume must be skipped. */
    protected var isLeaving = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedThemeVersion = ThemeStore.version
    }

    override fun onResume() {
        super.onResume()
        isLeaving = HomeNavigation.leaveIfRequested(this)
        if (!isLeaving && recreateOnThemeChange && ThemeStore.version != appliedThemeVersion) {
            isLeaving = true
            recreate()
        }
    }
}
