// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import android.content.Context
import android.content.SharedPreferences

/** Home preferences: memory meter under the clock and child lock. */
object HomePreferences {

    const val PREFS_NAME = "home"
    private const val KEY_MEMORY_METER = "memory_meter"
    private const val KEY_CHILD_LOCK = "child_lock"

    fun memoryMeter(context: Context): Boolean = prefs(context).getBoolean(KEY_MEMORY_METER, false)

    fun setMemoryMeter(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MEMORY_METER, enabled).apply()
    }

    /** Child lock: Settings, Fire OS shortcuts and app menus are hidden; the home only opens apps. */
    fun childLock(context: Context): Boolean = prefs(context).getBoolean(KEY_CHILD_LOCK, false)

    fun setChildLock(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHILD_LOCK, enabled).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
