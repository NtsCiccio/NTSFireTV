// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.apps

import android.content.Context
import android.content.SharedPreferences

/** "Tutte le app" (All apps) preferences: hidden apps, chosen category for each app, sort order. */
object AppPreferences {

    const val PREFS_NAME = "apps"
    private const val KEY_HIDDEN = "hidden"
    private const val KEY_SORT = "sort"
    private const val CATEGORY_PREFIX = "category:"

    /** Sort orders of "Tutte le app". */
    enum class Sort { ALPHABETICAL, MOST_USED, RECENT }

    fun sort(context: Context): Sort =
        Sort.entries.firstOrNull { it.name == prefs(context).getString(KEY_SORT, null) } ?: Sort.ALPHABETICAL

    fun setSort(context: Context, sort: Sort) {
        prefs(context).edit().putString(KEY_SORT, sort.name).apply()
    }

    /** Id of the category chosen by the user for the app, or null (automatic category). */
    fun category(context: Context, packageName: String): String? =
        prefs(context).getString(CATEGORY_PREFIX + packageName, null)

    fun setCategory(context: Context, packageName: String, categoryId: String) {
        prefs(context).edit().putString(CATEGORY_PREFIX + packageName, categoryId).apply()
    }

    fun hidden(context: Context): Set<String> = prefs(context).getStringSet(KEY_HIDDEN, null)?.toSet().orEmpty()

    fun hide(context: Context, packageName: String) = update(context) { it + packageName }

    fun show(context: Context, packageName: String) = update(context) { it - packageName }

    private fun update(context: Context, change: (Set<String>) -> Set<String>) {
        // New copy: the Set returned by getStringSet must not be modified
        prefs(context).edit().putStringSet(KEY_HIDDEN, HashSet(change(hidden(context)))).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
