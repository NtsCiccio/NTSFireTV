// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import android.app.Activity
import android.os.SystemClock

/**
 * Home key pressed while an NTSFireTV screen is open ("Impostazioni", "Tutte le app"…).
 *
 * HomeWatcherService brings our task to the front as is: instead of opening HomeActivity
 * (which Android would delay by ~5 s after the Home key), the screens above the home close themselves
 * in onResume until the home is reached, which clears the request.
 */
object HomeNavigation {

    /** After this time the request no longer applies (e.g. the user was in VLC and comes back later). */
    private const val WINDOW_MS = 3_000L

    @Volatile
    private var requestedAt = 0L

    fun requestHome() {
        requestedAt = SystemClock.elapsedRealtime()
    }

    fun clear() {
        requestedAt = 0L
    }

    /**
     * To be called in onResume of the screens above the home: if Home was pressed, closes the screen
     * without animation. @return true if the screen is closing.
     */
    fun leaveIfRequested(activity: Activity): Boolean {
        val requested = requestedAt
        if (requested == 0L || SystemClock.elapsedRealtime() - requested > WINDOW_MS) return false
        activity.finish()
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
        return true
    }
}
