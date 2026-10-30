// SPDX-License-Identifier: GPL-3.0-or-later
package it.ntsciccio.ntsfiretv.home

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import it.ntsciccio.ntsfiretv.R

/** What happens when a dock tile is chosen. */
sealed interface DockTarget {
    /** Opens an installed external app. */
    data class App(val packageName: String) : DockTarget

    /** Built-in file browser for USB/HDD (M6). */
    data object Video : DockTarget

    /** Grid with all installed apps (M3). */
    data object AllApps : DockTarget

    /** NTSFireTV settings (M9). */
    data object Settings : DockTarget
}

/**
 * A dock tile.
 * @property id stable identifier, used to save the order chosen by the user.
 * @property iconRes tile icon; for external apps it applies until there is a banner (app not installed).
 */
data class DockItem(
    val id: String,
    val label: String,
    val target: DockTarget,
    val iconRes: Int,
)

/** Default dock. */
object DefaultDock {
    val items = listOf(
        DockItem("browser", "Browser", DockTarget.App("com.phlox.tvwebbrowser"), R.drawable.ic_browser),
        DockItem("spotify", "Spotify", DockTarget.App("com.spotify.tv.android"), R.drawable.ic_music),
        DockItem("moonlight", "Moonlight", DockTarget.App("com.limelight"), R.drawable.ic_stream),
        DockItem("video", "Video", DockTarget.Video, R.drawable.ic_video),
        DockItem("all_apps", "Tutte le app", DockTarget.AllApps, R.drawable.ic_apps),
        DockItem("settings", "Impostazioni", DockTarget.Settings, R.drawable.ic_settings),
    )
}

/**
 * User-chosen dock (SharedPreferences): tile order and apps added from "Tutte le app" (All apps),
 * saved as `app:<package>`. [DefaultDock] is the base: its tiles cannot be removed.
 */
object DockPreferences {

    const val PREFS_NAME = "dock"
    private const val KEY_ORDER = "order"
    private const val APP_PREFIX = "app:"

    fun load(context: Context): List<DockItem> {
        val saved: String = prefs(context).getString(KEY_ORDER, null) ?: return DefaultDock.items
        val ids = saved.split(',').filter { it.isNotBlank() }
        val ordered = ids.mapNotNull { id ->
            DefaultDock.items.firstOrNull { it.id == id } ?: if (id.startsWith(APP_PREFIX)) appItem(context, id) else null
        }
        // Tiles added in later versions go at the end
        return ordered + DefaultDock.items.filter { it.id !in ids }
    }

    /** Saved order as is: comparing it is enough to know whether the dock changed. */
    fun orderKey(context: Context): String? = prefs(context).getString(KEY_ORDER, null)

    fun save(context: Context, items: List<DockItem>) {
        prefs(context).edit().putString(KEY_ORDER, items.joinToString(",") { it.id }).apply()
    }

    fun reset(context: Context) {
        prefs(context).edit().remove(KEY_ORDER).apply()
    }

    fun contains(context: Context, packageName: String): Boolean =
        load(context).any { (it.target as? DockTarget.App)?.packageName == packageName }

    /** Adds an app at the end of the dock. */
    fun addApp(context: Context, packageName: String) {
        if (contains(context, packageName)) return
        val item = appItem(context, APP_PREFIX + packageName) ?: return
        save(context, load(context) + item)
    }

    /** Removes an app added by the user (default tiles stay). */
    fun removeApp(context: Context, packageName: String) {
        save(context, load(context).filterNot { it.id == APP_PREFIX + packageName })
    }

    fun isRemovable(item: DockItem): Boolean = item.id.startsWith(APP_PREFIX)

    private fun appItem(context: Context, id: String): DockItem? {
        val packageName = id.removePrefix(APP_PREFIX)
        if (packageName.isBlank()) return null
        val label = try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
        return DockItem(id, label, DockTarget.App(packageName), R.drawable.ic_apps)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
